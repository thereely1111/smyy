/**
 * 「可视化演示」通道的状态机（对接**同一个**智能体、**同一个**会话的 S2 阶段）
 *
 * 2026-10-13 改口径：动画不再走「独立的数模动画生成助手」（现役 java 后端里根本
 * 没有这个 agent，旧链路必然报 `Agent not found: bfd1e86e-…`）。按 10-10 的合并口径，
 * 动画能力在 `数学建模解答助手` 的 `skills/mm-2-*` 里 —— 所以这条链现在是：
 *
 *   同一 agentId（`AGENT_ID`）+ **S1 的同一个 threadId**（`threadIdOf`）
 *   → 发 S2 请求（`buildAnimationPrompt`：派发「分析动画智能体」）
 *   → 产物落在同一会话空间的 `artifacts/<case_id>/animation/*.html`
 *
 * 三条硬约束（都踩过）：
 *   1. **会话必须与 S1 同一条**：智能体只能在本会话空间内按相对路径读到 S1 的
 *      分析产物（`analysis_result.json` / `truth/*`）。自己另开映射＝会话分叉，
 *      S2 进去也读不到上游，产物不会对。故只经 `threadIdOf` 取，不再自建映射。
 *   2. **闸门 1 必须被消费掉才发 run**：S1 收尾时智能体会挂 `ui_form_advanced`
 *      （分析与模型确认）。框架对「线程上还有未决中断」的**裸 run** 一律拒
 *      （`Thread has unresolved interrupts…`）。而且这道闸门按契约**必须真人提交**
 *      ——这里绝不自动应答。所以发 S2 前先查 `pendingInterruptIds`：有未决中断
 *      就只显示「等你确认」，等它被消费掉再发。
 *   3. **流关闭 ≠ 活干完**：总控把 S2 派给子智能体后本轮流就关了，渲染要再跑十几
 *      分钟。完成判据是产物（`animation/*.html` 逐档落盘 + `qa/qa_animation.json`）。
 *
 * 失败语义：不抛错。失败一律落 'failed' + 原因，由右栏/浮窗如实显示，
 * 绝不回落内置演示冒充真实结果。
 */

import type { CaseRef } from '@/stores/analysis'
import { threadIdOf } from '@/stores/analysis'
import { collectAnimations, runAnimationStage } from '@/api/animRun'
import type { RealAnimation } from '@/api/animRun'
import { pendingInterruptIds } from '@/api/interruptRelease'
import { AGENT_ID } from '@/config'
import { createUuid } from '@/utils/uuid'
import {
  liveBeginChannel,
  liveBind,
  liveCloseChannel,
  liveFeedChannel,
  liveRender as liveRenderOn,
} from '@/utils/analysis/live'

export type AnimationStatus = 'idle' | 'running' | 'done' | 'failed'
export type { RealAnimation } from '@/api/animRun'

/** 进度摘要（浮窗「已用时 / 工具调用」那行读它） */
export interface AnimProgress {
  textLength: number
  toolCalls: number
  lastTool: string
}

/**
 * 最长等待（毫秒）。动画流水线 = 派发 + scene_map + 逐档渲染 + 验收，
 * 实测量级与 S1 相当，给同样的 40 分钟；到点按失败收尾（后台可能仍在跑）。
 *
 * 注意：**从真正发出 S2 那一刻起算**，不含「等用户确认分析（闸门 1）」那段 ——
 * 用户看分析要多久是他的事，不该记在动画头上。
 */
const MAX_WAIT_MS = 40 * 60 * 1000

/** 两次「看产物」之间的间隔。收产物是轻请求（一棵产物树），10 秒足够跟手 */
const POLL_MS = 10 * 1000

/** S2 还没发出去（闸门 1 未消费）时浮窗显示的提示 */
const WAIT_CONFIRM_HINT = '等你在对话区确认分析后开始'

