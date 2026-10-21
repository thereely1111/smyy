/**
 * 实时对话通道：单例状态机 + DOM 渲染
 *
 * 把一次 AG-UI run 的事件流实时画进工作台的一条气泡。早先这条通道只收产物，事件流
 * 全丢，于是平台那边看得见的对话，站点这边看不见。
 *
 * 2026-10-08 定的铺法：正文（`text`）和真出错（`error`）直接铺；思考与工具调用折成
 * 一条默认收起的「思考过程」，想看再点开，不把对话变成日志查看器；还没有过程可展开、
 * 或最后一块正在流正文时，补一个 `busyHTML` 转圈。
 *
 * 两处容易写错的：
 *   块要按到达顺序分段，连续的过程块合成一条折叠条，正文穿插其间，这样「说一句、
 *   干点活、再说一句」的节奏才对；
 *   折叠条的展开态是用户的。整块 innerHTML 重写之前先记下哪些开着，重画后补回去，
 *   不然用户刚点开，700ms 一次的轮询就给他收回去了。
 *
 * 没并进 flow.ts，因为 flow 那层不碰 DOM。这是渲染器，注定要碰，分开之后边界才清楚：
 * blocks 归约，本文件渲染。
 */

import type { AguiEvent } from '@/types/agui'
import { Typewriter } from '@/utils/typewriter'
import {
  drainPending,
  emptyStream,
  reduce,
  closeOpenBlocks,
  settlePending,
  type LiveBlock,
  type LiveStream,
  type LiveTextBlock,
  type LiveThinkingBlock,
  type LiveToolBlock,
} from './blocks'
import { blockHTML, busyHTML, isLive, paintElapsed, procFoldHTML } from './render'

export type { LiveBlock, LiveStream } from './blocks'
export { resultText } from './blocks'

/**
 * 每个对话容器一份流状态。
 *
 * 不设单例：任务仓把工作台对话区按任务分了桶（core.ts 桥接层的 workHost），实时区
 * 得跟着分。否则 A 还在跑、我去 B 看一眼，A 剩下的事件就画进 B 里了。容器当 key，
 * 节点回收时状态跟着走（WeakMap），不用手工清。
 */
const streams = new WeakMap<HTMLElement, LiveStream>()
/** 最近一次挂载的容器，只在 liveMount 时更新，决定新一轮画哪 */
let mounted: HTMLElement | null = null

function streamOf(el: HTMLElement | null | undefined): LiveStream | null {
  if (!el) return null
  let s = streams.get(el)
  if (!s) {
    s = emptyStream()
    streams.set(el, s)
  }
  return s
}

/* ── 打字机（移植自 kuaike） ──────────────────────────────────────────────── */

/**
 * 每个正文块一台打字机，不是每轮一台。
 *
 * 一轮 run 可以有多个正文块（说一句、干点活、再说一句），归约层按到达顺序往各块的
 * `pending` 里堆。全轮共用一台就得维护「这段增量属于哪个块」的队列，顺序一乱正文就
 * 串位。按块分，块自己的闭包就是归属，不会串，代价只是同屏偶尔两台在跑。
 *
 * 表是 WeakMap，挂在块对象上。`liveBegin` 换掉 `s.blocks` 之后旧块被回收，打字机和
 * 定时器跟着失联，不用手工清。
 */
const typers = new WeakMap<LiveTextBlock, Typewriter>()

/** 块 → 它所在的容器，打字机吐字后要知道重画哪一块 */
const blockBox = new WeakMap<LiveTextBlock, HTMLElement>()

/**
 * 打字机重画的节流间隔。
 *
 * 打字机一秒回调约 50 次，每次都要整块重写 innerHTML（还要转 markdown），太贵。
 * 压到 12 帧/秒肉眼已经够顺，再叠上桥接层 700ms 的兜底轮询，看着是连续的。
 * 这里不求一帧不差，只求别一次性蹦出一整段。
 */
