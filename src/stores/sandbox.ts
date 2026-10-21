/**
 * 沙箱 store —— 执行状态 / 结构化输出 / 产物树 / 附件
 *
 * 设计要点：
 *   1. 「运行代码」有**两条通道**：
 *        local —— 本地执行引擎，不经 LLM，点一下就跑（智能体未就绪时可用）
 *        agent —— 往 AG-UI 发消息，由模型调 execute 工具
 *      两者产出同一种 ExecResult，UI 不必区分
 *   2. 执行输出结构化由 `utils/parseToolExec.ts` 的 ExecCollector 在前端完成
 *   3. 接口不可用时**降级为空态**，不弹全局 toast（面板内提示即可）
 *   4. 产物空状态必须区分「本会话还没产物」与「产物已被清理」（开了 GC 之后）
 */

import { createUuid } from '@/utils/uuid'
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { parseAguiStream, extractInterrupts, runAgui, userMessage, runEventError } from '@/api/agui'
import { listPendingInterrupts, registerInterrupts } from '@/api/formInterrupt'
import { fetchSessionTranscript, fetchSessionProgress, mergeTranscript, type SyncedChatMessage, type SessionTextMessage } from '@/api/session'
import { useProblemSessionStore } from '@/stores/problemSession'
import { fetchArtifactContent, fetchArtifactTree, saveArtifactContent } from '@/api/artifact'
import { deleteAttachment, listAttachments, precheckFiles, uploadAttachment } from '@/api/attachment'
import { AGENT_ID, ARTIFACTS_POLL_MS, IS_MOCK, RETENTION_DAYS } from '@/config'
import { isNotifyStreamConnected, subscribeSessionActivity } from '@/stores/notifyStream'
import { rebuildPendingGate, type PendingGate } from '@/utils/analysis/gateRebuild'
import { mockArtifactContent, mockArtifactTree, mockAttachments, mockAguiEvents } from '@/data/mockSandbox'
import { runLocalCode, type SandboxFile } from '@/engine/localRunner'
import { ExecCollector, type ExecResult } from '@/utils/parseToolExec'
import type { AguiEvent, AguiInterrupt, AguiRunRequest } from '@/types/agui'
import type { ArtifactContent, ArtifactNode, AttachmentItem } from '@/types/sandbox'

/** 产物为空的原因 —— 决定 UI 文案 */
export type EmptyReason = 'none' | 'no-artifacts' | 'cleaned'

/** 执行状态 */
export type RunState = 'idle' | 'running' | 'error'

/** 执行通道 —— UI 上要显式区分，避免使用者误以为本地通道也是隔离环境 */
export type ExecChannel = 'local' | 'agent'

export type ChatMessage = SyncedChatMessage

