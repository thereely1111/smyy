/**
 * 调「数学建模解答助手」做 S2 分析动画，并把动画产物读回来
 *
 * 范式与 `@/api/analysisRun` 一致：runAgui(SSE) + 会话产物树。
 * 关键差异只有两个：
 *   1. **同一会话**：S2 的输入是 S1 落盘的分析产物，threadId 必须与 S1 相同
 *      （经 `analysis/flow.threadIdOf` 取，不许另开映射），智能体才能在本会话
 *      空间里按相对路径读到 `truth/*.json` 与当前分析文件。
 *   2. **产物是 HTML 不是 JSON**：收集 `animation/*.html`（同名取排序最后一份，
 *      兼容重试后多版本并存），逐个读内容，交给站点用 blob iframe 挂载。
 *
 * 这条链只做「发请求、收产物」，不渲染、不碰 DOM。
 */

import { extractInterrupts, parseAguiStream, runAgui, userMessage, runEventError } from '@/api/agui'
import { fetchArtifactContent, fetchArtifactTree } from '@/api/artifact'
import { waitForArtifact } from '@/api/waitForArtifact'
import { registerInterrupts } from '@/api/formInterrupt'
import type { AguiInterrupt } from '@/types/agui'
import { AGENT_ID } from '@/config'
import { flattenArtifacts } from '@/api/analysisRun'
import type { AguiEvent } from '@/types/agui'
import type { ArtifactNode } from '@/types/sandbox'

/** 一档动画：`k` 是站点侧的演示键（'0' = 全局，'1'.. = 问题一..） */
export interface RealAnimation {
  k: string
  title: string
  sub: string
  html: string
  path: string
}

/** 进度回调：让浮窗能显示「智能体在第几步」 */
export interface AnimationProgress {
  textLength: number
  toolCalls: number
  lastTool: string
}

export interface RunAnimationOptions {
  threadId: string
  runId: string
  /** S1 登记的产物相对路径（原样转发进提示词，便于智能体定位上游） */
  upstreamPaths?: string[]
  /** 最长等待（毫秒）。映射 + 渲染 + 验收要跑确定性脚本，比全局分析宽 */
  timeoutMs?: number
  signal?: AbortSignal
  onProgress?: (p: AnimationProgress) => void
  /**
   * 每个原始 AG-UI 事件（透给浮窗的实时区）。
   *
   * 与 `onProgress` 的分工：onProgress 只给「已用时 / 工具次数」这类摘要数字，
   * 而事件流本身（思考段、工具入参、正文）要靠这个口才能画出来。
   * 不在这里做归约 —— 归约与渲染都在 `utils/analysis/live`，本模块只搬事件。
   */
  onEvent?: (ev: AguiEvent) => void
}

export interface AnimationStageRequest {
  case_id: string
  title: string
}

export interface AnimationOutcome {
  status: 'COMPLETED' | 'PARTIAL' | 'BLOCKED'
  anims: RealAnimation[]
  sceneMap: unknown | null
  qaReport: unknown | null
  replyText: string
  artifacts: string[]
  failureNote?: string
}

const ANIM_DIR_SEG = '/animation/'
const SCENE_MAP_TAIL = 'analysis/scene_map.json'
const QA_TAIL = 'qa/qa_animation.json'
/** 单会话最多收多少档（全局 + 四问 = 5，留余量防多余档位） */
const MAX_ANIMS = 8
const DEFAULT_TIMEOUT_MS = 20 * 60 * 1000

function isRecord(v: unknown): v is Record<string, unknown> {
  return !!v && typeof v === 'object' && !Array.isArray(v)
}

const normPath = (p: string): string => p.replace(/\\/g, '/')
const fileNameOf = (p: string): string => normPath(p).slice(normPath(p).lastIndexOf('/') + 1)

/** `02_问题一分析.html` → 「问题一分析」（去掉序号前缀与扩展名） */
function titleFromFileName(name: string): string {
  return name.replace(/\.html?$/i, '').replace(/^\d+[_-]/, '') || '分析动画'
}

/**
 * S2 阶段请求 → 给总控的用户消息。
 *
 * 对接的仍是总控型智能体：它自己会派发「分析动画智能体」（读上游 → scene_map →
 * render_animation.py 渲染 → check_animation.py 验收）。消息只给三样：
 * 上游产物在哪、本阶段要产出什么、附件没提供这件事实。
 */
