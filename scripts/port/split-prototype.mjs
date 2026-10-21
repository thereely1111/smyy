/**
 * 原型分片器 —— 把「数模演易原型v2.html」切成可搬运的片段
 *
 * 为什么需要它：原型是 1.82M 的单文件，脚本单块就 1.2M 字符。
 * 整份读进上下文既不现实也容易出错，必须按结构切块，逐块搬运。
 *
 * ⚠ 为什么不用现成的 JS 解析器：smyy 依赖里那份 typescript 被 pnpm 裁掉了
 *   package.json，require 不进去；而「自己写个朴素的花括号配平」会被
 *   `replace(/["'`]/g, ...)` 这种**正则字面量里的引号**骗到 —— 一旦把正则
 *   当成字符串的开始，后面的花括号配平会整体错位，切出来的片段静默错位。
 *   所以这里自带一个词法扫描器，能识别：单双引号串、模板串（含 ${} 嵌套）、
 *   正则字面量（含字符类 []）、行注释、块注释。
 *
 * 硬约束：**所有片段按序拼回，必须与原文逐字节一致**。不一致直接 ABORT 不落盘。
 *   这条自检是切分正确性的唯一判据（片段名对不上没关系，字节必须对上）。
 *
 * 用法：
 *   node scripts/port/split-prototype.mjs [outDir]
 *
 * 输出：
 *   <outDir>/style-0.css                 原型主样式（青绿令牌 + 组件类名）
 *   <outDir>/region-head.html            头部外框
 *   <outDir>/region-shell.html           应用外壳（4 个 view 的静态骨架 + 顶栏）
 *   <outDir>/app/<NNN>_<name>.js         应用脚本的顶层声明逐块
 *   <outDir>/anim/mod-<N>.inner.html     5 个动画模块的 template 内层 HTML
 *   <outDir>/index.json                  索引（含精确偏移，便于回溯原文）
 */

import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'

const SRC = path.resolve(import.meta.dirname, '../../../数模演易原型v2.html')
const OUT = process.argv[2] || path.join(os.tmpdir(), 'smyy-port')

// ── 词法扫描：跳过非代码片段 ────────────────────────────────────────────────

/** 跳过 'x' 或 "x"，返回结束位置的下一位 */
function skipQuoted(s, i) {
  const q = s[i]
  i += 1
  while (i < s.length) {
    const c = s[i]
    if (c === '\\') { i += 2; continue }
    if (c === q) return i + 1
    i += 1
  }
  return i
}

/** 跳过 `x`，支持 ${ expr } 嵌套（内层表达式里还能再出现任意代码） */
function skipTemplate(s, i) {
  i += 1
  while (i < s.length) {
    const c = s[i]
    if (c === '\\') { i += 2; continue }
    if (c === '`') return i + 1
    if (c === '$' && s[i + 1] === '{') {
      let depth = 1
      i += 2
      while (i < s.length && depth > 0) {
        const cc = s[i]
        const nx = skipAny(s, i)
        if (nx > i) { i = nx; continue }
        if (cc === '{') depth += 1
        else if (cc === '}') depth -= 1
        i += 1
      }
      continue
    }
    i += 1
  }
  return i
}

/** 跳过 /re/flags，非正则（如除法）返回 -1 */
function skipRegex(s, i) {
  i += 1
  let inClass = false
  while (i < s.length) {
    const c = s[i]
    if (c === '\\') { i += 2; continue }
    if (c === '\n') return -1 // 正则不能跨行 → 这是除法
    if (inClass) { if (c === ']') inClass = false; i += 1; continue }
    if (c === '[') { inClass = true; i += 1; continue }
    if (c === '/') {
      i += 1
      while (i < s.length && /[a-z]/i.test(s[i])) i += 1
      return i
    }
    i += 1
  }
  return -1
}

/** 跳过注释 / 字符串 / 模板串；非此类返回 -1 */
function skipAny(s, i) {
  const c = s[i]
  if (c === '/' && s[i + 1] === '/') {
    const j = s.indexOf('\n', i)
    return j < 0 ? s.length : j
  }
  if (c === '/' && s[i + 1] === '*') {
    const j = s.indexOf('*/', i)
    return j < 0 ? s.length : j + 2
  }
  if (c === '"' || c === "'") return skipQuoted(s, i)
  if (c === '`') return skipTemplate(s, i)
  return -1
}

