/**
 * 对话块 → HTML（站点风格）
 *
 * 分工：
 *   - `analysis/render.ts` 把**结构化产物**（JSON）翻成站点小节（`<h4>/<p>/<ul>`）
 *   - 本文件把**事件流**翻成对话区里的气泡
 *
 * ⚠️ 2026-10-08 用户定调（两次，口径要一起看）：
 *   ① **分析内容不铺屏** —— 思考与工具调用不再逐条铺进对话区，那会把对话变成
 *      日志查看器；分析期间只留一个**思考转圈**（`busyHTML`）。
 *   ② **思考要能展开** —— 但收起来，想看时点开就能看。所以过程不再「不上屏」，
 *      而是**折成一条默认收起的「思考过程」**（`procFoldHTML`）：summary 是一行
 *      摘要（耗时 + 操作数，运行中带转圈），点开才是思考原文与工具调用明细。
 *      上屏的正文（`text`）与真出错（`error`）仍然直接铺。
 *
 * ✅ **后端现在会发思考事件**（2026-10-12 直连库核对 `agui_events`：最近 8 小时
 *   `REASONING_MESSAGE_CONTENT` 63929 条、`REASONING_MESSAGE_START/END` 各 28x 条，
 *   与 `TOOL_CALL_*` 交替出现）。所以折叠条里既有思考原文、也有工具调用明细。
 *   （2026-10-08 那次探针只抓到 RUN_STARTED / TEXT_MESSAGE_* / TOOL_CALL_* —— 结论已过时，
 *    当时的 backend 不发 REASONING_*；`blocks.ts` 的 reduce 一直在认这两组事件名，
 *    所以这次不需要改归约层。）
 *   摘要那一行的文案（「已思考 Ns · 完成 N 项操作」）与平台 AgentHub 的显示口径一致。
 *
 * 为什么自带一个轻量 markdown：站点是零依赖单文件产物，不能引 remark 那一套；
 * 智能体回的正文却基本是 markdown。这里只覆盖它真会用的子集（标题/列表/表格/
 * 代码块/强调/链接），够用且可预测。
 */

import { esc } from '@/utils/analysis/render'
import type { LiveBlock, LiveThinkingBlock, LiveToolBlock } from './blocks'

/* ── 轻量 markdown ───────────────────────────────────────────────────────── */

/** 行内：先转义再套标记（标记都是 ASCII，转义不影响） */
function inline(s: string): string {
  let t = esc(s)
  t = t.replace(/`([^`]+)`/g, '<code>$1</code>')
  t = t.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  t = t.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
  t = t.replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer">$1</a>')
  return t
}

const RE_FENCE = /^\s*```/
const RE_HEAD = /^(#{1,6})\s+(.*)$/
const RE_UL = /^\s*[-*+]\s+(.*)$/
const RE_OL = /^\s*\d+[.)]\s+(.*)$/
const RE_TABLE_SEP = /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/

function cells(line: string): string[] {
  return line.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim())
}

/**
 * markdown → HTML。
 *
 * 刻意容错：流式增量时正文常常是「半句话 + 半张表」，不能因为一行没写完
 * 就把整段吞掉 —— 兜底一律当普通段落输出。
 */
