/**
 * 实时区「具名通道」的并发护栏（2026-10-13，移植 kuaike 链路的配套用例）
 *
 * 背景：动画浮窗与代码等待气泡是**与工作台对话区并发**存在的。
 * 它们如果走 `liveBegin()` / `liveFeed(ev)`（缺省落在全局「当前容器」），
 * 就会出两件坏事之一：
 *   · 抢容器 —— 浮窗的渲染把「下一轮 run 该画哪」改到浮窗里，对话区那条流被截；
 *   · 串流   —— 动画的思考/工具事件漏进工作台对话区（用户看到不属于这道题的明细）。
 *
 * 本套用例把这两件事钉死：通道之间互不可见、未登记的通道静默丢弃、
 * 而「工作台对话区那条老路径」（liveFeed 缺省落 mounted）语义不变。
 *
 * 零模型调用、零网络：只跑归约 + 通道路由，DOM 用一个最小桩。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const { build } = require(require.resolve('esbuild', { paths: [require.resolve('vite')] }))
const root = path.resolve(import.meta.dirname, '..')

globalThis.window = globalThis

const bundled = await build({
  stdin: {
    contents: `export * from '@/utils/analysis/live'\nexport * as render from '@/utils/analysis/live/render'`,
    resolveDir: root,
    loader: 'ts',
  },
  bundle: true,
  platform: 'browser',
  format: 'esm',
  write: false,
  define: { 'process.env.NODE_ENV': '"production"' },
  plugins: [{
    name: 'test-alias',
    setup(api) {
      /* `@/utils/analysis/live` 是**目录**（真实入口是 index.ts），
         不能一律补 `.ts` —— 先看 xx.ts，再退 xx/index.ts。 */
      api.onResolve({ filter: /^@\// }, (a) => {
        const base = path.join(root, 'src', a.path.slice(2))
        return { path: [base + '.ts', path.join(base, 'index.ts')].find((p) => fs.existsSync(p)) ?? base + '.ts' }
      })
    },
  }],
})
const live = await import(
  'data:text/javascript;base64,' + Buffer.from(bundled.outputFiles[0].text).toString('base64')
)

/**
 * 最小 DOM 桩。
 *
 * live 模块碰的 DOM 只有这几样：`innerHTML`（读改写）、`querySelectorAll`（找回
 * 折叠条的展开态、填 `.lv-elapsed`）、`children` + `dataset.lvk`（按块 patch 的
 * 结构比对）、`style.display`。桩住这些就够跑通「归约 → 通道路由」，不必拉真
 * jsdom（那会把用例拖成慢测）。
 *
 * `children` 恒为空数组：真浏览器里它由 innerHTML 解析填充，桩里不填 ——
 * 于是按块 patch 的「结构没变」这条路在桩里永不成立，每次都走整块重写。
 * 那对本套用例是无害的（它只断言归约结果与通道路由），真正的 patch 行为由
 * 真机探针 `probe-flicker.mjs` 把关。
 */
class El {
  constructor(name) {
    this.name = name
    this.innerHTML = ''
    this.style = {}
    this.children = []
    this.dataset = {}
  }
  querySelectorAll() {
    return []
  }
}

const text = (el) =>
  live.liveBlocks(el)
    .filter((b) => b.kind === 'text')
    .map((b) => b.text)
    .join('')

/**
 * 结算某个容器的打字机缓冲。
 *
 * 正文块是**逐字吐**的（20ms 一 tick），刚喂完事件时字符还在打字机里 ——
 * 要断言「最终文本」必须先把缓冲结清（`liveClose` 做的正是这件事，
 * 顺带停掉转圈）。这本身就是打字机接对了的一个证据：不结清就吐不完。
 */
const settle = (el) => live.liveClose(el)

const kinds = (el) => live.liveBlocks(el).map((b) => b.kind)

