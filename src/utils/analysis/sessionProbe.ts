/**
 * 「这座会话现在还能不能接着用」的判据（纯函数 + 一个白名单集合）。
 *
 * 修的是 2026-10-13 用户报的毛病：**把上一道题删掉之后，同一道题再输一遍就报错**
 * （对话区「智能体这条流报了错 / 该会话已被删除，请新建会话」，产物卡说没有分析稿）。
 *
 * 根因链（后端源码核实）：
 *   ① caseId 由题面短哈希决定（`user-<hash>`）⇒ 同一道题永远指向同一个会话键；
 *   ② `caseId → threadId` 映射持久在 localStorage，**删任务不清理**；
 *   ③ 删任务调后端 `POST /sessions/{sid}/clear`，它第一步是置**墓碑**
 *      （`markDeleted` 只写 `deleted_at`，**保留归属行**）；
 *   ④ 于是重新输入同一道题时复用了那条墓碑会话：归属校验
 *      `SessionAccessGuard.requireSessionOwned` 走的是 `findByAgentIdAndSessionId`
 *      派生查询（**不看墓碑**）⇒ owner 仍是本人 ⇒ 放行，附件接口照回 200；
 *   ⑤ 就算探活问得再细，墓碑会话也不会回答「我已删」—— 而 run 会被后端终态拒绝：
 *      `PlatformAguiRuntimeContextResolver` 校验 `isDeletedBySession` → 410
 *      「该会话已被删除，请新建会话」。用户看到的就是「旧任务干扰新任务」。
 *
 * 判据：**会话列表**是唯一如实回答的接口（它按 `deleted_at IS NULL` 过滤；
 * 归档的用 includeArchived 一并取回，归档不算删）。列表里没有 = 已删 / 从不是我的 ⇒
 * 换一条全新的会话。
 *
 * 例外（一律按「别误换会话」处理）：
 *   · `fresh` 白名单里的会话是本模块刚新建的，后端往往还没登记（登记发生在第一次
 *     run 被处理时），列表里必然没有 —— 不能因此换掉（那会每开一道新题白建一条死会话，
 *     是 2026-10-09 修过的老毛病）；
 *   · 列表请求本身失败（网络抖动 / 后端没起）传 `null` ⇒ 放行。
 */

/** 会话列表项里我们只关心 id（其余字段由 api/session 的类型承载） */
export interface SessionListItem {
  sessionId: string
}

/** 本页面生命周期内**由本模块新建**的 threadId（首次分配 / 轮换） */
const fresh = new Set<string>()

/** 记下一座刚新建的会话（后端可能还没登记，探活要跳过列表判定） */
export function markFreshThread(threadId: string): void {
  if (threadId) fresh.add(threadId)
}

export function isFreshThread(threadId: string): boolean {
  return fresh.has(threadId)
}

/**
 * 会话已跑通过一次 run ⇒ 后端必然登记过它，从此不再用「刚新建」豁免，
 * 探活一律以会话列表为准（否则墓碑会话会被这条豁免永久放行）。
 */
export function settleThread(threadId: string): void {
  fresh.delete(threadId)
}

/**
 * 会话被后端删掉（`POST …/clear` 置墓碑）后调用：把它从「刚新建」白名单摘掉。
 *
 * 不摘的后果就是 2026-10-20 云端报的那条：同一页面里先发题（进白名单）→ 删对话 →
 * 再输同一道题，探活看到白名单直接放行墓碑会话，run 被后端 410 拒绝，
 * 界面只会重复「该会话已被删除」。
 */
export function forgetThreads(ids: readonly string[]): void {
  for (const id of ids) if (id) fresh.delete(id)
}

/**
 * @param listed 该用户的会话列表；`null` = 列表请求失败（按放行处理）
 * @param threadId 待判定的会话
 * @returns true = 可以接着用；false = 已删 / 不是我的会话，调用方该换新的
 */
export function judgeSessionUsable(
  listed: readonly SessionListItem[] | null,
  threadId: string,
): boolean {
  if (!threadId) return false
  if (isFreshThread(threadId)) return true
  if (!listed) return true
  return listed.some((s) => s.sessionId === threadId)
}
