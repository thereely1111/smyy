/**
 * 把「没人回答的中断」从后端线程上摘掉。
 *
 * ── 为什么非有这一步不可 ──────────────────────────────────────────────────
 * 平台框架（`AguiResumeCoordinator`）对 HITL 中断的契约是硬的：
 *   ① 线程上还挂着**未决**中断时，任何**不带 resume** 的 run 一律被拒 ——
 *      报 `Thread has unresolved interrupts; RunAgentInput.resume must address all of them`；
 *   ② 带 resume 时，`resume[]` 的 interruptId 集合必须与线程上的未决集合**精确相等**
 *      （少一条、多一条都拒，报 `resume does not match` 并列出差集）。
 * 而中断登记在框架的**进程内存**里（重启才清）。所以「用户取消 / 表单认不出 /
 * 连问太多次」这些**不提交**的收尾，如果不主动发一次 `status:'cancelled'` 的 resume
 * 把它消费掉，这条会话上**之后每一次 run 都会被拒** —— 用户看到的就是对话区一句
 * 英文报错 +「这次没有拿到分析结果」，右栏「闸门」里却什么都没有，无从下手。
 *
 * 取消是合法答案：框架 `validateResumeStatuses` 只接受 `resolved` / `cancelled` 两种。
 * 平台侧对 cancelled 的语义是「这个 external 工具的结果 = 用户没答」，模型据此
 * 收尾或换一种问法，不会再挂同一个中断。
 *
 * ⚠️ 只清「确定没人会答」的中断。右栏还画着的卡片（用户正准备提交）不在清理范围里 ——
 * 调用方负责判断，本模块只负责把给定的 id 准确地回答掉。
 */

import { parseAguiStream, runAgui, runEventError } from '@/api/agui'
import { listLiveInterruptIds, listPendingInterrupts } from '@/api/formInterrupt'
import type { AguiEvent, AguiResumeItem } from '@/types/agui'

/** 清理请求自己的超时：中断不是产物，不该陪着等十几分钟 */
const RELEASE_TIMEOUT_MS = 20_000

/** 取消一条中断时给框架的载荷（平台按 external 工具的结果处理） */
const CANCELLED_PAYLOAD = { approved: false }

/**
 * 后端「线程上还有未决中断」的那句报错 —— **三种形状**都要认：
 *
 * | 形状 | 什么时候出现 |
 * |---|---|
 * | `Thread has unresolved interrupts; RunAgentInput.resume must address all of them` | 不带 resume 的裸 run |
 * | `resume does not match any open interrupt` | resume 里带了线程上不存在的 id |
 * | `RunAgentInput.resume must cover all open interrupts. missing=[…], unknown=[]` | **resume 少了几条**（2026-10-14 实测：跨账号操作时前端只凑出 1 条，就是这一句） |
 *
 * ⚠ 第三种（带 `missing=` 列表的）曾是漏网之鱼 —— 前两种的正则都认不出它，
 * 于是「失败后顺手清一次未决中断」的兜底从来不触发，用户被彻底卡死。
 */
export function isUnresolvedInterruptsError(text: string): boolean {
  return /unresolved interrupts|resume does not match|must cover all open interrupts/i.test(text || '')
}

/** 给用户看的中文说法 —— 英文原文对用户没有可操作性 */
export const UNRESOLVED_HINT =
  '这条会话上还挂着一张没处理完的确认卡片（平台因此拒绝了新的请求）。' +
  '右栏「闸门」里若还有卡片，先把它提交或取消；已经没有了就直接把这一句再发一次 —— 站点会自动把这条残留作废。' +
  '（刷新页面没用：它挂在后端进程内存里。若重发仍被挡，等 1~2 分钟后端的超时巡检把它回收掉再发。）'

/** 把英文原文换成人话（其余原样带出） */
export function humanizeFailure(note: string): string {
  if (!note) return note
  if (isUnresolvedInterruptsError(note)) return UNRESOLVED_HINT
  return note
}

/** 中断的 id（v2 是 `id`，老版本是 `interruptId`） */
function idOf(item: { id?: string; interruptId?: string }): string {
  return (item.id || item.interruptId || '').trim()
}

/**
 * 这条线程上还没被回答的中断 id —— **两个来源取并集**。
 *
 * | 来源 | 是什么 | 缺点 |
 * |---|---|---|
 * | `GET /interrupts` | 框架 `pendingInterruptsByThread` 的**权威快照** | 后端 2026-10-12 才有，老后端 404 |
 * | `GET /form-interrupts` | 平台登记表（HITL 白名单 ∩ 框架映射） | **枚举不全**：框架持锁但登记表没有的中断永远看不到 |
 *
 * 只用登记表会漏（后端 2026-10-12 实测：主编没看到闸门卡片、直接发新消息即报
 * `Thread has unresolved interrupts`，而前端枚举为空、无法解锁）；只用快照则在不含
 * 该接口的旧后端上直接失效。故并集，且**各自独立兜错** —— 一个源失败不影响另一个，
 * 两个都失败才返回空数组（本函数是自愈路径，拿不到就什么都不做，绝不把正常流程拖下水）。
 */
