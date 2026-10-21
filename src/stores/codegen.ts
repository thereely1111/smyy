/**
 * 「S3 解题代码」通道的状态机（与 `@/stores/analysis` 的单问深挖同构）
 *
 * 职责边界：
 *   - 发起一问的代码生成（**按问**，幂等）、缓存代码包
 *   - 刷新后先把会话里已有的代码产物接回来（reuse），读到就不重跑
 *   - 把结果（CodePackage）交给 core 的桥接层展示
 *
 * 与上游的关系：
 *   - **同一会话**：threadId 经 `analysis/flow.threadIdOf(caseId)` 取，与 S1/S2 相同 ——
 *     智能体要在本会话空间里读上游落盘的分析与动画产物。
 *   - **前置条件**：S1 done 才允许 start（上游没产出，S3 无从谈起）；
 *     这个判断由 core 的桥接层把关（它知道 S1 的状态），本模块只管跑。
 *
 * 换模型：包上记着生成时用的模型名。用户换了模型再走一遍时，
 * 名字对不上就**重跑**，不会把旧模型的结果挂到新模型名下。
 * 刷新后模型名没有产物可依，改记在 localStorage（见 modelKey）。
 *
 * 失败语义：不抛错。失败一律落 'failed' + 原因，由等待气泡如实显示，
 * 并回落内置剧本代码（标注来源，绝不冒充真实生成）。
 */

import { createUuid } from '@/utils/uuid'
import type { CaseRef } from '@/stores/analysis'
import { artifactPaths, threadIdOf } from '@/stores/analysis'
import { collectCodePackage, runCodeStage } from '@/api/codeRun'
import type { CodePackage, CodeProgress } from '@/api/codeRun'
import {
  liveBeginChannel,
  liveBind,
  liveCloseChannel,
  liveFeedChannel,
  liveRender as liveRenderOn,
} from '@/utils/analysis/live'

export type CodeStatus = 'idle' | 'running' | 'done' | 'failed'
export type { CodePackage, CodeProgress, CodeFile } from '@/api/codeRun'

interface QRun {
  status: CodeStatus
  pkg: CodePackage | null
  failure: string
  progress: CodeProgress
  startedAt: number
  skipped: boolean
  inflight: Promise<void> | null
  controller: AbortController | null
}

const runs = new Map<number, QRun>()

function run(qi: number): QRun {
  let r = runs.get(qi)
  if (!r) {
    r = {
      status: 'idle',
      pkg: null,
      failure: '',
      progress: { textLength: 0, toolCalls: 0, lastTool: '' },
      startedAt: 0,
      skipped: false,
      inflight: null,
      controller: null,
    }
    runs.set(qi, r)
  }
  return r
}

/* ── 模型名记忆（刷新后判定「这一问的包是不是这个模型生成的」） ────────── */

const MODEL_KEY = 'smyy.codegen_model'

function modelMap(): Record<string, string> {
  try {
    const raw = localStorage.getItem(MODEL_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw) as unknown
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed as Record<string, string>
  } catch {
    // 坏掉就当没有，不影响本次会话
  }
  return {}
}

const slotOf = (caseId: string, qi: number): string => `${caseId}#${qi}`

function modelOf(caseId: string, qi: number): string {
  return modelMap()[slotOf(caseId, qi)] ?? ''
}

function rememberModel(caseId: string, qi: number, name: string): void {
  try {
    localStorage.setItem(MODEL_KEY, JSON.stringify({ ...modelMap(), [slotOf(caseId, qi)]: name }))
  } catch {
    // 隐私模式：本次会话仍能用，只是刷新后可能重跑一次
  }
}

/* ── 对外查询 ─────────────────────────────────────────────────────────── */

export function questionStatus(qi: number): CodeStatus {
  return runs.get(qi)?.status ?? 'idle'
}

export function questionFailureText(qi: number): string {
  return runs.get(qi)?.failure ?? ''
}

export function questionProgressText(qi: number): string {
  const r = runs.get(qi)
  if (!r) return ''
  const secs = r.startedAt ? Math.round((Date.now() - r.startedAt) / 1000) : 0
  const bits = [`已用时 ${secs} 秒`]
  if (r.progress.toolCalls) bits.push(`工具调用 ${r.progress.toolCalls} 次`)
  if (r.progress.lastTool) bits.push(`最近：${r.progress.lastTool}`)
  if (r.progress.textLength) bits.push(`输出 ${r.progress.textLength} 字`)
  return bits.join(' · ')
}

/** 这一问的代码包就绪时返回它；未就绪返回 null */
export function takeQuestion(qi: number): CodePackage | null {
  const r = runs.get(qi)
  return r && r.status === 'done' && r.pkg ? r.pkg : null
}

/* ── 实时区（等待气泡里的进展明细，2026-10-13）───────────────────────────── */

/**
 * 通道名带问题号 —— 代码通道是**按问**的：一问一条等待气泡，第二问若与第一问
 * 共用一个通道名，它的事件会画进第一问那条（即将被 remove 的）气泡里。
 */