const state = {
  current: null as CaseRef | null,
  status: 'idle' as AnimationStatus,
  anims: [] as RealAnimation[],
  failure: '',
  progress: { textLength: 0, toolCalls: 0, lastTool: '' } as AnimProgress,
  /** 通道起跑时刻（浮窗「已用时」用它；闸门等待也算在内，用户看得见在等什么） */
  startedAt: 0,
  /** 真正发出 S2 的时刻；0 = 还没发（在等闸门 1）。超时判据以它为基准 */
  firedAt: 0,
  /** 流是否已经关掉（关掉只说明本轮结束，完成与否看验收产物） */
  streamDone: false,
  inflight: null as Promise<void> | null,
  controller: null as AbortController | null,
  generation: 0,
  listeners: [] as (() => void)[],
}

export function status(): AnimationStatus {
  return state.status
}

export function currentCase(): CaseRef | null {
  return state.current
}

export function failureText(): string {
  return state.failure
}

export function progressText(): string {
  const secs = state.startedAt ? Math.round((Date.now() - state.startedAt) / 1000) : 0
  const bits = [`已用时 ${secs} 秒`, `已出 ${state.anims.length} 档`]
  if (state.progress.toolCalls) bits.push(`工具调用 ${state.progress.toolCalls} 次`)
  if (state.progress.lastTool) bits.push(`最近：${state.progress.lastTool}`)
  return bits.join(' · ')
}

/** 当前已收到的档位（running 期间也在长）——右栏「边跑边出」读这里 */
export function knownAnims(): RealAnimation[] {
  return state.anims
}

/** 档位有新增时通知订阅者（右栏重画、登记进全屏列表） */
export function onUpdate(cb: () => void): () => void {
  state.listeners.push(cb)
  return () => {
    state.listeners = state.listeners.filter((x) => x !== cb)
  }
}

function notify(): void {
  for (const cb of [...state.listeners]) {
    try {
      cb()
    } catch {
      /* 订阅者出错不拖垮通道 */
    }
  }
}

/** 动画全部就绪时返回档位列表；未就绪返回 null（兼容旧调用方） */
export function takeAnims(): RealAnimation[] | null {
  return state.status === 'done' && state.anims.length ? state.anims : null
}

/* ── 实时区（浮窗里的进展明细，2026-10-13）───────────────────────────────── */

/**
 * 通道名。浮窗只有一个，所以是常量 —— 与工作台对话区（按任务分桶）不同，
 * 动画这条链路全局只有一条在跑。
 */
const LIVE_CH = 'anim'

/**
 * 浮窗把它的 `.lv-stream` 交进来（传 null 表示浮窗已摘下）。
 *
 * 为什么要这么绕（core 登记 → store 投递，而不是 store 直接拿容器）：
 * 浮窗是**在跑的时候才存在**的（收起 / 完成 / 失败都会被摘掉），而 run 是
 * store 发起的 —— 让 store 抓 DOM 会在「先起跑、后建浮窗」时抓空。
 */
export function liveAttach(el: HTMLElement | null): void {
  liveBind(LIVE_CH, el)
  if (el) liveRenderOn(el)
}

/** 增量刷新浮窗实时区（core 的 900ms 轮询 + 事件到达时都会调） */
export function liveRender(el?: HTMLElement | null): void {
  if (el) liveRenderOn(el)
}

/** 清掉结果（重新开始一道题时由桥接层调用） */
export function reset(): void {
  state.generation += 1
  state.controller?.abort()
  state.controller = null
  state.status = 'idle'
  state.anims = []
  state.failure = ''
  state.progress = { textLength: 0, toolCalls: 0, lastTool: '' }
  state.current = null
  state.startedAt = 0
  state.firedAt = 0
  state.streamDone = false
  state.inflight = null
}

/**
 * 发起一次动画生成（幂等，后台跑）。
 *
 * - 正在跑 → 复用同一个 promise，不会并发起两次
 * - 同一道题已经有结果 → 什么都不做
 * - 之前失败过 → 允许重试（右栏的「重试」走这里）
 *
 * 进不进入「发 run」这一步由轮询决定：先接回会话里已存在的动画产物，
 * 再等闸门 1 被消费掉，最后才发 S2 请求。详见文件头的三条硬约束。
 */