export function mdToHtml(src: string): string {
  const lines = src.replace(/\r\n?/g, '\n').split('\n')
  const out: string[] = []
  let list: 'ul' | 'ol' | null = null
  const closeList = (): void => {
    if (list) {
      out.push(`</${list}>`)
      list = null
    }
  }

  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i]

    if (RE_FENCE.test(line)) {
      closeList()
      const lang = line.replace(RE_FENCE, '').trim()
      const buf: string[] = []
      i += 1
      while (i < lines.length && !RE_FENCE.test(lines[i])) {
        buf.push(lines[i])
        i += 1
      }
      out.push(`<pre class="lv-code"${lang ? ` data-lang="${esc(lang)}"` : ''}><code>${esc(buf.join('\n'))}</code></pre>`)
      continue
    }

    if (!line.trim()) {
      closeList()
      continue
    }

    const head = line.match(RE_HEAD)
    if (head) {
      closeList()
      const lv = Math.min(6, head[1].length + 2) /* h1→h3：对话里不需要那么大的字 */
      out.push(`<h${lv} class="lv-h">${inline(head[2])}</h${lv}>`)
      continue
    }

    if (RE_TABLE_SEP.test(line) && out.length && lines[i - 1].includes('|')) {
      /* 上一行已被当表格头输出过 —— 这里把它升级成表头 + 收掉表格 */
      const head = cells(lines[i - 1])
      out.pop()
      const rows: string[][] = []
      i += 1
      while (i < lines.length && lines[i].includes('|') && lines[i].trim()) {
        rows.push(cells(lines[i]))
        i += 1
      }
      i -= 1
      out.push('<table class="lv-table"><thead><tr>' + head.map((c) => `<th>${inline(c)}</th>`).join('') + '</tr></thead><tbody>')
      for (const r of rows) {
        out.push('<tr>' + r.map((c) => `<td>${inline(c)}</td>`).join('') + '</tr>')
      }
      out.push('</tbody></table>')
      continue
    }

    const ul = line.match(RE_UL)
    if (ul) {
      if (list !== 'ul') { closeList(); out.push('<ul class="lv-ul">'); list = 'ul' }
      out.push(`<li>${inline(ul[1])}</li>`)
      continue
    }

    const ol = line.match(RE_OL)
    if (ol) {
      if (list !== 'ol') { closeList(); out.push('<ol class="lv-ol">'); list = 'ol' }
      out.push(`<li>${inline(ol[1])}</li>`)
      continue
    }

    closeList()
    out.push(`<p>${inline(line)}</p>`)
  }

  closeList()
  return out.join('')
}

/* ── 块 → HTML ───────────────────────────────────────────────────────────── */

/** 正文别太长 —— 对话区不是日志查看器（完整内容在产物面板里） */
const CLIP_CHARS = 2000
/** 工具结果同理，但它是「过程」明细，用户主动点开的，给得宽一点 */
const TOOL_RESULT_CHARS = 1200
/** 思考原文（后端开始发之后）也截一下 */
const THOUGHT_CHARS = 4000

function clip(s: string, max: number): string {
  return s.length > max ? `${s.slice(0, max)}\n…（已截断）` : s
}

export function blockHTML(b: LiveBlock): string {
  switch (b.kind) {
    case 'text':
      return `<div class="lv-answer">${b.text.trim() ? mdToHtml(b.text) : '<p class="lv-muted">…</p>'}</div>`
    case 'error':
      return `<div class="lv-error"><b>智能体这条流报了错</b><div>${esc(clip(b.text, CLIP_CHARS))}</div></div>`
    /* 思考 / 工具由 procFoldHTML 折成一条「思考过程」，不在这里单独出块 */
    default:
      return ''
  }
}

/** 这一块还在变吗（决定要不要显示「还在思考」的转圈） */
export function isLive(b: LiveBlock): boolean {
  if (b.kind === 'text') return !b.done
  if (b.kind === 'thinking') return !b.endedAt
  if (b.kind === 'tool') return b.status === 'running'
  return false
}

/** 秒数 → 「12 秒」/「1 分 05 秒」 */
function elapsedLabel(startedAt: number, now: number): string {
  if (!startedAt) return ''
  const sec = Math.max(0, Math.round((now - startedAt) / 1000))
  if (sec < 60) return `${sec} 秒`
  return `${Math.floor(sec / 60)} 分 ${String(sec % 60).padStart(2, '0')} 秒`
}

/**
 * 「（已 N 秒）」那一段 —— **秒数不进 HTML 字符串**，只留一个空壳节点。
 *
 * 为什么（2026-10-13）：实时区是**整块重写**的，等值守卫要靠「HTML 模具」比对。
 * 秒数一旦进了模具，模具就**每秒必然不等** ⇒ 每秒必然整块重建 ⇒ 里面的
 * `<i class="lv-spin">` 是全新节点、CSS 动画从 0° 重头转 —— 肉眼就是「闪」。
 * 抽成独立节点后模具与时间无关，秒数由 {@link paintElapsed} 走 `textContent`
 * 更新（只动一个文本节点，不重建任何元素）。
 */
function elapsedSpan(startedAt: number): string {
  return startedAt ? `（已 <span class="lv-elapsed" data-since="${startedAt}"></span>）` : ''
}

/**
 * 分析期间的**思考转圈**。
 *
 * 只在「还没有任何过程可展开」时出现（刚开始、或最后一块是正在流的正文）——
 * 最后一块是过程块时，转圈在折叠条的摘要里，不重复画。
 */
export function busyHTML(startedAt: number): string {
  return '<div class="lv-busy"><i class="lv-spin"></i><span>智能体正在思考…' +
    elapsedSpan(startedAt) + '</span></div>'
}