/** 前一个有效字符决定 `/` 是正则还是除号 */
const REGEX_PRECEDERS = new Set([
  '(', ',', '=', ':', '[', '!', '&', '|', '?', '{', '}', ';', '+', '-', '*', '%', '<', '>', '^', '~', '\n',
])
function isRegexPos(s, i) {
  let k = i - 1
  while (k >= 0 && /\s/.test(s[k])) k -= 1
  if (k < 0) return true
  return REGEX_PRECEDERS.has(s[k])
}

// ── 切分应用脚本的顶层声明 ──────────────────────────────────────────────────

const DECL_RE = /^(?:function\s+[A-Za-z_$][\w$]*|(?:const|let|var)\s+[A-Za-z_$][\w$]*)/
const NAME_RE = /^(?:function|const|let|var)\s+([A-Za-z_$][\w$]*)/

function declStarts(src) {
  const out = []
  let depth = 0
  let atLineStart = true
  let i = 0
  while (i < src.length) {
    const c = src[i]

    if (atLineStart) {
      if (c === ' ' || c === '\t' || c === '\r' || c === '\n') { i += 1; continue }
      if (depth === 0) {
        const head = src.slice(i, i + 140)
        if (DECL_RE.test(head)) out.push(i)
      }
      atLineStart = false
    }

    const nx = skipAny(src, i)
    if (nx > i) { i = nx; atLineStart = false; continue }

    if (c === '/' && isRegexPos(src, i)) {
      const r = skipRegex(src, i)
      if (r > 0) { i = r; atLineStart = false; continue }
    }

    if (c === '{' || c === '(' || c === '[') depth += 1
    else if (c === '}' || c === ')' || c === ']') depth -= 1

    if (c === '\n') atLineStart = true
    i += 1
  }
  return out
}

// ── 定位 HTML 区块 ─────────────────────────────────────────────────────────

/**
 * 顺序扫描 HTML 顶层块。
 *
 * ⚠ 不能直接正则全局搜 `<style>`：应用脚本里就有 JS 字符串拼出来的
 *   `'<style>'+ ... '</style>'`（给动画沙盘注入的动效样式），正则会把它
 *   当成真的样式块，切出来的区间会与脚本区间重叠（已实际踩过一次）。
 *   HTML 的 script / style / template 都不嵌套，所以「识别到一个就整体跳到
 *   它的结束标签之后」天然免疫字符串里的伪标签。
 */
function scanTopLevelBlocks(html) {
  const blocks = []
  let i = 0
  while (i < html.length) {
    const cands = ['<script', '<style', '<template']
      .map((t) => ({ t, p: html.indexOf(t, i) }))
      .filter((c) => c.p >= 0)
    if (cands.length === 0) break
    cands.sort((a, b) => a.p - b.p || b.t.length - a.t.length)
    const { t, p } = cands[0]
    const gt = html.indexOf('>', p)
    if (gt < 0) break
    const attrs = html.slice(p + t.length, gt)
    if (t === '<template') {
      // 配平：内部可能再出现 <template>（本文件实测没有，但不能假设）
      let depth = 1
      let k = gt + 1
      let end = -1
      while (depth > 0) {
        const nx = html.indexOf('<template', k)
        const cl = html.indexOf('</template>', k)
        if (cl < 0) break
        if (nx >= 0 && nx < cl) { depth += 1; k = nx + '<template'.length; continue }
        depth -= 1
        k = cl + '</template>'.length
        if (depth === 0) end = k
      }
      if (end < 0) break
      blocks.push({ kind: 'template', attrs: attrs.trim(), start: p, end, inner: html.slice(gt + 1, html.lastIndexOf('</template>', end)) })
      i = end
      continue
    }
    const closeTag = t === '<script' ? '</script>' : '</style>'
    const cl = html.indexOf(closeTag, gt)
    if (cl < 0) break
    const end = cl + closeTag.length
    blocks.push({ kind: t.slice(1), attrs: attrs.trim(), start: p, end, inner: html.slice(gt + 1, cl) })
    i = end
  }
  return blocks
}

