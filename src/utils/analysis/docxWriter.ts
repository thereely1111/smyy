/**
 * Markdown → 真 `.docx`（WordprocessingML + JSZip），**纯前端**。
 *
 * 脚手架没有「导出 Word」接口；之前那个 `POST …/export` 是私有扩展，云端/活跃侧都没有，
 * 所以导出只能在浏览器里做。这里生成的是标准 OOXML 包，Word / WPS 打开不会提示格式不符。
 *
 * 覆盖范围：标题、段落、粗斜体、行内码、代码块、有序/无序列表、表格、引用、分割线、LaTeX→Unicode。
 * 不做的：图片内联（产物里的相对路径在独立文档里没有落点）、脚注、复杂嵌套列表缩进。
 */

import JSZip from 'jszip'
import MarkdownIt from 'markdown-it'

/** markdown-it token 的最小子集（不引它的类型导出，避免版本间路径差异） */
interface Tok {
  type: string
  tag: string
  content: string
  info: string
  markup: string
  children?: Tok[] | null
}

const md = new MarkdownIt({ html: false, linkify: false, breaks: false })

/* ── LaTeX → Unicode（与后端 MarkdownDocxRenderer 对齐的子集） ───────────── */

const LATEX_SYMBOLS: ReadonlyArray<readonly [string, string]> = [
  ['\\alpha', 'α'], ['\\beta', 'β'], ['\\gamma', 'γ'], ['\\delta', 'δ'], ['\\epsilon', 'ε'],
  ['\\varepsilon', 'ε'], ['\\zeta', 'ζ'], ['\\eta', 'η'], ['\\theta', 'θ'], ['\\lambda', 'λ'],
  ['\\mu', 'μ'], ['\\nu', 'ν'], ['\\xi', 'ξ'], ['\\pi', 'π'], ['\\rho', 'ρ'], ['\\sigma', 'σ'],
  ['\\tau', 'τ'], ['\\phi', 'φ'], ['\\varphi', 'φ'], ['\\chi', 'χ'], ['\\psi', 'ψ'], ['\\omega', 'ω'],
  ['\\Delta', 'Δ'], ['\\Theta', 'Θ'], ['\\Lambda', 'Λ'], ['\\Pi', 'Π'], ['\\Sigma', 'Σ'],
  ['\\Phi', 'Φ'], ['\\Omega', 'Ω'],
  ['\\times', '×'], ['\\cdot', '·'], ['\\div', '÷'], ['\\pm', '±'], ['\\mp', '∓'],
  ['\\le', '≤'], ['\\leq', '≤'], ['\\ge', '≥'], ['\\geq', '≥'], ['\\ne', '≠'], ['\\neq', '≠'],
  ['\\approx', '≈'], ['\\equiv', '≡'], ['\\propto', '∝'], ['\\infty', '∞'], ['\\partial', '∂'],
  ['\\sum', '∑'], ['\\prod', '∏'], ['\\int', '∫'], ['\\sqrt', '√'], ['\\in', '∈'], ['\\notin', '∉'],
  ['\\cup', '∪'], ['\\cap', '∩'], ['\\subset', '⊂'], ['\\forall', '∀'], ['\\exists', '∃'],
  ['\\rightarrow', '→'], ['\\leftarrow', '←'], ['\\Rightarrow', '⇒'], ['\\to', '→'],
]

/** 长命令先换，否则 `\le` 会吃掉 `\leq` 的前缀 */
const LATEX_SORTED = [...LATEX_SYMBOLS].sort((a, b) => b[0].length - a[0].length)

const escapeRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** `$…$` / `$$…$$` 里的 LaTeX → Unicode；`\sqrt{2}` 连花括号一起换。
 *  只动公式定界符内的内容 —— 文本里的裸 `$`（金额之类）原样保留。 */
