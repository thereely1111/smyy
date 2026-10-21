/**
 * 「附件跟着开题走」端到端探针（CDP，无第三方依赖）
 *
 * 验什么（2026-10-09 修的那个「上传了文件智能体读不到」）：
 *   1. 首页选的文件，开题时会真的 POST 到**全局会话**的 attachments 端点；
 *   2. 那条 run 的附件清单指向会话工作区（而不是「本次未随请求提供内容」）。
 *
 * 为什么非真跑不可：这条链路横跨「file input → 暂存区 → 开题时上传 → 提示词渲染」，
 * 静态检查看不出来。
 *
 * 断网打法：用 CDP 的 Fetch 域在浏览器网络层拦下 AG-UI 的 run 端点与附件端点，
 * 自己造假响应。**正式分析 run 故意不响应** —— 页面就停在「智能体正在跑」，
 * 于是不必等真模型。本探针不依赖后端在跑，只依赖 vite dev server（登录那一步仍打真接口）。
 *
 * ── 2026-10-14 变更：单问深挖（`analysisBootQuestion` / `…-q1` 会话重传）整条通路已从
 *    前端删除（逐问改由用户在对话区自由输入驱动，见 AGENTS.md §五），本探针相应去掉
 *    那一半检查，只留「首页附件 → 开题上传到全局会话」。
 *
 * 运行：node scripts/port/probe-attach.mjs [baseUrl] [user] [pass]
 */

import { spawn } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 18721 // 避开本机动态端口区（1024–15001）
const BASE = process.argv[2] || 'http://localhost:5181'
const USER = process.argv[3] || 'smyui0929test'
const PASS = process.argv[4] || 'Test0929a'
const OUT_DIR = process.env.SMYY_OUT_DIR || resolve(import.meta.dirname, '../../../outputs')

const PROBLEM = '某工厂要在四周内安排生产计划，已知每周产能上限与需求，求成本最小的排产方案。'
const ATTACH_NAME = 'data-2026.csv'
const ATTACH_BODY = 'week,capacity,demand\n1,120,100\n2,120,140\n'

const profile = mkdtempSync(join(tmpdir(), 'cdp-attach-'))
/* 探针要上传真文件 —— 落在临时目录，不进项目 */
const attachPath = join(profile, ATTACH_NAME)
writeFileSync(attachPath, ATTACH_BODY, 'utf8')

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
  on(method, handler) {
    const list = this.handlers.get(method) || []
    list.push(handler)
    this.handlers.set(method, list)
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
  await cdp.send('DOM.enable')
  return cdp
}

async function evaluate(cdp, expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (r.exceptionDetails) throw new Error('页面求值异常：' + JSON.stringify(r.exceptionDetails).slice(0, 400))
  return r.result.value
}

