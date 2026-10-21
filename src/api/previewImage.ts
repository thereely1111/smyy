/**
 * 封面预览图接口
 *
 * 对接后端 `PreviewImageController`：
 *   POST /api/agents/{agentId}/sessions/{sessionId}/preview-image
 *        body: { prompt, fileName? }
 *        返回: { path, fileName, size, mimeType }
 *
 * 与走智能体的 `generate_image` 工具**不是一条路**：这里后端直接调生图模型、
 * 直接落盘到该会话的 `artifacts/`，不需要智能体、不需要工具白名单。
 * 封面只依赖题面，走这条路可预期得多（不会出现"模型没按你说的调工具"）。
 *
 * ⚠️ `path` 是**相对产物路径**（含 artifacts 前缀），原样回传给产物接口即可，
 *    前端不要自己拼 `artifacts/` 前缀（后端 `agent-platform.artifacts-dir` 可配）。
 */

import { http } from '@/api/http'

export interface PreviewImageResult {
  /** 相对产物路径，直接喂给 fetchArtifactContent / fetchArtifactObjectUrl */
  path: string
  fileName: string
  size: number
  mimeType: string
}

/**
 * 生成一张封面并落盘到该会话的产物目录。
 *
 * @param prompt 出图提示词（由调用方按题面拼）
 * @param fileName 可选；不传后端用默认名（同一题重复生成即覆盖）
 */
export function generatePreviewImage(
  agentId: string,
  sessionId: string,
  prompt: string,
  fileName?: string,
): Promise<PreviewImageResult> {
  const path =
    `/api/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sessionId)}/preview-image`
  // 出图是同步慢请求（上游要几十秒），单独给个长超时，别用默认值把它掐了
  return http.post<PreviewImageResult>(path, { prompt, fileName }, { timeoutMs: 300_000 })
}