export const useSandboxStore = defineStore('sandbox', () => {
  /* ── 会话上下文（由调用方在进入工作台时设置） ─────────────── */
  const sessionId = ref('')
  const problemId = ref('')

  /* ── 对话 ────────────────────────────────────────────────── */
  const messages = ref<ChatMessage[]>([])
  const state = ref<RunState>('idle')
  const runError = ref('')
  const syncError = ref('')
  const progressText = ref('')
  const approvalNotice = ref('')

  /* ── 执行结果 ─────────────────────────────────────────────── */
  const execs = ref<ExecResult[]>([])
  const interrupts = ref<AguiInterrupt[]>([])
  /** 是否真的收到过工具结果 —— 用于区分「没跑」与「跑了没输出」 */
  const hasExecuted = ref(false)
  /** 最近一次执行走的通道（UI 用来显示来源标记） */
  const lastChannel = ref<ExecChannel>('local')

  /* ── 产物 ────────────────────────────────────────────────── */
  const tree = ref<ArtifactNode[] | null>(null)
  const treeLoading = ref(false)
  const treeError = ref('')
  const emptyReason = ref<EmptyReason>('none')

  const selectedPath = ref('')
  const content = ref<ArtifactContent | null>(null)
  const contentLoading = ref(false)
  const contentError = ref('')
  const dirty = ref(false)
  const saving = ref(false)
  /**
   * 打开文件时的原始内容 —— 「还原」的基准。
   *
   * 只存一份文本，不存历史栈：用户要的是「改坏了能回到刚打开的样子」，
   * 不是完整的撤销树（那是编辑器该干的事，这里不做）。
   */
  const baseline = ref('')
  let fileRequestId = 0

  /* ── 附件 ────────────────────────────────────────────────── */
  const attachments = ref<AttachmentItem[]>([])
  const attachLoading = ref(false)
  const attachError = ref('')

  let abortCtl: AbortController | null = null
  let pollTimer: number | undefined
  /** 实时通知流订阅句柄（进工作台订阅、离开退订；全应用单例连接） */
  let notifyUnsub: (() => void) | null = null
  /** 可见性补拉监听句柄（同上，离开工作台要摘掉） */
  let visibilityHandler: (() => void) | null = null
  let generation = 0
  let syncEpoch: number | undefined
  /** 已在对话里呈现过的「转录重建闸门」id —— 换会话 / 提交换掉时复位，避免重复弹同一张卡 */
  let rebuiltGateId = ''
  const stagedAnswers = new Map<string, NonNullable<AguiRunRequest['resume']>[number]>()
  const stagedForms = new Map<string, AguiInterrupt>()
  interface RunContext { controller: AbortController; generation: number }
  const isCurrent = (ctx: RunContext): boolean => ctx.generation === generation && !ctx.controller.signal.aborted
  function invalidateRun(): void {
    generation++
    abortCtl?.abort()
    abortCtl = null
  }
  function beginRun(): RunContext {
    invalidateRun()
    const ctx = { controller: new AbortController(), generation }
    abortCtl = ctx.controller
    return ctx
  }

  const isMock = IS_MOCK
  const latestExec = computed<ExecResult | null>(() =>
    execs.value.length ? execs.value[execs.value.length - 1] : null,
  )
  const isRunning = computed(() => state.value === 'running')

  /* ── 生命周期 ────────────────────────────────────────────── */

  /** 进入工作台：绑定会话并拉首屏数据 */
  async function attach(pProblemId: string, pSessionId: string): Promise<void> {
    invalidateRun()
    stopPolling()
    const epoch = generation
    stagedAnswers.clear()
    stagedForms.clear()
    syncError.value = ''
    progressText.value = ''
    approvalNotice.value = ''
    problemId.value = pProblemId
    sessionId.value = pSessionId
    messages.value = []
    execs.value = []
    interrupts.value = []
    hasExecuted.value = false
    activeThreadId.value = ''
    activeRunId.value = ''
    activeAssistantId.value = ''
    rebuiltGateId = ''
    state.value = 'idle'
    runError.value = ''
    closeFile()
    await Promise.all([refreshTree(), loadAttachments()])
    if (epoch !== generation) return
    /* 首次进工作台走 deep：本地没有任何 run 在跑，而会话里可能正挂着一张
       「后端唤醒出来的」闸门 —— 那是唯一能把它捞回来的时机 */
    await syncSession(true)
    if (epoch === generation) startPollingIfNeeded()
  }

  /** 离开：停掉流与轮询，清空预览（产物留在磁盘，不清） */
  function detach(): void {
    invalidateRun()
    stopPolling()
    state.value = 'idle'
  }

  /* ── 对话 / 执行 ─────────────────────────────────────────── */

  /** 当前 AG-UI run 的身份；HITL resume 必须复用这两个值。 */
  const activeThreadId = ref('')
  const activeRunId = ref('')
  const activeAssistantId = ref('')

  function interruptIdOf(interrupt: AguiInterrupt): string {
    return interrupt.id || interrupt.interruptId || ''
  }

  /**
   * 把一张「转录重建出来的闸门」挂进对话区（移植自 kuaike `detectPendingGate`）。
   *
   * 沙箱这条本来靠 `listPendingInterrupts` 登记表拿待填表单 —— 唤醒 run 也走同一条
   * 登记路径，所以多数时候够用。缺的是**登记表不可信**那一档：后端重启后内存清空、
   * 或 run 没正常收尾导致旧条目被清掉，此时只有 transcript 里还留着「没有配对
   * tool_result 的表单调用」。判据与 kuaike 同源（见 `@/utils/analysis/gateRebuild`）。
   *
   * 入参给的是**平台原件**（`input`），因为 `ChatPanel.vue` 自己认 `pages` 协议、
   * 不经 `gate.ts` 归一化。
   */
  function adoptRebuiltGate(sid: string, pending: PendingGate): void {
    rebuiltGateId = pending.interruptId
    interrupts.value = [{
      id: pending.interruptId,
      toolCallId: pending.toolCallId,
      message: '运行等待确认，请完成以下表单后继续',
      metadata: { toolName: pending.toolName, toolInput: pending.input, runId: pending.runId },
    }]
    /* HITL resume 必须复用当初那条 run 的 (threadId, runId)，否则后端认不出中断 */
    activeThreadId.value = sid
    activeRunId.value = pending.runId || AGENT_ID
    lastChannel.value = 'agent'
  }

  /**
   * 发一条消息并读 SSE 流。mock 模式只播本地事件，真实模式走 AG-UI。
   * 同一函数也被 resume 复用，保证文本、工具结果、HITL 结束事件的处理一致。
   */
  async function streamRun(
    body: AguiRunRequest,
    assistantId: string,
    ctx: RunContext,
  ): Promise<void> {
    const collector = new ExecCollector()
    let appendedExecCount = 0
    let streamMessageId = assistantId
    const terminal: { type: 'success' | 'interrupt' | 'error' | ''; error: string } = { type: '', error: '' }
    const handleEvent = (ev: AguiEvent): void => {
      if (!isCurrent(ctx)) return
      const type = String(ev['type'] ?? '')
      if (type === 'TEXT_MESSAGE_START' && typeof ev['messageId'] === 'string') {
        const remoteId = ev['messageId']
        const current = messages.value.find(m => m.id === streamMessageId)
        if (current?.text && current.remoteId !== remoteId) {
          streamMessageId = remoteId
          if (!messages.value.some(m => m.id === remoteId))
            messages.value.push({ id: remoteId, remoteId, role: 'assistant', text: '', at: Date.now(), local: true })
        } else if (current) current.remoteId = remoteId
      }
      if (/TEXT_MESSAGE_CONTENT/i.test(type)) {
        const delta = typeof ev['delta'] === 'string' ? ev['delta'] : ''
        if (delta) {
          const idx = messages.value.findIndex((m) => m.id === streamMessageId)
          if (idx >= 0) messages.value[idx] = { ...messages.value[idx], text: messages.value[idx].text + delta }
        }
      }
      if (collector.push(ev)) {
        const snapshot = collector.list()
        const fresh = snapshot.slice(appendedExecCount)
        if (fresh.length) {
          execs.value = [...execs.value, ...fresh]
          appendedExecCount = snapshot.length
          hasExecuted.value = true
        }
      }
      const ints = extractInterrupts(ev)
      if (ints.length) interrupts.value = ints
      const error = runEventError(ev)
      if (error) { terminal.type = 'error'; terminal.error ||= error }
      else if (type === 'RUN_FINISHED' && terminal.type !== 'error') {
        const outcome = ev['outcome'] as { type?: string } | undefined
        terminal.type = outcome?.type === 'interrupt' ? 'interrupt' : 'success'
      }
    }

    if (isMock) {
      for (const ev of mockAguiEvents()) {
        if (!isCurrent(ctx)) return
        handleEvent(ev)
        await sleep(180)
      }
      return
    }

    const res = await runAgui(AGENT_ID, body, ctx.controller.signal)
    await parseAguiStream(res, handleEvent, ctx.controller.signal)
    if (!isCurrent(ctx)) return
    if (terminal.type === 'error') throw new Error(terminal.error || '智能体运行失败')
    if (!terminal.type) throw new Error('智能体流提前结束，未收到运行结束事件')
    if (terminal.type === 'interrupt') {
      if (!interrupts.value.length) throw new Error('智能体请求确认，但未返回可用的中断表单')
      try {
        await registerInterrupts(AGENT_ID, body.threadId, interrupts.value, body.runId)
      } catch (e) {
        // 本页仍可直接 resume；刷新后可能无法找回，必须如实提示。
        if (!isCurrent(ctx)) return
        runError.value = `待确认表单登记失败，刷新页面可能丢失：${describeError(e)}`
      }
    }
    // Never DELETE the whole session here: a background run may have just opened
    // a new gate. GET reconciles against the framework's live interrupt IDs.
  }

  async function finishStream(assistantId: string, ctx: RunContext): Promise<void> {
    if (!isCurrent(ctx)) return
    const idx = messages.value.findIndex((m) => m.id === assistantId)
    if (idx >= 0 && !messages.value[idx].text) {
      messages.value[idx] = { ...messages.value[idx], text: '（本次没有返回文本输出）' }
    }
    // 先拉最新产物，再切到 idle，避免 WorkbenchView 的回执 watcher
    // 在产物树更新前先写入一份空清单。
    await refreshTree()
    if (!isCurrent(ctx)) return
    state.value = 'idle'
    await syncSession()
    if (isCurrent(ctx)) startPollingIfNeeded()
  }
  async function send(text: string, opts: { runId: string; threadId: string }): Promise<void> {
    const trimmed = text.trim()
    if (!trimmed || state.value === 'running' || interrupts.value.length) return

    const ctx = beginRun()
    const now = Date.now()
    const userId = `u-${createUuid()}`
    const assistantId = `a-${createUuid()}`
    messages.value.push({ id: userId, role: 'user', text: trimmed, at: now, local: true })
    messages.value.push({ id: assistantId, role: 'assistant', text: '', at: now, local: true })
    activeAssistantId.value = assistantId
    activeThreadId.value = opts.threadId
    activeRunId.value = opts.runId
    lastChannel.value = 'agent'
    state.value = 'running'
    runError.value = ''
    interrupts.value = []

    try {
      await streamRun({
        threadId: opts.threadId,
        runId: opts.runId,
        messages: [userMessage(userId, trimmed)],
      }, assistantId, ctx)
      await finishStream(assistantId, ctx)
    } catch (e) {
      if (!isCurrent(ctx)) return
      state.value = 'error'
      runError.value = describeError(e)
    } finally {
      if (abortCtl === ctx.controller) abortCtl = null
    }
  }

  /** Collect every explicit answer before resuming; never silently cancel siblings. */
  async function resumeInterrupt(
    interruptId: string,
    payload: { approved?: boolean; editedArgs?: Record<string, unknown>; output?: string; [key: string]: unknown } = {},
    status: 'resolved' | 'cancelled' = 'resolved',
  ): Promise<void> {
    if (!interruptId || state.value === 'running') return
    const selected = interrupts.value.find(item => interruptIdOf(item) === interruptId)
    if (!selected) return
    stagedForms.set(interruptId, selected)
    stagedAnswers.set(interruptId, { interruptId, status, payload })
    interrupts.value = interrupts.value.filter(item => interruptIdOf(item) !== interruptId)
    if (interrupts.value.length) {
      approvalNotice.value = '本页已暂存答复；还需处理 ' + interrupts.value.length + ' 张表单后统一续跑（刷新需重新填写）。'
      return
    }
    await submitAnswers()
  }

  async function submitAnswers(): Promise<void> {
    const runId = activeRunId.value
    const threadId = activeThreadId.value || sessionId.value
    if (!runId || !threadId) {
      interrupts.value = [...stagedForms.values()]
      stagedAnswers.clear(); stagedForms.clear()
      runError.value = '找不到原始运行身份，无法安全续跑；请重新进入会话恢复待确认表单'
      state.value = 'error'
      return
    }
    const previousInterrupts = [...stagedForms.values()]
    const resume = [...stagedAnswers.values()]
    stagedAnswers.clear(); stagedForms.clear()
    approvalNotice.value = ''
    const ctx = beginRun()
    const assistantId = 'a-' + createUuid()
    activeAssistantId.value = assistantId
    messages.value.push({ id: assistantId, role: 'assistant', text: '', at: Date.now(), local: true })
    state.value = 'running'
    runError.value = ''
    lastChannel.value = 'agent'
    try {
      await streamRun({ threadId, runId, messages: [], resume }, assistantId, ctx)
      await finishStream(assistantId, ctx)
    } catch (e) {
      if (!isCurrent(ctx)) return
      if (!interrupts.value.length) interrupts.value = previousInterrupts
      state.value = 'error'
      runError.value = describeError(e)
    } finally {
      if (abortCtl === ctx.controller) abortCtl = null
    }
  }

  /** Only this explicit whole-batch action cancels every open form. */
  async function cancelInterrupts(): Promise<void> {
    if (state.value === 'running') return
    for (const form of interrupts.value) stagedForms.set(interruptIdOf(form), form)
    if (!stagedForms.size) return
    for (const id of stagedForms.keys()) stagedAnswers.set(id, { interruptId: id, status: 'cancelled', payload: { approved: false } })
    interrupts.value = []
    await submitAnswers()
  }

  /** Stop the browser request; backend/background task cancellation is not implied. */
  function stop(): void {
    invalidateRun()
    state.value = 'idle'
    startPollingIfNeeded()
  }
  /**
   * 本地执行通道 —— 不经 LLM，直接跑一段代码。
   *
   * 与 `send()` 的区别：
   *   - 不产生对话消息（没有模型参与，不该出现「助手气泡」）
   *   - 结果直接进 `execs`，与 agent 通道共用一个列表
   *   - 不需要 `sessionId`（同页面执行，不落会话工作区）
   *
   * ⚠️ 只在页内执行，**不是**隔离沙箱。UI 必须如实标注。
   */
  async function runLocal(code: string, label = ''): Promise<void> {
    if (state.value === 'running') return
    const trimmed = code.trim()
    if (!trimmed) return

    state.value = 'running'
    runError.value = ''
    lastChannel.value = 'local'

    try {
      const out = await runLocalCode(trimmed)
      // label 作为命令描述保留下来，列表里才看得出跑的是哪一段
      execs.value = [...execs.value, { ...out.result, command: label || out.result.command }]
      hasExecuted.value = true
      if (out.result.isError) {
        runError.value = out.result.stderr || `退出码 ${out.result.exitCode}`
        state.value = 'error'
      } else {
        state.value = 'idle'
      }
    } catch (e) {
      state.value = 'error'
      runError.value = describeError(e)
    }
  }

  /** 清空执行记录（不动文件） */
  function clearExecs(): void {
    execs.value = []
    hasExecuted.value = false
    runError.value = ''
    state.value = 'idle'
  }

  /**
   * 跑沙箱里的一个文件 —— 就是「改代码 → 运行」这一步。
   *
   * 与 `runLocal()` 的区别只有一点：**给代码一个文件视图**。
   * 打开的文件本身（可能已被用户改过、尚未保存）作为执行入口，
   * 会话里其它产物文件作为可读数据 —— 这样 `readFile('data/附件1.csv')`
   * 能拿到内容，用户改数据文件也不需要来回粘贴。
   *
   * 注意：**跑的是编辑器里的当前内容，不是磁盘上那份**。
   * 也就是「改了还没保存也能先跑一遍看结果」，这正是这个功能的意义。
   * 想跑存档版本，先点保存。
   */
  async function runFile(code: string, path: string): Promise<void> {
    if (state.value === 'running') return
    const trimmed = code.trim()
    if (!trimmed) {
      runError.value = '这个文件是空的，没有可执行的代码'
      state.value = 'error'
      return
    }

    state.value = 'running'
    runError.value = ''
    lastChannel.value = 'local'

    // 组装文件视图。
    //
    // 关键：`readFile` 在**已执行的代码里是同步的**，没法等网络 ——
    // 所以必须在跑之前把要用的文件都取好。做法是把产物树里的文本文件
    // 全部预取一份（它们都不大），而不是等代码问了再去拉。
    // 拉不到的（二进制/超大/接口挂了）直接不进视图，代码读到时
    // 会看到明确的 ENOENT 与可读清单，不会静默给出半个结果。
    const files = await collectSandboxFiles(path, code)

    try {
      const out = await runLocalCode(trimmed, { files, entryPath: path })
      execs.value = [...execs.value, { ...out.result, command: `run ${path}` }]
      hasExecuted.value = true
      if (out.result.isError) {
        runError.value = out.result.stderr || `退出码 ${out.result.exitCode}`
        state.value = 'error'
      } else {
        state.value = 'idle'
      }
    } catch (e) {
      state.value = 'error'
      runError.value = describeError(e)
    }
  }

  /* ── 产物 ────────────────────────────────────────────────── */

  async function refreshTree(): Promise<void> {
    if (!sessionId.value) {
      tree.value = null
      return
    }
    const sid = sessionId.value
    const epoch = generation
    treeLoading.value = true
    treeError.value = ''
    try {
      const nodes = isMock ? mockArtifactTree() : await fetchArtifactTree(AGENT_ID, sid)
      if (epoch !== generation || sid !== sessionId.value) return
      const flat = flatten(nodes)
      tree.value = flat
      // 区分两种空：从未产生过产物 vs 产物被清理
      if (flat.length === 0) {
        emptyReason.value = hasExecuted.value || messages.value.length > 0 ? 'cleaned' : 'no-artifacts'
      } else {
        emptyReason.value = 'none'
      }
    } catch {
      if (epoch !== generation || sid !== sessionId.value) return
      // 401/404/网络错：降级空树，不阻断工作台（登录未就绪时会走这里）
      tree.value = []
      treeError.value = '产物接口暂不可用（需登录后调用）'
      emptyReason.value = 'none'
    } finally {
      if (epoch === generation && sid === sessionId.value) treeLoading.value = false
    }
  }

  async function openFile(node: ArtifactNode): Promise<void> {
    if (node.type !== 'file') return
    const requestId = ++fileRequestId
    const sid = sessionId.value
    const epoch = generation
    selectedPath.value = node.path
    content.value = null
    contentLoading.value = true
    contentError.value = ''
    dirty.value = false
    baseline.value = ''
    try {
      let result: ArtifactContent
      if (isMock) {
        const m = mockArtifactContent(node.path)
        result = { path: node.path, content: m.content, binary: m.binary }
      } else {
        result = await fetchArtifactContent(AGENT_ID, sid, node.path)
      }
      if (requestId !== fileRequestId || epoch !== generation || sid !== sessionId.value) return
      content.value = result
      // 记下打开时的样子 —— 之后「还原」与「是否真的改过」都以此为准
      baseline.value = result.content ?? ''
    } catch (e) {
      if (requestId !== fileRequestId || epoch !== generation || sid !== sessionId.value) return
      contentError.value = `读取失败：${describeError(e)}`
    } finally {
      if (requestId === fileRequestId && epoch === generation && sid === sessionId.value) contentLoading.value = false
    }
  }

  function editContent(next: string): void {
    if (!content.value || content.value.content == null || contentLoading.value || contentError.value) return
    content.value = { ...content.value, content: next }
    dirty.value = next !== baseline.value
  }

  async function saveFile(): Promise<boolean> {
    if (!content.value || !dirty.value || saving.value || contentLoading.value || contentError.value) return false
    /* 不可预览的文件（二进制 / 超限 / 非 UTF-8）content 为 null —— 没有内容可写 */
    if (content.value.content == null) return false
    const path = content.value.path
    const text = content.value.content
    const sid = sessionId.value
    saving.value = true
    try {
      if (!isMock) await saveArtifactContent(AGENT_ID, sid, path, text)
      if (sid === sessionId.value && content.value?.path === path) {
        baseline.value = text
        dirty.value = content.value.content !== text
      }
      if (!isMock && sid === sessionId.value) await refreshTree()
      return true
    } catch (e) {
      treeError.value = `保存失败：${describeError(e)}`
      return false
    } finally {
      saving.value = false
    }
  }

  function closeFile(): void {
    fileRequestId++
    selectedPath.value = ''
    content.value = null
    contentLoading.value = false
    contentError.value = ''
    dirty.value = false
    baseline.value = ''
  }

  /** 把当前文件还原成「打开时的样子」（不落盘，改完仍需点保存） */
  function revertFile(): void {
    if (!content.value) return
    content.value = { ...content.value, content: baseline.value }
    dirty.value = content.value.content !== baseline.value
  }

  /* ── 附件 ────────────────────────────────────────────────── */

  async function loadAttachments(): Promise<void> {
    if (!sessionId.value) return
    const sid = sessionId.value
    const epoch = generation
    attachLoading.value = true
    attachError.value = ''
    try {
      const items = isMock ? mockAttachments() : await listAttachments(AGENT_ID, sid)
      if (epoch !== generation || sid !== sessionId.value) return
      attachments.value = items
    } catch {
      if (epoch !== generation || sid !== sessionId.value) return
      attachments.value = []
      attachError.value = '附件接口暂不可用（需登录后调用）'
    } finally {
      if (epoch === generation && sid === sessionId.value) attachLoading.value = false
    }
  }

  /** 上传（含前置校验）；返回被拒原因供 UI 提示 */
  async function uploadFiles(files: File[]): Promise<{ message: string }[]> {
    if (!sessionId.value) return []
    const { accepted, rejected } = precheckFiles(files)
    const msgs = rejected.map((r) => ({ message: r.message }))
    for (const f of accepted) {
      try {
        if (isMock) {
          attachments.value.push({
            fileId: `mock-${Date.now()}-${f.name}`,
            ref: f.name.replace(/[^\x00-\x7F]/g, '') || 'file',
            fileName: f.name,
            size: f.size,
          })
        } else {
          const item = await uploadAttachment(AGENT_ID, sessionId.value, f)
          attachments.value.push(item)
        }
      } catch (e) {
        msgs.push({ message: `「${f.name}」上传失败：${describeError(e)}` })
      }
    }
    return msgs
  }

  async function removeAttachment(fileId: string): Promise<void> {
    if (isMock) {
      attachments.value = attachments.value.filter((a) => a.fileId !== fileId)
      return
    }
    try {
      await deleteAttachment(AGENT_ID, sessionId.value, fileId)
      attachments.value = attachments.value.filter((a) => a.fileId !== fileId)
    } catch (e) {
      attachError.value = `删除失败：${describeError(e)}`
    }
  }

  /* ── 运行时的文件视图组装 ─────────────────────────────────── */

  /** 能被当文本读进沙箱的扩展名 —— 二进制/图片不进视图 */
  const TEXTY = new Set([
    'txt', 'md', 'markdown', 'json', 'csv', 'tsv',
    'js', 'mjs', 'cjs', 'ts', 'py', 'java', 'sql', 'sh',
    'html', 'css', 'xml', 'yaml', 'yml',
  ])

  /**
   * 收集本次执行要给代码看的文件。
   *
   * 取「产物树里的全部文本文件」，当前文件用编辑器里的内容覆盖
   * （可能未保存 —— 这正是「改了先跑一遍」的意义）。
   * 单个文件拉取失败不中断整体：少一个文件只是代码读它时报 ENOENT，
   * 比整次运行失败好排查。
   */
  async function collectSandboxFiles(entryPath: string, entryCode: string): Promise<SandboxFile[]> {
    const out: SandboxFile[] = [{ path: entryPath, content: entryCode }]

    for (const node of tree.value ?? []) {
      if (node.type !== 'file') continue
      if (node.path === entryPath) continue
      const ext = node.path.split('.').pop()?.toLowerCase() ?? ''
      if (!TEXTY.has(ext)) continue

      // 已经加载在编辑器里的，直接用内存里的（可能也改过）；不可预览的跳过
      if (node.path === selectedPath.value && content.value) {
        const text = content.value.content
        if (text != null) out.push({ path: node.path, content: text })
        continue
      }
      try {
        if (isMock) {
          const m = mockArtifactContent(node.path)
          if (!m.binary) out.push({ path: node.path, content: m.content })
        } else {
          const c = await fetchArtifactContent(AGENT_ID, sessionId.value, node.path)
          /* 后端在二进制 / 超限 / 非 UTF-8 时把 content 置 null 并给 reason —— 据此过滤，
             不再依赖它并不返回的 binary / truncated（那两个字段只有 mock 分支有） */
          if (c.content != null && !c.truncated) out.push({ path: node.path, content: c.content })
        }
      } catch {
        // 拉不到就跳过 —— 代码读到时会看到可读清单，知道缺什么
      }
    }
    return out
  }

  /* Read-only background synchronization, independent from artifact-poll configuration. */
  async function syncSession(deep = false): Promise<void> {
    /* 2026-10-09：待填表单（interrupts）不受 state==='running' 限制 —— 闸门可能是**后台唤醒 run**
       渲染的（inbox 唤醒 / 看门狗补醒），本页那条流早收尾了，state 会一直停在 running，
       旧写法于是永远拉不到闸门（实测：A 题 16:47 渲染的「闸门 1」，界面一直看不到，
       因为 state 卡在 running，syncSession 在第一行就 return 了）。
       对话记录与进度仍然只在非 running 时同步，避免覆盖正在流式写入的内容。 */
    if (isMock || !sessionId.value || syncEpoch === generation) return
    const sid = sessionId.value
    const epoch = generation
    syncEpoch = epoch
    const running = state.value === 'running'
    const current = (): boolean => epoch === generation && sid === sessionId.value
    try {
      const results = await Promise.allSettled([
        fetchSessionTranscript(AGENT_ID, sid), listPendingInterrupts(AGENT_ID, sid), fetchSessionProgress(AGENT_ID, sid),
      ])
      if (!current()) return
      const [transcript, forms, progress] = results
      const failures: string[] = []
      if (!running && transcript.status === 'fulfilled') messages.value = mergeTranscript(messages.value, transcript.value.messages)
      else if (!running) failures.push('对话历史')
      if (forms.status === 'fulfilled') {
        const pending = forms.value
        // A timeout/new gate invalidates the partial batch; never submit stale answers.
        const knownIds = new Set([...interrupts.value.map(interruptIdOf), ...stagedForms.keys()])
        if (stagedAnswers.size && (pending.length !== knownIds.size || pending.some(p => !knownIds.has(interruptIdOf(p))))) {
          stagedAnswers.clear(); stagedForms.clear()
          approvalNotice.value = pending.length ? '后台待确认项已变化，请重新填写当前表单。' : '原待确认项已结束或超时，暂存答复未提交。'
        }
        if (!stagedAnswers.size) {
          interrupts.value = pending
          if (pending.length) {
            activeThreadId.value = sid
            const originalRunId = pending[0]?.metadata?.['runId']
            activeRunId.value = typeof originalRunId === 'string' && originalRunId ? originalRunId : AGENT_ID
            lastChannel.value = 'agent'
          }
        }
      } else failures.push('待确认表单')

      /* 登记表报不出东西时，再扫一遍转录 —— 后端唤醒 run 的闸门只可能留在那里
         （登记表被清空/重启丢失那一档，只有扫转录才捞得回来）。
         用同一次 Promise.allSettled 已经拉回来的转录，不另发请求。
         ⚠️ 只在 deep 那一档做（通知信号 / 切回可见 / 首次进工作台）：定时轮询也扫的话，
         稳态下会每 5 秒多打一次登记表接口，纯属浪费。 */
      if (deep && !interrupts.value.length && !stagedAnswers.size && transcript.status === 'fulfilled') {
        const pending = await rebuildPendingGate(AGENT_ID, sid, transcript.value.messages as SessionTextMessage[])
        if (
          current() &&
          pending &&
          !stagedAnswers.size &&
          !interrupts.value.length &&
          rebuiltGateId !== pending.interruptId
        ) {
          adoptRebuiltGate(sid, pending)
        }
      }
      if (!running && progress.status === 'fulfilled') {
        const p = progress.value
        progressText.value = p.hitlStuck ? '后台存在无法通过当前表单处理的审批，需要检查或恢复会话。' :
          p.stallNow ? '后台长时间没有新进展，可能停滞；请检查服务日志，不要重复提交相同任务。' :
          p.nextAction === 'WAIT_HITL' ? '后台正在等待人工确认，请处理待确认表单。' :
          p.nextAction === 'CONTINUE' ? '后台仍需推进；本页会同步后续回复，无需反复发送“继续”。' :
          p.nextAction === 'IDLE' ? '后台暂时阻塞，请查看最后回复中的缺件或操作要求。' : ''
      } else failures.push('后台进度')
      syncError.value = failures.length ? failures.join('、') + '同步失败，将自动重试；请检查登录状态或网络。' : ''
    } finally { if (syncEpoch === epoch) syncEpoch = undefined }
  }

  function startPollingIfNeeded(): void {
    if (isMock || !sessionId.value) return
    stopPolling()
    /* P0：订阅 user 级实时通知流。
       后端自发 run（wakeup 续跑 / 看门狗补投 / 唤醒轮里挂起的 HITL 闸门）不在本页那条
       SSE 里，只靠 5s 轮询会撞上「闸门在盲窗里被消解」—— 表现为闸门不弹或弹了又消失。
       通知流把游标信号即时推来，这里立刻触发一次既有同步（transcript / 进度 / 待填表单）。 */
    notifyUnsub = subscribeSessionActivity((activity) => {
      if (activity.sessionId !== sessionId.value) return
      /* 通知流是「有事发生」的权威信号 —— 走 deep，顺带把转录里挂起的闸门扫一次 */
      void syncSession(true)
    })
    /* kuaike 的 visibilitychange 补拉：轮询在 document.hidden 时一律 return，
       切回可见却不补一次的话，后台唤醒的闸门 / 进度会一直看不到
       （用户切回来只看到一片「还在等」）。
       ⚠️ 无 DOM 环境（离线单测）里没有 document —— 整段跳过，别把测试打挂。 */
    if (!visibilityHandler && typeof document !== 'undefined') {
      visibilityHandler = () => {
        if (document.hidden) return
        if (sessionId.value) void syncSession(true)
      }
      document.addEventListener('visibilitychange', visibilityHandler)
    }
    let lastArtifacts = Date.now()
    // 通知流已建连时把兜底轮询降频（信号为主路径，轮询只为最终一致）；未连上保持 5s 覆盖。
    pollTimer = window.setInterval(() => {
      if (typeof document !== 'undefined' && document.hidden) return
      void syncSession()
      if (state.value !== 'running' && Date.now() - lastArtifacts >= (ARTIFACTS_POLL_MS > 0 ? ARTIFACTS_POLL_MS : 10000)) {
        lastArtifacts = Date.now()
        void refreshTree()
      }
    }, isNotifyStreamConnected() ? 20000 : 5000)
  }

  function stopPolling(): void {
    if (pollTimer !== undefined) {
      window.clearInterval(pollTimer)
      pollTimer = undefined
    }
    notifyUnsub?.()
    notifyUnsub = null
    if (visibilityHandler && typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', visibilityHandler)
    }
    visibilityHandler = null
  }

  return {
    // 上下文
    sessionId, problemId,
    // 对话
    messages, state, runError, syncError, progressText, approvalNotice, isRunning,
    // 执行
    execs, latestExec, hasExecuted, interrupts, lastChannel,
    // 产物
    tree, treeLoading, treeError, emptyReason,
    selectedPath, content, contentLoading, contentError, dirty, saving, baseline,
    // 附件
    attachments, attachLoading, attachError,
    // 环境信息
    isMock, retentionDays: RETENTION_DAYS,
    // 动作
    attach, detach, syncSession, send, resumeInterrupt, cancelInterrupts, stop, runLocal, runFile, clearExecs,
    refreshTree, openFile, editContent, saveFile, closeFile, revertFile,
    loadAttachments, uploadFiles, removeAttachment,
  }
})

/* ── 工具 ──────────────────────────────────────────────────────── */

/**
 * 把嵌套产物树摊平成列表。
 *
 * 目录**不进入结果列表** —— 它们只作为路径前缀体现在文件名里
 * （`data/附件1.csv`）。这样列表里每一项都可点开预览，
 * 不会出现「点了目录没反应」的死交互。
 */
function flatten(nodes: ArtifactNode[]): ArtifactNode[] {
  const out: ArtifactNode[] = []
  const walk = (list: ArtifactNode[], prefix = ''): void => {
    for (const n of list) {
      const display = prefix ? `${prefix}/${n.name}` : n.name
      if (n.children) {
        // 目录：只作为路径前缀向下走（空目录不产出条目，避免点开无反应）
        if (n.children.length) walk(n.children, display)
      } else {
        out.push({ ...n, name: display, type: 'file' })
      }
    }
  }
  walk(nodes)
  return out
}
function describeError(e: unknown): string {
  if (e instanceof Error) return e.message
  return String(e)
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => window.setTimeout(r, ms))
}