export function buildAnimationPrompt(req: AnimationStageRequest, upstream: string[]): string {
  return [
    '这是同一道数学建模赛题的下一阶段：**S2 分析动画**（沿用本会话与同一份 case 产物，case_id 见结构化请求）。',
    '',
    '## 上游（S1）产物 —— 已在本会话空间落盘，用文件工具读取',
    '- `analysis_result.json`：其 `primary_artifacts` 才是当前分析文件的权威指针，**不要按文件名猜版本**；',
    '- `truth/problem_contract.json`',
    '- `truth/solution_map.json`',
    ...(upstream.length ? ['S1 登记的产物相对路径（原样转发）：', ...upstream.map((p) => `- ${p}`)] : []),
    '',
    '## 本阶段任务',
    '按你的总控流程把 S2 派发给「分析动画智能体」：把**已审核**的分析映射成 scene_map，用确定性脚本渲染分档动画 HTML，并完成静态验收。不要重新分析题目、不要改动模型路线。',
    '',
    '## 期望产物（放在你会话空间的 `artifacts/<case_id>/` 下）',
    '- `analysis/scene_map.json`',
    '- `animation/*.html`（第一档固定「全局问题分析」，之后每问一档）',
    '- `qa/qa_animation.json`',
    '',
    '## 边界',
    '- 本题附件内容未随请求提供：`facts.json` 可能不存在，图表数组引用不到真实数据时按你的规范标注示意或省略该图表，**不要编造数据**。',
    '- 本次由站点自动发起，**不需要发闸门表单**；确有需要用户拍板的事，写进产物里说明即可。',
    '',
    '## 结构化请求',
    '```json',
    JSON.stringify(req, null, 2),
    '```',
  ].join('\n')
}

/**
 * 从产物树里收集动画档位文件路径（不读内容）。
 *
 * 同名多份（重试产生多版本）取路径排序最后一份；按文件名的序号前缀排序，
 * 保证 '0' = 全局档、'1'.. 与问题顺序一致。
 */
export function collectAnimationPaths(files: ArtifactNode[]): string[] {
  const byName = new Map<string, ArtifactNode>()
  for (const f of files) {
    const p = normPath(f.path)
    if (!p.includes(ANIM_DIR_SEG) || !/\.html?$/i.test(p)) continue
    const name = fileNameOf(p)
    const prev = byName.get(name)
    if (!prev || (f.updatedAt ?? 0) > (prev.updatedAt ?? 0) ||
      ((f.updatedAt ?? 0) === (prev.updatedAt ?? 0) && normPath(prev.path).localeCompare(p) < 0)) byName.set(name, f)
  }
  return [...byName.values()]
    .map((f) => normPath(f.path))
    .sort((a, b) => {
      const na = fileNameOf(a).replace(/\.html?$/i, '')
      const nb = fileNameOf(b).replace(/\.html?$/i, '')
      const da = na.match(/^(\d+)/)?.[1]
      const db = nb.match(/^(\d+)/)?.[1]
      if (da && db && da !== db) return Number(da) - Number(db)
      return a.localeCompare(b)
    })
    .slice(0, MAX_ANIMS)
}

/**
 * 收集并读取全部动画产物（run 结束与 reuse 共用）。
 *
 * 单个文件读不到不算整体失败 —— 其余档位照用。
 */
export async function collectAnimations(
  threadId: string,
  since = 0,
): Promise<{ anims: RealAnimation[]; sceneMap: unknown | null; qaReport: unknown | null; paths: string[] }> {
  const tree = await fetchArtifactTree(AGENT_ID, threadId)
  const files = flattenArtifacts(tree).filter((f) => !since || (f.updatedAt != null && f.updatedAt >= since))
  const paths = collectAnimationPaths(files)

  const read = async (path: string): Promise<string> => {
    try {
      return (await fetchArtifactContent(AGENT_ID, threadId, path))?.content ?? ''
    } catch {
      return ''
    }
  }
  const readJsonByTail = async (tail: string): Promise<unknown | null> => {
    const node = files.find((f) => normPath(f.path).endsWith(`/${tail}`))
    if (!node) return null
    const raw = await read(node.path)
    if (!raw) return null
    const text = raw.trim()
    const fence = text.match(/```(?:json)?\s*([\s\S]*?)```/i)
    try {
      return JSON.parse(fence ? fence[1].trim() : text)
    } catch {
      return null
    }
  }

  const anims: RealAnimation[] = []
  for (let i = 0; i < paths.length; i += 1) {
    const path = paths[i]
    const html = await read(path)
    if (!html) continue
    anims.push({
      k: String(i),
      title: titleFromFileName(fileNameOf(path)),
      sub: '智能体按题面生成',
      html,
      path,
    })
  }

  const sceneMap = await readJsonByTail(SCENE_MAP_TAIL)
  const qaReport = await readJsonByTail(QA_TAIL)
  const extra: string[] = []
  for (const f of files) {
    const p = normPath(f.path)
    if (p.endsWith(`/${SCENE_MAP_TAIL}`) || p.endsWith(`/${QA_TAIL}`)) extra.push(p)
  }
  return { anims, sceneMap, qaReport, paths: [...paths, ...extra] }
}