function fail(msg) {
  console.error('\n❌ ABORT: ' + msg)
  process.exit(1)
}

// ── 主流程 ─────────────────────────────────────────────────────────────────

const html = fs.readFileSync(SRC, 'utf8')
console.log('原型：', SRC)
console.log('总字符：', html.length)

const blocks = scanTopLevelBlocks(html)
const styleBlocks = blocks.filter((b) => b.kind === 'style')
const scriptBlocks = blocks.filter((b) => b.kind === 'script')
const tplBlocks = blocks.filter((b) => b.kind === 'template')
console.log(`顶层块：style ${styleBlocks.length} / script ${scriptBlocks.length} / template ${tplBlocks.length}`)

const appScript = scriptBlocks.find((b) => b.attrs === '')
if (!appScript) fail('没找到应用主脚本（无属性的 <script>）')
console.log('应用脚本：偏移', appScript.start, '长度', appScript.inner.length)

// 动画模块：只认 template 里带 id="anim-tpl-N" 的
const modules = tplBlocks
  .filter((b) => /anim-tpl-\d/.test(b.attrs))
  .map((b) => {
    const idm = /id="([^"]+)"/.exec(b.attrs)
    return { ...b, id: idm ? idm[1] : 'unknown' }
  })
if (modules.length === 0) fail('没找到动画模块 template')

// ── 拼回自检：所有片段按序拼起来必须与原文逐字节一致 ────────────────────────

const pieces = [...styleBlocks, ...scriptBlocks, ...modules]
  .map((b) => ({ start: b.start, end: b.end, text: html.slice(b.start, b.end) }))
  .sort((a, b) => a.start - b.start)

for (let i = 1; i < pieces.length; i += 1) {
  if (pieces[i].start < pieces[i - 1].end) {
    fail(`片段区间重叠：${pieces[i - 1].start}-${pieces[i - 1].end} 与 ${pieces[i].start}-${pieces[i].end}`)
  }
}
for (const p of pieces) {
  if (html.slice(p.start, p.end) !== p.text) fail(`片段回读不一致 @${p.start}`)
}
console.log(`✅ 分区自检通过：${pieces.length} 个区块回读逐字节一致`)

// ── 落盘 ───────────────────────────────────────────────────────────────────

fs.rmSync(OUT, { recursive: true, force: true })
fs.mkdirSync(path.join(OUT, 'app'), { recursive: true })
fs.mkdirSync(path.join(OUT, 'anim'), { recursive: true })

const index = { source: SRC, totalChars: html.length, styles: [], regions: [], app: [], anim: [] }

// 主样式
const mainStyle = styleBlocks[0]
fs.writeFileSync(path.join(OUT, 'style-0.css'), mainStyle.inner, 'utf8')
index.styles.push({ name: 'style-0.css', start: mainStyle.start, end: mainStyle.end, chars: mainStyle.inner.length })

// 头部外框（<!DOCTYPE> 到第一个 style 之前）
fs.writeFileSync(path.join(OUT, 'region-head.html'), html.slice(0, mainStyle.start), 'utf8')
index.regions.push({ name: 'region-head.html', start: 0, end: mainStyle.start, chars: mainStyle.start })

// 应用外壳：主样式结束 → 应用脚本开始，中间就是全部 DOM
const shellStart = mainStyle.end
const shellEnd = appScript.start
const shell = html.slice(shellStart, shellEnd)
fs.writeFileSync(path.join(OUT, 'region-shell.html'), shell, 'utf8')
index.regions.push({ name: 'region-shell.html', start: shellStart, end: shellEnd, chars: shell.length })

// 尾部外框（最后一个动画模块结束 → 文件末尾）
const tailStart = modules[modules.length - 1].end
fs.writeFileSync(path.join(OUT, 'region-tail.html'), html.slice(tailStart), 'utf8')
index.regions.push({ name: 'region-tail.html', start: tailStart, end: html.length, chars: html.length - tailStart })

// ── 覆盖率报告：把「已抽区域」与「未抽区域」都列出来，缺口必须显式可见 ─────────
const covered = [...index.regions.map((r) => [r.start, r.end]), ...pieces.map((p) => [p.start, p.end])]
  .sort((a, b) => a[0] - b[0])