const liveCh = (qi: number): string => `code:${qi}`

/** 第 qi 问的等待气泡把它的 `.lv-stream` 交进来（传 null 表示气泡已摘掉） */
export function liveAttach(qi: number, el: HTMLElement | null): void {
  liveBind(liveCh(qi), el)
  if (el) liveRenderOn(el)
}

/** 增量刷新气泡实时区（core 的 700ms 轮询 + 事件到达时都会调） */
export function liveRender(el?: HTMLElement | null): void {
  if (el) liveRenderOn(el)
}

/** 用户点等待气泡里的「跳过」→ 按失败处理，回落内置剧本 */
export function skipQuestion(qi: number): void {
  const r = runs.get(qi)
  if (!r || r.status !== 'running') return
  r.skipped = true
  r.controller?.abort()
  r.failure = '你选择了跳过等待'
  r.status = 'failed'
}

/* ── 发起 ─────────────────────────────────────────────────────────────── */

/**
 * 发起这一问的代码生成（幂等）。
 *
 * - 正在跑 → 复用同一个 promise
 * - 已经有结果、且是**同一个模型**生成的 → 什么都不做
 * - 已有结果但换了模型 → 重跑（旧包丢弃）
 * - 之前失败过 → 允许重试
 */
export function startQuestion(ref: CaseRef, qi: number, model: { name: string; desc?: string }): Promise<void> {
  const r = run(qi)
  if (r.inflight) return r.inflight
  if (r.status === 'done' && r.pkg && r.pkg.model === model.name) return Promise.resolve()

  /* 换模型：旧包不能再留，否则界面上会挂着另一个模型的代码 */
  if (r.status === 'done' && r.pkg && r.pkg.model !== model.name) {
    r.pkg = null
    r.status = 'idle'
  }

  r.status = 'running'
  r.failure = ''
  r.skipped = false
  r.progress = { textLength: 0, toolCalls: 0, lastTool: '' }
  r.startedAt = Date.now()

  const threadId = threadIdOf(ref.caseId)
  const runId = `run-code-${createUuid()}`
  const controller = new AbortController()
  r.controller = controller

  /* 等待气泡若已登记，先清掉上一轮内容；没登记就什么也不做 */
  liveBeginChannel(liveCh(qi))

  const p = runCodeStage(
    {
      case_id: ref.caseId,
      title: ref.title,
      question_id: qi + 1,
      model: { name: model.name, desc: model.desc },
    },
    {
      threadId,
      runId,
      // S1/S2 的产物路径原样转发：总控按它定位上游
      upstreamPaths: artifactPaths(),
      signal: controller.signal,
      onProgress: (prog) => {
        if (r.skipped) return
        r.progress = prog
      },
      /* 事件流实时画进等待气泡（思考 / 工具 / 正文） */
      onEvent: (ev) => {
        if (r.skipped) return
        liveFeedChannel(liveCh(qi), ev)
      },
    },
  )
    .then((outcome) => {
      if (r.skipped) return
      if (outcome.pkg && outcome.pkg.mainText) {
        r.pkg = outcome.pkg
        r.status = 'done'
        r.failure = ''
        rememberModel(ref.caseId, qi, model.name)
        return
      }
      r.status = 'failed'
      r.failure = outcome.failureNote || (outcome.replyText ? '智能体没有写出代码产物文件' : '代码通道没有返回可用的源码包')
    })
    .catch((e: unknown) => {
      if (r.skipped) return
      r.status = 'failed'
      r.failure = e instanceof Error ? e.message : '代码通道调用失败'
    })
    .finally(() => {
      /* 流已收（或断了）：结清打字机缓冲，气泡随后会被摘掉 */
      liveCloseChannel(liveCh(qi))
      r.controller = null
      r.inflight = null
      if (r.skipped) r.status = 'failed'
    })

  r.inflight = p
  return p
}

/**
 * 刷新后用已有产物接回，避免重跑一次模型。
 *
 * 只在「本地没有结果」时调用。会话里没有代码产物、或**这一问的包是别的模型
 * 生成的**（模型名对不上）都返回 false，由调用方重跑。
 */
export async function reuseQuestion(
  ref: CaseRef,
  qi: number,
  model: { name: string; desc?: string },
): Promise<boolean> {
  const r = run(qi)
  if (r.status === 'running') return false

  const remembered = modelOf(ref.caseId, qi)
  /* 上次是换模型前生成的 → 不复用，直接重跑，免得把旧模型的代码挂在新模型名下 */
  if (remembered && remembered !== model.name) return false

  try {
    const { pkg } = await collectCodePackage(threadIdOf(ref.caseId), qi, model.name)
    if (!pkg || !pkg.mainText) return false
    r.pkg = pkg
    r.status = 'done'
    r.failure = ''
    rememberModel(ref.caseId, qi, model.name)
    return true
  } catch {
    return false
  }
}

/** 清掉结果（重新开始一道题时由桥接层调用） */
export function reset(): void {
  for (const r of runs.values()) r.controller?.abort()
  runs.clear()
}
