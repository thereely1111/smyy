/**
 * 「只传题目文件」这条路上的 会话 id 分叉 · 现场取证探针（CDP，无第三方依赖）
 *
 * 背景：用户报「智能体后台还在跑任务，前端关闭不了」。
 * 已确证后端在 09:56-10:16 期间**一条 Interrupt session 都没收到**，却有大量
 * 自主唤醒在烧模型 ⇒ 怀疑「暂停」按钮打向了一个后端不认的会话 id。
 *
 * 本探针只验一件事（不改代码、不做假设）：
 *   「只传题目文件、一个字没写」开题之后，**任务仓里那份 threadId**
 *   与 **映射表里 caseId 对应的那一条**，是不是同一个。
 *   不一致 ⇒ 暂停/删除走 fallback 时必然打向死会话 ⇒ 后端收不到请求。
 *
 * 步骤：
 *   1. 登录（默认探针账号）→ 清 localStorage → 回首页
 *   2. 走真实链路选一个题目文件（core.pickProblem + 真实 change 事件）
 *   3. 点「开始演易」进工作台，等 run 真的发出去
 *   4. 采集：任务仓 / 映射表 / threadIdsForTask / threadIdForTask / hasProblemFile
 *   5. 点任务行的「暂停」，用 Network 层看**到底有没有**发出 /interrupt
 *
 * ⚠️ 会真发一次 run（烧额度）。用探针账号，别拿生产账号跑。
 * 运行：node scripts/port/probe-pause.mjs [baseUrl] [user] [pass]
 */

import { spawn } from 'node:child_process'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 18715 // 避开本机动态端口区（1024–15001）
const BASE = process.argv[2] || 'http://localhost:5181'
const USER = process.argv[3] || 'smyui0929test'
const PASS = process.argv[4] || 'Test0929a'
/** 截图输出目录：默认 <数模演易>/outputs（仓外一级），用 SMYY_OUT_DIR 覆盖 */
const OUT_DIR = process.env.SMYY_OUT_DIR || resolve(import.meta.dirname, '../../../outputs')

/* 故意用「两个字 + .pdf」复刻用户现场（B题）：短到不够 MIN_USER_TEXT，
   只能靠题目文件那一路算 caseId */
const FILE_NAME = 'B题.pdf'
const FILE_PATH = join(mkdtempSync(join(tmpdir(), 'probe-pause-')), FILE_NAME)
writeFileSync(FILE_PATH, '%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n')

const profile = mkdtempSync(join(tmpdir(), 'cdp-pause-'))
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
      '--window-size=1440,1100',
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

async function evaluate(cdp, expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (r.exceptionDetails) return { __err: JSON.stringify(r.exceptionDetails).slice(0, 400) }
  return r.result.value
}

const out = []
const say = (label, val) => {
  const line = `${label}  ${typeof val === 'string' ? val : JSON.stringify(val)}`
  out.push(line)
  console.log(line)
}

/* ── 采集：这条任务在前端眼里的全部标识 ────────────────────────────── */
const PROBE = `(async () => {
  const flow = await import('/src/stores/analysis.ts')
  const raw = localStorage.getItem('smyy.work.tasks') || '{}'
  let items = []
  try { items = JSON.parse(raw).items || [] } catch (e) { items = [{ __err: String(e) }] }
  const t = items[0] || {}
  const uid = localStorage.getItem('user_id') || ''
  const mapKey = 'smyy.analysis_thread_map' + (uid ? '.' + uid : '')
  let map = {}
  try { map = JSON.parse(localStorage.getItem(mapKey) || '{}') } catch (e) { map = { __err: String(e) } }
  return {
    mapKey,
    task: { caseId: t.caseId, protoId: t.protoId, title: t.title, fromDetail: t.fromDetail, threadId: t.threadId, paused: t.paused },
    taskCount: items.length,
    map,
    mapValues: Object.values(map),
    hasProblemFileHome: flow.hasProblemFile('home'),
    heldListHome: flow.heldList('home').map((f) => ({ name: f.name, kind: f.kind, uploaded: f.uploaded })),
    idsForTask: flow.threadIdsForTask(t.protoId || '', t.title || '', !!t.fromDetail),
    idForTask: flow.threadIdForTask(t.protoId || '', t.title || '', !!t.fromDetail),
    stage: (document.getElementById('w-stage') || {}).textContent || '',
    rows: [...document.querySelectorAll('.wside .ws-row')].map((r) => ({
      name: (r.querySelector('.ws-name') || {}).textContent || '',
      meta: (r.querySelector('.ws-meta') || {}).textContent || '',
      acts: [...r.querySelectorAll('.ws-act')].map((b) => b.textContent.trim()),
    })),
  }
})()`