const PAINT_MS = 80
const paintAt = new WeakMap<HTMLElement, number>()
const paintTimer = new WeakMap<HTMLElement, number>()

function schedulePaint(box: HTMLElement): void {
  const now = Date.now()
  const last = paintAt.get(box) ?? 0
  if (now - last >= PAINT_MS) {
    paintAt.set(box, now)
    liveRender(box)
    return
  }
  if (paintTimer.has(box)) return
  const t = window.setTimeout(() => {
    paintTimer.delete(box)
    paintAt.set(box, Date.now())
    liveRender(box)
  }, PAINT_MS - (now - last))
  paintTimer.set(box, t)
}

/** 取（或建）某正文块的打字机，吐字直接写回该块并按节流重画 */
function typerFor(b: LiveTextBlock, box: HTMLElement): Typewriter {
  let t = typers.get(b)
  if (!t) {
    t = new Typewriter({
      onDrain: (chunk) => {
        b.text += chunk
        const el = blockBox.get(b)
        if (el) schedulePaint(el)
      },
    })
    typers.set(b, t)
    blockBox.set(b, box)
  }
  return t
}

/** 把归约层堆下的增量交给各自的打字机 */
function pump(stream: LiveStream, box: HTMLElement): void {
  for (const { block, text } of drainPending(stream)) {
    typerFor(block, box).append(text)
  }
}

/** 收尾：剩余增量全部吐出，别留半句在缓冲里 */
function settle(stream: LiveStream): void {
  for (const { block, text } of drainPending(stream)) {
    const t = typers.get(block)
    if (t) t.append(text)
    else block.text += text
  }
  for (const b of stream.blocks) {
    if (b.kind !== 'text') continue
    typers.get(b)?.flushNow()
  }
}

/** 硬清：丢掉没吐完的字符，停掉定时器（换题 / 退出工作台） */
function discard(stream: LiveStream): void {
  for (const b of stream.blocks) {
    if (b.kind !== 'text') continue
    typers.get(b)?.cancel()
  }
  settlePending(stream)
}

/* ── 生命周期 ────────────────────────────────────────────────────────────── */

/**
 * 开始一轮 run：清空这一轮宿主容器里上一轮的内容，把容器交回调用方当凭据。
 *
 * 为什么要凭据：一轮 run 的宿主得在它开始那一刻定下来。用户中途切任务，mounted 会变，
 * 但这一轮的事件流不能跟着改道。调用方拿着这个容器调 liveFeed / liveClose，就永远写在
 * 自己那块对话区里。
 *
 * @param userText 触发这一轮的用户输入（自动发起的 run 传空）
 */
export function liveBegin(userText = ''): HTMLElement | null {
  return beginOn(mounted, userText)
}

/**
 * 在指定容器上开始一轮，不动「当前容器」。
 *
 * 2026-10-13 加的：动画浮窗和代码等待气泡跟工作台对话区是并发的。它们要走 `liveBegin()`，
 * 画的就是当前挂载的容器，三条通道互相抢 —— 浮窗一动，下一轮 run 的落点被带偏到浮窗里。
 * 并发通道一律走这个口，宿主是谁由调用方说清。
 */
export function liveBeginOn(el: HTMLElement | null, userText = ''): HTMLElement | null {
  return beginOn(el, userText)
}

function beginOn(el: HTMLElement | null, userText: string): HTMLElement | null {
  const s = streamOf(el)
  if (s) {
    /* 上一轮可能还有打字机在吐（用户切回来又发起新一轮），先把定时器停掉，
       不然它接着往作废的块里写，白跑。 */
    discard(s)
    s.blocks = []
    s.events = 0
    s.closed = false
    s.userText = userText
    s.startedAt = Date.now()
  }
  if (el) el.innerHTML = ''
  return el
}

