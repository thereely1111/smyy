/**
 * 分析报告卡（P2）—— 分析跑完后把产物里的分析主稿整理成 Word 交到对话区
 *
 * 需求口径（2026-10-09 用户定）：「**以 docx 为主，动画以 html**」。所以这张卡只处理
 * Markdown 主稿 → docx；动画产物是 HTML，本来就能在浏览器里直接跑，转成 Word 反而
 * 丢交互，不进这条路径（它们在旁边的「智能体产物」卡里照旧可见）。
 *
 * 三条与产物卡一致的纪律：
 *   ① **会话坐标挂载时冻结**（cardId），切任务后点「重新生成」不会读错会话；
 *   ② **只读地拿会话**（`sessionThreadFor`）—— 看一眼产物不该顺手建一个会话；
 *   ③ **读不到就如实说**：找不到可导出的 Markdown、后端不可用，都写清楚是哪一种，
 *      不画成「正在生成」转到天荒地老。
 *
 * 自动挑哪一个文件：产物里常有多份 md（`analysis/analysis_v1.0.md`、
 * `truth/*.md`、子会话各写一套…）。这里按「越像分析主稿得分越高」排序（`/analysis/`
 * 目录、文件名带 `analysis_v`、带 result 依次加权），并**排除 `input/`**（那是用户
 * 自己传的原题与附件，不是分析结果）。挑不中就不导出，写明原因让用户点产物卡自己看。
 */

import { fetchArtifactTree } from '@/api/artifact'
import { AGENT_ID } from '@/config'
import { artifactPreview, sessionThreadFor } from '@/utils/analysis/artifact'
import { flattenFiles, type ArtifactFile } from '@/utils/analysis/artifact/render'
import { saveBlob, type ExportedDoc } from '@/api/exportDoc'
import { exportDocLocal } from '@/utils/analysis/docExport'

type Stage = 'idle' | 'finding' | 'exporting' | 'ready' | 'error'

interface View {
  /** 挂载时冻结的题号 */
  caseId: string
  stage: Stage
  /** 这次导出用的源文件 */
  source: ArtifactFile | null
  doc: ExportedDoc | null
  /** 找不到可导出文件时的说明（与「出错」分开：这是「还没写出来」） */
  missing: string
  error: string
}

const views = new WeakMap<HTMLElement, View>()
let mounted: HTMLElement | null = null

function viewOf(box: HTMLElement, caseId: string): View {
  let v = views.get(box)
  if (!v) {
    v = { caseId, stage: 'idle', source: null, doc: null, missing: '', error: '' }
    views.set(box, v)
  } else if (caseId) {
    v.caseId = caseId
  }
  return v
}

function paint(box: HTMLElement, v: View): void {
  ensureReportStyle()
  box.innerHTML = cardHTML(v)
}

export function reportMount(el: HTMLElement | null, caseId = ''): void {
  mounted = el
  if (!el) return
  paint(el, viewOf(el, caseId))
}

/* ── 挑源文件 ───────────────────────────────────────────────────────────── */

/** 用户自己传的原题 / 附件所在目录，不算分析结果 */
const INPUT_DIR = /(^|\/)input\//i

function scoreOf(f: ArtifactFile): number {
  const path = f.path.toLowerCase()
  const name = f.name.toLowerCase()
  let s = 0
  if (path.includes('/analysis/')) s += 4
  if (/analysis[v_-]?\d/.test(name)) s += 4
  else if (name.includes('analysis')) s += 2
  if (name.includes('result')) s += 1
  return s
}

/** 从产物清单里挑一份「最像分析主稿」的 Markdown；挑不出返回 null */
export function pickReportSource(files: ArtifactFile[]): ArtifactFile | null {
  const md = files.filter((f) => /\.md$/i.test(f.name) && !INPUT_DIR.test(f.path))
  if (!md.length) return null
  return md.reduce((best, f) => {
    const d = scoreOf(f) - scoreOf(best)
    if (d > 0) return f
    // 同分取更新的一份：重跑过的产物比旧的更可信
    return d === 0 && f.updatedAt > best.updatedAt ? f : best
  })
}

