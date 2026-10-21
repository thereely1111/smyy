/**
 * 「对话区为什么会闪」—— 重画计数探针（CDP，真机，2026-10-13）
 *
 * 现象：分析气泡在跑的时候整块地在闪。
 *
 * 假说（静态读代码得出的三条，本探针来量）：
 *   ① 桥接轮询每 700ms **无条件**重写 `.an-head`（build-port.mjs:2277，连
 *      「内容没变就不写」的等值判断都没有）；
 *   ② 同一拍还调 `H.liveRender(liveBox)` 整块重写 `.lv-stream`（:2278）；
 *   ③ 移植的打字机每吐一段就 `schedulePaint` → `liveRender` → 又是整块
 *      `innerHTML=`（live/index.ts:337-351，PAINT_MS=80 ⇒ 最高 ~12.5 次/秒）。
 *
 * 为什么「整块重写」会表现为「闪」而不是「跳」：
 *   · `.lv-stream` 里那个 `<i class="lv-spin">` 每次重建都是**新节点**，
 *     CSS `animation: lv-rotate` 从头转起 —— 转圈看起来在抖；
 *   · `.an-head` 每次重建会把 `<button class="an-skip">跳过等待</button>` 也换新，
 *     于是**建气泡时绑在那个节点上的监听器失效**（点不动，顺带带出的 bug）；
 *   · 正文块每次重建都要重跑一遍 markdown 解析再整段替换 —— 文字重排 + 闪。
 *
 * 关键：`liveRender` 那个 `if (el.innerHTML === html) return` 守卫**挡不住** ——
 * 字符串里含秒数（busyHTML「已 N 秒」/ procFoldHTML 摘要 / progressText 的
 * elapsedLine），**每秒至少必然重建一次**。所以本探针除了数总次数，还按秒分桶、
 * 并记录每一秒的正文长度：**正文没长、却仍在重写的那一秒**就是「秒数驱动重写」
 * 的实锤。
 *
 * 运行：node scripts/port/probe-flicker.mjs [baseUrl] [user] [pass]
 * 产出：控制台表格（可直接与修复后对比）
 */

import { spawn } from 'node:child_process'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 18730 // 避开本机动态端口区（1024–15001）与其它探针（18708 / 18724）
const BASE = process.argv[2] || 'http://127.0.0.1:5181'
const USER = process.argv[3] || 'smyui0929test'
const PASS = process.argv[4] || 'Test0929a'

const PROBLEM = '某工厂要在四周内安排生产计划，已知每周产能上限与需求，求成本最小的排产方案。'

const profile = mkdtempSync(join(tmpdir(), 'cdp-flicker-'))
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function launch() {
  return spawn(
    CHROME,
    [
      '--headless=new',
      `--remote-debugging-port=${PORT}`,
      `--user-data-dir=${profile}`,
      '--no-first-run',
      '--no-default-browser-check',
      /* 本机挂着系统代理（HTTP_PROXY），Chrome 继承后连 127.0.0.1 也走代理 ⇒
         页面变 chrome-error://chromewebdata，所有断言一起灭。必须显式关掉。 */
      '--no-proxy-server',
      '--disable-gpu',
      '--window-size=1440,1000',
      'about:blank',
    ],
    { stdio: 'ignore' },
  )
}

class CDP {
  constructor(ws) {
    this.ws = ws
    this.id = 0
    this.pending = new Map()
    this.events = []
    this.handlers = new Map()
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(typeof ev.data === 'string' ? ev.data : ev.data.toString())
      if (msg.method) {
        this.events.push(msg)
        const hs = this.handlers.get(msg.method)
        if (hs) for (const h of hs) h(msg.params)
      }
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id)
        this.pending.delete(msg.id)
        if (msg.error) reject(new Error(JSON.stringify(msg.error)))
        else resolve(msg.result)
      }
    })
  }
  on(method, fn) {
    if (!this.handlers.has(method)) this.handlers.set(method, [])
    this.handlers.get(method).push(fn)
  }
  send(method, params = {}) {
    const id = ++this.id
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id)
          reject(new Error(`CDP 超时：${method}`))
        }
      }, 60000)
    })
  }
}