let shot = ''
const chrome = launch()
try {
  await getBrowserWs()

  const loginRes = await fetch(BASE + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER, password: PASS }),
  })
  const tokens = await loginRes.json().catch(() => ({}))
  say('登录', `HTTP ${loginRes.status} token=${!!tokens.accessToken}`)
  if (!tokens.accessToken) throw new Error('拿不到 token')

  const info = JSON.stringify({ userId: tokens.userId, username: tokens.username ?? USER, role: tokens.role ?? '' })
  const TOKEN_SRC =
    `try{` +
    `if(!sessionStorage.getItem('__probeSeeded')){localStorage.clear();sessionStorage.setItem('__probeSeeded','1');}` +
    `localStorage.setItem('access_token',${JSON.stringify(tokens.accessToken)});` +
    `localStorage.setItem('refresh_token',${JSON.stringify(tokens.refreshToken || '')});` +
    `localStorage.setItem('user_id',${JSON.stringify(tokens.userId || '')});` +
    `localStorage.setItem('user_info',${JSON.stringify(info)});}catch(e){}`

  const res = await fetch(`http://127.0.0.1:${PORT}/json/new?${encodeURIComponent('about:blank')}`, { method: 'PUT' })
  const tab = await res.json()
  const ws = new WebSocket(tab.webSocketDebuggerUrl)
  await new Promise((r, j) => {
    ws.addEventListener('open', r)
    ws.addEventListener('error', j)
  })
  const cdp = new CDP(ws)
  await cdp.send('Runtime.enable')
  await cdp.send('Page.enable')
  await cdp.send('Network.enable')
  await cdp.send('DOM.enable')
  await cdp.send('Page.setInterceptFileChooserDialog', { enabled: true }).catch(() => {})
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: TOKEN_SRC })

  await cdp.send('Page.navigate', { url: `${BASE}/?probe=pause${Date.now()}#/` })
  await sleep(4500)
  say('落地页', await evaluate(cdp, `location.hash + ' | ' + document.title`))

  /* ── 1. 走真实链路选题目文件（pickProblem 会绑 onchange 并 click，
          文件对话框由 setInterceptFileChooserDialog 拦掉；随后我们塞真文件并派发 change） */
  const picked = await evaluate(
    cdp,
    `(async () => {
       const core = await import('/src/legacy/prototype/core.ts')
       core.pickProblem('home')
       const fi = document.getElementById('file-input')
       if (!fi) return '没有 #file-input'
       const dt = new DataTransfer()
       dt.items.add(new File([new Blob(['%PDF-1.4\\n'])], ${JSON.stringify(FILE_NAME)}, { type: 'application/pdf' }))
       fi.files = dt.files
       fi.dispatchEvent(new Event('change', { bubbles: true }))
       return 'ok files=' + (fi.files ? fi.files.length : -1)
     })()`,
  )
  say('选题目文件', picked)
  await sleep(1200)

  const afterPick = await evaluate(cdp, PROBE)
  say('选完文件后 hasProblemFile(home)', afterPick.hasProblemFileHome)
  say('选完文件后 heldList(home)', afterPick.heldListHome)

  /* ── 2. 开始演易 ─────────────────────────────────────────────────── */
  const started = await evaluate(
    cdp,
    `(() => { const b = document.getElementById('home-send'); if(!b) return '没有 #home-send'; b.click(); return 'clicked' })()`,
  )
  say('点开始演易', started)
  await sleep(14000)

  say('—— 开题 14 秒后的前端状态 ——', '')
  const st = await evaluate(cdp, PROBE)
  if (st && st.__err) {
    say('采集异常', st.__err)
  } else {
    say('映射表 key', st.mapKey)
    say('任务仓 task', st.task)
    say('映射表内容', st.map)
    say('hasProblemFile(home)', st.hasProblemFileHome)
    say('heldList(home)', st.heldListHome)
    say('threadIdsForTask(...)', st.idsForTask)
    say('threadIdForTask(...)', st.idForTask)
    say('顶栏 stage', st.stage)
    say('左侧任务行', st.rows)

    const v = st.mapValues || []
    const taskTid = st.task && st.task.threadId
    say(
      '★ 判定：任务仓 threadId 是否在映射表里',
      v.includes(taskTid) ? '是（同一座会话）' : `否 —— 任务仓=${taskTid} 映射表=${JSON.stringify(v)}`,
    )
    say(
      '★ 判定：threadIdsForTask 是否算得出会话',
      (st.idsForTask || []).length ? `算得出 ${JSON.stringify(st.idsForTask)}` : '算不出（返回空数组）⇒ 暂停/删除会回落任务仓那份',
    )
  }

  /* ── 2.5 刷新页面：复刻用户「用了一阵再点关闭」的现场 ─────────────────
     刷新只清内存态（附件暂存区），localStorage 里的任务仓与映射表都还在。
     这一步是整条链的关键变量：内存态一空，hasProblemFile 就变 false。   */
  const before = cdp.events.length
  await cdp.send('Page.reload', {})
  await sleep(6500)
  say('刷新后 hash', await evaluate(cdp, `location.hash`))
  const afterReload = await evaluate(cdp, PROBE)
  if (afterReload && !afterReload.__err) {
    say('刷新后 任务仓 task', afterReload.task)
    say('刷新后 映射表内容', afterReload.map)
    say('刷新后 hasProblemFile(home)', afterReload.hasProblemFileHome)
    say('刷新后 threadIdsForTask(...)', afterReload.idsForTask)
    say('刷新后 左侧任务行', afterReload.rows)
  }

  /* ── 3. 点「暂停」，看网络层有没有真发出去、打的是哪一座会话 ─────────── */
  await evaluate(cdp, `(() => {
    const rows = [...document.querySelectorAll('.wside .ws-row')]
    for (const r of rows) {
      for (const b of r.querySelectorAll('.ws-act')) {
        if (b.textContent.trim() === '暂停') { b.click(); return 1 }
      }
    }
    return -1
  })()`)
  await sleep(9000)

  const calls = []
  const codes = {}
  for (const e of cdp.events.slice(before)) {
    if (e.method === 'Network.responseReceived') {
      const u = e.params?.response?.url || ''
      if (/\/sessions\/[^/]+\/(interrupt|clear)/.test(u)) codes[u.replace(BASE, '')] = e.params.response.status
    }
    if (e.method !== 'Network.requestWillBeSent') continue
    const url = e.params?.request?.url || ''
    if (!/\/sessions\/[^/]+\/(interrupt|clear)/.test(url)) continue
    calls.push(url.replace(BASE, ''))
  }
  say('★ 暂停后实际发出的控制请求', calls.length ? calls : '（零条 —— 一条都没发出去）')
  say('★ 这些请求的后端响应码', Object.keys(codes).length ? codes : '（没收到响应）')

  const afterPause = await evaluate(cdp, PROBE)
  if (afterPause && !afterPause.__err) {
    say('暂停后 task.paused', afterPause.task && afterPause.task.paused)
    say('暂停后左侧任务行', afterPause.rows)
  }

  const png = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
  shot = join(OUT_DIR, 'probe-pause-' + Date.now() + '.png')
  const { mkdirSync } = await import('node:fs')
  mkdirSync(OUT_DIR, { recursive: true })
  writeFileSync(shot, Buffer.from(png.data, 'base64'))
  say('截图', shot)
} catch (e) {
  say('探针异常', e instanceof Error ? e.message : String(e))
} finally {
  chrome.kill()
}
console.log('\n===== 汇总 =====')
console.log(out.join('\n'))