/**
 * 把容器里所有 `.lv-elapsed[data-since]` 的秒数填上。
 *
 * 只改文本节点，**不重建元素** —— 这正是「秒数不再引起闪烁」的关键：
 * 转圈节点原地不动，动画不中断，只有那一小段文字在变。
 */
export function paintElapsed(root: HTMLElement, now: number): void {
  root.querySelectorAll<HTMLElement>('.lv-elapsed[data-since]').forEach((n) => {
    const since = Number(n.getAttribute('data-since')) || 0
    const t = elapsedLabel(since, now)
    if (n.textContent !== t) n.textContent = t
  })
}

/* ── 思考过程折叠条 ─────────────────────────────────────────────────────── */

/** 工具入参一行摘要（原文可能是一整段 JSON，收成一行够看） */
function argsBrief(args: string): string {
  const s = (args || '').replace(/\s+/g, ' ').trim()
  if (!s) return ''
  return s.length > 130 ? `${s.slice(0, 130)}…` : s
}

type ProcBlock = LiveThinkingBlock | LiveToolBlock

function procBody(bs: ProcBlock[]): string {
  const out: string[] = []
  for (const b of bs) {
    if (b.kind === 'thinking') {
      const t = b.text.trim()
      if (t) out.push(`<div class="lv-thought">${esc(clip(t, THOUGHT_CHARS))}</div>`)
      continue
    }
    out.push(
      '<div class="lv-tool' + (b.status === 'error' ? ' error' : '') + '">' +
        `<b>${esc(b.name)}</b>` +
        (argsBrief(b.args) ? `<span class="lv-args">${esc(argsBrief(b.args))}</span>` : '<span class="lv-args"></span>') +
        `<em>${b.status === 'running' ? '运行中' : b.status === 'error' ? '出错' : '完成'}</em>` +
      '</div>',
    )
    if (b.result.trim()) out.push(`<pre class="lv-tout">${esc(clip(b.result.trim(), TOOL_RESULT_CHARS))}</pre>`)
  }
  return out.join('')
}

/**
 * 把一段连续的过程块（思考 + 工具调用）折成一条**默认收起**的折叠条。
 *
 * 为什么是「一段连续」而不是整轮合一条：智能体常常是「说一句 → 跑点东西 → 再说
 * 一句」，按段落分折，展开后看到的过程才对得上旁边那几句正文。
 *
 * @param idx 第几条折叠条（决定 data-lv，重画时据此保住用户点开的那些）
 */
export function procFoldHTML(bs: ProcBlock[], idx: number): string {
  if (!bs.length) return ''
  const live = bs.some((b) => isLive(b))
  const thoughts = bs.filter((b): b is LiveThinkingBlock => b.kind === 'thinking')
  const tools = bs.filter((b): b is LiveToolBlock => b.kind === 'tool')

  const from = Math.min(...bs.map((b) => b.startedAt || 0).filter((n) => n > 0), Number.MAX_SAFE_INTEGER)
  const ends = bs.map((b) => b.endedAt || 0).filter((n) => n > 0)
  /* 已结束的段落才有「终值用时」—— 它是**时间无关**的，可以写进模具。
     还在跑的段落用 elapsedSpan 留空壳，交由 paintElapsed 每秒补（见文件顶部注释）。 */
  const dur = from !== Number.MAX_SAFE_INTEGER && ends.length ? elapsedLabel(from, Math.max(...ends)) : ''
  const tick = from === Number.MAX_SAFE_INTEGER ? '' : elapsedSpan(from)

  /* 第一段是「在不在思考 / 已思考多久」。⚠️ live 时的「已用时」是个**标记节点**，
     必须拼在 esc 之外 —— 跟着 esc 走会被转义成字面量文本（实测踩过）。 */
  const head = thoughts.length
    ? (live ? '正在思考…' : `已思考 ${dur || '片刻'}`)
    : (live ? '正在处理…' : `过程用时 ${dur || '片刻'}`)
  const bits: string[] = [esc(head) + (live ? tick : '')]
  if (tools.length) bits.push(esc(`${live ? '已完成' : '完成'} ${tools.length} 项操作`))

  return `<details class="lv-think" data-lv="p${idx}">` +
    `<summary>${live ? '<i class="lv-spin"></i>' : ''}<span>${bits.join(' · ')}</span></summary>` +
    `<div class="lv-proc">${procBody(bs)}</div>` +
    '</details>'
}
