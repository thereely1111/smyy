/**
 * 「AG-UI 到底发了哪些事件」取证（CDP，无第三方依赖）
 *
 * 为什么需要：想把「智能体的思考内容」做成可展开的折叠条，前提是后端**真的**会发
 * 思考事件（REASONING_MESSAGE_* / THINKING_BLOCK_*）。站点这边此前只统计过工具次数，
 * 思考段一直是 0，所以先抓一份事件类型直方图+样张，避免做出一个永远空着的折叠条。
 *
 * 手法：页面起来之前包 window.fetch，把 content-type 是 event-stream 的响应用
 * clone() 旁路读一遍，拼进 window.__sseText；跑完在页面里正则统计 `"type"` 取值。
 *
 * 运行：node scripts/port/probe-events.mjs [baseUrl] [user] [pass] [秒数]
 */

import { spawn } from 'node:child_process'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 18712 // 避开本机动态端口区（1024–15001）
const BASE = process.argv[2] || 'http://localhost:5181'
const USER = process.argv[3] || 'smyui0929test'
const PASS = process.argv[4] || 'Test0929a'
const SECONDS = Number(process.argv[5] || 120)

const PROBLEM = '某工厂要在四周内安排生产计划，已知每周产能上限与需求，求成本最小的排产方案。'

const profile = mkdtempSync(join(tmpdir(), 'cdp-events-'))
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
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(typeof ev.data === 'string' ? ev.data : ev.data.toString())
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

const TAP = `(() => {
  window.__sseText = ''
  const orig = window.fetch
  window.fetch = function () {
    const p = orig.apply(this, arguments)
    return p.then((res) => {
      try {
        const ct = (res.headers && res.headers.get('content-type')) || ''
        if (/event-stream|text\\/plain/i.test(ct)) {
          const clone = res.clone()
          const reader = clone.body.getReader()
          const dec = new TextDecoder()
          const pump = () => reader.read().then(({ done, value }) => {
            if (done) return
            try { window.__sseText += dec.decode(value, { stream: true }) } catch (e) {}
            return pump()
          }).catch(() => {})
          pump()
        }
      } catch (e) {}
      return res
    })
  }
})()`

const chrome = launch()
try {
  await getBrowserWs()
  const loginRes = await fetch(BASE + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER, password: PASS }),
  })
  const tokens = await loginRes.json().catch(() => ({}))
  if (!tokens.accessToken) throw new Error(`登录失败 HTTP ${loginRes.status}`)

  const res = await fetch(`http://127.0.0.1:${PORT}/json/new?${encodeURIComponent('about:blank')}`, { method: 'PUT' })
  const target = await res.json()
  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((r, j) => {
    ws.addEventListener('open', r)
    ws.addEventListener('error', j)
  })
  const cdp = new CDP(ws)
  await cdp.send('Runtime.enable')
  await cdp.send('Page.enable')

  const info = JSON.stringify({ userId: tokens.userId, username: tokens.username ?? USER, role: tokens.role ?? '' })
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source:
      `try{localStorage.clear();` +
      `localStorage.setItem('access_token',${JSON.stringify(tokens.accessToken)});` +
      `localStorage.setItem('refresh_token',${JSON.stringify(tokens.refreshToken || '')});` +
      `localStorage.setItem('user_id',${JSON.stringify(tokens.userId || '')});` +
      `localStorage.setItem('user_info',${JSON.stringify(info)});}catch(e){}`,
  })
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: TAP })

  const evaluate = async (expr) => {
    const r = await cdp.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })
    if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails).slice(0, 300))
    return r.result.value
  }

  await cdp.send('Page.navigate', { url: `${BASE}/?probe=ev${Date.now()}#/` })
  await sleep(4000)
  await evaluate(
    `(() => {
       const ta = document.getElementById('home-input')
       ta.value = ${JSON.stringify(PROBLEM)}
       ta.dispatchEvent(new Event('input', { bubbles: true }))
       document.getElementById('home-send').click()
       return 1
     })()`,
  )

  const deadline = Date.now() + SECONDS * 1000
  let lastLen = 0
  while (Date.now() < deadline) {
    await sleep(3000)
    const len = await evaluate(`(window.__sseText || '').length`).catch(() => lastLen)
    if (len !== lastLen) {
      console.log(`… SSE 累计 ${len} 字节`)
      lastLen = len
    }
  }

  const hist = await evaluate(`
    (() => {
      const t = window.__sseText || ''
      const m = t.match(/"type"\\s*:\\s*"([^"]+)"/g) || []
      const c = {}
      for (const s of m) { const k = s.replace(/.*"type"\\s*:\\s*"/, '').replace(/"$/, ''); c[k] = (c[k] || 0) + 1 }
      return { bytes: t.length, types: c }
    })()
  `)
  console.log('\n──── 事件类型直方图 ────')
  console.log(JSON.stringify(hist, null, 2))

  const sample = await evaluate(`
    (() => {
      const t = window.__sseText || ''
      const keys = ['REASONING','THINKING','TOOL','TEXT_MESSAGE_START','RUN_FINISHED','RAW']
      const out = {}
      for (const k of keys) {
        const i = t.indexOf(k)
        out[k] = i < 0 ? null : t.slice(Math.max(0, i - 60), i + 260).replace(/\\n/g, '⏎').slice(0, 320)
      }
      return out
    })()
  `)
  console.log('\n──── 关键事件样张（各取第一处） ────')
  for (const [k, v] of Object.entries(sample)) console.log(`\n[${k}]\n${v === null ? '(未出现)' : v}`)
} catch (e) {
  console.log('探针异常：' + (e instanceof Error ? e.message : String(e)))
} finally {
  chrome.kill()
  await sleep(300)
}
