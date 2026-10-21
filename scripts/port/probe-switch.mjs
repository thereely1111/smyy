/**
 * 多任务切换 · 对话显示回归探针（CDP，无第三方依赖）
 *
 * 为什么单独一个：probe-workbench.mjs 里的切任务只发生一次、且两条任务都还没
 * 跑出真产物。用户报「任务切换会导致对话显示出现问题」，需要的是**反复切**、
 * 且**切的时候两边都在跑**的场景。
 *
 * 断言：
 *   1. 切过去的那条任务，对话区挂的确实是它的容器（data-work-task）
 *   2. 反复切 6 次，每条任务的对话字数不漂（不丢内容、不重复堆）
 *   3. 每条任务自己的折叠条 / 气泡数量固定（不串台、不翻倍）
 *   4. 切回来时「思考过程」展开态不被冲掉
 *   5. 无未捕获异常 / console.error
 *
 * 运行：node scripts/port/probe-switch.mjs
 */

import { spawn } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 18715 // 避开本机动态端口区（1024–15001）
const BASE = process.argv[2] || 'http://localhost:5181'
const USER = process.argv[3] || 'smyui0929test'
const PASS = process.argv[4] || 'Test0929a'
const OUT_DIR = process.env.SMYY_OUT_DIR || resolve(import.meta.dirname, '../../../outputs')

const PROBLEM_A = '某工厂要在四周内安排生产计划，已知每周产能上限与需求，求成本最小的排产方案。'
const PROBLEM_B = '某城市要在地铁沿线布置共享单车投放点，已知各站点日均需求与调度成本，求总成本最小的投放方案。'

const profile = mkdtempSync(join(tmpdir(), 'cdp-sw-'))
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

let TOKEN_SRC = ''

async function newPage() {
  const res = await fetch(`http://127.0.0.1:${PORT}/json/new?${encodeURIComponent('about:blank')}`, { method: 'PUT' })
  const t = await res.json()
  const ws = new WebSocket(t.webSocketDebuggerUrl)
  await new Promise((r, j) => {
    ws.addEventListener('open', r)
    ws.addEventListener('error', j)
  })
  const cdp = new CDP(ws)
  await cdp.send('Runtime.enable')
  await cdp.send('Page.enable')
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: TOKEN_SRC })
  return cdp
}

