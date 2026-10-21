/**
 * 附件接口（赛题数据预载入口）
 *
 * 对接后端 AttachmentController（既有，零改动）：
 *   POST   /api/agents/{agentId}/sessions/{sessionId}/attachments        上传（≤20MB）
 *   GET    /api/agents/{agentId}/sessions/{sessionId}/attachments        列表
 *   DELETE /api/agents/{agentId}/sessions/{sessionId}/attachments/{fileId}
 *
 * ⚠️ 中文文件名陷阱：后端会把中文名 ASCII 化
 *    （`K8s灾备演练.pdf` → `K8s.pdf`，纯中文名 → `file.pdf`），
 *    原名存在 uploads/.names.json sidecar。
 *    → **展示一律用 `fileName` 字段**，`ref` 只用于发消息时引用。
 */

import { http } from '@/api/http'
import { ALLOWED_EXTENSIONS, ATTACH_MAX_MB, type AttachmentItem } from '@/types/sandbox'

const base = (agentId: string, sessionId: string) =>
  `/api/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sessionId)}/attachments`

/**
 * 后端响应 → 前端条目。
 *
 * 后端 `AttachmentResponse` 只有 fileName/ref/size/mimeType/createdAt，**没有 fileId**；
 * 而删除接口要的是「uploads/ 下的真实存储名」——它就是 `ref` 去掉 `@uploads/` 前缀。
 * 这里一次补全，别让调用方各自兜底（早先工作台直用 `item.fileId`，删附件必打
 * `/attachments/undefined` → 400）。
 */
interface RawAttachment {
  fileId?: string
  ref?: string
  fileName?: string
  size?: number
  uploadedAt?: string
  createdAt?: string
}

function toItem(raw: RawAttachment): AttachmentItem {
  const ref = String(raw.ref ?? '')
  const storeName = ref.replace(/^@?uploads\//, '')
  const fileId = raw.fileId || storeName || String(raw.fileName ?? '')
  return {
    fileId,
    ref,
    fileName: raw.fileName || ref || fileId,
    size: Number(raw.size ?? 0),
    uploadedAt: raw.uploadedAt ?? raw.createdAt,
  }
}

/** 上传附件 */
export function uploadAttachment(
  agentId: string,
  sessionId: string,
  file: File,
): Promise<AttachmentItem> {
  const fd = new FormData()
  fd.append('file', file, file.name)
  return http.upload<RawAttachment>(base(agentId, sessionId), fd).then(toItem)
}

/**
 * 列出附件。
 *
 * ⚠️ 后端回的是分页体 `{ items, total }`（实测 `GET .../attachments` → `{"items":[],"total":0}`），
 * **不是数组** —— 早先这里直接断言数组，导致工作台附件区恒显「还没有附件」，上传后再拉
 * 列表还会 `push` 抛 TypeError。这里统一解包，调用方拿到的永远是数组。
 */
export function listAttachments(
  agentId: string,
  sessionId: string,
  signal?: AbortSignal,
): Promise<AttachmentItem[]> {
  return http
    .get<{ items?: RawAttachment[] } | RawAttachment[]>(base(agentId, sessionId), undefined, { signal })
    .then((res) => (Array.isArray(res) ? res : res?.items ?? []).map(toItem))
}

/** 删除附件 */
export function deleteAttachment(agentId: string, sessionId: string, fileId: string): Promise<void> {
  return http.delete<void>(`${base(agentId, sessionId)}/${encodeURIComponent(fileId)}`)
}

/* ── 前端前置校验：不要让用户选完文件才吃 400 ──────────────────── */

export interface PrecheckResult {
  ok: boolean
  message?: string
}

/** 取扩展名（小写，不含点） */
export function extOf(name: string): string {
  const i = name.lastIndexOf('.')
  return i < 0 ? '' : name.slice(i + 1).toLowerCase()
}

/** 单文件校验：白名单 + 体积上限 */
export function precheckFile(file: File, maxMB = ATTACH_MAX_MB): PrecheckResult {
  const ext = extOf(file.name)
  if (!ext) {
    return { ok: false, message: `「${file.name}」没有扩展名，无法判断格式` }
  }
  if (!(ALLOWED_EXTENSIONS as readonly string[]).includes(ext)) {
    return { ok: false, message: `不支持 .${ext} 格式（${file.name}）` }
  }
  const limit = maxMB * 1024 * 1024
  if (file.size > limit) {
    const mb = (file.size / 1024 / 1024).toFixed(1)
    return { ok: false, message: `「${file.name}」${mb}MB，超过 ${maxMB}MB 上限` }
  }
  return { ok: true }
}

/** 批量校验，返回通过列表与被拒原因 */
export function precheckFiles(files: File[], maxMB = ATTACH_MAX_MB): {
  accepted: File[]
  rejected: { file: File; message: string }[]
} {
  const accepted: File[] = []
  const rejected: { file: File; message: string }[] = []
  for (const f of files) {
    const r = precheckFile(f, maxMB)
    if (r.ok) accepted.push(f)
    else rejected.push({ file: f, message: r.message ?? '校验未通过' })
  }
  return { accepted, rejected }
}
