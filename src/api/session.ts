/**
 * 会话接口 —— 对接后端 AgentManagementController / SessionExtensionController。
 *
 * 会话 ID 即工作台的 `sessionId`（前端约定前缀 `main-<uuid>`）。后端按 JWT userId
 * 校验会话归属，非本人会话返回 403/404。
 *
 * 端点：
 *   GET    /api/agents/{agentId}/sessions                                   会话列表
 *   PATCH  /api/agents/{agentId}/sessions/{sid}/title                       设置标题
 *   PATCH  /api/agents/{agentId}/sessions/{sid}/archive                     归档 / 取消归档
 *   POST   /api/agents/{agentId}/sessions/{sid}/clear                       硬删（不可恢复）
 *   GET    /api/agents/{agentId}/sessions/{sid}/transcript                  完整转录（持久化）
 *   GET    /api/agents/{agentId}/sessions/{sid}/progress                    进度台账
 *   GET    /api/agents/{agentId}/sessions/{sid}/extensions                 读取扩展键值
 *   PUT    /api/agents/{agentId}/sessions/{sid}/extensions                 批量 upsert
 *   POST   /api/agents/{agentId}/sessions/{sid}/extensions/entries         写单条
 *   DELETE /api/agents/{agentId}/sessions/{sid}/extensions/entries/{key}   删单条
 *   DELETE /api/agents/{agentId}/sessions/{sid}/extensions                 清空
 *
 * 说明：后端**禁止引入业务表**（架构硬约束），业务数据一律以 key-value 形式挂在会话上。
 * 同步语义见 `fetchSessionTranscript` / `fetchSessionProgress` / `mergeTranscript`：
 * transcript 是权威源（刷新/重启不丢），本地乐观气泡在未被后端确认前保留。
 */

import { http } from '@/api/http'

/* ── 会话列表 / 生命周期 ─────────────────────────────────────────────── */

/** 会话列表项（后端 RuntimeDtos.SessionInfo） */
export interface SessionInfo {
  sessionId: string
  /** 后端生成的状态摘要，可能为空 */
  summary: string
  updatedAt: string
  /** 非 null 表示已归档（状态与产物目录均保留，可恢复） */
  archivedAt: string | null
  /** 手工设置过的标题；未设置时为 null */
  title: string | null
}

function sessionBase(agentId: string): string {
  return `/api/agents/${encodeURIComponent(agentId)}/sessions`
}

function sessionPath(agentId: string, sessionId: string): string {
  return `${sessionBase(agentId)}/${encodeURIComponent(sessionId)}`
}

/** 当前用户的会话列表（后端按真实用户分区，不允许匿名） */
export function listSessions(agentId: string, includeArchived = false): Promise<SessionInfo[]> {
  return http.get<SessionInfo[]>(sessionBase(agentId), { includeArchived: String(includeArchived) })
}

/**
 * 设置会话标题（例如把题目名写成会话标题）。
 *
 * ⚠️ 后端是 `UPDATE agent_session_owners SET title=...`，**会话不存在时静默影响 0 行**。
 * 因此必须在会话真正落库之后调用（首条 AG-UI 消息发出之后）。
 */
export function setSessionTitle(agentId: string, sessionId: string, title: string): Promise<void> {
  return http.patch<void>(`${sessionPath(agentId, sessionId)}/title`, { title })
}

/** 归档 / 取消归档（幂等，产物与状态均保留） */
export function setSessionArchived(agentId: string, sessionId: string, archived: boolean): Promise<void> {
  return http.patch<void>(`${sessionPath(agentId, sessionId)}/archive`, { archived })
}

/** 硬删会话（产物文件、运行状态、扩展元数据一并删除，不可恢复） */
export function clearSession(agentId: string, sessionId: string): Promise<void> {
  return http.post<void>(`${sessionPath(agentId, sessionId)}/clear`)
}

/* ── 转录同步（工作台对话区恢复用） ─────────────────────────────────── */

export interface SessionTextMessage {
  id: string
  role: string
  createdAt?: string
  content: Array<{ type: string; text?: string }>
}

/** 进度台账（宽松形态，仅取工作台要展示的字段） */
export interface SessionProgress {
  /** 智能体自己写的阶段标记（S0 / S1a / S1b / S2 / S3 / S5）—— 平台从尾文本的「阶段=」抽的 */
  phase?: string
  /** CONTINUE / WAIT_HITL / DONE / IDLE / UNKNOWN */
  nextAction?: string
  progressLine?: string
  stallNow?: boolean
  hitlStuck?: boolean
}

/** 工作台对话区的一条消息（本地乐观气泡 + 远端权威文本的合并结果） */
export interface SyncedChatMessage {
  id: string
  role: 'user' | 'assistant'
  text: string
  at: number
  remoteId?: string
  local?: boolean
}

/** 拉取会话完整转录（持久化，刷新/重启都不丢） */
export function fetchSessionTranscript(
  agentId: string,
  sessionId: string,
): Promise<{ messages: SessionTextMessage[] }> {
  return http.get(`${sessionPath(agentId, sessionId)}/transcript`)
}

/** 拉取会话进度台账 */
export function fetchSessionProgress(agentId: string, sessionId: string): Promise<SessionProgress> {
  return http.get(`${sessionPath(agentId, sessionId)}/progress`)
}

