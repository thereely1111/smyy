/**
 * 打字机消费层 —— 逐字打印
 *
 * 来源：kuaike `src/utils/typewriter.ts`（跨项目验证过）。smyy 原本**没有**这一层：
 * AG-UI 的 `TEXT_MESSAGE_CONTENT` delta 一到就直接拼进对话块，正文是「一段一段
 * 蹦出来」的，观感上不像模型在写。
 *
 * 本类刻意**不感知消息结构**：只负责把喂进来的 delta 按节奏吐出去，吐到哪由
 * 调用方在 `onDrain` 里决定（smyy 是写进 `live/blocks.ts` 的正文块）。
 *
 * 三条保证：
 *   - 零丢失、零重复、零乱序：`finish()` 的 Promise 在缓冲排空后才 resolve，
 *     调用方 `await` 它即可保证最终文本 === 所有 delta 依序拼接。
 *   - 标签页隐藏时立刻吐完：用户看不到打字过程，没必要把 `await finish()` 拖长。
 *   - 兜底：无论驱动循环为何没收敛，`FINISH_SAFETY_MS` 到点强制吐完并唤醒等待者。
 */

/** 驱动节奏：约 50 次/秒 */
const TICK_MS = 20
/** 每 tick 至少吐 1 个字符：缓冲区很小时即「逐字」，约 50 字/秒，肉眼可辨 */
const MIN_CHARS_PER_TICK = 1
/** 每 tick 至多吐的字符数：约束洪峰吞吐上限，避免大文本瞬间闪现、失掉逐字感 */
const MAX_CHARS_PER_TICK = 30
/** 加速系数 K：每 tick 吐出 `ceil(backlog / K)` 个字符 */
const BACKLOG_DIVISOR = 10
/** `finish()` 兜底超时 */
const FINISH_SAFETY_MS = 10_000

export interface TypewriterOptions {
  /**
   * 每次吐出若干字符时的写入口。调用方在此把字符追加到目标（通常是某个正文块），
   * 由调用方决定写向哪里并自行保证目标有效。
   */
  onDrain: (chunk: string) => void
}

export class Typewriter {
  private buffer = ''
  private timer: ReturnType<typeof setInterval> | null = null
  private safety: ReturnType<typeof setTimeout> | null = null
  /** 硬取消标记：取消后忽略后续 append，且不再吐字 */
  private canceled = false
  /** `finish()` 的等待者：缓冲区排空（或 teardown）时统一唤醒，避免调用方永久 await */
  private drainResolvers: Array<() => void> = []

  constructor(private readonly opts: TypewriterOptions) {}

  /** 缓冲区里还有多少字没吐（调试 / 判断「是否还在打字」用） */
  get pending(): number {
    return this.buffer.length
  }

  /** 入队：事件回调只需把 delta 追加进来，不直接写目标 */
  append(text: string): void {
    if (!text || this.canceled) return
    this.buffer += text
    this.ensureRunning()
  }

  /**
   * 声明输入结束，返回的 Promise 在缓冲区排空后 resolve。
   */
  finish(): Promise<void> {
    if (this.buffer.length === 0) {
      this.teardown()
      return Promise.resolve()
    }
    this.ensureRunning()
    // 兜底：无论驱动循环为何未收敛，到点强制吐完并唤醒等待者
    if (this.safety === null) {
      this.safety = setTimeout(() => this.flushNow(), FINISH_SAFETY_MS)
    }
    return new Promise<void>((resolve) => this.drainResolvers.push(resolve))
  }

  /** 立即把剩余字符全部同步吐出（abort / 错误兜底 / 收尾时使用），并停止驱动 */
  flushNow(): void {
    if (this.buffer.length) {
      const rest = this.buffer
      this.buffer = ''
      this.opts.onDrain(rest)
    }
    this.teardown()
  }

  /** 硬取消：停止驱动并丢弃剩余字符（对话区被清空 / 容器被回收时使用） */
  cancel(): void {
    this.canceled = true
    this.buffer = ''
    this.teardown()
  }

  private ensureRunning(): void {
    if (this.timer !== null || this.canceled) return
    this.timer = setInterval(() => this.tick(), TICK_MS)
  }

  private tick(): void {
    // 标签页隐藏：用户看不到打字过程，直接吐完收工，避免后台节流把 await finish() 拖长
    if (typeof document !== 'undefined' && document.hidden) {
      this.flushNow()
      return
    }
    if (this.buffer.length) {
      const backlog = this.buffer.length
      const take = Math.min(
        backlog,
        Math.max(MIN_CHARS_PER_TICK, Math.ceil(backlog / BACKLOG_DIVISOR)),
        MAX_CHARS_PER_TICK,
      )
      const chunk = this.buffer.slice(0, take)
      this.buffer = this.buffer.slice(take)
      this.opts.onDrain(chunk)
    }
    if (this.buffer.length === 0) {
      this.teardown()
    }
  }

  /** 清理定时器并唤醒所有 finish() 等待者（幂等） */
  private teardown(): void {
    if (this.timer !== null) {
      clearInterval(this.timer)
      this.timer = null
    }
    if (this.safety !== null) {
      clearTimeout(this.safety)
      this.safety = null
    }
    const resolvers = this.drainResolvers
    this.drainResolvers = []
    for (const resolve of resolvers) resolve()
  }
}
