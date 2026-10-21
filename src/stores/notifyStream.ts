/**
 * 用户级实时通知流 —— 对接后端 `GET /api/notify/stream`（SSE，常驻）。
 *
 * 背景：AG-UI 的 `POST /agui/run` 只推送**前端自己发起**的那条 run；服务端自发的 run
 * （后台子智能体完成 → wakeup 续跑、看门狗补投递、唤醒轮里挂起的 HITL 闸门）前端收不到。
 * 仅靠定时轮询 transcript 兜底有两个问题：感知延迟（闸门在盲窗里被消解，轮询轮不到，
 * 表现为「闸门不弹 / 弹了又自己消失」）与成本（绝大多数轮询无新内容）。
 *
 * 本模块订阅 user 级通知流：信号只带游标 `{ sessionId, runId, eventSequence, reason }`，
 * **不含消息内容**。当前会话收到信号 → 立刻触发一次既有的同步（transcript / 进度 /
 * 待填表单），把「感知延迟」从轮询周期压到毫秒级。
 *
 * 通道特性（与后端 NotifyStreamRegistry 契约对齐）：
 * - **尽力而为**：不保交付、不保序；丢失由兜底轮询覆盖，重复由同步侧的去重吸收
 *   —— 因此本模块不做本地去重 / 缓冲。
 * - **全应用单例**：无论多少订阅者，只有一条物理连接（引用计数管理）。
 * - **自动重连**：断流后指数退避（1s 起，封顶 30s）；重连成功不补发。
 *
 * 来源：kuaike `src/stores/notifyStream.ts`（跨项目验证过）。
 */

import { httpStreamGet } from '@/api/http'
import { parseAguiStream } from '@/api/agui'

/** 一条会话活动信号（后端 run-activity 事件 payload） */
export interface SessionActivity {
  sessionId: string
  runId: string
  /** agui_events 水位线；wakeup / interrupt 路径不经落库时为 null */
  eventSequence: number | null
  /** run-finished | wakeup | interrupt */
  reason: string
}

type ActivityHandler = (activity: SessionActivity) => void

const RECONNECT_BASE_MS = 1_000
const RECONNECT_MAX_MS = 30_000

// ── 模块级单例连接（引用计数） ─────────────────────────────────────────
let refCount = 0
let abortController: AbortController | null = null
/** 期望状态：false = 需要一条活连接（或有订阅者） */
let stopped = true
/**
 * 实际状态：是否已有一条 runLoop 在运行（含退避 sleep 中）。
 *
 * ⚠️ 与 stopped 分离是防连接泄漏的关键：「停止 → 立刻重订阅」时，若只有 stopped 一个
 * 状态位，旧循环从退避 sleep 醒来会看到 stopped 已被改回 false 而继续跑 —— 两条循环
 * 并行、连接数单调上涨。loopActive 表示「已有循环负责」。
 */
let loopActive = false
/** 当前是否有一条活着的物理连接（供兜底轮询判断降频） */
let physicalConnected = false
const handlers = new Set<ActivityHandler>()

/**
 * 通知流是否健康（存在未断开的物理连接）。
 * 仅供兜底轮询降频判断：true 表示信号主路径可用，兜底定时器可拉长周期。
 */
export function isNotifyStreamConnected(): boolean {
  return physicalConnected
}

/** 解析一条 run-activity payload；形状不对的信号静默丢弃 */
function toActivity(raw: unknown): SessionActivity | null {
  if (!raw || typeof raw !== 'object') return null
  const obj = raw as Record<string, unknown>
  if (typeof obj.sessionId !== 'string' || !obj.sessionId) return null
  return {
    sessionId: obj.sessionId,
    runId: typeof obj.runId === 'string' ? obj.runId : '',
    eventSequence: typeof obj.eventSequence === 'number' ? obj.eventSequence : null,
    reason: typeof obj.reason === 'string' ? obj.reason : '',
  }
}

function notifyAll(activity: SessionActivity): void {
  for (const handler of handlers) {
    try {
      handler(activity)
    } catch (e) {
      console.error('[notifyStream] handler 异常', e)
    }
  }
}

/**
 * 连接主循环：建立流 → 消费到断开 → 退避重连，直到最后一个订阅者离开。
 * 静默（不弹 toast）：断连是常态，由兜底轮询保证一致。
 */
async function runLoop(): Promise<void> {
  let backoffMs = RECONNECT_BASE_MS
  while (!stopped) {
    abortController = new AbortController()
    try {
      const res = await httpStreamGet('/api/notify/stream', abortController.signal)
      // 响应头已到 = 物理连接建立；流关闭 / 异常时复位。
      physicalConnected = true
      backoffMs = RECONNECT_BASE_MS
      await parseAguiStream(
        res,
        (ev) => {
          const activity = toActivity(ev)
          if (activity) notifyAll(activity)
        },
        abortController.signal,
      )
    } catch {
      // 静默：连接失败 / 中断 / 后端未部署（404），交给退避重试与兜底轮询
    } finally {
      physicalConnected = false
      abortController = null
    }
    if (stopped) break
    await new Promise((r) => setTimeout(r, backoffMs))
    backoffMs = Math.min(RECONNECT_MAX_MS, backoffMs * 2)
  }
}

function startIfNeeded(): void {
  stopped = false
  if (loopActive) return
  loopActive = true
  void runLoop().finally(() => {
    loopActive = false
  })
}

function stopIfLast(): void {
  if (refCount > 0) return
  stopped = true
  abortController?.abort()
  abortController = null
}

/**
 * 订阅会话活动通知（全应用共享一条物理连接）。
 *
 * @returns 取消订阅函数
 */
export function subscribeSessionActivity(handler: ActivityHandler): () => void {
  handlers.add(handler)
  refCount += 1
  startIfNeeded()
  return () => {
    handlers.delete(handler)
    refCount -= 1
    stopIfLast()
  }
}

// Vite HMR 守卫：热更新会重置模块状态，但旧闭包里的 runLoop 仍在跑 —— 每次 HMR 泄漏
// 一条 SSE 连接。模块被替换前显式终止旧循环。
if (import.meta.hot) {
  import.meta.hot.dispose(() => {
    stopped = true
    abortController?.abort()
    abortController = null
    physicalConnected = false
  })
}
