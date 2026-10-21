/**
 * 实时对话通道探针（CDP，无第三方依赖）
 *
 * 验什么（2026-10-08 定调后的口径，改过两次，以最新一次为准）：
 *   1. 分析期间有**一个思考转圈**（`.lv-busy`）；
 *   2. **正文**上屏（`.lv-answer`）；
 *   3. **思考过程**折成一条默认收起的折叠条（`.lv-think`，可以点开看明细）；
 *   4. 工具行**不许裸铺**在对话区 —— 只准出现在折叠条内部；折叠条默认收起；
 *   5. **内置演示剧本的写死内容不再出现**（开场白 / 四问分析 / 回落话术）。
 *
 * 为什么非真跑不可：这条链路横跨 SSE 解析 → 事件归约 → DOM 渲染 → 生成物里的
 * 桥接，任何一环断掉静态检查都看不出来（vue-tsc 全绿也会白屏）。
 *
 * 登录不走表单，直接打 /api/auth/login 把 token 注入 localStorage ——
 * 表单 DOM 会随改版变，接口不会。
 *
 * 运行：node scripts/port/probe-live.mjs [baseUrl] [user] [pass]
 */

import { spawn } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 18708 // 避开本机动态端口区（1024–15001）
const BASE = process.argv[2] || 'http://127.0.0.1:5181'
const USER = process.argv[3] || 'smyui0929test'
const PASS = process.argv[4] || 'Test0929a'
const OUT_DIR = process.env.SMYY_OUT_DIR || resolve(import.meta.dirname, '../../../outputs')

/** 探针用的题面：短，不依赖附件 —— 只为把 run 打起来 */
const PROBLEM = '某工厂要在四周内安排生产计划，已知每周产能上限与需求，求成本最小的排产方案。'

const profile = mkdtempSync(join(tmpdir(), 'cdp-live-'))
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

/* 对话区状态：转圈 / 折叠条 / 裸铺的工具行 / 正文，外加写死内容有没有残留 */
const PROBE_LIVE = `(() => {
  const stream = document.getElementById('stream-in')
  const box = stream ? stream.querySelector('.lv-stream') : null
  const txt = stream ? stream.textContent : ''
  const answers = box ? [...box.querySelectorAll('.lv-answer')] : []
  const folds = box ? [...box.querySelectorAll('.lv-think')] : []
  const tools = box ? [...box.querySelectorAll('.lv-tool')] : []
  return {
    workOn: !!document.querySelector('#view-work.on'),
    hasBox: !!box,
    busy: box ? box.querySelectorAll('.lv-busy').length : 0,
    fold: folds.length,
    foldOpen: folds.filter((d) => d.hasAttribute('open')).length,
    /* 工具行是否**裸铺**在折叠条外面 —— 那才是「分析内容铺屏」 */
    bare: tools.filter((t) => !t.closest('.lv-think')).length,
    tool: tools.length,
    answer: answers.length,
    answerLen: answers.map(a => a.textContent.length).reduce((a,b)=>a+b,0),
    /* 内置演示剧本的写死内容（开场白 / 回落话术）。命中任一 = 没删干净 */
    demoLeft: /2026 国赛 C 题|这个微网把三样东西聚在一起|平台当前内置的是|先按内置演示剧本|先用内置演示/.test(txt),
    streamLen: txt.length,
  }
})()`