export async function pendingInterruptIds(agentId: string, threadId: string): Promise<string[]> {
  return (await probePendingInterruptIds(agentId, threadId)).ids
}

/** 未决中断的探测结果 —— 除了 id 列表，还带「有没有来源失联」 */
export interface PendingProbe {
  ids: string[]
  /** 两个来源里至少一个没答上来（404 / 网络错误 / 归属校验挡掉） */
  degraded: boolean
}

/**
 * 同 {@link pendingInterruptIds}，但把「查不到」这件事**显式暴露**出来。
 *
 * ⚠ 为什么非暴露不可（2026-10-14 闸门死锁的根因）：
 * 两个来源失效时都是**静默**的 —— `/interrupts` 带会话归属校验（非 owner 直接
 * 404「会话不存在」），`/form-interrupts` 按 `(agentId, userId, sessionId)` 取数
 * （**换了账号就返回空数组，不报错**）。调用方若只看 id 列表，永远分不清
 * 「线程上真的没有未决中断」与「当前账号根本看不到」。实测那个案例：用户用另一个
 * 账号操作开题账号的会话，前端据此拼出的 `resume[]` 只有 1 条，被框架
 * `must cover all open interrupts` 整体拒绝，页面上却没有任何线索。
 */
export async function probePendingInterruptIds(agentId: string, threadId: string): Promise<PendingProbe> {
  if (!threadId) return { ids: [], degraded: false }
  let degraded = false
  const [live, registered] = await Promise.all([
    listLiveInterruptIds(agentId, threadId).catch(() => {
      degraded = true
      return [] as string[]
    }),
    listPendingInterrupts(agentId, threadId)
      .then((list) => list.map(idOf).filter(Boolean))
      .catch(() => {
        degraded = true
        return [] as string[]
      }),
  ])
  return { ids: [...new Set([...live, ...registered])], degraded }
}

/**
 * 按 `status:'cancelled'` 把给定的中断全部回答掉，让线程回到「能正常发新 run」。
 *
 * 只求消费掉：**事件全部丢弃**（不渲染、不进对话区），失败也不抛 ——
 * 调用方拿返回值决定要不要提示用户。
 */
export async function releaseInterrupts(
  agentId: string,
  threadId: string,
  runId: string,
  interruptIds: string[],
): Promise<boolean> {
  const ids = [...new Set((interruptIds || []).map((s) => (s || '').trim()).filter(Boolean))]
  if (!threadId || !ids.length) return false
  const resume: AguiResumeItem[] = ids.map((interruptId) => ({
    interruptId,
    status: 'cancelled' as const,
    payload: CANCELLED_PAYLOAD,
  }))
  const ctl = new AbortController()
  const timer = window.setTimeout(() => ctl.abort(), RELEASE_TIMEOUT_MS)
  try {
    const res = await runAgui(
      agentId,
      {
        threadId,
        runId: runId || `release-${Date.now()}`,
        messages: [],
        resume,
      },
      ctl.signal,
    )
    await parseAguiStream(res, () => {}, ctl.signal)
    return true
  } catch {
    return false
  } finally {
    window.clearTimeout(timer)
  }
}

/**
 * 一步到位：查回这条线程上的**全部**未决中断并全部取消。
 * 返回清掉了几条（0 = 本来就没有 / 查不到）。
 */
export async function releasePendingInterrupts(
  agentId: string,
  threadId: string,
  runId = '',
): Promise<number> {
  const ids = await pendingInterruptIds(agentId, threadId)
  if (!ids.length) return 0
  await releaseInterrupts(agentId, threadId, runId, ids)
  return ids.length
}

/**
 * 按「要答的那一条 + 其余一并取消」拼 resume 列表。
 *
 * 用途：转录重建出来的闸门只对应**一张**卡片，可线程上未必只有这一条未决中断
 * （登记表回灌、超时重发都会留下第二条）。而框架要求 resume 集合与未决集合精确
 * 相等 —— 只答一条必然被拒。所以提交时把要答的那条按用户填的值 resolved，
 * 其余没有卡片可填的按 cancelled 一起交掉。
 *
 * @param answerId 用户真正答的那条；不在 live 里也算数（照旧发出去）
 * @param live     线程上的全部未决 id
 * @param payload  用户答案（`gatePayload(values)`）
 */
export function resumeCoveringAll(
  answerId: string,
  live: string[],
  payload: { output: string },
): AguiResumeItem[] {
  const ids = [...new Set([...(live || []), (answerId || '').trim()].filter(Boolean))]
  return ids.map((interruptId) => ({
    interruptId,
    status: interruptId === answerId ? ('resolved' as const) : ('cancelled' as const),
    payload: interruptId === answerId ? payload : CANCELLED_PAYLOAD,
  }))
}

