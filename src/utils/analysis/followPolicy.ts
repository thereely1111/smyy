/**
 * 「跟着后端跑」的停机判据（纯函数，2026-10-14 从 `stores/analysis.ts` 抽出）。
 *
 * 抽出来的理由：这里的判据曾经**误判过一次**，而且是线上用户先看见的 ——
 * 旧规则「父会话 run-status 连续空闲 3 轮（3 × 5 秒）即收工」在**异步交接空窗**里
 * 直接把还在跑的活判成「会话已空闲」（实测 16:34:04 父会话收流、16:39:07 才被唤醒，
 * 空窗 303 秒，而子智能体 `题目解析智能体` 全程 RUNNING）。
 *
 * 为什么不能只看父会话在不在跑：`GET …/run-status` 的 `running` 背后是
 * `SessionActivityTracker.isRunning(sessionId)` —— 按 **sessionId** 计的进程内存计数。
 * 子智能体跑在它自己的会话里，父会话这会儿必然报「空闲」；真正把活接着推进的是
 * 平台的 inbox 看门狗 → wakeup 通道（节拍 30s/60s + 冷却，实测隔了 5 分钟）。
 *
 * 所以这里的输入是 {@link FollowTick.busy}，口径是
 * `run-status.running || run-status.pendingWork`（P0-2 后端已提供 `pendingWork`：
 * 父会话在跑 ∪ 子任务 RUNNING/PENDING ∪ 结果待投递）。再加两道保险：
 *   · 台账 `nextAction=CONTINUE`（平台 `SessionProgressService` 自己判「应继续推进」，
 *     与它的停滞看护同一判据）⇒ **不以空闲收尾**，只认产物 / 闸门 / 上限；
 *   · 台账拿不到（老后端 / 跨账号 404）⇒ 退回「busy=false 且持续满
 *     {@link FOLLOW_IDLE_GRACE_MS}」。
 */

/** 连续探到「后端没在跑」几轮才够格谈收工（一轮 5 秒） */
export const FOLLOW_IDLE_ROUNDS = 3

/** 空闲还要**持续**这么久才算真收工 —— 交接空窗实测能到 5 分钟，15 秒纯粹是误判 */
export const FOLLOW_IDLE_GRACE_MS = 3 * 60 * 1000

/** 探针连续问不出来几轮按「跟丢了」收尾，别把界面吊满 40 分钟 */
export const FOLLOW_PROBE_LOST_ROUNDS = FOLLOW_IDLE_ROUNDS * 4

/** 台账说「后端应继续推进」（SessionProgressService 的常量字面值） */
export const NEXT_CONTINUE = 'CONTINUE'

/** 台账已经把话说死：不必再等产物 */
export function isSettledAction(nextAction: string): boolean {
  return nextAction === 'DONE' || nextAction === 'WAIT_HITL' || nextAction === 'IDLE'
}

export type FollowStopReason =
  /** 台账表了态（DONE / WAIT_HITL / IDLE），或闸门挂出来了 */
  | 'settled'
  /** 后端空闲够久（见 {@link FOLLOW_IDLE_GRACE_MS}） */
  | 'idle'
  /** 到跟随上限 */
  | 'timeout'
  /** 探针连续失联：读不到后端状态，按未知收尾 */
  | 'lost'
  /** 用户停手 */
  | 'stopped'

export interface FollowTick {
  /** 台账这一轮是不是**本会话**刚拉到的（拉不到就不能拿它当判据） */
  ledgerOk: boolean
  /** 台账 nextAction：DONE / WAIT_HITL / IDLE / CONTINUE / UNKNOWN / '' */
  nextAction: string
  /**
   * 后端还有没有在途工作：`running || pendingWork`
   * （true 有 / false 没有 / null 探针问不出来）。
   *
   * 刻意不叫 `running` —— 它不等于「父会话在跑」，见文件头。
   */
  busy: boolean | null
  /** 连续「没在跑」轮数（含本轮） */
  idleStreak: number
  /** 本轮「没在跑」已持续多久（ms） */
  idleForMs: number
  /** 连续探针失败轮数（含本轮） */
  probeFails: number
}

/** 该不该收工；null = 继续跟 */
export function followStopReason(t: FollowTick): FollowStopReason | null {
  if (isSettledAction(t.nextAction)) return 'settled'
  if (t.busy === true) return null
  if (t.busy === false) {
    /* 台账说「应继续推进」⇒ 后端只是把活派给了子智能体、在等它回报。
       子智能体跑在它自己的会话里，父会话这段时间**必然**报空闲 —— 不是收工。
       （`pendingWork` 已能覆盖大部分这种形态；这条兜住「子任务记录已消失、
       但平台仍打算补醒」的尾巴，与平台的停滞看护同口径。） */
    if (t.ledgerOk && t.nextAction === NEXT_CONTINUE) return null
    if (t.idleStreak < FOLLOW_IDLE_ROUNDS) return null
    return t.idleForMs >= FOLLOW_IDLE_GRACE_MS ? 'idle' : null
  }
  return t.probeFails >= FOLLOW_PROBE_LOST_ROUNDS ? 'lost' : null
}
