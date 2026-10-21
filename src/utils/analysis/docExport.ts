/**
 * 本地导出 Word —— 脚手架里没有 `POST …/export`（那是私有扩展，云端/活跃侧均无此路由），
 * 所以导出全程在浏览器里完成，只复用脚手架既有的只读接口：
 *   `GET /api/agents/{agentId}/sessions/{sessionId}/artifacts/content?path=…`
 *
 * 产物由 `docxWriter.ts` 生成标准 OOXML，Word / WPS 正常打开、可编辑。
 * 与 kuaike 的做法同为纯前端导出，区别是这里出真 `.docx` 而不是 HTML 内核的 `.doc`。
 *
 * 边界：图片不内联（产物相对路径在独立文档里没落点）；公式走 LaTeX→Unicode 降级。
 */

import { fetchArtifactContent } from '@/api/artifact'
import type { ExportedDoc } from '@/api/exportDoc'
import { markdownToDocx } from '@/utils/analysis/docxWriter'

export { latexToUnicode } from '@/utils/analysis/docxWriter'

/** `…/analysis_v1.0.md` → `analysis_v1.0.docx` */
export function docNameOf(path: string): string {
  const base = path.slice(path.lastIndexOf('/') + 1) || 'report.md'
  return base.replace(/\.[^.]+$/, '') + '.docx'
}

/** 拉原稿 Markdown → 浏览器渲染成 docx */
export async function exportDocLocal(agentId: string, sessionId: string, path: string): Promise<ExportedDoc> {
  const file = await fetchArtifactContent(agentId, sessionId, path)
  if (!file || !file.content) throw new Error(file?.reason || '读不到原稿内容，无法生成 Word')
  const fileName = docNameOf(path)
  const blob = await markdownToDocx(file.content)
  return { fileName, blob, size: blob.size, storedPath: '', local: true }
}