function pageErrors(cdp, from = 0) {
  const out = []
  for (const e of cdp.events.slice(from)) {
    if (e.method === 'Runtime.exceptionThrown') {
      const d = e.params?.exceptionDetails
      out.push('uncaught: ' + (d?.exception?.description || d?.text || '').split('\n')[0])
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

/* ── 假响应 ───────────────────────────────────────────────────────────── */

const sseLine = (o) => 'data: ' + JSON.stringify(o) + '\n\n'

function readyStream(threadId, runId) {
  return [
    { type: 'RUN_STARTED', threadId, runId },
    { type: 'TEXT_MESSAGE_START', messageId: 'm-ready', role: 'assistant' },
    { type: 'TEXT_MESSAGE_CONTENT', messageId: 'm-ready', delta: '就绪' },
    { type: 'TEXT_MESSAGE_END', messageId: 'm-ready' },
    { type: 'RUN_FINISHED', threadId, runId, outcome: { type: 'success' } },
  ]
    .map(sseLine)
    .join('')
}

/* ── 拦截 ─────────────────────────────────────────────────────────────── */

const chrome = launch()
let cdpRef = null
let shotPath = ''

/** 上传请求：{ sessionId, name } */
const uploads = []

/** run 请求：{ threadId, runId, prompt, ready } */
const runs = []
/** 故意挂住不响应的 requestId（模拟「智能体正在跑」） */
const heldRequests = []

function sessionOf(url) {
  const m = /\/sessions\/([^/]+)\//.exec(url)
  return m ? decodeURIComponent(m[1]) : ''
}

function promptOf(body) {
  const c = body?.messages?.[0]?.content
  if (Array.isArray(c) && c[0] && typeof c[0].text === 'string') return c[0].text
  if (typeof body?.messages?.[0]?.content === 'string') return body.messages[0].content
  return ''
}

function isReadyRun(body) {
  const t = promptOf(body)
  return typeof t === 'string' && (t.includes('站点自动消息') || t.trim() === '就绪')
}

function fulfill(requestId, code, type, text) {
  void cdpRef
    .send('Fetch.fulfillRequest', {
      requestId,
      responseCode: code,
      responseHeaders: [{ name: 'Content-Type', value: type }],
      body: Buffer.from(text, 'utf8').toString('base64'),
    })
    .catch(() => {
      /* 页面已经取消这条请求 —— 不算错 */
    })
}

function onRequestPaused(params) {
  const url = params.request.url
  const method = params.request.method
  const postData = params.request.postData || ''

  /* 附件接口：上传造成功响应，列表/删除都当成功 */
  if (url.includes('/attachments')) {
    if (method === 'POST') {
      const m = /filename="([^"]*)"/.exec(postData)
      const name = (m && m[1]) || ATTACH_NAME
      uploads.push({ sessionId: sessionOf(url), name, url })
      fulfill(
        params.requestId,
        200,
        'application/json',
        JSON.stringify({ fileId: 'f-' + uploads.length, ref: 'data.csv', fileName: name, size: ATTACH_BODY.length }),
      )
      return
    }
    fulfill(params.requestId, 200, 'application/json', '[]')
    return
  }

  /* 产物接口：新会话本来就没有产物，一律 404（省得真打后端） */
  if (url.includes('/artifacts')) {
    fulfill(params.requestId, 404, 'application/json', '{"message":"会话不存在"}')
    return
  }

  /* AG-UI run */
  if (url.includes('/agui/run')) {
    let body = {}
    try {
      body = JSON.parse(postData || '{}')
    } catch {
      /* 不是 JSON 就当空体 */
    }
    const ready = isReadyRun(body)
    runs.push({
      url,
      threadId: typeof body.threadId === 'string' ? body.threadId : '',
      runId: typeof body.runId === 'string' ? body.runId : '',
      prompt: promptOf(body),
      ready,
    })
    if (ready) {
      fulfill(params.requestId, 200, 'text/event-stream', readyStream(runs[runs.length - 1].threadId, runs[runs.length - 1].runId))
    } else {
      /* 正式分析：不响应，页面停在「正在跑」——单问深挖的前置检查才通得过 */
      heldRequests.push(params.requestId)
    }
    return
  }

  void cdpRef.send('Fetch.continueRequest', { requestId: params.requestId }).catch(() => {})
}

/* ── 主流程 ───────────────────────────────────────────────────────────── */

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
  cdp.on('Fetch.requestPaused', onRequestPaused)
  const info = JSON.stringify({ userId: tokens.userId, username: tokens.username ?? USER, role: tokens.role ?? '' })
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source:
      `try{localStorage.clear();` +
      `localStorage.setItem('access_token',${JSON.stringify(tokens.accessToken)});` +
      `localStorage.setItem('refresh_token',${JSON.stringify(tokens.refreshToken || '')});` +
      `localStorage.setItem('user_id',${JSON.stringify(tokens.userId || '')});` +
      `localStorage.setItem('user_info',${JSON.stringify(info)});}catch(e){}`,
  })
  await cdp.send('Fetch.enable', {
    patterns: [
      { urlPattern: '*agui/run*', requestStage: 'Request' },
      { urlPattern: '*/attachments*', requestStage: 'Request' },
      { urlPattern: '*/artifacts*', requestStage: 'Request' },
    ],
  })

  await cdp.send('Page.navigate', { url: `${BASE}/?probe=at${Date.now()}#/` })
  await sleep(4000)
  const e1 = pageErrors(cdp)
  check('首页无未捕获异常', e1.length === 0, e1.slice(0, 2).join(' | '))

  /* 1. 往 #file-input 里真塞一个文件（走原型的 pickFile，与用户点「上传题目」同一条路） */
  const picked = await evaluate(cdp, `typeof window.pickFile === 'function'`)
  check('原型的上传入口挂到了 window（pickFile）', picked)
  if (!picked) throw new Error('pickFile 不在 window 上，无法模拟选文件')

  await evaluate(cdp, `window.pickFile('home','problem'); 1`)
  const el = await cdp.send('Runtime.evaluate', { expression: `document.getElementById('file-input')` })
  const objectId = el.result?.objectId
  check('拿得到 #file-input 的元素句柄', !!objectId)
  if (!objectId) throw new Error('拿不到 #file-input')
  await cdp.send('DOM.setFileInputFiles', { files: [attachPath], objectId })
  await sleep(800)

  /* setFileInputFiles 一般会自己派发 change；没派发就补一次（否则暂存区是空的） */
  let chips = await evaluate(cdp, `(() => { const s=document.getElementById('attach-strip-home'); return s?s.querySelectorAll('.at-chip').length:0 })()`)
  if (!chips) {
    await evaluate(cdp, `document.getElementById('file-input').dispatchEvent(new Event('change',{bubbles:true})); 1`)
    await sleep(600)
    chips = await evaluate(cdp, `(() => { const s=document.getElementById('attach-strip-home'); return s?s.querySelectorAll('.at-chip').length:0 })()`)
  }
  check('文件进了首页附件暂存区', chips > 0, `暂存区 ${chips} 个文件`)

  /* 2. 开题 → 全局会话应收到上传 */
  await evaluate(
    cdp,
    `(() => {
       const ta = document.getElementById('home-input')
       ta.value = ${JSON.stringify(PROBLEM)}
       ta.dispatchEvent(new Event('input', { bubbles: true }))
       document.getElementById('home-send').click()
       return 1
     })()`,
  )

  let globalUpload = null
  const d1 = Date.now() + 30_000
  while (Date.now() < d1) {
    globalUpload = uploads.find((u) => u.sessionId && !u.sessionId.includes('-q'))
    if (globalUpload) break
    await sleep(600)
  }
  const globalTid = globalUpload ? globalUpload.sessionId : ''
  check('开题时文件真的上传到了全局会话', !!globalUpload, globalUpload ? `session=${globalTid} · ${globalUpload.name}` : '30s 内没有上传请求')

  /* 5. 截图留证 */
  mkdirSync(OUT_DIR, { recursive: true })
  shotPath = join(OUT_DIR, `probe-attach-${Date.now()}.png`)
  const shot = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
  writeFileSync(shotPath, Buffer.from(shot.data, 'base64'))
  console.log(`\n截图：${shotPath}`)

  const e2 = pageErrors(cdp, e1.length)
  check('全程无未捕获异常', e2.length === 0, e2.slice(0, 3).join(' | '))
} catch (e) {
  check('探针本身跑通', false, e instanceof Error ? e.message : String(e))
} finally {
  chrome.kill()
  await sleep(300)
}

const failed = results.filter((r) => !r.ok)
console.log(`\n合计 ${results.length} 项，通过 ${results.length - failed.length}，失败 ${failed.length}`)
process.exit(failed.length ? 1 : 0)