export function latexToUnicode(input: string): string {
  return input
    .replace(/\$\$([\s\S]+?)\$\$/g, (_m, s: string) => symbolsOf(s.trim()))
    .replace(/\$([^$\n]+?)\$/g, (_m, s: string) => symbolsOf(s))
}

function symbolsOf(src: string): string {
  let out = src
  for (const [key, symbol] of LATEX_SORTED) {
    out = out.replace(new RegExp(escapeRe(key) + '\\{([^{}]*)\\}', 'g'), symbol + '$1')
    out = out.split(key).join(symbol)
  }
  return out
}

/* ── XML 片段 ───────────────────────────────────────────────────────────── */

const esc = (s: string) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

interface RunStyle { bold?: boolean; italic?: boolean; mono?: boolean; link?: boolean; size?: number }

function run(text: string, st: RunStyle = {}): string {
  if (!text) return ''
  // 子元素顺序按 OOXML schema：rFonts → b → i → color → sz/szCs → u
  const rPr =
    '<w:rPr><w:rFonts w:ascii="' + (st.mono ? 'Consolas' : 'Times New Roman') +
    '" w:hAnsi="' + (st.mono ? 'Consolas' : 'Times New Roman') +
    '" w:eastAsia="' + (st.mono ? 'Consolas' : '宋体') + '"/>' +
    (st.bold ? '<w:b/>' : '') +
    (st.italic ? '<w:i/>' : '') +
    (st.link ? '<w:color w:val="0563C1"/>' : '') +
    (st.size ? '<w:sz w:val="' + st.size + '"/><w:szCs w:val="' + st.size + '"/>' : '') +
    (st.link ? '<w:u w:val="single"/>' : '') +
    '</w:rPr>'
  // 段内含换行时拆成多个 run，靠 <w:br/> 连接
  const parts = text.split('\n')
  const body = parts
    .map((p, i) => (i ? '<w:br/>' : '') + '<w:t xml:space="preserve">' + esc(p) + '</w:t>')
    .join('')
  return '<w:r>' + rPr + body + '</w:r>'
}

function para(runs: string, pPr = ''): string {
  return '<w:p>' + (pPr ? '<w:pPr>' + pPr + '</w:pPr>' : '') + runs + '</w:p>'
}

const HEADING_PPR: Record<string, string> = {
  h1: '<w:spacing w:before="240" w:after="200" w:line="360" w:lineRule="auto"/><w:jc w:val="center"/>',
  h2: '<w:spacing w:before="300" w:after="140" w:line="360" w:lineRule="auto"/>',
  h3: '<w:spacing w:before="240" w:after="120" w:line="360" w:lineRule="auto"/>',
  h4: '<w:spacing w:before="200" w:after="100" w:line="360" w:lineRule="auto"/>',
  h5: '<w:spacing w:before="200" w:after="100" w:line="360" w:lineRule="auto"/>',
  h6: '<w:spacing w:before="200" w:after="100" w:line="360" w:lineRule="auto"/>',
}

const HEADING_SIZE: Record<string, number> = { h1: 32, h2: 26, h3: 23, h4: 21, h5: 21, h6: 21 }

const BODY_PPR = '<w:spacing w:after="120" w:line="360" w:lineRule="auto"/>'

/* ── inline token → runs ────────────────────────────────────────────────── */

function inlineRuns(tok: Tok | undefined): string {
  if (!tok || !tok.children) return ''
  let bold = false
  let italic = false
  let link = false
  const out: string[] = []
  for (const c of tok.children) {
    switch (c.type) {
      case 'text':
        out.push(run(latexToUnicode(c.content), { bold, italic, link }))
        break
      case 'strong_open': bold = true; break
      case 'strong_close': bold = false; break
      case 'em_open': italic = true; break
      case 'em_close': italic = false; break
      case 'link_open': link = true; break
      case 'link_close': link = false; break
      case 'code_inline':
        out.push(run(c.content, { mono: true, bold, italic }))
        break
      case 'softbreak':
      case 'hardbreak':
        out.push('<w:r><w:br/></w:r>')
        break
      case 'image': {
        // 图片不内联：留一行占位说明，避免读者以为内容丢了
        const alt = /alt="([^"]*)"/.exec(c.content)?.[1] || '图片'
        out.push(run('［图：' + alt + '］', { italic: true }))
        break
      }
      default:
        if (c.content && c.type !== 'html_inline') out.push(run(latexToUnicode(c.content), { bold, italic, link }))
    }
  }
  return out.join('')
}