const gaps = []
let cursor = 0
for (const [a, b] of covered) {
  if (a > cursor) gaps.push([cursor, a])
  cursor = Math.max(cursor, b)
}
if (cursor < html.length) gaps.push([cursor, html.length])
const coveredChars = covered.reduce((s, [a, b]) => s + (b - a), 0)
console.log(`覆盖率：已抽 ${coveredChars} / ${html.length} = ${(coveredChars * 100 / html.length).toFixed(2)}%`)
console.log(`未抽区域 ${gaps.length} 段：`)
for (const [a, b] of gaps) {
  const t = html.slice(a, b).replace(/\s+/g, ' ').trim().slice(0, 70)
  console.log(`   [${a}..${b}] ${b - a} 字符  ${t || '(纯空白)'}`)
}

// 应用脚本逐顶层声明切块
const starts = declStarts(appScript.inner)
console.log('顶层声明数：', starts.length)
const bounds = starts.concat([appScript.inner.length])
for (let i = 0; i < starts.length; i += 1) {
  const a = starts[i]
  let b = bounds[i + 1]
  // 尾部空白（含换行）回退到下一段，保证片段只含自身
  const raw = appScript.inner.slice(a, b)
  const head = NAME_RE.exec(appScript.inner.slice(a, a + 140))
  const name = head ? head[1] : 'unknown'
  const kind = appScript.inner.slice(a, a + 8).startsWith('function') ? 'fn' : 'data'
  const file = `${String(i).padStart(3, '0')}_${kind}_${name}.js`
  fs.writeFileSync(path.join(OUT, 'app', file), raw, 'utf8')
  index.app.push({ file, name, kind, chars: raw.length, start: appScript.start + a, end: appScript.start + b })
}

// 前置片段（第一个声明之前的注释/横幅）单独留一份
if (starts.length > 0 && starts[0] > 0) {
  fs.writeFileSync(path.join(OUT, 'app', '000_head.js'), appScript.inner.slice(0, starts[0]), 'utf8')
  index.app.unshift({ file: '000_head.js', name: '(head)', kind: 'head', chars: starts[0], start: appScript.start, end: appScript.start + starts[0] })
}

// 动画模块
for (let i = 0; i < modules.length; i += 1) {
  const m = modules[i]
  const file = `mod-${i}.inner.html`
  fs.writeFileSync(path.join(OUT, 'anim', file), m.inner, 'utf8')
  index.anim.push({ file, id: m.id, chars: m.inner.length, start: m.start, end: m.end })
}

// ★ 最终自检：把 app 片段与 anim 内层分别拼回，逐字节比对原区域
const rebuiltApp = index.app.map((r) => fs.readFileSync(path.join(OUT, 'app', r.file), 'utf8')).join('')
if (rebuiltApp !== appScript.inner) {
  const n = Math.min(rebuiltApp.length, appScript.inner.length)
  let d = 0
  while (d < n && rebuiltApp[d] === appScript.inner[d]) d += 1
  fail(`应用脚本拼回不一致，首个差异在偏移 ${d}`)
}
const rebuiltAnim = index.anim.map((r) => fs.readFileSync(path.join(OUT, 'anim', r.file), 'utf8')).join('')
const origAnim = modules.map((m) => m.inner).join('')
if (rebuiltAnim !== origAnim) fail('动画模块拼回不一致')

console.log('✅ 拼回自检通过：应用脚本与动画模块均与原文逐字节一致')

fs.writeFileSync(path.join(OUT, 'index.json'), JSON.stringify(index, null, 1), 'utf8')

console.log('\n输出目录：', OUT)
console.log('样式：', mainStyle.inner.length, '字符')
console.log('应用外壳：', shell.length, '字符')
console.log('应用脚本：', index.app.length, '个片段 /', index.app.reduce((s, r) => s + r.chars, 0), '字符')
console.log('动画模块：', index.anim.length, '个 /', index.anim.reduce((s, r) => s + r.chars, 0), '字符')
const top = [...index.app].sort((a, b) => b.chars - a.chars).slice(0, 18)
console.log('\n--- 最大的 18 个应用片段 ---')
for (const r of top) console.log(String(r.chars).padStart(8), r.kind.padEnd(5), r.name)