/**
 * 从框架的 resume 契约报错里，把「缺的那几条」捞出来。
 *
 * 报错形状（框架 `AguiResumeCoordinator`，平台 `PlatformAguiRuntimeContextResolver`
 * 只做日志、不抛）：
 *   `RunAgentInput.resume must cover all open interrupts. missing=[id1, id2], unknown=[]`
 *
 * 里面**直接带着缺失中断的完整 id**。这是跨账号 / 会话级接口不可用时，前端**唯一**
 * 还能凑齐 `resume[]` 的入口 —— 它不依赖任何查询权限，只依赖这次失败本身。
 */
export function parseMissingInterruptIds(text: string): string[] {
  const hit = /missing=\[([^\]]*)\]/.exec(text || '')
  if (!hit) return []
  return hit[1]
    .split(',')
    .map((s) => s.trim().replace(/^['"]+|['"]+$/g, ''))
    .filter(Boolean)
}

/** 一次「覆盖全量」的 resume 提交结果 */
export interface ResumeAttempt {
  /** 非空 = 最终仍失败，后端错误原文 */
  error: string
  /** 是否靠「从报错里补 missing」才成功的 */
  repaired: boolean
  /** 提交前读不到线程全量未决中断（跨账号 / 接口不可用） */
  degraded: boolean
}

/**
 * 提交一次 resume —— 保证 `resume[]` 覆盖线程上的**全部**未决中断。
 *
 * 框架的契约是硬的：`resume[]` 与未决集合必须**精确相等**（少一条、多一条都整体拒绝）。
 * 而前端拿「未决集合」的渠道随时可能失效（见 {@link probePendingInterruptIds}），
 * 所以这里做最多三轮：
 *   · 第 1 轮：`probePendingInterruptIds()` 的全量 + 用户答的那一条；
 *   · 第 2/3 轮：把上一轮报错里的 `missing=[…]` 补进来重发（**不再查接口**）。
 *
 * ⚠ 失败轮的错误事件**暂扣不发**：调用方（`analysisRun` / `askAgent`）一拿到 error
 * 事件就会置 `streamError` 并收尾，重试就没意义了。只有最终仍失败时，扣下的事件才
 * 补交给调用方 —— 对调用方来说，语义与「一次就失败」完全一致。
 */
export async function submitResumeCoveringAll(opts: {
  agentId: string
  threadId: string
  runId: string
  /** 用户真正答的那条中断 id（其余一律按 cancelled 交掉） */
  answerId: string
  payload: { output: string }
  signal?: AbortSignal
  onEvent: (event: AguiEvent) => void
  /**
   * 平台受理了这次 resume（第一个非错误事件到达 / 空流无错）。
   *
   * 存在理由：**回执不该等整条流**。受理后的 run 可能连跑几分钟，调用方若等
   * `submitResumeCoveringAll` 返回才交回执，卡片会一直停在「提交中…」。
   */
  onAccepted?: () => void
}): Promise<ResumeAttempt> {
  const { agentId, threadId, runId, answerId, payload, signal, onEvent, onAccepted } = opts
  let extra: string[] = []
  let degraded = false
  /** 只报一次「受理」：多轮补发时第一轮就该报出去 */
  let told = false
  const say = (): void => {
    if (told) return
    told = true
    onAccepted?.()
  }
  for (let round = 0; round < 3; round += 1) {
    let live: string[] = []
    if (round === 0) {
      const probe = await probePendingInterruptIds(agentId, threadId)
      live = probe.ids
      degraded = probe.degraded
    }
    const res = await runAgui(
      agentId,
      {
        threadId,
        runId,
        messages: [],
        resume: resumeCoveringAll(answerId, [...live, ...extra], payload),
      },
      signal,
    )
    let error = ''
    const held: AguiEvent[] = []
    await parseAguiStream(res, (ev) => {
      const e = runEventError(ev)
      if (e) {
        error ||= e
        held.push(ev)
        return
      }
      /* 第一个非错误事件 = 平台已经接了这个 resume（run 起来了）。契约拒绝发生在 run
         开始**之前**，只会给 RUN_ERROR、不会有任何先导事件 —— 2026-10-19 用
         agui_events 实证：被拒的 run 一条事件都没落库，受理的从 RUN_STARTED 开始。 */
      say()
      onEvent(ev)
    }, signal)
    if (!error) {
      say() /* 空流也算受理：后端没动静，但也没报错 */
      return { error: '', repaired: round > 0, degraded }
    }
    /* 只有「resume 集合不匹配」才值得补一次；模型崩 / 权限 / 超时补了也没用 */
    const missing = isUnresolvedInterruptsError(error) && round < 2 ? parseMissingInterruptIds(error) : []
    if (!missing.length) {
      held.forEach(onEvent)
      return { error, repaired: round > 0, degraded }
    }
    extra = missing
  }
  return { error: '', repaired: true, degraded }
}