/** 一条完整的正文消息（三件套），用来验证打字机结算后文本无损 */
const say = (id, s) => [
  { type: 'TEXT_MESSAGE_START', messageId: id },
  { type: 'TEXT_MESSAGE_CONTENT', messageId: id, delta: s },
  { type: 'TEXT_MESSAGE_END', messageId: id },
]
const think = (id, s) => [
  { type: 'REASONING_MESSAGE_START', messageId: id },
  { type: 'REASONING_MESSAGE_CONTENT', messageId: id, delta: s },
  { type: 'REASONING_MESSAGE_END', messageId: id },
]

test('具名通道：事件落在通道宿主，不动「当前容器」', () => {
  const chat = new El('chat')     // 工作台对话区（挂在 mounted 上）
  const anim = new El('anim')     // 动画浮窗
  live.liveMount(chat)

  live.liveBind('anim', anim)
  live.liveFeedChannel('anim', { type: 'TEXT_MESSAGE_START', messageId: 'a1' })
  live.liveFeedChannel('anim', { type: 'TEXT_MESSAGE_CONTENT', messageId: 'a1', delta: '动画正文' })

  assert.equal(live.liveActive(anim), true)
  assert.equal(live.liveBlocks(chat).length, 0, '对话区不能收到浮窗的事件')
  settle(anim)
  assert.equal(text(anim), '动画正文')
  assert.equal(live.liveBlocks(chat).length, 0, '结清浮窗也不该波及对话区')
})

test('缺省路径语义不变：liveFeed 无 box 时仍落到「当前容器」', () => {
  const chat = new El('chat')
  live.liveMount(chat)
  for (const ev of say('c1', '对话区的正文')) live.liveFeed(ev)
  settle(chat)
  assert.equal(text(chat), '对话区的正文')
})

test('liveFeedOn(null) 丢弃事件 —— 绝不回退到对话区', () => {
  const chat = new El('chat')
  live.liveMount(chat)
  const before = live.liveBlocks(chat).length
  live.liveFeedOn(null, { type: 'TEXT_MESSAGE_START', messageId: 'x' })
  live.liveFeedOn(null, { type: 'TEXT_MESSAGE_CONTENT', messageId: 'x', delta: '不该出现' })
  assert.equal(live.liveBlocks(chat).length, before, 'null 就是「没人在看」，不能落进对话区')
})

test('通道未登记时静默丢弃（不抛、不串流）', () => {
  const chat = new El('chat')
  live.liveMount(chat)
  const before = live.liveBlocks(chat).length
  live.liveBind('code:0', null)
  live.liveFeedChannel('code:0', { type: 'TEXT_MESSAGE_START', messageId: 'y' })
  assert.equal(live.liveBlocks(chat).length, before)
})

test('liveBeginChannel 只清自己那条通道，对话区不受影响', () => {
  const chat = new El('chat')
  const anim = new El('anim')
  live.liveMount(chat)
  for (const ev of say('c1', '对话区已有内容')) live.liveFeed(ev)
  settle(chat)
  live.liveBind('anim', anim)
  live.liveFeedChannel('anim', { type: 'TEXT_MESSAGE_START', messageId: 'a1' })
  live.liveFeedChannel('anim', { type: 'TEXT_MESSAGE_CONTENT', messageId: 'a1', delta: '上一轮' })

  live.liveBeginChannel('anim')

  assert.equal(live.liveBlocks(anim).length, 0, '新一轮：浮窗清空')
  assert.equal(text(chat), '对话区已有内容', '对话区纹丝不动')
})

test('liveBeginChannel 不把「当前容器」带偏（再发一轮仍画在对话区）', () => {
  const chat = new El('chat')
  const anim = new El('anim')
  live.liveMount(chat)
  live.liveBind('anim', anim)
  live.liveBeginChannel('anim')       // 动浮窗
  live.liveFeed({ type: 'TEXT_MESSAGE_START', messageId: 'c9' })
  live.liveFeed({ type: 'TEXT_MESSAGE_CONTENT', messageId: 'c9', delta: '还是对话区' })
  settle(chat)
  assert.equal(text(chat), '还是对话区')
  assert.equal(live.liveBlocks(anim).length, 0)
})

