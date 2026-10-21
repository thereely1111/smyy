/**
 * 产物卡渲染层（Step3）
 *
 * 只做一件事：把产物树拍平成一列文件，画成**站点风格**的卡片墙。
 * 不做像素级复刻平台那套深色卡片 —— 配色一律沿用站点（青绿主色 / 墨色文字 / 浅底），
 * 与 live 那条通道同一个口径。
 *
 * 两条纪律：
 *   ① **只列主要产物**
 *      过程件（`truth/*` 契约、运行说明、依赖清单、上传的原题附件）不进清单；
 *      名字一律换成中文短名（全局分析 / 问题一代码），原文件名只在悬停提示里。
 *      过滤与改名都在 `pickMainFiles` / `labelOf` 两个纯函数里，可单测。
 */

import type { ArtifactNode } from '@/types/sandbox'

/** 一个要上屏的产物文件 */
export interface ArtifactFile {
  name: string
  /** 相对路径；点开预览时原样回传（见 sandbox/api/artifact.ts 的硬约束） */
  path: string
  size: number
  updatedAt: number
}

/**
 * 产物树 → 文件列表。
 *
 * **保留接口给的顺序**（每层「目录优先、名称升序」），深度优先展开 ——
 * 这样同一批产物（`analysis/` 那几个、`truth/` 那几个）会连在一起，
 * 不需要额外排序，也就不必引入「谁更重要」这种判断。
 */
export function flattenFiles(nodes: ArtifactNode[] | null | undefined): ArtifactFile[] {
  const out: ArtifactFile[] = []
  const walk = (list: ArtifactNode[]): void => {
    for (const n of list) {
      if (n.type === 'dir') {
        if (n.children?.length) walk(n.children)
        continue
      }
      out.push({ name: n.name, path: n.path, size: n.size ?? 0, updatedAt: n.updatedAt ?? 0 })
    }
  }
  if (Array.isArray(nodes)) walk(nodes)
  return out
}