async function getBrowserWs() {
  for (let i = 0; i < 60; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/json/version`)
      const j = await res.json()
      if (j.webSocketDebuggerUrl) return j.webSocketDebuggerUrl
    } catch {
      /* retry */
    }
    await sleep(250)
  }
  throw new Error('Chrome CDP 未就绪')
}

async function newPage(url) {
  const res = await fetch(`http://127.0.0.1:${PORT}/json/new?${encodeURIComponent(url)}`, { method: 'PUT' })
  const t = await res.json()
  const ws = new WebSocket(t.webSocketDebuggerUrl)
  await new Promise((r, j) => {
    ws.addEventListener('open', r)
    ws.addEventListener('error', j)
  })
  const cdp = new CDP(ws)
  await cdp.send('Runtime.enable')
  await cdp.send('Page.enable')
  return cdp
}

async function evaluate(cdp, expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (r.exceptionDetails) {
    const d = r.exceptionDetails
    throw new Error('页面求值异常：' + (d.exception?.description || d.text || '').split('\n').slice(0, 3).join(' | '))
  }
  return r.result.value
}

function pageErrors(cdp, from = 0) {
  const out = []
  for (const e of cdp.events.slice(from)) {
    if (e.method === 'Runtime.exceptionThrown') {
      const d = e.params?.exceptionDetails
      out.push('uncaught: ' + (d.exception?.description || d.text || '').split('\n')[0])
    }
    if (e.method === 'Runtime.consoleAPICalled' && e.params?.type === 'error') {
      out.push('console.error: ' + (e.params.args || []).map((a) => a.value ?? a.description ?? '').join(' ').slice(0, 200))
    }
  }
  return out
}

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

/**
 * 装表：观察 body 子树（视图切换会换 #view-work，挂 body 最稳），
 * 按秒分桶数「整块重写」次数，并记录被新建出来的 `.lv-spin` / `.lv-answer` 节点。
 *
 * 判据：一次 `innerHTML=` 赋值在 MutationObserver 眼里 = 目标节点上的一条
 * childList 记录。所以「目标 class 上收到几条 childList」就等于「被整块重写了几次」。
 */
const INSTALL = `(() => {
  const F = { t0: Date.now(), head: 0, live: 0, spin: 0, answer: 0, elapsed: 0, buckets: {} }
  window.__flk = F
  const secOf = () => Math.floor((Date.now() - F.t0) / 1000)
  const bucket = (s) => (F.buckets[s] = F.buckets[s] || { head: 0, live: 0, ansLen: 0, elapsed: 0 })
  const hasSpin = (n) => n.nodeType === 1 && !!(n.classList?.contains('lv-spin') || n.querySelector?.('.lv-spin'))
  const hasAnswer = (n) => n.nodeType === 1 && !!(n.classList?.contains('lv-answer') || n.querySelector?.('.lv-answer'))
  window.__flkObs = new MutationObserver((rs) => {
    for (const r of rs) {
      if (r.type !== 'childList') continue
      const t = r.target
      if (!t || t.nodeType !== 1) continue
      const s = secOf()
      /* 秒数走 textContent：容器（.lv-elapsed / .an-meter）本身不动，只换里面的文本节点。
         这不是「闪」，单独记一列，用来证明「秒数还在跳、但没有整块重建」。 */
      if (t.classList.contains('lv-elapsed') || t.classList.contains('an-meter')) {
        F.elapsed++; bucket(s).elapsed++
        continue
      }
      if (t.classList.contains('an-head')) { F.head++; bucket(s).head++ }
      else if (t.classList.contains('lv-stream')) { F.live++; bucket(s).live++ }
      else continue
      for (const n of r.addedNodes) {
        if (hasSpin(n)) F.spin++
        if (hasAnswer(n)) F.answer++
      }
    }
  })
  window.__flkObs.observe(document.body, { childList: true, subtree: true })
  return true
})()`

/** 采样：内容指纹 + 各目标节点当前状态（用于「内容没变却仍在重写」的交叉验证） */
const SAMPLE = `(() => {
  const F = window.__flk
  if (!F) return null
  const s = Math.floor((Date.now() - F.t0) / 1000)
  const b = F.buckets[s] || (F.buckets[s] = { head: 0, live: 0, ansLen: 0, elapsed: 0 })
  const stream = document.getElementById('stream-in')
  const box = stream ? stream.querySelector('.lv-stream') : null
  const ans = box ? [...box.querySelectorAll('.lv-answer')] : []
  const len = ans.map(a => a.textContent.length).reduce((a, b) => a + b, 0)
  if (len > b.ansLen) b.ansLen = len
  /* 内容指纹 = 折叠条数 + 工具行数 + **去掉秒数后的真实内容长度** + 有没有转圈。
     ⚠️ 长度必须排除 .lv-elapsed —— 那几段秒数每秒都在变，算进去指纹就永远在变，
     这个指标会退化成「恒不算重写」，失去判别力。
     指纹一变就说明「真有新内容」，这时重写是应该的；指纹没变还在重写才是问题。 */
  let content = 0
  if (box) box.querySelectorAll('.lv-answer,.lv-thought,.lv-tool,.lv-tout,.lv-error').forEach(e => { content += e.textContent.length })
  const fold = box ? box.querySelectorAll('.lv-think').length : 0
  const tool = box ? box.querySelectorAll('.lv-tool').length : 0
  const busy = box ? box.querySelectorAll('.lv-busy').length : 0
  b.ck = fold + '|' + tool + '|' + content + '|' + busy
  return {
    head: F.head, live: F.live, spin: F.spin, answer: F.answer,
    hasBox: !!box,
    ansLen: len, fold, tool, busy,
    spinNow: box ? box.querySelectorAll('.lv-spin').length : 0,
    busyText: box ? (box.querySelector('.lv-busy')?.textContent || '') : '',
  }
})()`

const chrome = launch()
let cdpRef = null

try {
  await getBrowserWs()

  const loginRes = await fetch(BASE + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER, password: PASS }),
  })
  const tokens = await loginRes.json().catch(() => ({}))
  check('登录接口返回 token', loginRes.ok && !!tokens.accessToken, `HTTP ${loginRes.status}`)
  if (!tokens.accessToken) throw new Error('拿不到 token，后面的用例没有意义')

  const cdp = await newPage('about:blank')
  cdpRef = cdp

  const info = JSON.stringify({ userId: tokens.userId, username: tokens.username ?? USER, role: tokens.role ?? '' })
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source:
      `try{localStorage.clear();` +
      `localStorage.setItem('access_token',${JSON.stringify(tokens.accessToken)});` +
      `localStorage.setItem('refresh_token',${JSON.stringify(tokens.refreshToken || '')});` +
      `localStorage.setItem('user_id',${JSON.stringify(tokens.userId || '')});` +
      `localStorage.setItem('user_info',${JSON.stringify(info)});}catch(e){}`,
  })

  const stamp = Date.now()
  await cdp.send('Page.navigate', { url: `${BASE}/?probe=${stamp}#/` })
  await sleep(4000)

  check('token 已注入页面', await evaluate(cdp, `!!localStorage.getItem('access_token')`))
  const e1 = pageErrors(cdp)
  check('首页无未捕获异常', e1.length === 0, e1.slice(0, 2).join(' | '))

  /* 先装表再点发送 —— 气泡是点完才建的，表要在那之前就位 */
  check('重画计数表已就位', await evaluate(cdp, INSTALL))

  const started = await evaluate(
    cdp,
    `(() => {
       const ta = document.getElementById('home-input')
       if (!ta) return { ok: false, why: 'no #home-input' }
       ta.value = ${JSON.stringify(PROBLEM)}
       ta.dispatchEvent(new Event('input', { bubbles: true }))
       const btn = document.getElementById('home-send')
       if (!btn) return { ok: false, why: 'no #home-send' }
       btn.click()
       return { ok: true }
     })()`,
  )
  check('首页可输入题面并点「开始演易」', started.ok, started.why || '')

  /* 盯住 90s，每 250ms 采一次（比 700ms 轮询密，才能把整块重写都记上） */
  const deadline = Date.now() + 90_000
  let last = null
  let sawBusy = false
  let maxAns = 0
  let reloaded = false
  while (Date.now() < deadline) {
    last = await evaluate(cdp, SAMPLE)
    /* 计数表丢了 = 页面在中途整页重载（vite 全量刷新 / HMR 兜底）。
       这时 t0 与 buckets 都被重置，本轮的数全不作数 —— 必须显式报出来，
       否则会以「0 次重写」的样子假绿（实测踩过一次）。 */
    if (!last) {
      reloaded = true
      break
    }
    if (last.busy > 0) sawBusy = true
    maxAns = Math.max(maxAns, last.ansLen)
    /* 跑到「正文已经出来、且转圈消失」就说明这一轮打字过程已被完整覆盖 */
    if (last.ansLen > 400 && last.busy === 0 && last.fold > 0) break
    await sleep(250)
  }

  const final = await evaluate(cdp, `(() => { const F = window.__flk; return F ? { head: F.head, live: F.live, spin: F.spin, answer: F.answer, elapsed: F.elapsed, buckets: F.buckets } : null })()`)
  const dur = await evaluate(cdp, `window.__flk ? Math.round((Date.now() - window.__flk.t0) / 1000) : 0`)

  check('观测期间页面未重载（重载会让本轮测量作废）', !reloaded, reloaded ? 'vite 全量刷新 —— 请重跑' : '')

  check('分析气泡建出（.lv-stream 在）', !!(last && last.hasBox))
  check('分析期间出现过思考转圈（.lv-busy）', sawBusy)
  check('正文上屏（.lv-answer 有字）', maxAns > 0, `最多 ${maxAns} 字`)
  check('思考过程折成折叠条（.lv-think）', !!(last && last.fold > 0), last ? `fold=${last.fold}` : '')

  const e2 = pageErrors(cdp)
  check('运行期间无未捕获异常', e2.length === 0, e2.slice(0, 2).join(' | '))

  /* ── 测量结果 ─────────────────────────────────────────────────────────── */
  const buckets = (final && final.buckets) || {}
  const secs = Object.keys(buckets)
    .map(Number)
    .sort((a, b) => a - b)
  const rows = secs.map((s) => ({ s, ...buckets[s] }))
  const peakLive = rows.reduce((m, r) => Math.max(m, r.live), 0)
  const peakHead = rows.reduce((m, r) => Math.max(m, r.head), 0)
  const liveSecs = rows.filter((r) => r.live > 0).length
  /* 内容指纹没变、却仍在重写的那几秒 —— 「与内容无关的重写」的实锤。
     修复前这个数很大（秒数驱动的每秒重写），修复后应当为 0。 */
  const quietRewrite = rows.filter((r, i) => i > 0 && r.live > 0 && r.ck === rows[i - 1].ck).length

  console.log('\n' + '─'.repeat(64))
  console.log('重画计数（整块 innerHTML 重写次数）')
  console.log('─'.repeat(64))
  console.log(`观测时长            ${dur} 秒`)
  console.log(`.an-head  重写      ${final ? final.head : 0} 次${peakHead ? `（峰值 ${peakHead} 次/秒）` : ''}`)
  console.log(`.lv-stream 重写     ${final ? final.live : 0} 次${peakLive ? `（峰值 ${peakLive} 次/秒）` : ''}`)
  console.log(`  其中发生重写的秒   ${liveSecs} / ${secs.length} 秒`)
  console.log(`  内容没变却重写的秒 ${quietRewrite} 秒（修复前 87 秒 —— 秒数驱动的铁证）`)
  console.log(`.lv-spin 新建       ${final ? final.spin : 0} 次（每建一次 CSS 转圈就从 0° 重头转）`)
  console.log(`.lv-answer 新建     ${final ? final.answer : 0} 次（每建一次 markdown 整段重解析）`)
  console.log(`秒数文本更新        ${final ? final.elapsed : 0} 次（走 textContent，不是「闪」）`)
  console.log('─'.repeat(64))
  console.log('   秒  | head | live | 秒数 | 内容 | 正文长度')
  rows.forEach((r, i) => {
    const chg = i === 0 ? '首' : r.ck === rows[i - 1].ck ? '—' : '变'
    console.log(`  ${String(r.s).padStart(4)} | ${String(r.head).padStart(4)} | ${String(r.live).padStart(4)} | ${String(r.elapsed ?? 0).padStart(4)} | ${chg}   | ${r.ansLen}`)
  })
  console.log('─'.repeat(64))

  /* 修复后应当成立的判据（修复前会挂，见记忆里那张 before 表） */
  check('（目标）.an-head 不再每秒重写', (final ? final.head : 0) <= 4, `${final ? final.head : 0} 次`)
  check(
    '（目标）转圈只在内容变化时重建（不再每秒一次）',
    (final ? final.spin : 0) <= 10,
    `${final ? final.spin : 0} 次 / ${dur} 秒`,
  )
  check('（目标）秒数改为 textContent 更新（仍在跳）', (final ? final.elapsed : 0) > 0, `${final ? final.elapsed : 0} 次`)
  check('（目标）不存在「内容没变却仍在重写」的秒', quietRewrite === 0, `${quietRewrite} 秒`)
  check('（诊断）整块重写总次数', true, `.lv-stream ${final ? final.live : 0} 次 · 峰值 ${peakLive} 次/秒`)

  const soft = (r) => r.name.startsWith('（诊断）')
  const hard = results.filter((r) => !soft(r))
  const failed = hard.filter((r) => !r.ok)
  console.log(`\n合计 ${results.length} 项：硬性 ${hard.length}（失败 ${failed.length}）· 诊断 ${results.filter(soft).length}`)
} catch (err) {
  console.log('探针异常：' + (err && err.message ? err.message : String(err)))
} finally {
  try {
    chrome.kill()
  } catch {
    /* ignore */
  }
  process.exit(0)
}
