/**
 * 导出产物相关的公共类型与浏览器下载工具。
 *
 * 注意：**没有服务端导出接口**。脚手架只提供产物的读/写/下载原文件
 * （`/artifacts`、`/artifacts/content`、`/artifacts/download`），不做格式转换；
 * Word 由前端生成（见 `utils/analysis/docxWriter.ts`）。
 */

export interface ExportedDoc {
  /** 下载用的文件名 */
  fileName: string
  blob: Blob
  /** 字节数 */
  size: number
  /** 服务端落盘位置；本地生成时恒为空串 */
  storedPath: string
  /** true = 浏览器本地生成（当前唯一路径） */
  local?: boolean
}

/** 从 `Content-Disposition` 取文件名：优先 RFC 5987 的 `filename*=`，退回 `filename="…"` */
export function fileNameOf(disposition: string | null, fallback: string): string {
  if (!disposition) return fallback
  const star = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (star) {
    try {
      return decodeURIComponent(star[1])
    } catch {
      return star[1]
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(disposition)
  return plain ? plain[1] : fallback
}

/** 触发浏览器下载。用临时 `<a download>`，随后回收 objectURL（否则 blob 一直驻留内存） */
export function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName || 'report.docx'
  a.rel = 'noopener'
  a.style.display = 'none'
  document.body.appendChild(a)
  a.click()
  a.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 4000)
}
