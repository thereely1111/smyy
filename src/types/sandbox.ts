/** 产物树节点（后端 SessionArtifactController 返回） */
export interface ArtifactNode {
  name: string
  /** 相对路径；**回传后端时必须原样使用**（artifacts-dir 可配，不能硬编码前缀） */
  path: string
  type: 'file' | 'dir'
  size?: number
  /**
   * 最后修改时间，**epoch 毫秒**（后端 `RuntimeDtos.ArtifactNode.updatedAt: Long`）。
   *
   * 早先这里写的是 `modifiedAt?: string`，与后端字段名对不上 —— 接口按原样透传，
   * 所以那个字段从来就是 undefined。产物里同名文件并存是常态（本会话 + 各子会话
   * 合并成一棵树），要靠它挑「最新那一份」，字段名错了就只能瞎猜。
   */
  updatedAt?: number
  children?: ArtifactNode[]
}

/** 文件内容读取结果 */
export interface ArtifactContent {
  path: string
  /** 二进制 / 超限 / 非 UTF-8 时后端**不抛错**，只把内容置 null 并在 `reason` 里写明原因 */
  content: string | null
  size?: number
  /** 二进制或超限时后端只回占位说明 → 前端要标明「不可预览」 */
  truncated?: boolean
  binary?: boolean
  /** 后端拒绝预览时的原因文案（`content` 为 null 时才有） */
  reason?: string
}

/** 附件（上传后返回） */
export interface AttachmentItem {
  fileId: string
  /** 后端引用的 ASCII 名，**不要当展示名** */
  ref: string
  /** 原始文件名 —— 展示一律用这个 */
  fileName: string
  size: number
  uploadedAt?: string
}

/** 附件白名单（与后端 AttachmentController.ALLOWED_EXTENSIONS 保持一致） */
export const ALLOWED_EXTENSIONS = [
  'txt', 'md', 'markdown', 'json', 'csv', 'tsv',
  'py', 'java', 'js', 'ts', 'tsx', 'jsx', 'html', 'css', 'sql', 'xml', 'yaml', 'yml', 'sh',
  'pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx',
  'png', 'jpg', 'jpeg',
] as const

/** 单文件上限（MB）—— 与后端 MAX_ATTACH_BYTES（20MB）一致 */
export const ATTACH_MAX_MB = 20