/* ── 导出 ───────────────────────────────────────────────────────────────── */

/**
 * 拉产物树 → 挑主稿 → 导出 docx（结果存进这一格的 view，供「下载」直接用）。
 *
 * 幂等：重复调用会重新导出并覆盖同一份 `exports/`（后端同名覆盖），不会攒一堆副本。
 */
export function reportRefresh(el?: HTMLElement | null, caseId = ''): Promise<void> {
  const box = el ?? mounted
  if (!box) return Promise.resolve()
  const v = viewOf(box, caseId)

  const threadId = sessionThreadFor(v.caseId)
  if (!AGENT_ID || !threadId) {
    v.stage = 'error'
    v.error = v.caseId ? '这道题的会话还没在后端建立，暂时导不出报告' : '还没有会话（先在首页发一次题）'
    paint(box, v)
    return Promise.resolve()
  }

  v.stage = 'finding'
  v.error = ''
  v.missing = ''
  paint(box, v)

  return fetchArtifactTree(AGENT_ID, threadId)
    .then((nodes) => {
      const src = pickReportSource(flattenFiles(nodes))
      if (!src) {
        v.stage = 'error'
        v.source = null
        v.doc = null
        v.missing = '这个会话的产物里还没有 Markdown 分析稿。智能体写产物常见要二三十分钟，'
          + '过会儿点「重新生成」再看；已经写好的文件都在下面那张「智能体产物」卡里。'
        paint(box, v)
        return
      }
      v.source = src
      v.stage = 'exporting'
      paint(box, v)
      /* 不叫服务端导出：脚手架没有该路由（私有扩展在云端不存在），
         直接在浏览器里按原稿生成 docx（见 utils/analysis/docxWriter.ts）。 */
      return exportDocLocal(AGENT_ID, threadId, src.path)
        .then((doc) => {
          v.doc = doc
          v.stage = 'ready'
          paint(box, v)
        })
    })
    .catch((e: unknown) => {
      v.stage = 'error'
      v.error = e instanceof Error ? e.message : String(e)
      paint(box, v)
    })
}

/** 下载 Word；还没生成过就先导一次再下载 */
export function reportDownload(el?: HTMLElement | null, caseId = ''): Promise<void> {
  const box = el ?? mounted
  if (!box) return Promise.resolve()
  const v = viewOf(box, caseId)
  if (v.doc) {
    saveBlob(v.doc.blob, v.doc.fileName)
    return Promise.resolve()
  }
  return reportRefresh(box, caseId).then(() => {
    if (v.doc) saveBlob(v.doc.blob, v.doc.fileName)
  })
}

/** 预览**原稿 Markdown**（Word 不在这里渲染——那是下载后本地打开的事） */
export function reportPreview(el?: HTMLElement | null, caseId = '') {
  const box = el ?? mounted
  if (!box) return Promise.resolve(null)
  const v = viewOf(box, caseId)
  if (!v.source) return Promise.resolve(null)
  return artifactPreview(v.source.path, box, v.caseId)
}

/* ── 渲染 ───────────────────────────────────────────────────────────────── */