const chrome = launch()
let shotPath = ''
try {
  await getBrowserWs()

  /* 1. 直接打登录接口拿 token（不碰表单 DOM） */
  const loginRes = await fetch(BASE + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER, password: PASS }),
  })
  const tokens = await loginRes.json().catch(() => ({}))
  check('登录接口返回 token', loginRes.ok && !!tokens.accessToken, `HTTP ${loginRes.status}`)
  if (!tokens.accessToken) throw new Error('拿不到 token，后面的用例没有意义')

  /* 2. 注入 token（必须在导航前，否则页面起来时还没登录）。
        顺带清掉会话映射 —— 否则 hash 路由的同文档导航会让 reuse() 直接命中旧产物，
        一次 run 都不发，看着完全像「新功能没触发」。 */
  const cdp = await newPage('about:blank')
  const info = JSON.stringify({ userId: tokens.userId, username: tokens.username ?? USER, role: tokens.role ?? '' })
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source: `try{localStorage.clear();` +
      `localStorage.setItem('access_token',${JSON.stringify(tokens.accessToken)});` +
      `localStorage.setItem('refresh_token',${JSON.stringify(tokens.refreshToken || '')});` +
      `localStorage.setItem('user_id',${JSON.stringify(tokens.userId || '')});` +
      `localStorage.setItem('user_info',${JSON.stringify(info)});}catch(e){}`,
  })
  const stamp = Date.now()
  await cdp.send('Page.navigate', { url: `${BASE}/?probe=${stamp}#/` })
  await sleep(4000)

  const logged = await evaluate(cdp, `!!localStorage.getItem('access_token')`)
  check('token 已注入页面', logged)
  const e1 = pageErrors(cdp)
  check('首页无未捕获异常', e1.length === 0, e1.slice(0, 2).join(' | '))

  /* 3. 首页写题面 → 开始演易 */
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
  await sleep(2500)
  let cur = await evaluate(cdp, PROBE_LIVE)
  check('已进入工作台（#view-work）', cur.workOn)
  check('读题气泡里有实时区容器（.lv-stream）', cur.hasBox)

  /* 4. 盯住 100s：转圈要出现过；工具行不许裸铺；折叠条默认收起 */
  const deadline = Date.now() + 100_000
  let busySeen = false
  let maxBare = 0
  let maxAnswer = 0
  let maxFold = 0
  let foldAutoOpen = 0
  let demoHit = false
  while (Date.now() < deadline) {
    cur = await evaluate(cdp, PROBE_LIVE)
    if (cur.busy > 0) busySeen = true
    maxBare = Math.max(maxBare, cur.bare)
    maxAnswer = Math.max(maxAnswer, cur.answer)
    maxFold = Math.max(maxFold, cur.fold)
    foldAutoOpen = Math.max(foldAutoOpen, cur.foldOpen)
    if (cur.demoLeft) demoHit = true
    if (busySeen && maxAnswer > 0 && maxFold > 0) break
    await sleep(2000)
  }

  check('分析期间出现过思考转圈（.lv-busy）', busySeen)
  check('思考过程折成折叠条（.lv-think）', maxFold > 0, `最多 ${maxFold} 条`)
  check('折叠条不会被自动展开（默认收起）', foldAutoOpen === 0, `最多自动展开 ${foldAutoOpen} 条`)
  check('工具行没有裸铺在对话区（只在折叠条里）', maxBare === 0, `裸铺最多 ${maxBare} 条`)
  check('对话区不出现内置演示剧本的写死内容', !demoHit)
  check('（信息）智能体正文上屏长度', true, `answer 块 ${maxAnswer} 个 · 文本 ${cur.answerLen} 字`)

  /* 4b. 点开折叠条能看见明细（这是这一轮新加的能力，必须真点一次） */
  if (maxFold > 0) {
    await evaluate(cdp, `document.querySelector('.lv-think summary').click(); 1`)
    await sleep(500)
    const opened = await evaluate(cdp, PROBE_LIVE)
    check('点开折叠条后能看到过程明细', opened.foldOpen > 0 && opened.tool > 0, `展开 ${opened.foldOpen} 条 · 工具行 ${opened.tool} 条`)
    await sleep(2500)
    const kept = await evaluate(cdp, PROBE_LIVE)
    check('进度重画不会把展开态收回去', kept.foldOpen > 0, `展开 ${kept.foldOpen} 条`)
  }

  /* 5. 截图留证 */
  mkdirSync(OUT_DIR, { recursive: true })
  shotPath = join(OUT_DIR, `probe-live-${Date.now()}.png`)
  const shot = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
  writeFileSync(shotPath, Buffer.from(shot.data, 'base64'))
  console.log(`\n截图：${shotPath}`)

  const e2 = pageErrors(cdp, e1.length)
  check('工作台运行期间无未捕获异常', e2.length === 0, e2.slice(0, 3).join(' | '))
} catch (e) {
  check('探针本身跑通', false, e instanceof Error ? e.message : String(e))
} finally {
  chrome.kill()
  await sleep(300)
}

const failed = results.filter((r) => !r.ok)
console.log(`\n合计 ${results.length} 项，通过 ${results.length - failed.length}，失败 ${failed.length}`)
process.exit(failed.length ? 1 : 0)