/** 喂一个 AG-UI 事件，box 缺省就是最近挂载的那个容器 */
export function liveFeed(ev: AguiEvent, box?: HTMLElement | null): void {
  feedOn(box ?? mounted, ev)
}

/**
 * 喂一个事件到指定容器。跟 `liveFeed` 只差一条：不做「当前容器」回退。
 *
 * 传 null 就是这一步没有容器，事件直接丢，绝不落进对话区那份流。`liveFeed(ev, null)`
 * 会回退到 mounted，对并发通道来说等于「动画的思考漏进了工作台对话」，所以并发通道
 * 必须用这个口。
 */
export function liveFeedOn(el: HTMLElement | null, ev: AguiEvent): void {
  feedOn(el, ev)
}

function feedOn(el: HTMLElement | null, ev: AguiEvent): void {
  const s = streamOf(el)
  if (!s) return
  if (!s.startedAt) s.startedAt = Date.now()
  reduce(s, ev)
  /* 有容器就交给打字机逐字吐；没有（桥接层还没挂上）就直接并进 text。
     宁可少一段动画，也别把正文丢在 pending 里没人管。 */
  if (el) pump(s, el)
  else settlePending(s)
}

/**
 * 收尾。除了把状态标成已关闭，别的都不改 —— 「活干完了」由 flow 判
 * （异步编排型智能体会先关流、后写产物）。
 *
 * 打字机在这里结清：流都关了还有字符压在缓冲里，正文就永远缺个尾巴。
 */
export function liveClose(box?: HTMLElement | null): void {
  const s = streamOf(box ?? mounted)
  if (!s) return
  settle(s)
  closeOpenBlocks(s)
  s.closed = true
}

/** 彻底清掉某个容器的实时记录（换题、退出工作台），缺省是最近挂载的那个 */
export function liveReset(box?: HTMLElement | null): void {
  const el = box ?? mounted
  if (!el) return
  const s = streams.get(el)
  if (s) discard(s)
  streams.delete(el)
  el.innerHTML = ''
}

/* ── 「提交闸门 → 自己发 resume」那一轮：单独一条气泡（2026-10-20） ────────── */