/** 从 SSE 事件里取工具名（字段名有版本差异，挨个试） */
function toolNameOf(ev: AguiEvent): string {
  const candidates = ['toolCallName', 'toolName', 'name']
  for (const k of candidates) {
    const v = ev[k]
    if (typeof v === 'string' && v) return v
  }
  const meta = ev['metadata']
  if (isRecord(meta) && typeof meta['toolName'] === 'string') return meta['toolName'] as string
  return ''
}

/**
 * 跑一次 S2：发请求 → 读流 → 收动画产物。
 *
 * 失败（网络、401、超时、产物全缺）返回 `BLOCKED`，**不抛错** ——
 * 调用方（flow）拿到 BLOCKED 就标失败，浮窗如实显示原因。
 */
export async function runAnimationStage(
  req: AnimationStageRequest,
  opts: RunAnimationOptions,
): Promise<AnimationOutcome> {
  const timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const startedAt = Date.now()
  const blocked: AnimationOutcome = {
    status: 'BLOCKED',
    anims: [],
    sceneMap: null,
    qaReport: null,
    replyText: '',
    artifacts: [],
  }

  const ctl = new AbortController()
  const onOuterAbort = (): void => ctl.abort()
  opts.signal?.addEventListener('abort', onOuterAbort, { once: true })
  if (opts.signal?.aborted) ctl.abort()
  const timer = window.setTimeout(() => ctl.abort(), timeoutMs)

  let text = ''
  let toolCalls = 0
  let lastTool = ''
  let interrupted = false
  let pending: AguiInterrupt[] = []
  let streamError = ''
  let finished = false

  try {
    const res = await runAgui(
      AGENT_ID,
      {
        threadId: opts.threadId,
        runId: opts.runId,
        // id 不是可选项：后端 RunAgentInput 反序列化缺 id 直接 400
        messages: [userMessage(`u-${opts.runId}`, buildAnimationPrompt(req, opts.upstreamPaths ?? []))],
      },
      ctl.signal,
    )
    await parseAguiStream(
      res,
      (ev) => {
        /* 先原样透给浮窗的实时区（归约/渲染在那边），再做本模块的摘要统计 */
        opts.onEvent?.(ev)
        const type = String(ev['type'] ?? '')
        if (/TEXT_MESSAGE_CONTENT/i.test(type)) {
          const delta = typeof ev['delta'] === 'string' ? ev['delta'] : ''
          if (delta) text += delta
        }
        if (/TOOL_CALL_START|TOOL_CALL_ARGS/i.test(type)) {
          if (/TOOL_CALL_START/i.test(type)) toolCalls += 1
          const n = toolNameOf(ev)
          if (n) lastTool = n
        }
        if (type === 'RUN_FINISHED') {
          finished = true
          const outcome = ev['outcome']
          if (isRecord(outcome) && outcome['type'] === 'interrupt') interrupted = true
        }
        const forms = extractInterrupts(ev)
        if (forms.length) { pending = forms; interrupted = true }
        streamError ||= runEventError(ev)
        opts.onProgress?.({ textLength: text.length, toolCalls, lastTool })
      },
      ctl.signal,
    )
  } catch (e) {
    return { ...blocked, replyText: text, failureNote: streamError ||
      (ctl.signal.aborted ? '动画流等待超时或已取消' : e instanceof Error ? e.message : '动画通道调用失败') }
  } finally {
    window.clearTimeout(timer)
    opts.signal?.removeEventListener('abort', onOuterAbort)
  }

  if (interrupted && !streamError && !ctl.signal.aborted) {
    try {
      if (!pending.length) throw new Error('未返回可用的确认表单')
      await registerInterrupts(AGENT_ID, opts.threadId, pending, opts.runId)
    } catch (e) {
      streamError = '待确认表单登记失败：' + (e instanceof Error ? e.message : String(e))
    }
  }
  if (streamError || interrupted || !finished || ctl.signal.aborted) {
    return { ...blocked, replyText: text, failureNote: streamError ||
      (interrupted ? '智能体需要人工确认，请到沙箱对话处理' :
        !finished ? '动画流意外断开，未收到 RUN_FINISHED' : '动画请求已取消') }
  }

  // 后台子智能体的产物不会继续进入当前 SSE；只读轮询到截止时间。
  opts.onProgress?.({ textLength: text.length, toolCalls, lastTool: '等待后台动画产物' })
  const got = await waitForArtifact(async () => {
    const result = await collectAnimations(opts.threadId, startedAt - 2000)
    return result.anims.length ? result : null
  }, startedAt + timeoutMs, opts.signal)
  if (!got) return { ...blocked, replyText: text, failureNote: opts.signal?.aborted
    ? '动画产物等待已取消' : '等待动画产物超时，后台可能仍在运行' }
  return { status: 'COMPLETED', anims: got.anims, sceneMap: got.sceneMap,
    qaReport: got.qaReport, replyText: text, artifacts: got.paths }
}