function esc(s: string): string {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function fmtSize(bytes: number): string {
  if (!bytes || bytes < 0) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function cardHTML(v: View): string {
  const head =
    '<div class="rep-head"><span class="rep-title">分析报告</span>' +
    '<em class="rep-badge">Word</em>' +
    '<button type="button" class="rep-btn ghost" data-report="refresh">' +
    (v.stage === 'exporting' || v.stage === 'finding' ? '处理中…' : '重新生成') +
    '</button></div>'

  let body: string
  if (v.stage === 'idle') {
    body = '<div class="rep-note">准备生成分析报告…</div>'
  } else if (v.stage === 'finding') {
    body = '<div class="rep-note">正在读这个会话的产物，找分析主稿…</div>'
  } else if (v.stage === 'exporting') {
    body = '<div class="rep-note">正在把《' + esc(v.source?.name ?? '') + '》整理成 Word…</div>'
  } else if (v.stage === 'ready' && v.doc) {
    body =
      '<div class="rep-file"><span class="rep-ico">DOCX</span>' +
      '<span class="rep-meta"><b>' + esc(v.doc.fileName) + '</b>' +
      '<em>' + esc(fmtSize(v.doc.size)) + ' · 源：' + esc(v.doc.storedPath || (v.source?.name ?? '')) + '</em>' +
      '</span></div>' +
      '<div class="rep-acts">' +
      '<button type="button" class="rep-btn main" data-report="download">下载 Word</button>' +
      '<button type="button" class="rep-btn" data-report="preview">预览原稿</button>' +
      '</div>' +
      '<div class="rep-note small">' +
      '这份 Word 由浏览器按原稿现场生成（标准 .docx，可直接编辑），没有落到会话目录；' +
      '原稿仍按 Markdown 保存在产物区，随时可再导出。</div>'
  } else if (v.missing) {
    body = '<div class="rep-note warn">' + esc(v.missing) + '</div>'
  } else {
    body =
      '<div class="rep-note warn">导出没成功：' + esc(v.error || '未知原因') + '</div>' +
      '<div class="rep-acts"><button type="button" class="rep-btn" data-report="retry">重试</button></div>'
  }
  return head + body
}

/* ── 样式（自己注入，沿用站点口径，不动生成物那套 CSS） ──────────────────── */

const STYLE_ID = 'smyy-report-style'

export function ensureReportStyle(): void {
  if (typeof document === 'undefined') return
  /* 光看 id 不够：活久了的文档里那张表可能是旧版本的（见 live/index.ts 同一处说明） */
  const prev = document.getElementById(STYLE_ID)
  if (prev) {
    if (prev.textContent === REPORT_CSS) return
    prev.remove()
  }
  const el = document.createElement('style')
  el.id = STYLE_ID
  el.textContent = REPORT_CSS
  document.head.appendChild(el)
}

const REPORT_CSS = `
.rep-card{display:block;margin:10px 0 2px;padding:10px 12px;border:1px solid #dfeaea;border-radius:10px;background:#fff}
.rep-head{display:flex;align-items:center;gap:8px}
.rep-title{font-size:13.5px;font-weight:600;color:#0b2e36}
.rep-badge{padding:0 6px;border-radius:999px;background:#eef6f5;color:#0b6e68;font-style:normal;font-size:11px;line-height:16px}
.rep-head .rep-btn{margin-left:auto}
.rep-btn{flex:0 0 auto;padding:3px 12px;border:1px solid #cde6e3;border-radius:999px;background:#f2fbfa;color:#0b6e68;font-size:12px;line-height:18px;cursor:pointer;transition:background .12s ease,color .12s ease,border-color .12s ease}
.rep-btn:hover{background:#12a594;border-color:#12a594;color:#fff}
.rep-btn.main{background:#12a594;border-color:#12a594;color:#fff}
.rep-btn.main:hover{background:#0b6e68;border-color:#0b6e68}
.rep-acts{display:flex;gap:8px;margin-top:8px}
.rep-file{display:flex;align-items:center;gap:9px;margin-top:8px;padding:8px 9px;border:1px solid #e6f0ef;border-radius:9px;background:#fafdfd}
.rep-ico{flex:0 0 auto;width:34px;height:26px;display:flex;align-items:center;justify-content:center;border-radius:7px;background:#146c87;color:#fff;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:9.5px;font-weight:700}
.rep-meta{flex:1 1 auto;min-width:0;display:flex;flex-direction:column;gap:1px}
.rep-meta b{font-size:12.5px;font-weight:600;color:#123039;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.rep-meta em{font-style:normal;font-size:11px;color:#8fa5a9;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.rep-note{margin:6px 0 2px;padding:9px 11px;border:1px dashed #dfeaea;border-radius:9px;background:#fafdfd;color:#57727c;font-size:12.5px;line-height:1.7}
.rep-note.small{margin-top:8px;font-size:11.5px;color:#8fa5a9;border-style:solid;background:#fbfdfd}
.rep-note.warn{border-color:#e7d9bf;background:#fdfaf3;color:#8a6313}
`
