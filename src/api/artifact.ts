/**
 * 产物接口
 *
 * 对接后端 SessionArtifactController：
 *   GET  /api/agents/{agentId}/sessions/{sessionId}/artifacts          产物树
 *   GET  /api/agents/{agentId}/sessions/{sessionId}/artifacts/content  文件内容（≤5MB）
 *   PUT  /api/agents/{agentId}/sessions/{sessionId}/artifacts/content?path=…
 *        覆盖写（≤2MB，只能改已存在文件），body 仅传 { content }，成功返回 204
 *
 * ⚠️ 两条硬约束：
 *   1. **产物路径一律用接口返回的 `path` 原样回传**
 *      后端 `agent-platform.artifacts-dir` 可配（默认 artifacts），
 *      前端硬编码 `artifacts/` 前缀会在后端改配置后失配。
 *   2. **覆盖写不能新建、不能删除**，只能改已存在的文件。新建文件由智能体自己写。
 */

import { http, rawFetch, ApiError } from '@/api/http'
import type { ArtifactContent, ArtifactNode } from '@/types/sandbox'

const base = (agentId: string, sessionId: string) =>
  `/api/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sessionId)}/artifacts`

/** 拉产物树 */
export function fetchArtifactTree(agentId: string, sessionId: string, signal?: AbortSignal): Promise<ArtifactNode[]> {
  return http.get<ArtifactNode[]>(base(agentId, sessionId), undefined, { signal })
}

/** 读文件内容 */
export function fetchArtifactContent(
  agentId: string,
  sessionId: string,
  path: string,
  signal?: AbortSignal,
): Promise<ArtifactContent> {
  return http.get<ArtifactContent>(`${base(agentId, sessionId)}/content`, { path }, { signal })
}

/** 覆盖写文件内容（只能改已存在文件，≤2MB） */
export function saveArtifactContent(
  agentId: string,
  sessionId: string,
  path: string,
  content: string,
): Promise<void> {
  // Java 后端把 path 放在 query，成功返回 204；页面只关心保存是否成功。
  const query = new URLSearchParams({ path }).toString()
  return http.put<void>(`${base(agentId, sessionId)}/content?${query}`, { content })
}

/**
 * 取产物文件的**原始字节**并转成 objectURL——图片等二进制预览用。
 *
 * 为什么不用 `/content`：那个接口对二进制只回 `content=null` + reason，
 * 图拿不到；`/raw` 才回真字节（Content-Type 由后端按扩展名给）。
 *
 * ⚠️ 返回的 objectURL 由调用方负责在不用时 `URL.revokeObjectURL`（这里不回收，
 * 因为调用方可能还要把 URL 挂在 DOM 上）。
 */
export async function fetchArtifactObjectUrl(
  agentId: string,
  sessionId: string,
  path: string,
  signal?: AbortSignal,
): Promise<string> {
  const res = await rawFetch(`${base(agentId, sessionId)}/raw`, {
    method: 'GET',
    params: { path },
    signal,
  })
  if (!res.ok) {
    throw new ApiError(res.status, `读取产物字节失败（HTTP ${res.status}）`)
  }
  return URL.createObjectURL(await res.blob())
}

/** Read bytes for the browser Python workspace using the existing authenticated artifact route. */
export async function fetchArtifactBytes(agentId: string, sessionId: string, path: string, signal?: AbortSignal): Promise<Uint8Array> {
  const response = await rawFetch(`${base(agentId, sessionId)}/raw`, { method: 'GET', params: { path }, signal })
  if (!response.ok) throw new ApiError(response.status, `读取运行数据失败 ${path}（HTTP ${response.status}）`)
  const reader = response.body?.getReader()
  if (!reader) throw new Error(`读取运行数据失败 ${path}`)
  const parts: Uint8Array[] = []
  let size = 0
  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      size += value.byteLength
      if (size > 20 * 1024 * 1024) { await reader.cancel(); throw new Error(`文件 ${path} 超过 20 MB`) }
      parts.push(value)
    }
  } finally { reader.releaseLock() }
  const bytes = new Uint8Array(size)
  let offset = 0
  for (const part of parts) { bytes.set(part, offset); offset += part.byteLength }
  return bytes
}
