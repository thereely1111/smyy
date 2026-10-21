/**
 * 工作台任务 ↔ 平台后端会话的状态同步
 *
 * 工作台的任务条是**本地**的（localStorage），后端并不知道「任务」这个概念；
 * 后端知道的只有**会话**（`main-xxxxxxxx-…`）。所以这里的口径是：
 *   - 「暂停」= 中断该会话正在跑的 run（后端不动数据）
 *   - 「删除」= 清掉该会话（先停 run，再删产物与状态，**不可逆**）
 *
 * 两个端点都在 AgentManagementController 上，都走 `accessGuard.requireSessionOwned`
 * 做归属校验 —— 所以传错 sessionId 只会拿到 404，不会误伤别人的会话。
 *
 * 为什么单独一个文件：这两个动作是「页面外壳」而不是「分析通道」的职责
 * （分析通道只管发 run 与收产物）。放这里，flow / core 都不必认识后端。
 */

import { http } from '@/api/http'
import { AGENT_ID } from '@/config'

const base = (sessionId: string): string =>
  '/api/agents/' + encodeURIComponent(AGENT_ID) + '/sessions/' + encodeURIComponent(sessionId)

export interface InterruptResult {
  /** agent-platform-java 的字段：true = 确实触发了运行中断 */
  interrupted?: boolean
  /** 旧后端兼容字段；新后端不返回该字段 */
  stopped?: boolean
}

/**
 * 中断会话在跑的 run（幂等）。
 *
 * `interrupted=false`（旧后端为 `stopped=false`）不是失败：说明这一刻没有 run 在跑（可能刚结束、或在等 HITL 表单），
 * 调用方照常打「已暂停」标记即可 —— 用户看到的是「这个任务停住了」，语义一致。
 */
export async function interruptSession(sessionId: string): Promise<boolean> {
  if (!sessionId) return false
  const r = await http.post<InterruptResult>(`${base(sessionId)}/interrupt`)
  return Boolean(r?.interrupted ?? r?.stopped)
}

/**
 * 不可逆清除一个会话：先中断在跑的 run，再删产物目录 / 状态行 / 归属行 / 会话域各表。
 *
 * 这是「删除任务」的语义。调用方要先让用户确认。
 */
export async function clearSession(sessionId: string): Promise<void> {
  if (!sessionId) return
  await http.post<void>(`${base(sessionId)}/clear`)
}