export function esc(s: string): string {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** 文件大小：`12.1 KB` 这种口径（1024 进制、一位小数），与截图一致 */
export function fmtSize(bytes: number): string {
  if (!bytes || bytes < 0) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/**
 * 扩展名 → 图标。**按「打开它要什么工具」分色**，不按智能体业务分：
 * 文档＝青绿、代码/结构化＝青蓝、表格＝绿、图片＝暖金、PDF＝砖红、其余＝灰。
 */
const EXT_ICON: Record<string, { text: string; tone: string }> = {
  md: { text: 'M', tone: 'doc' },
  txt: { text: 'TXT', tone: 'doc' },
  rst: { text: 'TXT', tone: 'doc' },
  json: { text: '{}', tone: 'code' },
  jsonl: { text: '{}', tone: 'code' },
  yaml: { text: '{}', tone: 'code' },
  yml: { text: '{}', tone: 'code' },
  py: { text: 'PY', tone: 'code' },
  js: { text: 'JS', tone: 'code' },
  ts: { text: 'TS', tone: 'code' },
  java: { text: 'JV', tone: 'code' },
  sh: { text: 'SH', tone: 'code' },
  html: { text: '<>', tone: 'code' },
  css: { text: '{}', tone: 'code' },
  ipynb: { text: 'NB', tone: 'code' },
  csv: { text: 'CSV', tone: 'sheet' },
  tsv: { text: 'CSV', tone: 'sheet' },
  xlsx: { text: 'X', tone: 'sheet' },
  xls: { text: 'X', tone: 'sheet' },
  png: { text: 'IMG', tone: 'img' },
  jpg: { text: 'IMG', tone: 'img' },
  jpeg: { text: 'IMG', tone: 'img' },
  webp: { text: 'IMG', tone: 'img' },
  svg: { text: 'SVG', tone: 'img' },
  pdf: { text: 'PDF', tone: 'pdf' },
}

function extOf(name: string): string {
  const i = name.lastIndexOf('.')
  return i < 0 ? '' : name.slice(i + 1).toLowerCase()
}

export function iconOf(name: string): { text: string; tone: string } {
  return EXT_ICON[extOf(name)] ?? { text: '·', tone: 'bin' }
}

/* ── 主要产物：过滤 + 中文短名───────────────────────
   用户原话：「产物进行过滤，只要任务主要产物，比如说全局分析只要全局分析的文件；
   然后产物名字也简单做一下修改，全局分析的文件就叫全局分析，问题一的代码就叫问题一代码」。

   两条规则分开写，改一条不影响另一条：
     ① **过滤**（`pickMainFiles`）：只留四类能直接交出去的东西 —— 全局/逐问分析稿、
        各问解题代码、各问动画、各问结果图表。契约文件（`truth/*`）、运行说明、
        依赖清单、上传的原题附件一律不列（它们不是产物，是过程件）。
     ② **改名**（`labelOf`）：按「哪一问 + 什么类型」拼中文短名，原文件名降级成
        悬停提示里的副标题 —— 智能体起的文件名（`q01_m01_a01/main.py`）对用户没有意义。
   ────────────────────────────────────────────────────────────────────────── */

const CN_NUM = ['一', '二', '三', '四', '五', '六', '七', '八', '九', '十']

/** 路径里的问题号（0 基）；认不出来返回 -1 */
function qIndexOf(path: string): number {
  const p = String(path).replace(/\\/g, '/').toLowerCase()
  const m = /(?:^|\/)q(\d{1,2})(?:[_\-\/]|$)/.exec(p)
  if (!m) return -1
  const n = Number(m[1])
  return Number.isFinite(n) && n > 0 ? n - 1 : -1
}

function qCn(qi: number): string {
  return qi >= 0 ? CN_NUM[qi] ?? String(qi + 1) : ''
}

/** 过程件 / 辅助件：不进「主要产物」 */
const NOISE_NAME = /^(requirements\.txt|expected_outputs\.json|analysis_result\.json|readme(\S*)?\.md|readme_run\.md|\.ds_store)$/i
const NOISE_DIR = /\/(truth|input|uploads|__pycache__)\//i

function isNoise(f: ArtifactFile): boolean {
  const p = String(f.path).replace(/\\/g, '/')
  if (NOISE_NAME.test(f.name)) return true
  if (NOISE_DIR.test('/' + p.replace(/^\/+/, ''))) return true
  return false
}

/** 主要产物的类型（决定图标配色与排序） */
type MainKind = 'analysis' | 'code' | 'anim' | 'result' | 'other'

export interface MainFile extends ArtifactFile {
  /** 上屏用的中文短名（原文件名进 title） */
  label: string
  kind: MainKind
}

function kindOf(f: ArtifactFile): MainKind {
  const p = String(f.path).replace(/\\/g, '/').toLowerCase()
  const ext = extOf(f.name)
  if (ext === 'md' || ext === 'rst' || ext === 'txt') return 'analysis'
  if (ext === 'py' || ext === 'ipynb' || ext === 'js' || ext === 'm' || ext === 'r') return 'code'
  if (ext === 'html' || ext === 'htm') return 'anim'
  if (ext === 'csv' || ext === 'xlsx' || ext === 'xls' || ext === 'png' || ext === 'jpg' || ext === 'jpeg' || ext === 'svg') {
    return /\/results?\//.test(p) || ext === 'csv' || ext === 'xlsx' || ext === 'xls' ? 'result' : 'result'
  }
  return 'other'
}

/** 中文短名：全局分析 / 问题一分析 / 问题一代码 / 问题一动画 / 问题一结果 */
export function labelOf(f: ArtifactFile): string {
  const kind = kindOf(f)
  const qi = qIndexOf(f.path) >= 0 ? qIndexOf(f.path) : qIndexOf(f.name)
  const cn = qCn(qi)
  if (kind === 'analysis') return cn ? `问题${cn}分析` : '全局分析'
  if (kind === 'code') return cn ? `问题${cn}代码` : '解题代码'
  if (kind === 'anim') return cn ? `问题${cn}动画` : '可视化演示'
  if (kind === 'result') return cn ? `问题${cn}结果` : '结果文件'
  return f.name
}

const KIND_RANK: Record<MainKind, number> = { analysis: 0, code: 1, anim: 2, result: 3, other: 4 }

/**
 * 产物列表 → **主要产物**列表。
 *
 * 代码按问收成一条：一问常常十几个 `.py`，全列出来等于没过滤。收的口径是
 * 「主入口优先」（`main.py`），没有就取路径最短的那个 —— 与 `codeRun.ts`
 * 挑主入口的口径一致，两边不会各说各话。
 *
 * 兜底：一条都认不出来时（换了一套目录约定的新题）就把过滤放下、原样全列 ——
 * 宁可多列，也不能让「产物」面板空着骗人说没产物。
 */
export function pickMainFiles(files: ArtifactFile[]): MainFile[] {
  const keep: MainFile[] = []
  const codeByQ = new Map<string, MainFile>()

  for (const f of files) {
    if (!f || !f.name) continue
    if (isNoise(f)) continue
    const kind = kindOf(f)
    if (kind === 'other') continue
    const out: MainFile = { ...f, kind, label: labelOf(f) }
    if (kind !== 'code') {
      keep.push(out)
      continue
    }
    const key = String(qIndexOf(f.path) >= 0 ? qIndexOf(f.path) : -1)
    const prev = codeByQ.get(key)
    if (!prev) {
      codeByQ.set(key, out)
      continue
    }
    /* main.py 优先；都没有就看谁路径短（多半是入口） */
    const isMain = /(^|\/)main\.(py|m|js|r)$/i.test(f.path)
    const prevMain = /(^|\/)main\.(py|m|js|r)$/i.test(prev.path)
    if (isMain && !prevMain) codeByQ.set(key, out)
    else if (isMain === prevMain && f.path.length < prev.path.length) codeByQ.set(key, out)
  }

  for (const v of codeByQ.values()) keep.push(v)

  if (!keep.length) {
    return files.filter((f) => f && f.name).map((f) => ({ ...f, kind: 'other' as MainKind, label: f.name }))
  }

  /* 同一类里按问题号排；不同类型按 分析 → 代码 → 动画 → 结果 */
  keep.sort((a, b) => {
    const ka = KIND_RANK[a.kind] - KIND_RANK[b.kind]
    if (ka) return ka
    const qa = qIndexOf(a.path)
    const qb = qIndexOf(b.path)
    if (qa !== qb) return qa - qb
    return a.label.localeCompare(b.label, 'zh')
  })
  return keep
}

/** 一张文件卡（`data-path` 是给桥接层做事件委托用的，路径原样） */
export function cardHTML(f: ArtifactFile, label?: string): string {
  const ic = iconOf(f.name)
  return (
    '<button type="button" class="art-item" data-path="' + esc(f.path) + '" title="' + esc(f.path) + '">' +
    '<span class="art-ico ' + ic.tone + '">' + esc(ic.text) + '</span>' +
    '<span class="art-meta"><b>' + esc(label || f.name) + '</b><em>' + fmtSize(f.size) + '</em></span>' +
    '<span class="art-go">›</span>' +
    '</button>'
  )
}

export interface WallState {
  files: ArtifactFile[]
  /** 正在拉树 */
  loading: boolean
  /** 至少成功拉到过一次（决定了要不要报「N 项」） */
  loaded: boolean
  /** 失败原因（读不到清单时如实显示，不装作「没有产物」） */
  error: string
}

/** 整张卡的 HTML（含头部与刷新按钮）—— 作为 `.art-card` 的 innerHTML */
export function wallHTML(s: WallState): string {
  /* 2026-10-20：上屏只列**主要产物**（过滤 + 中文短名都在 pickMainFiles 里） */
  const main = pickMainFiles(s.files)
  const filtered = s.files.length > main.length
  const count = s.loaded ? '<em class="art-count">' + main.length + ' 项</em>' : ''
  const head =
    '<div class="art-head"><span class="art-title">任务产物</span>' +
    count +
    '<button type="button" class="art-refresh" data-art-refresh="1">' +
    (s.loading ? '读取中…' : '刷新') +
    '</button></div>'

  let body: string
  if (s.error) {
    body = '<div class="art-empty">产物清单没读到：' + esc(s.error) + '</div>'
  } else if (s.loading && !s.loaded) {
    body = '<div class="art-empty">正在读这个会话的产物…</div>'
  } else if (!main.length) {
    body =
      '<div class="art-empty">这个会话还没有产物。智能体写出来的文件会出现在这里 —— ' +
      '它可能还在写（这类题常见要二三十分钟），过会儿点上方「刷新」再看。</div>'
  } else {
    body = '<div class="art-grid">' + main.map((f) => cardHTML(f, f.label)).join('') + '</div>'
  }

  const note = main.length
    ? '<div class="art-note">只列主要产物（全局分析 / 各问代码 · 动画 · 结果）'
      + (filtered ? '，另有 ' + (s.files.length - main.length) + ' 个过程文件已折叠' : '')
      + '。点一下就能看全文。</div>'
    : ''

  return head + note + body
}

/* ── 样式（自己注入，不动生成物那套 CSS） ─────────────────────────────────── */

const STYLE_ID = 'smyy-artifact-style'

export function ensureArtifactStyle(): void {
  if (typeof document === 'undefined') return
  /* 光看 id 不够：活久了的文档里那张表可能是旧版本的（见 live/index.ts 同一处说明） */
  const prev = document.getElementById(STYLE_ID)
  if (prev) {
    if (prev.textContent === ARTIFACT_CSS) return
    prev.remove()
  }
  const el = document.createElement('style')
  el.id = STYLE_ID
  el.textContent = ARTIFACT_CSS
  document.head.appendChild(el)
}

const ARTIFACT_CSS = `
.art-card{display:block;margin:10px 0 2px;padding:10px 12px;border:1px solid #dfeaea;border-radius:10px;background:#fff}
.art-head{display:flex;align-items:center;gap:8px}
.art-title{font-size:13.5px;font-weight:600;color:#0b2e36}
.art-count{padding:0 6px;border-radius:999px;background:#eef6f5;color:#0b6e68;font-style:normal;font-size:11px;line-height:16px}
.art-refresh{margin-left:auto;flex:0 0 auto;padding:2px 10px;border:1px solid #cde6e3;border-radius:999px;background:#f2fbfa;color:#0b6e68;font-size:12px;line-height:18px;cursor:pointer;transition:background .12s ease,color .12s ease,border-color .12s ease}
.art-refresh:hover{background:#12a594;border-color:#12a594;color:#fff}
.art-note{margin:5px 0 8px;color:#8fa5a9;font-size:11.5px;line-height:1.6}
.art-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}
.art-item{display:flex;align-items:center;gap:9px;width:100%;min-width:0;padding:8px 9px;border:1px solid #e6f0ef;border-radius:9px;background:#fafdfd;text-align:left;cursor:pointer;transition:background .12s ease,border-color .12s ease}
.art-item:hover{background:#f0f8f7;border-color:#bfe3df}
.art-ico{flex:0 0 auto;width:26px;height:26px;display:flex;align-items:center;justify-content:center;border-radius:7px;background:#9fb0b4;color:#fff;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:10.5px;font-weight:700;letter-spacing:-.2px}
.art-ico.doc{background:#12a594}
.art-ico.code{background:#146c87}
.art-ico.sheet{background:#2f9e6f}
.art-ico.img{background:#e0a04a}
.art-ico.pdf{background:#b0563f}
.art-meta{flex:1 1 auto;min-width:0;display:flex;flex-direction:column;gap:1px}
.art-meta b{font-size:12.5px;font-weight:600;color:#123039;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.art-meta em{font-style:normal;font-size:11px;color:#8fa5a9}
.art-go{flex:0 0 auto;color:#bfd5d3;font-size:15px;line-height:1}
.art-item:hover .art-go{color:#0b6e68}
.art-empty{margin:6px 0 2px;padding:9px 11px;border:1px dashed #dfeaea;border-radius:9px;background:#fafdfd;color:#57727c;font-size:12.5px;line-height:1.7}
.art-pv{margin:0;padding:12px 14px;color:#123039;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:12px;line-height:1.7;white-space:pre-wrap;word-break:break-word}
.art-pv-note{margin:10px 12px;padding:9px 11px;border:1px dashed #dfeaea;border-radius:9px;background:#fafdfd;color:#57727c;font-size:12.5px;line-height:1.7}
.art-pv-img{display:block;max-width:100%;height:auto;margin:0 auto;border-radius:9px}
@media (max-width:560px){.art-grid{grid-template-columns:minmax(0,1fr)}}
`