/* ── 块级 token → 段落/表格 ─────────────────────────────────────────────── */

const PREFIX_PPR = (level: number) =>
  '<w:spacing w:after="80" w:line="360" w:lineRule="auto"/><w:ind w:left="' + (360 + level * 360) + '"/>'

function tableXml(rows: string[][]): string {
  const border = (side: string) => '<w:' + side + ' w:val="single" w:sz="4" w:space="0" w:color="94A3B8"/>'
  const cell = (text: string, head: boolean) =>
    '<w:tc><w:tcPr><w:tcW w:w="0" w:type="auto"/>' +
    (head ? '<w:shd w:val="clear" w:fill="F1F5F9"/>' : '') +
    '</w:tcPr>' +
    para(run(text, { bold: head, size: 20 }),
      '<w:spacing w:before="40" w:after="40" w:line="300" w:lineRule="auto"/>') +
    '</w:tc>'
  const body = rows
    .map((cells, i) => '<w:tr>' + cells.map((c) => cell(c, i === 0)).join('') + '</w:tr>')
    .join('')
  return (
    '<w:tbl><w:tblPr><w:tblW w:w="5000" w:type="pct"/><w:tblBorders>' +
    border('top') + border('left') + border('bottom') + border('right') + border('insideH') + border('insideV') +
    '</w:tblBorders></w:tblPr>' + body + '</w:tbl>' +
    // 表格后补一个空段落，否则两张表会黏在一起
    para('')
  )
}

function blocksXml(tokens: Tok[]): string {
  const out: string[] = []
  let listDepth = 0
  let ordered = false
  let orderedIndex = 0
  let inQuote = false

  // 表格收集态
  let table: string[][] | null = null
  let row: string[] | null = null
  let cellText = ''

  const flushCell = () => {
    if (row) row.push(cellText.trim())
    cellText = ''
  }

  for (let i = 0; i < tokens.length; i++) {
    const t = tokens[i]

    // 表格优先处理（内部结构简单，单独走一条线）
    if (t.type === 'table_open') { table = []; continue }
    if (t.type === 'table_close') {
      if (table && table.length) out.push(tableXml(table))
      table = null
      continue
    }
    if (table) {
      if (t.type === 'tr_open') row = []
      else if (t.type === 'tr_close') { if (row) table.push(row); row = null }
      else if (t.type === 'th_open' || t.type === 'td_open') cellText = ''
      else if (t.type === 'th_close' || t.type === 'td_close') flushCell()
      else if (t.type === 'inline') {
        // 单元格里只留纯文本，保留内联结构意义不大
        cellText += (t.children || []).map((c) => c.content).join('')
      }
      continue
    }

    switch (t.type) {
      case 'heading_open': {
        const tag = t.tag as keyof typeof HEADING_PPR
        const inline = tokens[i + 1]
        const text = (inline?.children || []).map((c) => latexToUnicode(c.content)).join('')
        out.push(para(run(text, { bold: true, size: HEADING_SIZE[tag] }), HEADING_PPR[tag]))
        i += 2 // 跳过 inline 与 heading_close
        break
      }
      case 'paragraph_open': {
        const inline = tokens[i + 1]
        const runs = inlineRuns(inline)
        // 列表项里的段落要自己带前缀（markdown-it 不生成编号）
        const mark = listDepth > 0 && runs ? (ordered ? orderedIndex + '. ' : '· ') : ''
        const pPr = inQuote
          ? '<w:spacing w:after="120" w:line="360" w:lineRule="auto"/><w:ind w:left="600"/>'
          : listDepth > 0 ? PREFIX_PPR(Math.max(0, listDepth - 1)) : BODY_PPR
        out.push(para(mark ? run(mark) + runs : runs, pPr))
        i += 1
        break
      }
      case 'fence':
      case 'code_block': {
        const lines = t.content.replace(/\n$/, '').split('\n')
        for (const line of lines) {
          out.push(para(run(line || ' ', { mono: true, size: 19 }),
            '<w:ind w:left="360"/><w:spacing w:after="0" w:line="280" w:lineRule="auto"/>'))
        }
        out.push(para(''))
        break
      }
      case 'bullet_list_open': listDepth++; ordered = false; break
      case 'ordered_list_open': listDepth++; ordered = true; orderedIndex = 0; break
      case 'bullet_list_close':
      case 'ordered_list_close': listDepth = Math.max(0, listDepth - 1); break
      case 'list_item_open': if (ordered) orderedIndex++; break
      case 'list_item_close': break
      case 'blockquote_open': inQuote = true; break
      case 'blockquote_close': inQuote = false; break
      case 'hr': out.push(para('', '<w:pBdr><w:bottom w:val="single" w:sz="6" w:space="1" w:color="CBD5E1"/></w:pBdr>')); break
      case 'inline':
        // 段落的 inline 已在 paragraph_open 里消费掉，这里不再输出（否则整段重复）
        break
      default:
        break
    }
  }
  return out.join('')
}