function escHTML(s: string): string {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** 从已挂载的实时区往上找到任务级对话区（`.msg` 的父节点），找不到退回 #stream-in */
function resumeHost(): HTMLElement | null {
  const anchor = mounted && mounted.closest ? mounted.closest('.msg') : null
  if (anchor && anchor.parentElement) return anchor.parentElement
  return typeof document === 'undefined' ? null : document.getElementById('stream-in')
}

/**
 * 给「闸门提交后的续跑」单开一条气泡，返回它的实时区。
 *
 * 不能复用 `liveBegin()`。2026-10-20 用户点完提交，对话里只剩他自己上一句话，
 * 智能体写着思考却像没接上后端。原因是 `liveBegin()` 画在 `mounted` 那块上，
 * 而 `beginOn` 一进来就 `el.innerHTML=''`：上一条气泡刚跑完的内容当场被洗掉，
 * 这一轮的输出又接着往这条空壳里写。
 *
 * 现在这一轮自己开一条，旧气泡留着不动，新的从下面长出来。
 */
export function liveBeginResume(note = ''): HTMLElement | null {
  const host = resumeHost()
  if (!host || typeof document === 'undefined') return null
  const el = document.createElement('div')
  el.className = 'msg ai resume-run'
  el.innerHTML =
    '<div class="bubble"><div class="txt">' +
    '<div class="an-head"><h4>已收到你的选择，接着往下做</h4>' +
    '<p class="src-note resume-note">' +
    escHTML(note || '智能体正在按你的选择继续，进度会实时显示在这里。') +
    '</p></div>' +
    '<div class="lv-stream"></div>' +
    '</div></div>'
  host.appendChild(el)
  const st = host.closest ? (host.closest('.stream') as HTMLElement | null) : null
  if (st) st.scrollTop = st.scrollHeight
  const box = el.querySelector('.lv-stream') as HTMLElement | null
  if (box) beginOn(box, '')
  return box
}

/** 改续跑气泡的说明行 */
export function liveNoteResume(box: HTMLElement | null, text: string): void {
  const msg = box && box.closest ? box.closest('.msg') : null
  const note = msg ? msg.querySelector('.resume-note') : null
  if (note && text) note.textContent = text
}

/** 续跑收尾：结清打字机，再补一句「这轮完了，等你发话」 */
export function liveFinishResume(box: HTMLElement | null, text = ''): void {
  if (!box) return
  liveClose(box)
  const msg = box.closest ? box.closest('.msg') : null
  if (!msg || msg.querySelector('.resume-end')) return
  const p = document.createElement('p')
  p.className = 'src-note resume-end'
  p.textContent = text || '这一轮做完了。接着说一句，我就接着往下走。'
  const txt = msg.querySelector('.txt')
  if (txt) txt.appendChild(p)
}

/* ── 具名通道（2026-10-13：动画浮窗 / 代码等待气泡） ──────────────────────── */

/**
 * 通道名 → 当前宿主容器。
 *
 * 那两个容器是流动的：动画浮窗只在跑的时候才建（`animFloatRender`），收起、失败、完成
 * 都会被摘掉；代码等待气泡一问一个，答完就 remove。而事件是 store（`stores/animation` /
 * `stores/codegen`）在跑 run 时喂进来的，store 不该认识 DOM，也不该每次重建容器后重拿
 * 一次引用。所以让容器这侧主动登记：core 建好就 `liveBind`，摘掉就 `liveBind(name, null)`，
 * store 只按名字投递。名字没了说明没人在看，事件丢弃。
 */
const channels = new Map<string, HTMLElement>()

/** 登记或注销一个通道的宿主 */
export function liveBind(name: string, el: HTMLElement | null): void {
  if (el) channels.set(name, el)
  else channels.delete(name)
}

/** 在具名通道的宿主上开始一轮；宿主还没登记就什么也不做 */
export function liveBeginChannel(name: string, userText = ''): HTMLElement | null {
  const el = channels.get(name)
  return el ? beginOn(el, userText) : null
}

/** 往具名通道投一个事件；宿主不在就丢弃 */
export function liveFeedChannel(name: string, ev: AguiEvent): void {
  feedOn(channels.get(name) ?? null, ev)
}

/** 具名通道收尾：结清打字机、停掉转圈；宿主不在就什么也不做 */
export function liveCloseChannel(name: string): void {
  const el = channels.get(name)
  if (el) liveClose(el)
}

/** 这一轮有没有东西可显示（正文、错误、过程块都算） */
export function liveActive(box?: HTMLElement | null): boolean {
  /* 别只认正文和错误：一轮只跑了工具调用、没来得及说话是常态，那种时候气泡里
     那条「思考过程」也该留着，不然用户点开是空的 */
  const s = streamOf(box ?? mounted)
  return !!s && s.blocks.length > 0
}

/** 这一轮收到 RUN_FINISHED 没有 */
export function liveClosed(box?: HTMLElement | null): boolean {
  return !!streamOf(box ?? mounted)?.closed
}

export function liveBlocks(box?: HTMLElement | null): LiveBlock[] {
  return streamOf(box ?? mounted)?.blocks ?? []
}

/** 思考段数、工具次数（只用来判断还在不在干活，不再上屏） */
export function liveCounters(box?: HTMLElement | null): { think: number; tools: number } {
  let think = 0
  let tools = 0
  for (const b of streamOf(box ?? mounted)?.blocks ?? []) {
    if (b.kind === 'thinking') think += 1
    else if (b.kind === 'tool') tools += 1
  }
  return { think, tools }
}

/* ── DOM 渲染 ────────────────────────────────────────────────────────────── */

/**
 * 把实时区挂到某个容器上，传 null 卸载。
 * 这里是唯一更新「当前容器」的地方，下一轮 run 画哪由它决定。
 */
export function liveMount(el: HTMLElement | null): void {
  if (el === mounted) return
  mounted = el
  if (mounted) {
    streamOf(mounted)
    mounted.innerHTML = ''
  }
}

/**
 * 刷新，桥接层的轮询会调（事件回调里也能调）。
 * 传了容器就以参数为准（气泡重建后旧引用会失效），并且不改「当前容器」，否则别的
 * 任务刷一下，下一轮 run 就画错地方。
 *
 * 2026-10-13 从整块重写改成按块 patch。整块重写会把每个子节点换掉，里面的
 * `<i class="lv-spin">` 跟着重生、CSS 动画从 0° 重头转，打字机吐字时（最高 12.5 次/秒）
 * 肉眼就是闪。现在只动模具真变了的块，秒数单独走 {@link paintElapsed}。
 */
export function liveRender(target?: HTMLElement | null): void {
  const el = target ?? mounted
  if (!el) return
  ensureLiveStyle()

  const s = streamOf(el)
  if (!s) return

  const open = openState(el)
  patchChildren(el, streamPieces(s))
  restoreOpen(el, open)
  paintElapsed(el, Date.now())
}

/** 一块对话内容：`key` 稳定（块只往后追加，前面的键不变），`html` 与时间无关 */
interface Piece {
  key: string
  html: string
}

/**
 * 上一轮写进每个块节点的「HTML 模具」。
 *
 * 不能拿 `el.innerHTML` 去比：里面 `.lv-elapsed` 的秒数每秒都变，永远比不相等，等于
 * 没有守卫。模具里秒数是空壳，只有真有新内容时才变。节点被换掉时这份记录随 WeakMap
 * 一起走。
 */
const molds = new WeakMap<HTMLElement, string>()

/** 块列表 → 一块块与时间无关的 HTML（过程折成折叠条，正文和错误直接铺） */
function streamPieces(s: LiveStream): Piece[] {
  const out: Piece[] = []
  let buf: Array<LiveThinkingBlock | LiveToolBlock> = []
  let foldIdx = 0

  const flush = (): void => {
    if (!buf.length) return
    out.push({ key: `f${foldIdx}`, html: procFoldHTML(buf, foldIdx) })
    foldIdx += 1
    buf = []
  }

  s.blocks.forEach((b, i) => {
    if (b.kind === 'thinking' || b.kind === 'tool') {
      buf.push(b)
      return
    }
    flush()
    if (b.kind === 'text' || b.kind === 'error') out.push({ key: `b${i}`, html: blockHTML(b) })
  })

  const tail = s.blocks[s.blocks.length - 1]
  const tailIsProc = !!tail && (tail.kind === 'thinking' || tail.kind === 'tool') && isLive(tail)
  flush()

  /* 还在干活、且转圈没画在折叠条里 → 补一个独立的 */
  if (!s.closed && !tailIsProc && pending(s)) out.push({ key: 'busy', html: busyHTML(s.startedAt) })
  return out
}

/**
 * 按块更新，只动模具真变了的块，结构变化也按键增删。
 *
 * 有 DOM 的时候按 `data-lvk` 认领既有节点：多余的摘掉、缺的建出来、顺序不对的挪一下，
 * 然后只给模具变了的节点写 innerHTML。于是打字机吐字时只有正文那块被重写，转圈和
 * 折叠条的节点原地不动，动画不中断。
 *
 * 没 DOM（单测的极简桩）就退回字符串拼接整体写入。桩里 children 恒空，逐块 patch
 * 无从谈起，这层逻辑也不是那些用例要测的。
 */
function patchChildren(el: HTMLElement, pieces: Piece[]): void {
  if (typeof document === 'undefined') {
    el.innerHTML = pieces
      .map((p) => `<div class="lv-block" data-lvk="${p.key}">${p.html}</div>`)
      .join('')
    return
  }

  const cur = Array.from(el.children) as HTMLElement[]
  const byKey = new Map<string, HTMLElement>()
  for (const c of cur) {
    const k = c.dataset.lvk
    if (k && !byKey.has(k)) byKey.set(k, c)
  }

  /* 先摘掉这一轮用不上的（含没有键的杂节点），免得它占着位置把顺序带偏 */
  const wanted = new Set(pieces.map((p) => p.key))
  for (const c of cur) {
    if (!c.dataset.lvk || !wanted.has(c.dataset.lvk)) c.remove()
  }

  let at = 0
  for (const p of pieces) {
    let node = byKey.get(p.key)
    if (!node) {
      node = document.createElement('div')
      node.className = 'lv-block'
      node.dataset.lvk = p.key
    }
    /* 新建的和顺序不对的都要落到第 at 位。新建那支别漏插 —— 漏过一次：节点建出来
       没进 DOM，整块实时区空白，只有探针抓得住 */
    if (el.children[at] !== node) el.insertBefore(node, el.children[at] ?? null)
    if (molds.get(node) !== p.html) {
      node.innerHTML = p.html
      molds.set(node, p.html)
    }
    at += 1
  }
}

/** 记下当前哪些折叠条开着（data-lv → 是否 open） */
function openState(el: HTMLElement): Map<string, boolean> {
  const m = new Map<string, boolean>()
  el.querySelectorAll<HTMLElement>('[data-lv]').forEach((d) => {
    m.set(d.dataset.lv ?? '', d.hasAttribute('open'))
  })
  return m
}

/** 重画后把展开态补回去，没记过的（新块）保持收起 */
function restoreOpen(el: HTMLElement, m: Map<string, boolean>): void {
  if (!m.size) return
  el.querySelectorAll<HTMLElement>('[data-lv]').forEach((d) => {
    const want = m.get(d.dataset.lv ?? '')
    if (want === undefined) return
    if (want) d.setAttribute('open', '')
    else d.removeAttribute('open')
  })
}

/** 还有活没干完吗：没有块（刚开始），或最后一块还在变 */
function pending(s: LiveStream): boolean {
  if (!s.blocks.length) return !!s.startedAt
  return isLive(s.blocks[s.blocks.length - 1])
}

/* ── 样式（自己注入，不动生成物那套 CSS） ─────────────────────────────────── */

const STYLE_ID = 'smyy-live-style'

/**
 * 把对话流那张样式表挂进 head，幂等。
 *
 * 2026-10-12 起导出：它原先只由 liveRender 懒注入。页面刷新后，工作台对话区是从任务
 * 快照整块 innerHTML 贴回来的，那一刻没有任何渲染函数被调用，这张表就不在 head 里，
 * 快照里那堆 lv-* 节点全掉回浏览器默认样式，用户看到的是「对话内容没样式」。现在由
 * 页面外壳挂载时统一补一次（见 utils/analysis/styles.ts）。
 */
export function ensureLiveStyle(): void {
  if (typeof document === 'undefined') return
  const prev = document.getElementById(STYLE_ID)
  if (prev) {
    /* 2026-10-12 补：光看 id 在不在不够。文档活久了（HMR 更新过模块，或者这页根本
       没重载），head 里那张 <style> 是旧版本的：id 还在、内容不是现在的，新标记配
       旧样式，页面上就是「对话内容没样式」或者只掉一部分。id 命中就 return 会让这种
       错位永远修不好，因为每次调用都早退。内容一致才放过，不一致就换掉。 */
    if (prev.textContent === LIVE_CSS) return
    prev.remove()
  }
  const el = document.createElement('style')
  el.id = STYLE_ID
  el.textContent = LIVE_CSS
  document.head.appendChild(el)
}

/** 站点配色：青绿主色 / 墨色文字 / 浅底 */
const LIVE_CSS = `
.lv-stream{display:flex;flex-direction:column;gap:8px;margin:10px 0 2px}
.lv-block{display:block}
.lv-busy{display:flex;align-items:center;gap:8px;padding:4px 0 2px;color:#57727c;font-size:12.5px;line-height:1.5}
.lv-spin{width:13px;height:13px;flex:0 0 auto;border:1.6px solid #cde6e3;border-top-color:#12a594;border-radius:50%;animation:lv-rotate .8s linear infinite}
@keyframes lv-rotate{to{transform:rotate(360deg)}}
.lv-answer{font-size:13.5px;line-height:1.8;color:#123039}
.lv-answer p{margin:7px 0}
.lv-answer .lv-h{margin:12px 0 6px;color:#0b2e36;font-weight:600}
.lv-answer .lv-ul,.lv-answer .lv-ol{margin:6px 0;padding-left:20px}
.lv-answer li{margin:3px 0}
.lv-answer code{padding:1px 5px;border-radius:4px;background:#eef6f5;color:#0b6e68;font-size:12.5px}
.lv-answer strong{color:#0b2e36}
.lv-code{margin:6px 0;padding:9px 11px;border-radius:7px;background:#0f2a30;color:#d6ecea;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:12px;line-height:1.65;overflow-x:auto;white-space:pre-wrap;word-break:break-word}
.lv-table{width:100%;margin:7px 0;border-collapse:collapse;font-size:12.5px}
.lv-table th,.lv-table td{border:1px solid #dfeaea;padding:5px 8px;text-align:left}
.lv-table th{background:#f0f8f7;color:#0b6e68;font-weight:600}
.lv-muted{margin:4px 0;color:#9fb0b4;font-size:12.5px}
.lv-error{margin:8px 0;padding:9px 11px;border:1px solid #e7b7b0;border-left-width:3px;border-radius:7px;background:#fdf4f2;color:#a5352a;font-size:12.5px;line-height:1.7}
.lv-error b{display:block;margin-bottom:3px}
/* 思考过程：默认收起，点开才看明细 —— 收着是为了对话不变成日志，能展开是为了别把过程藏死 */
.lv-think{margin:2px 0 6px;border:1px solid #e2eeed;border-radius:8px;background:#fafdfd}
.lv-think>summary{display:flex;align-items:center;gap:7px;padding:6px 10px;list-style:none;cursor:pointer;color:#57727c;font-size:12px;line-height:1.55}
.lv-think>summary::-webkit-details-marker{display:none}
.lv-think>summary::after{content:'展开';margin-left:auto;flex:0 0 auto;color:#8fa5a9;font-size:11px}
.lv-think[open]>summary::after{content:'收起'}
.lv-think>summary:hover{color:#0b6e68}
.lv-proc{padding:2px 10px 9px;border-top:1px dashed #e2eeed}
.lv-thought{padding:6px 0;color:#3d6570;font-size:12.5px;line-height:1.75;white-space:pre-wrap;word-break:break-word}
.lv-tool{display:flex;align-items:baseline;gap:8px;padding:5px 0;font-size:12px;color:#123039}
.lv-tool b{flex:0 0 auto;color:#0b6e68;font-weight:600;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
.lv-tool .lv-args{flex:1 1 auto;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#57727c}
.lv-tool em{flex:0 0 auto;padding:0 6px;border-radius:999px;background:#eef6f5;color:#0b6e68;font-style:normal;font-size:11px;line-height:16px}
.lv-tool.error em{background:#fdf4f2;color:#a5352a}
.lv-tout{margin:0 0 7px;padding:8px 10px;border-radius:6px;background:#f2f7f7;color:#3d6570;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:11.5px;line-height:1.6;white-space:pre-wrap;word-break:break-word;max-height:260px;overflow:auto}
`
