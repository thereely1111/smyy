/**
 * 「对话时总是跳回首页」取证探针（CDP，无第三方依赖）
 *
 * 目的：不是猜，是**抓现场**。工作台对话进行中视图突然回到首页时，到底是
 *   ① 原型内部调了 go('home')（DOM 层切视图，走 classList.toggle）
 *   ② 路由被改写（hash 变化 / replaceState，带调用栈）
 *   ③ 页面整个重载（Vite 全量刷新 / 崩溃重载）—— 状态机被重置，workTitle 空了，
 *      go('work') 直接 return，于是停在首页
 * 这三条路的表现完全不同，探针要能分开。
 *
 * 手法：
 *   - 在页面**起来之前**注入记账脚本（Page.addScriptToEvaluateOnNewDocument）：
 *       · 包 history.pushState / replaceState，记 url + 调用栈
 *       · 包 DOMTokenList.toggle，专门抓「给某个 .view 加 on」的那一下 + 调用栈
 *       · hashchange / popstate / beforeunload / error / unhandledrejection 记账
 *       · sessionStorage 记启动次数（能区分「重载」与「没重载」）
 *   - 主循环每 500ms 采样一次：hash / 首页是否亮 / 工作台是否亮 / 启动次数
 *
 * 运行：node scripts/port/probe-home-jump.mjs [baseUrl] [user] [pass] [秒数]
 */

import { spawn } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 18709 // 避开本机动态端口区（1024–15001）
const BASE = process.argv[2] || 'http://localhost:5181'
const USER = process.argv[3] || 'smyui0929test'
const PASS = process.argv[4] || 'Test0929a'
const SECONDS = Number(process.argv[5] || 150)
const OUT_DIR = process.env.SMYY_OUT_DIR || resolve(import.meta.dirname, '../../../outputs')

const PROBLEM = '某工厂要在四周内安排生产计划，已知每周产能上限与需求，求成本最小的排产方案。'

const profile = mkdtempSync(join(tmpdir(), 'cdp-jump-'))
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
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(typeof ev.data === 'string' ? ev.data : ev.data.toString())
      if (msg.method) this.events.push(msg)
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id)
        this.pending.delete(msg.id)
        if (msg.error) reject(new Error(JSON.stringify(msg.error)))
        else resolve(msg.result)
      }
    })
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
  if (r.exceptionDetails) throw new Error('页面求值异常：' + JSON.stringify(r.exceptionDetails).slice(0, 400))
  return r.result.value
}

/* ── 注入的记账脚本（页面起来之前跑） ─────────────────────────────────────── */
const RECORDER = `(() => {
  const log = []
  const t0 = Date.now()
  window.__navLog = log
  const stack = () => { try { throw new Error('x') } catch (e) {
    return (e.stack || '').split('\\n').slice(2, 8).map(s => s.trim()).join(' · ')
  } }
  const add = (kind, detail, extra) => {
    log.push(Object.assign({ t: Date.now() - t0, kind, detail, hash: location.hash, boot: window.__bootCount }, extra || {}))
    if (log.length > 300) log.shift()
  }

  window.__bootCount = Number(sessionStorage.getItem('__bootCount') || '0') + 1
  try { sessionStorage.setItem('__bootCount', String(window.__bootCount)) } catch (e) {}

  const wrap = (name) => {
    const orig = history[name].bind(history)
    history[name] = function () {
      add(name, String(arguments[2] || ''), { stack: stack() })
      return orig.apply(history, arguments)
    }
  }
  wrap('pushState'); wrap('replaceState')

  /* 专抓「切视图」：原型 go() 里就是 classList.toggle('on', x===v) */
  const toggle = DOMTokenList.prototype.toggle
  DOMTokenList.prototype.toggle = function (tok, force) {
    if (tok === 'on' && force === true && typeof this.value === 'string' && this.value.indexOf('view-') >= 0) {
      add('view-on', this.value, { stack: stack() })
    }
    return toggle.apply(this, arguments)
  }

  addEventListener('hashchange', () => add('hashchange', location.hash))
  addEventListener('popstate', () => add('popstate', location.hash))
  addEventListener('beforeunload', () => add('beforeunload', location.hash))
  addEventListener('error', (e) => add('window.error', String((e && e.message) || e)))
  addEventListener('unhandledrejection', (e) => add('unhandledrejection', String((e && e.reason && e.reason.message) || e)))
})()`