/* ── 打包 ───────────────────────────────────────────────────────────────── */

const CONTENT_TYPES =
  '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
  '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">' +
  '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>' +
  '<Default Extension="xml" ContentType="application/xml"/>' +
  '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>' +
  '<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>' +
  '</Types>'

const ROOT_RELS =
  '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
  '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
  '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>' +
  '</Relationships>'

const DOC_RELS =
  '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
  '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' +
  '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>' +
  '</Relationships>'

/** 文档默认样式：宋体 / 10.5pt / 1.5 倍行距，与后端渲染同一口径 */
const STYLES =
  '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
  '<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">' +
  '<w:docDefaults><w:rPrDefault><w:rPr>' +
  '<w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:eastAsia="宋体" w:cs="Times New Roman"/>' +
  '<w:sz w:val="21"/><w:szCs w:val="21"/></w:rPr></w:rPrDefault>' +
  '<w:pPrDefault><w:pPr><w:spacing w:line="360" w:lineRule="auto"/></w:pPr></w:pPrDefault>' +
  '</w:docDefaults>' +
  '<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>' +
  '</w:styles>'

/** Markdown → docx 字节（可直接 `new Blob([bytes])` 下载） */
export async function markdownToDocx(markdown: string): Promise<Blob> {
  const body = blocksXml(md.parse(markdown, {}) as unknown as Tok[])
  const document =
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>' +
    '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">' +
    '<w:body>' + body +
    '<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>' +
    '<w:pgMar w:top="1247" w:right="1134" w:bottom="1247" w:left="1134" w:header="851" w:footer="992" w:gutter="0"/>' +
    '</w:sectPr></w:body></w:document>'

  const zip = new JSZip()
  // createFolders:false —— 不写目录条目，OPC 包里只有文件（Word 对多余目录条目并不总是友好）
  const put = (name: string, data: string) => zip.file(name, data, { createFolders: false })
  put('[Content_Types].xml', CONTENT_TYPES)
  put('_rels/.rels', ROOT_RELS)
  put('word/document.xml', document)
  put('word/styles.xml', STYLES)
  put('word/_rels/document.xml.rels', DOC_RELS)
  return zip.generateAsync({
    type: 'blob',
    mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    compression: 'DEFLATE',
  })
}