export function start(ref: CaseRef): Promise<void> {
  if (state.status === 'running' && state.inflight) return state.inflight
  if (state.status === 'done' && state.current?.caseId === ref.caseId) return Promise.resolve()

  state.current = ref
  state.status = 'running'
  state.failure = ''
  state.progress = { textLength: 0, toolCalls: 0, lastTool: '' }
  state.streamDone = false
  state.startedAt = Date.now()
  state.firedAt = 0

  /* S1 的那条会话 —— 只此一个来源，不许自建映射（见文件头约束 1） */
  const threadId = threadIdOf(ref.caseId)
  // createUuid：http 的 ip 访问下 crypto.randomUUID 取不到，须走带兜底的实现
  const runId = `run-anim-${createUuid()}`
  const since = Date.now() - 2000
  const generation = ++state.generation
  const controller = new AbortController()
  state.controller = controller

  /* 浮窗若已登记，先把上一轮的内容清掉；没登记就什么也不做（事件会按
     「现在没人在看」丢弃，绝不落进工作台对话区那条流里） */
  liveBeginChannel(LIVE_CH)

  const alive = (): boolean => state.generation === generation && !controller.signal.aborted

  /* 边跑边收：每轮把新落盘的档位并进来。k（站点步骤号）不能按当轮收集序号定 ——
     那样先到的档占错位、后到的档会撞号。统一按文件路径全序排好再重编 k，
     与 collectAnimations 的排序口径（文件名序号前缀）一致。 */
  const pathRank = (p: string): number => {
    const name = p.replace(/\\/g, '/').split('/').pop() ?? ''
    const n = Number(name.match(/^(\d+)/)?.[1] ?? 'NaN')
    return Number.isFinite(n) ? n : Number.MAX_SAFE_INTEGER
  }
  const merge = (anims: RealAnimation[]): boolean => {
    let added = false
    for (const a of anims) {
      if (state.anims.some((x) => x.path === a.path)) continue
      state.anims.push(a)
      added = true
    }
    if (added) {
      state.anims.sort((x, y) => pathRank(x.path) - pathRank(y.path) || x.path.localeCompare(y.path))
      state.anims.forEach((a, i) => {
        a.k = String(i)
      })
    }
    return added
  }

  const poller = (async () => {
    let idleRounds = 0
    /** S2 是否已经发出去 —— 只发一次；发过之后不再查中断（那条 run 自己也管闸门） */
    let fired = false
    for (;;) {
      await new Promise((r) => window.setTimeout(r, POLL_MS))
      if (!alive()) return

      let qaReady = false
      try {
        const got = await collectAnimations(threadId, since)
        qaReady = got.qaReport != null
        if (merge(got.anims)) {
          idleRounds = 0
          state.failure = ''
          notify()
        } else {
          idleRounds += 1
        }
      } catch {
        idleRounds += 1
      }

      /* 完成判据是**验收报告落盘**（它整条流水线的收尾产物），不是流关没关 ——
         总控派发子智能体后本轮流就关了，渲染要再跑十几分钟 */
      if (qaReady) {
        if (state.anims.length) {
          state.status = 'done'
          state.failure = ''
        } else if (state.firedAt && Date.now() - state.firedAt > 3 * 60 * 1000) {
          state.status = 'failed'
          state.failure = state.failure || '动画流水线走到了验收，但没有产出任何动画档位'
        }
        notify()
        return
      }

      /* 会话里已经有动画产物（重进工作台 / 上一轮跑过）→ 直接接回，不再发 run */
      if (!fired && state.anims.length) {
        state.status = 'done'
        notify()
        return
      }

      if (!fired) {
        /* 约束 2：线程上还有未决中断（＝闸门 1 没确认）→ 一个请求都不发。
           `pendingInterruptIds` 两个来源都失败时返回空数组 ⇒ 退化成「照发」，
           那种情况下后端会如实拒，错误一样看得见。 */
        let blocked = false
        try {
          blocked = (await pendingInterruptIds(AGENT_ID, threadId)).length > 0
        } catch {
          blocked = false
        }
        if (blocked) {
          state.progress = { ...state.progress, lastTool: WAIT_CONFIRM_HINT }
          continue
        }

        fired = true
        state.firedAt = Date.now()
        state.progress = { ...state.progress, lastTool: '已发起分析动画' }
        void runAnimationStage(
          { case_id: ref.caseId, title: ref.title },
          {
            threadId,
            runId,
            /* 超时从发出 S2 起算（轮询里另有 MAX_WAIT 兜底） */
            timeoutMs: MAX_WAIT_MS,
            signal: controller.signal,
            onProgress: (p: AnimProgress) => {
              if (!alive()) return
              state.progress = p
            },
            /* 事件流实时画进浮窗（思考 / 工具 / 正文）—— 与工作台对话区同一套归约与渲染 */
            onEvent: (ev) => {
              if (!alive()) return
              liveFeedChannel(LIVE_CH, ev)
            },
          },
        )
          .then((outcome) => {
            if (!alive()) return
            if (outcome.status === 'COMPLETED') {
              state.streamDone = true
              if (merge(outcome.anims)) notify()
              return
            }
            state.streamDone = true
            /* 并发被拒：这条会话上另有一条活跃 run（多半是智能体自己把 S2 派出去了）
               → 不当失败，继续按「后台在跑」轮询产物 */
            if (/正在执行|已在执行|已有.{0,6}任务|concurrent/i.test(outcome.failureNote || '')) {
              state.progress = { ...state.progress, lastTool: '等待后台动画任务' }
              return
            }
            /* 真失败：原因如实记下；已收到的档位保留，收尾交给轮询判据 */
            state.failure = outcome.failureNote || '动画通道没有返回可用的结果'
            notify()
          })
          .catch((e: unknown) => {
            if (!alive()) return
            state.streamDone = true
            if (!state.anims.length) {
              state.status = 'failed'
              state.failure = e instanceof Error ? e.message : '动画通道调用失败'
              notify()
            }
          })
          .finally(() => {
            /* 流已收（或断了）：把打字机缓冲结清，别让正文缺个尾巴 —— 浮窗随后会被摘掉 */
            liveCloseChannel(LIVE_CH)
          })
        continue
      }

      /* 流已关但什么都没出：再宽限 3 分钟（后台可能仍在跑），到点如实报失败 */
      if (state.streamDone && !state.anims.length && state.failure &&
        state.firedAt && Date.now() - state.firedAt > 3 * 60 * 1000) {
        state.status = 'failed'
        notify()
        return
      }
      if (state.firedAt && Date.now() - state.firedAt > MAX_WAIT_MS) {
        state.status = 'failed'
        state.failure = state.anims.length
          ? '等待超时：已收到的档位保留，后续档位可能没有生成完'
          : '等待动画产物超时，后台可能仍在运行'
        notify()
        return
      }
    }
  })()

  state.inflight = poller.then(() => {
    if (!alive()) return
    state.controller = null
    state.inflight = null
  })
  return state.inflight
}

/**
 * 刷新后用本会话里已有的动画产物接回，避免重跑一次模型。
 * 会话里没有动画产物（或后端不可用）返回 false，由调用方决定是否重跑。
 */
export async function reuse(ref: CaseRef): Promise<boolean> {
  if (state.status === 'running') return false
  try {
    const threadId = threadIdOf(ref.caseId)
    const { anims } = await collectAnimations(threadId)
    if (!anims.length) return false
    state.current = ref
    state.anims = anims
    state.status = 'done'
    state.failure = ''
    notify()
    return true
  } catch {
    return false
  }
}