/* ── 采样 ─────────────────────────────────────────────────────────────────── */
const SNAPSHOT = `(() => {
  const on = (id) => { const el = document.getElementById(id); return !!(el && el.classList.contains('on')) }
  const stream = document.getElementById('stream-in')
  return {
    hash: location.hash,
    home: on('view-home'),
    detail: on('view-detail'),
    work: on('view-work'),
    boot: window.__bootCount || 0,
    nav: (window.__navLog || []).length,
    streamLen: stream ? stream.textContent.length : -1,
    workTitle: (function () { const e = document.getElementById('w-title'); return e ? e.textContent : '' })(),
    hasInput: !!document.getElementById('home-input'),
  }
})()`

const chrome = launch()
let shotPath = ''
const timeline = []
try {
  await getBrowserWs()

  const loginRes = await fetch(BASE + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER, password: PASS }),
  })
  const tokens = await loginRes.json().catch(() => ({}))
  if (!tokens.accessToken) throw new Error(`登录失败 HTTP ${loginRes.status}`)

  const cdp = await newPage('about:blank')
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: RECORDER })
  const info = JSON.stringify({ userId: tokens.userId, username: tokens.username ?? USER, role: tokens.role ?? '' })
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source:
      `try{localStorage.clear();` +
      `localStorage.setItem('access_token',${JSON.stringify(tokens.accessToken)});` +
      `localStorage.setItem('refresh_token',${JSON.stringify(tokens.refreshToken || '')});` +
      `localStorage.setItem('user_id',${JSON.stringify(tokens.userId || '')});` +
      `localStorage.setItem('user_info',${JSON.stringify(info)});}catch(e){}`,
  })

  await cdp.send('Page.navigate', { url: `${BASE}/?probe=${Date.now()}#/` })
  await sleep(4000)

  /* 首页写题面 → 开始演易 */
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
  console.log(`起跑：${started.ok ? '已点开始演易' : '失败 ' + started.why}`)

  /* 盯 SECONDS 秒 */
  const deadline = Date.now() + SECONDS * 1000
  let prev = null
  let jumpAt = null
  while (Date.now() < deadline) {
    let cur = null
    try {
      cur = await evaluate(cdp, SNAPSHOT)
    } catch (e) {
      timeline.push({ t: 'ERR', err: String(e).slice(0, 120) })
      await sleep(500)
      continue
    }
    if (!prev || cur.hash !== prev.hash || cur.home !== prev.home || cur.work !== prev.work || cur.boot !== prev.boot) {
      timeline.push({ ...cur, wall: new Date().toISOString().slice(11, 19) })
      console.log(
        `[${timeline.length}] hash=${cur.hash || '(空)'} home=${cur.home ? '亮' : '-'} work=${cur.work ? '亮' : '-'} ` +
          `boot=${cur.boot} nav=${cur.nav} title="${cur.workTitle}"`,
      )
      if (!jumpAt && prev && prev.work && !cur.work && cur.home) {
        jumpAt = cur
        const shot = await cdp.send('Page.captureScreenshot', { format: 'png' })
        mkdirSync(OUT_DIR, { recursive: true })
        shotPath = join(OUT_DIR, `probe-jump-${Date.now()}.png`)
        writeFileSync(shotPath, Buffer.from(shot.data, 'base64'))
      }
    }
    prev = cur
    await sleep(500)
  }

  /* 把记账日志取回来 */
  const navLog = await evaluate(cdp, `window.__navLog || []`)
  const rendered = await evaluate(cdp, `document.body.innerText.slice(0, 200)`)

  console.log('\n──── 视图/路由记账 ────')
  for (const r of navLog) {
    console.log(`+${String(r.t).padStart(6)}ms  boot=${r.boot}  ${r.kind.padEnd(20)} ${String(r.detail).slice(0, 60)}`)
    if (r.stack) console.log(`           └ ${r.stack.slice(0, 400)}`)
  }
  console.log('\n──── 采样时间线 ────')
  for (const r of timeline) console.log(JSON.stringify(r))
  console.log('\n当前 body 开头：', JSON.stringify(rendered.slice(0, 120)))
  if (jumpAt) console.log(`\n⚠ 抓到跳回首页：hash=${jumpAt.hash} boot=${jumpAt.boot}`)
  else console.log('\n本次采样窗口内没有观察到「工作台 → 首页」的切换')
  if (shotPath) console.log(`截图：${shotPath}`)
} catch (e) {
  console.log('探针异常：' + (e instanceof Error ? e.message : String(e)))
} finally {
  chrome.kill()
  await sleep(300)
}