/**
 * 这条会话的后端 run 还在不在跑 —— 返回两个口径，别混用。
 *
 * 为什么非有这个探针：AG-UI 的 SSE 受超时约束挂不久，前端断流 ≠ 后端干完 ——
 * smyy 的智能体是**异步编排型**，流一关它就转后台（派子智能体 + wakeup 自驱续跑），
 * 实测一轮 20–40 分钟。而这段窗口里再发 run 必被并发守卫拒（409）。
 *
 * - `running`：**父会话**还有 run 在跑（`SessionActivityTracker` 权威）。
 *   决定「用户新发一句话要不要排队」—— `waitForIdle` 只认它。
 * - `pendingWork`（2026-10-14 起后端提供）：这条会话**还有在途工作** ——
 *   父会话在跑 ∪ 子任务还在 RUNNING/PENDING ∪ 子任务结果尚未投递回父会话。
 *   决定「跟随要不要收工」—— `followSession` 取 `running || pendingWork`。
 *
 * ⚠ 分开的理由（2026-10-14 实测）：派完子任务后父会话必然收流空闲，此时
 * `running=false` 而 `pendingWork=true`（子智能体跑在它自己的会话里）。旧版只看
 * `running`，于是 303 秒的交接空窗里把还在跑的一轮判成「会话已空闲」。
 * 反过来也不能把两者合并：合并后子智能体在跑时 `running` 也为真，
 * 用户想追问一句就会被 `waitForIdle` 卡到子任务结束。
 *
 * ⚠ 平台侧的活跃计数已知会泄漏（`begin/end` 不配对，看护 300s 才兜底释放），
 * 所以 `running=true` 可能是**滞后**的；调用方不要拿它当唯一出口，见 analysis 的
 * `waitForIdle`（有上限）与 `followSession`（还有台账/产物两条旁证）。
 *
 * `pendingWork` 缺席（老后端）按 false 处理 —— 行为退化成只看 `running`。
 */
export function fetchSessionRunStatus(
  agentId: string,
  sessionId: string,
): Promise<{ running: boolean; pendingWork?: boolean }> {
  return http.get(`${sessionPath(agentId, sessionId)}/run-status`)
}

/**
 * 把远端转录合并进本地消息列表。
 *
 * 先按 id / remoteId 匹配；匹配不到时用「同角色 + 同文本」消费一条本地乐观气泡
 * （每条至多消费一次）。**尚未被后端确认的本地消息一律保留** —— 后端快照只对
 * 已落库的消息是权威的，不代表用户刚发出的输入。
 */
export function mergeTranscript(
  local: SyncedChatMessage[],
  remote: SessionTextMessage[],
): SyncedChatMessage[] {
  const consumed = new Set<string>()
  const seen = new Set<string>()
  const out: SyncedChatMessage[] = []
  for (const item of remote) {
    if (!item.id || seen.has(item.id) || (item.role !== 'user' && item.role !== 'assistant')) continue
    seen.add(item.id)
    const text = (item.content ?? []).filter((b) => b.type === 'text').map((b) => b.text ?? '').join('')
    if (!text) continue
    const match =
      local.find((m) => !consumed.has(m.id) && (m.id === item.id || m.remoteId === item.id)) ??
      local.find((m) => m.local && !consumed.has(m.id) && m.role === item.role && m.text === text)
    if (match) consumed.add(match.id)
    out.push({
      id: match?.id ?? item.id,
      remoteId: item.id,
      role: item.role,
      text,
      at: Date.parse(item.createdAt ?? '') || match?.at || 0,
    })
  }
  return [...out, ...local.filter((m) => m.local && !consumed.has(m.id))]
}

/* ── 会话扩展键值 ───────────────────────────────────────────────────── */

/** 单个扩展条目 */
export interface ExtensionEntry {
  key: string
  value: string
}

/** 会话扩展信息完整响应 */
export interface SessionExtension {
  id: string
  agentId: string
  sessionId: string
  userId: string
  entries: ExtensionEntry[]
  createdAt: string
  updatedAt: string
}

function extBase(agentId: string, sessionId: string): string {
  return `${sessionPath(agentId, sessionId)}/extensions`
}

/** 读取某会话的全部扩展信息（不存在时后端返回 404） */
export function getExtension(agentId: string, sessionId: string): Promise<SessionExtension> {
  return http.get<SessionExtension>(extBase(agentId, sessionId))
}

/** 批量写入扩展条目（已存在的 key 覆盖，不存在的 key 新增） */
export function upsertExtensionEntries(
  agentId: string,
  sessionId: string,
  entries: ExtensionEntry[],
): Promise<SessionExtension> {
  return http.put<SessionExtension>(extBase(agentId, sessionId), { entries })
}

/** 写入单个扩展条目 */
export function putExtensionEntry(
  agentId: string,
  sessionId: string,
  key: string,
  value: string,
): Promise<SessionExtension> {
  return http.post<SessionExtension>(`${extBase(agentId, sessionId)}/entries`, { key, value })
}

/** 删除单个扩展条目 */
export function deleteExtensionEntry(agentId: string, sessionId: string, key: string): Promise<void> {
  return http.delete<void>(`${extBase(agentId, sessionId)}/entries/${encodeURIComponent(key)}`)
}

/** 清空某会话的全部扩展信息 */
export function deleteExtension(agentId: string, sessionId: string): Promise<void> {
  return http.delete<void>(extBase(agentId, sessionId))
}