async function evaluate(cdp, expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (r.exceptionDetails) throw new Error('页面求值异常：' + JSON.stringify(r.exceptionDetails).slice(0, 300))
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
      out.push('console.error: ' + (e.params.args || []).map((a) => a.value ?? a.description ?? '').join(' ').slice(0, 160))
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
 * 快照：把「当前挂载的容器」与「所有内存里的容器」都量一遍。
 * WORK_HOSTS 是 core 的内部变量（不是 window），拿不到 —— 所以改用
 * 「切过去之后量到的内容」侧写：字数 / 气泡数 / 折叠条数。
 */
const SNAP = `(() => {
  const slot = document.getElementById('stream-in')
  const host = slot ? slot.querySelector('[data-work-task]') : null
  const rows = [...document.querySelectorAll('.wside .ws-row')]
  const txt = (el) => (el ? el.textContent.trim() : '')
  return {
    hash: location.hash,
    view: (document.getElementById('view-work') || {}).className || '',
    workTitle: (document.getElementById('w-title') || {}).textContent || '',
    hostId: host ? host.getAttribute('data-work-task') : '',
    hostLen: host ? host.textContent.length : (slot ? slot.textContent.length : -1),
    hostChildren: host ? host.children.length : -1,
    hostHTML: host ? host.innerHTML.length : -1,
    bubbles: host ? host.querySelectorAll('.msg, .bubble, .an-bubble').length : -1,
    folds: host ? host.querySelectorAll('.lv-think').length : -1,
    foldsOpen: host ? [...host.querySelectorAll('.lv-think')].filter((d) => d.hasAttribute('open')).length : -1,
    busy: host ? host.querySelectorAll('.lv-busy').length : -1,
    slotChildren: slot ? slot.children.length : -1,
    sideNames: rows.map((r) => txt(r.querySelector('.ws-name'))),
    sideActive: rows.findIndex((r) => r.classList.contains('on')),
  }
})()`

const clickRow = (kw) => `(() => {
  const rows = [...document.querySelectorAll('.wside .ws-row')]
  for (const r of rows) {
    const n = (r.querySelector('.ws-name') || {}).textContent || ''
    if (n.includes(${JSON.stringify(kw)})) { r.click(); return 1 }
  }
  return -1
})()`

const chrome = launch()
let shotDir = ''
try {
  await getBrowserWs()

  const loginRes = await fetch(BASE + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER, password: PASS }),
  })
  const tokens = await loginRes.json().catch(() => ({}))
  check('登录接口返回 token', loginRes.ok && !!tokens.accessToken, `HTTP ${loginRes.status}`)
  if (!tokens.accessToken) throw new Error('拿不到 token')

  const info = JSON.stringify({ userId: tokens.userId, username: tokens.username ?? USER, role: tokens.role ?? '' })
  TOKEN_SRC =
    `try{` +
    `if(!sessionStorage.getItem('__probeSeeded')){localStorage.clear();sessionStorage.setItem('__probeSeeded','1');}` +
    `localStorage.setItem('access_token',${JSON.stringify(tokens.accessToken)});` +
    `localStorage.setItem('refresh_token',${JSON.stringify(tokens.refreshToken || '')});` +
    `localStorage.setItem('user_id',${JSON.stringify(tokens.userId || '')});` +
    `localStorage.setItem('user_info',${JSON.stringify(info)});}catch(e){}`

  mkdirSync(OUT_DIR, { recursive: true })
  shotDir = join(OUT_DIR, `switch-${Date.now()}`)
  mkdirSync(shotDir, { recursive: true })

  const cdp = await newPage()
  await cdp.send('Page.navigate', { url: `${BASE}/?probe=sw${Date.now()}#/` })
  await sleep(4200)

  const writeAndStart = (text) =>
    evaluate(
      cdp,
      `(() => {
         const ta = document.getElementById('home-input')
         ta.value = ${JSON.stringify(text)}
         ta.dispatchEvent(new Event('input', { bubbles: true }))
         document.getElementById('home-send').click()
         return 1
       })()`,
    )

  const shot = async (name) => {
    const png = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
    writeFileSync(join(shotDir, `${name}.png`), Buffer.from(png.data, 'base64'))
  }

  /* ── 第一条：等它跑出真产物（折叠条 / 转圈） ───────────────────────── */
  await writeAndStart(PROBLEM_A)
  await sleep(6000)
  let s = await evaluate(cdp, SNAP)
  const idA = s.hostId
  check('A 开题进工作台且对话区是这条任务的容器', !!idA, `hash=${s.hash} host=${idA}`)

  let aRun = s
  const deadline = Date.now() + 90_000
  while (Date.now() < deadline) {
    const cur = await evaluate(cdp, SNAP)
    if (cur.folds > 0 || cur.hostLen > 300) {
      aRun = cur
      break
    }
    await sleep(2000)
  }
  await shot('01-taskA')
  check('A 第一条跑出了内容（折叠条或正文）', aRun.folds > 0 || aRun.hostLen > 300, `folds=${aRun.folds} len=${aRun.hostLen}`)

  /* ── 第二条 ───────────────────────────────────────────────────────── */
  await evaluate(cdp, `document.getElementById('nv-home').click(); 1`)
  await sleep(1500)
  await writeAndStart(PROBLEM_B)
  await sleep(8000)
  s = await evaluate(cdp, SNAP)
  const idB = s.hostId
  check('B 第二条任务有独立容器', !!idB && idB !== idA, `${idA} vs ${idB}`)
  await shot('02-taskB')

  /* ── 反复切：A→B→A→B→A→B ─────────────────────────────────────────── */
  const seq = [
    { kw: '工厂', id: idA, tag: 'A' },
    { kw: '共享单车', id: idB, tag: 'B' },
    { kw: '工厂', id: idA, tag: 'A' },
    { kw: '共享单车', id: idB, tag: 'B' },
    { kw: '工厂', id: idA, tag: 'A' },
    { kw: '共享单车', id: idB, tag: 'B' },
  ]
  const seen = { A: [], B: [] }
  let wrongHost = 0
  let slotCleared = 0
  for (let i = 0; i < seq.length; i++) {
    const st = seq[i]
    await evaluate(cdp, clickRow(st.kw))
    await sleep(1600)
    const cur = await evaluate(cdp, SNAP)
    if (cur.hostId !== st.id) wrongHost += 1
    if (cur.slotChildren !== 1) slotCleared += 1
    seen[st.tag].push({ id: cur.hostId, len: cur.hostLen, folds: cur.folds, busy: cur.busy, idx: i })
    await shot(`03-switch-${String(i + 1).padStart(2, '0')}-${st.tag}`)
    console.log(
      `     切第 ${i + 1} 次 → 期望${st.tag} 实得 host=${cur.hostId} len=${cur.hostLen} folds=${cur.folds} busy=${cur.busy} 栏内选中=${cur.sideActive}`,
    )
  }

  check('C 六次切换，每次都挂到对的那条容器上', wrongHost === 0, `错 ${wrongHost} 次`)
  check('C 六次切换，槽位里始终恰好一个容器', slotCleared === 0, `异常 ${slotCleared} 次`)

  const lens = (tag) => seen[tag].map((x) => x.len)
  const spreadA = Math.max(...lens('A')) - Math.min(...lens('A'))
  const spreadB = Math.max(...lens('B')) - Math.min(...lens('B'))
  check('D 切来切去，第一条的对话字数不漂（不丢也不堆）', spreadA <= 60, `A 三次：${lens('A').join(' / ')}（差 ${spreadA}）`)
  check('D 切来切去，第二条的对话字数不漂', spreadB <= 60, `B 三次：${lens('B').join(' / ')}（差 ${spreadB}）`)

  const foldA = seen.A.map((x) => x.folds)
  const foldB = seen.B.map((x) => x.folds)
  check(
    'D 每条任务的折叠条数量固定（不串台、不翻倍）',
    new Set(foldA).size === 1 && new Set(foldB).size === 1,
    `A folds=${foldA.join('/')} · B folds=${foldB.join('/')}`,
  )

  /* ── 展开态跨切换保留 ─────────────────────────────────────────────── */
  await evaluate(cdp, clickRow('工厂'))
  await sleep(1200)
  let d = await evaluate(cdp, SNAP)
  if (d.folds > 0) {
    await evaluate(cdp, `document.querySelector('#stream-in [data-work-task] .lv-think summary').click(); 1`)
    await sleep(400)
    const opened = await evaluate(cdp, SNAP)
    check('E 点开后确实展开', opened.foldsOpen >= 1, `展开 ${opened.foldsOpen}`)
    await evaluate(cdp, clickRow('共享单车'))
    await sleep(1400)
    await evaluate(cdp, clickRow('工厂'))
    await sleep(1400)
    d = await evaluate(cdp, SNAP)
    check('E 切走再切回，折叠条的展开态还在', d.foldsOpen >= 1, `展开 ${d.foldsOpen} / 共 ${d.folds}`)
  } else {
    check('E 第一条还没长出折叠条，跳过展开态用例', true, `folds=${d.folds}`)
  }

  await shot('04-final')
  const errs = pageErrors(cdp)
  check('F 全流程无未捕获异常 / console.error', errs.length === 0, errs.slice(0, 3).join(' | '))
} catch (e) {
  check('探针本身跑通', false, e instanceof Error ? e.message : String(e))
} finally {
  chrome.kill()
  await sleep(300)
}

const failed = results.filter((r) => !r.ok)
console.log(`\n合计 ${results.length} 项，通过 ${results.length - failed.length}，失败 ${failed.length}`)
if (shotDir) console.log(`截图：${shotDir}`)
process.exit(failed.length ? 1 : 0)