test('liveCloseChannel 结清打字机：正文一字不差不缺尾', () => {
  const anim = new El('anim')
  live.liveBind('anim', anim)
  live.liveBeginChannel('anim')
  for (const ev of think('t1', '先看一眼题目')) live.liveFeedChannel('anim', ev)
  for (const ev of say('a1', '结论：这道题按储能套利建模。')) live.liveFeedChannel('anim', ev)

  live.liveCloseChannel('anim')

  assert.equal(text(anim), '结论：这道题按储能套利建模。', '缓冲必须结清，不能留半句')
  assert.deepEqual(kinds(anim), ['thinking', 'text'], '折叠条在前、正文在后')
  assert.equal(live.liveClosed(anim), true)
})

test('两条通道同时投递互不干扰（浮窗 + 代码气泡 + 对话区）', () => {
  const chat = new El('chat')
  const anim = new El('anim')
  const code = new El('code')
  live.liveMount(chat)
  live.liveBind('anim', anim)
  live.liveBind('code:0', code)

  for (const ev of say('c1', '对话')) live.liveFeed(ev)
  for (const ev of say('a1', '动画')) live.liveFeedChannel('anim', ev)
  for (const ev of say('q1', '代码')) live.liveFeedChannel('code:0', ev)
  settle(chat)
  live.liveCloseChannel('anim')
  live.liveCloseChannel('code:0')

  assert.equal(text(chat), '对话')
  assert.equal(text(anim), '动画')
  assert.equal(text(code), '代码')
})

test('代码通道按问分开：第 0 问的事件画不进第 1 问的气泡', () => {
  const q0 = new El('code:0')
  const q1 = new El('code:1')
  live.liveBind('code:0', q0)
  live.liveBind('code:1', q1)
  for (const ev of say('x1', '问题一的代码')) live.liveFeedChannel('code:0', ev)
  live.liveCloseChannel('code:0')
  assert.equal(text(q0), '问题一的代码')
  assert.equal(live.liveBlocks(q1).length, 0)
})

/* ── 模具与时间无关（2026-10-13：「不闪」的前提） ─────────────────────────
 *
 * 实时区整块重写的等值守卫靠「HTML 模具」比对。秒数一旦进了模具，模具就每秒
 * 必然不等 ⇒ 每秒必然整块重建 ⇒ 里面的 `<i class="lv-spin">` 重生、CSS 动画
 * 从 0° 重头转 —— 肉眼就是「闪」。所以这里把口径钉死：**还在跑的那些段落，
 * 秒数只留一个空壳 `.lv-elapsed` 节点**，由 paintElapsed 走 textContent 补；
 * 已结束的段落用时是终值，可以写进模具。
 */

test('转圈模具不含秒数：只留 data-since 空壳（否则每秒必然整块重建）', () => {
  const html = live.render.busyHTML(1_800_000_000_000)
  assert.match(html, /class="lv-busy"/)
  assert.match(html, /class="lv-elapsed" data-since="1800000000000"/, '要留 data-since 给 paintElapsed')
  assert.doesNotMatch(html, /已\s*\d+\s*秒/, '秒数不许写进模具')
})

test('还在跑的过程段：留空壳，不写死秒数', () => {
  const bs = [{ kind: 'thinking', text: '正在想', startedAt: 1_700_000_000_000 }]
  const html = live.render.procFoldHTML(bs, 0)
  assert.match(html, /正在思考…（已 <span class="lv-elapsed" data-since="1700000000000">/)
  assert.doesNotMatch(html, /已\s*\d+\s*秒/)
})

test('已结束的过程段：用时是终值，写进模具（与时间无关）', () => {
  const bs = [{ kind: 'thinking', text: '想完了', startedAt: 1_000_000, endedAt: 1_012_000 }]
  const html = live.render.procFoldHTML(bs, 0)
  assert.match(html, /已思考 12 秒/)
  assert.doesNotMatch(html, /lv-elapsed/)
})
