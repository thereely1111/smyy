/**
 * 工作台（左侧任务栏 + 暂停/删除 + 顶栏左对齐）· 端到端探针（CDP，无第三方依赖）
 *
 * 验九件事：
 *   A. 开题后：视图进工作台；对话区是**属于这条任务的容器**（data-work-task）；
 *      左侧任务栏出现且恰好 1 行
 *   B. 思考过程：出现 `.lv-think` 折叠条，**默认收起**；点开后能看到明细（工具行）
 *   C. 回首页：**首页不再有任何任务查看 UI**（.wb-bar 不存在）；顶栏「工作台」按钮常显，
 *      且点它真能回到工作台（刷新后 workTitle 为空时也点得动 —— 这是本轮的回归点）
 *   D. 开第二条任务：两条任务都出现在左侧栏
 *   E. 从左侧栏切回第一条：容器换回第一条那个，**不串台**
 *   F. 刷新：#/work 不再掉回首页
 *   G. 暂停 / 继续：行的副标题变「已暂停」、按钮文案变「继续」，再点能恢复；
 *   G2. 并且**在对端点上真打到后端**（CDP 网络层看请求，带真实非空会话 id）——
 *       UI 变了而后端一次没收到，正是早先那个「暂停按钮不管用」的 bug
 *   H. 删除：两步确认（第一次点只变文案），删掉后该行消失、前台切到剩下那条；
 *   H2. 同样验证 clear 端点真的被打到
 *   I. 空任务仓直开 #/work：落到首页并把 URL 收成 #/；全流程无未捕获异常
 *
 * ⚠️ 注入脚本里的 localStorage.clear() 只在**首个文档**跑（sessionStorage 打标记）——
 *    否则刷新那一步会把任务仓一起清掉，F 会假失败。
 *
 * ⚠️ 暂停/删除会真打后端（interrupt / clear）。后端没起也不影响本探针：失败路径
 *    也被断言覆盖（打标记 + 如实说明）。但**不要**拿生产会话跑这个探针 ——
 *    clear 是不可逆删除。探针用的是自建的两道假题，会话是它们自己的。
 *
 * 运行：node scripts/port/probe-workbench.mjs [baseUrl] [user] [pass]
 */

import { spawn } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 18713 // 避开本机动态端口区（1024–15001）
const BASE = process.argv[2] || 'http://127.0.0.1:5181'
const USER = process.argv[3] || 'smyui0929test'
const PASS = process.argv[4] || 'Test0929a'
const OUT_DIR = process.env.SMYY_OUT_DIR || resolve(import.meta.dirname, '../../../outputs')

const PROBLEM_A = '某工厂要在四周内安排生产计划，已知每周产能上限与需求，求成本最小的排产方案。'
const PROBLEM_B = '某城市要在地铁沿线布置共享单车投放点，已知各站点日均需求与调度成本，求总成本最小的投放方案。'

const profile = mkdtempSync(join(tmpdir(), 'cdp-wb-'))
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
  /* Network 只用来**观察**请求（不拦截）—— G/H 组要证「暂停/删除真的打到了后端」，
     光看 UI 文案变化证明不了（早先的 bug 正是「UI 变了、后端一次都没收到」） */
  await cdp.send('Network.enable')
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: TOKEN_SRC })
  return cdp
}

/**
 * 从 CDP 事件里筛出打给控制端点的请求。
 *
 * `tail` 传 '/interrupt'（暂停）或 '/clear'（删除）；
 * 端点形状：`/api/agents/{agentId}/sessions/{sessionId}/{interrupt|clear}`。
 */
function controlCalls(cdp, tail) {
  const out = []
  const endpoint = tail.replace(/^\//, '') // 'interrupt' | 'clear'
  /* ⚠️ 不能只按子串匹配：vite dev 会把源码模块也当 URL 发出（`/src/api/interruptRelease.ts`），
     它含 `interrupt` 字样，旧版过滤器会把它当成一次中断请求（sessionId 记成空串，
     于是「带的都是真实会话 id」这条断言假失败）。只认平台控制端点：
     `/api/agents/<agentId>/sessions/<sid>/<endpoint>`。 */
  const re = new RegExp('/api/agents/[^/]+/sessions/([^/]+)/' + endpoint + '(?:s|\\b)')
  for (const e of cdp.events) {
    if (e.method !== 'Network.requestWillBeSent') continue
    const url = e.params?.request?.url || ''
    const m = url.match(re)
    if (!m) continue
    out.push({ url, sessionId: decodeURIComponent(m[1]) })
  }
  return out
}

/** 当前账号的「caseId → threadId」映射表里的全部会话 id */
async function threadMapIds(cdp) {
  return evaluate(
    cdp,
    `(() => {
       try {
         const uid = localStorage.getItem('user_id') || ''
         const raw = localStorage.getItem('smyy.analysis_thread_map' + (uid ? '.' + uid : '')) || '{}'
         const m = JSON.parse(raw)
         return Object.values(m || {}).filter((v) => typeof v === 'string' && v)
       } catch (e) { return [] }
     })()`,
  )
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

/** 工作台 / 任务栏 / 折叠条 / 顶栏 的快照 */
const SNAP = `(() => {
  const on = (id) => { const el = document.getElementById(id); return !!(el && el.classList.contains('on')) }
  const slot = document.getElementById('stream-in')
  const host = slot ? slot.querySelector('[data-work-task]') : null
  const navWork = document.getElementById('nv-work')
  const side = document.querySelector('.wside')
  const rows = [...document.querySelectorAll('.wside .ws-row')]
  const th = [...document.querySelectorAll('.lv-think')]
  const txt = (el) => (el ? el.textContent.trim() : '')
  const wtop = document.querySelector('#view-work .wtop')
  const badge = document.querySelector('#view-work .env-badge')
  const tools = document.querySelector('#view-work .stag') /* .flow-tools 已随顶栏重构移除，右侧状态组改看 .stag */
  let top = null
  if (wtop && badge && tools) {
    const r = wtop.getBoundingClientRect(), b = badge.getBoundingClientRect(), t = tools.getBoundingClientRect()
    top = { width: Math.round(r.width), badgeLeft: Math.round(b.left - r.left), groupRight: Math.round(t.right - r.left) }
  }
  return {
    hash: location.hash,
    home: on('view-home'), work: on('view-work'),
    workTitle: (document.getElementById('w-title') || {}).textContent || '',
    hostId: host ? host.getAttribute('data-work-task') : '',
    hostLen: host ? host.textContent.length : (slot ? slot.textContent.length : -1),
    navWorkShown: !!navWork && getComputedStyle(navWork).display !== 'none',
    barCount: document.querySelectorAll('.wb-bar, .wb-in, .wb-more').length,
    sideShown: !!side && side.offsetWidth > 0,
    sideCount: rows.length,
    sideNames: rows.map((r) => txt(r.querySelector('.ws-name'))),
    sideMetas: rows.map((r) => txt(r.querySelector('.ws-meta'))),
    sideActs: rows.map((r) => [...r.querySelectorAll('.ws-act')].map((b) => txt(b)).join('/')),
    sideActive: rows.findIndex((r) => r.classList.contains('on')),
    top,
    folds: th.length,
    foldsOpen: th.filter((d) => d.hasAttribute('open')).length,
    foldSummary: th.length ? txt(th[0].querySelector('summary')) : '',
    foldBodyLen: th.length ? txt(th[0].querySelector('.lv-proc')).length : -1,
    toolRows: document.querySelectorAll('.lv-think .lv-tool').length,
    taskCount: (() => { try { return (JSON.parse(localStorage.getItem('smyy.work.tasks') || '{}').items || []).length } catch (e) { return -1 } })(),
  }
})()`

/** 点某条任务行（按题面关键字找）；返回找到没有 */
const clickRow = (kw) => `(() => {
  const rows = [...document.querySelectorAll('.wside .ws-row')]
  for (const r of rows) {
    const n = (r.querySelector('.ws-name') || {}).textContent || ''
    if (n.includes(${JSON.stringify(kw)})) { r.click(); return 1 }
  }
  return -1
})()`

/** 点某条任务行里的动作按钮；which = '暂停' | '继续' | '删除' | '确认删除' */
const clickAct = (kw, which) => `(() => {
  const rows = [...document.querySelectorAll('.wside .ws-row')]
  for (const r of rows) {
    const n = (r.querySelector('.ws-name') || {}).textContent || ''
    if (!n.includes(${JSON.stringify(kw)})) continue
    for (const b of r.querySelectorAll('.ws-act')) {
      if (b.textContent.trim() === ${JSON.stringify(which)}) { b.click(); return 1 }
    }
    return -2
  }
  return -1
})()`

const chrome = launch()
let shot = ''
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

  /* ── A. 开第一条任务 ───────────────────────────────────────────────── */
  const cdp = await newPage()
  await cdp.send('Page.navigate', { url: `${BASE}/?probe=wb${Date.now()}#/` })
  await sleep(4200)
  const home0 = await evaluate(cdp, SNAP)
  check('A 初始：首页没有任何任务查看 UI（.wb-bar 已搬走）', home0.barCount === 0, `命中 ${home0.barCount} 个`)

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

  await writeAndStart(PROBLEM_A)
  await sleep(5000)
  let s = await evaluate(cdp, SNAP)
  check('A 开题后进入工作台', s.work, `hash=${s.hash}`)
  check('A 对话区是「属于这条任务」的容器', !!s.hostId, `data-work-task=${s.hostId}`)
  check('A 任务仓登记了 1 条', s.taskCount === 1, `count=${s.taskCount}`)
  check('A 左侧任务栏出现且恰 1 行', s.sideShown && s.sideCount === 1, `shown=${s.sideShown} rows=${s.sideCount}`)
  check('A 左侧栏显示的是这道题', (s.sideNames[0] || '').includes('工厂'), s.sideNames[0] || '')
  check('A 顶栏状态与操作贴最右，左半区只留 返回/标签/题名/进度条', !!s.top && s.top.groupRight > s.top.width - 70 && s.top.badgeLeft > s.top.width * 0.4, s.top ? `组右缘 ${s.top.groupRight} / 栏宽 ${s.top.width} · 徽标左缘 ${s.top.badgeLeft}` : '取不到 .wtop')
  const idA = s.hostId

  /* ── B. 思考过程折叠条 ─────────────────────────────────────────────── */
  let found = null
  /* 窗口给足：真机后端冷启动时，第一条思考事件偶尔要到 80s 后才到
     （2026-10-09 实测 75s 会假红；live 探针那侧 100s 就能等到） */
  const deadline = Date.now() + 120_000
  while (Date.now() < deadline) {
    const cur = await evaluate(cdp, SNAP)
    if (cur.folds > 0) {
      found = cur
      break
    }
    await sleep(2000)
  }
  check('B 出现「思考过程」折叠条（.lv-think）', !!found, found ? `共 ${found.folds} 条 · 摘要「${found.foldSummary}」` : '75s 内没有出现')
  check('B 折叠条默认是收起的', !!found && found.foldsOpen === 0, found ? `展开 ${found.foldsOpen} 条` : '')

  if (found) {
    await evaluate(cdp, `document.querySelector('.lv-think summary').click(); 1`)
    await sleep(400)
    const opened = await evaluate(cdp, SNAP)
    check('B 点开后确实展开了，且里面有条目', opened.foldsOpen === 1 && opened.foldBodyLen > 0, `明细 ${opened.foldBodyLen} 字 · 工具行 ${opened.toolRows} 条`)
    check('B 展开的是过程明细（工具调用行）', opened.toolRows > 0, `toolRows=${opened.toolRows}`)

    /* 展开态要顶得住 700ms 一次的进度重画 */
    await sleep(2500)
    const stillOpen = await evaluate(cdp, SNAP)
    check('B 进度重画不会把用户点开的折叠条收回去', stillOpen.foldsOpen >= 1, `展开 ${stillOpen.foldsOpen} 条`)

    mkdirSync(OUT_DIR, { recursive: true })
    shot = join(OUT_DIR, `probe-workbench-${Date.now()}.png`)
    const png = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
    writeFileSync(shot, Buffer.from(png.data, 'base64'))
  }

  /* ── C. 回首页：没有任务 UI，靠顶栏按钮回去 ─────────────────────────── */
  await evaluate(cdp, `document.getElementById('nv-home').click(); 1`)
  await sleep(1500)
  s = await evaluate(cdp, SNAP)
  check('C 首页不再显示任务查看（常驻条已删）', s.barCount === 0 && s.home, `barCount=${s.barCount} home=${s.home}`)
  check('C 顶栏「工作台」按钮常显', s.navWorkShown)
  await evaluate(cdp, `document.getElementById('nv-work').click(); 1`)
  await sleep(1800)
  s = await evaluate(cdp, SNAP)
  check('C 点顶栏「工作台」能回到那条对话（不再只弹 toast）', s.work && s.workTitle.includes('工厂'), `hash=${s.hash} title=${(s.workTitle || '').slice(0, 16)}`)
  check('C 回来时左侧任务栏还在', s.sideShown && s.sideCount === 1, `rows=${s.sideCount}`)

  /* ── D. 开第二条任务 ───────────────────────────────────────────────── */
  await evaluate(cdp, `document.getElementById('nv-home').click(); 1`)
  await sleep(1200)
  await writeAndStart(PROBLEM_B)
  await sleep(5000)
  s = await evaluate(cdp, SNAP)
  check('D 第二条任务也登记在册', s.taskCount === 2, `count=${s.taskCount}`)
  check('D 左侧栏列出两条任务', s.sideCount === 2, `rows=${s.sideCount} → ${s.sideNames.join(' | ')}`)
  check('D 工作台标题换成了第二条题面', s.workTitle.includes('共享单车'), s.workTitle.slice(0, 24))
  const idB = s.hostId
  check('D 两条任务各自一个对话容器', !!idA && !!idB && idA !== idB, `${idA} vs ${idB}`)

  /* ── E. 从左侧栏切回第一条 ─────────────────────────────────────────── */
  const hit = await evaluate(cdp, clickRow('工厂'))
  await sleep(1800)
  s = await evaluate(cdp, SNAP)
  check('E 左侧栏点得到第一条任务', hit === 1, `clickRow=${hit}`)
  check('E 切回了第一条（题面一致）', s.work && s.workTitle.includes('工厂'), `title=${(s.workTitle || '').slice(0, 20)}`)
  check('E 换台换的是容器本身（不串台）', s.hostId === idA, `挂载 ${s.hostId} · 期望 ${idA}`)
  check('E 第一条的对话内容还在', s.hostLen > 100, `${s.hostLen} 字`)
  check('E 左侧栏高亮跟着走', s.sideActive === s.sideNames.findIndex((n) => n.includes('工厂')), `active=${s.sideActive}`)

  /* ── F. 刷新不再掉回首页 ──────────────────────────────────────────── */
  await cdp.send('Page.reload', { ignoreCache: false })
  await sleep(6000)
  s = await evaluate(cdp, SNAP)
  check('F 刷新后仍在工作台（旧址会掉回首页）', s.work, `hash=${s.hash} title=${(s.workTitle || '').slice(0, 20)}`)
  check('F 刷新后恢复的是那一条任务（题面一致）', s.workTitle.includes('工厂'), s.workTitle.slice(0, 20))
  check('F 刷新后对话快照贴回来了', s.hostLen > 100, `${s.hostLen} 字`)
  check('F 刷新后左侧栏仍在', s.sideCount === 2, `rows=${s.sideCount}`)

  /* ── G. 暂停 / 继续 ────────────────────────────────────────────────── */
  const p1 = await evaluate(cdp, clickAct('工厂', '暂停'))
  await sleep(3500)
  s = await evaluate(cdp, SNAP)
  check('G 点得到「暂停」', p1 === 1, `clickAct=${p1}`)
  check('G 行副标题变成「已暂停」', (s.sideMetas[s.sideNames.findIndex((n) => n.includes('工厂'))] || '').startsWith('已暂停'), s.sideMetas.join(' | '))
  check('G 按钮文案变成「继续」', (s.sideActs[s.sideNames.findIndex((n) => n.includes('工厂'))] || '').startsWith('继续'), s.sideActs.join(' | '))

  /* G2. 「暂停」必须真的打到后端 —— 只看 UI 文案变化证明不了什么。
         早先的 bug 就是「行变了、后端一次都没收到」：点暂停时拿不到会话 id（空串），
         interruptSession('') 直接 return false，一条请求都不发。 */
  const gCalls = controlCalls(cdp, '/interrupt')
  const gIds = [...new Set(gCalls.map((c) => c.sessionId))]
  const mapIds = await threadMapIds(cdp)
  check('G2 点暂停真的打了后端 interrupt 端点', gCalls.length >= 1, `${gCalls.length} 次`)
  check('G2 interrupt 带着真实会话 id（修复前是空串 → 一条都不发）', gIds.length >= 1 && gIds.every((id) => id.startsWith('main-')), gIds.join(' | ') || '(无)')
  check('G2 打的会话能在会话映射表里找到（不是瞎编的 id）', gIds.length >= 1 && gIds.every((id) => mapIds.includes(id)), `映射表 ${mapIds.length} 条：${mapIds.join(' | ')}`)

  const p2 = await evaluate(cdp, clickAct('工厂', '继续'))
  await sleep(1200)
  s = await evaluate(cdp, SNAP)
  check('G 点「继续」能恢复', p2 === 1 && !(s.sideMetas[s.sideNames.findIndex((n) => n.includes('工厂'))] || '').startsWith('已暂停'), s.sideMetas.join(' | '))

  /* ── H. 删除：两步确认 ─────────────────────────────────────────────── */
  const d1 = await evaluate(cdp, clickAct('工厂', '删除'))
  await sleep(500)
  s = await evaluate(cdp, SNAP)
  check('H 第一次点「删除」只是变成「确认删除」', d1 === 1 && (s.sideActs[s.sideNames.findIndex((n) => n.includes('工厂'))] || '').includes('确认删除'), s.sideActs.join(' | '))
  const d2 = await evaluate(cdp, clickAct('工厂', '确认删除'))
  /* 后端 clear 要先中断在跑的 run，再删产物目录 / 状态行 / 归属行 —— 耗时随会话大小波动，
     固定 sleep 会假红（2026-10-09 实测 3.5s 不够）。等行真的消失（最多 20s）再断言。 */
  {
    const dd = Date.now() + 20_000
    while (Date.now() < dd) {
      s = await evaluate(cdp, SNAP)
      if (!s.sideNames.some((n) => n.includes('工厂'))) break
      await sleep(500)
    }
  }
  s = await evaluate(cdp, SNAP)
  check('H 第二次点才真删：行消失', d2 === 1 && !s.sideNames.some((n) => n.includes('工厂')), `rows=${s.sideCount} → ${s.sideNames.join(' | ')}`)

  /* H2. 「删除」同样要真的打到后端 —— 否则只是本地少了一行，后端会话还活着 */
  const cCalls = controlCalls(cdp, '/clear')
  const cIds = [...new Set(cCalls.map((c) => c.sessionId))]
  check('H2 点确认删除真的打了后端 clear 端点', cCalls.length >= 1, `${cCalls.length} 次`)
  check('H2 clear 带着真实会话 id', cIds.length >= 1 && cIds.every((id) => id.startsWith('main-')), cIds.join(' | ') || '(无)')
  check('H 任务仓里也少了一条', s.taskCount === 1, `count=${s.taskCount}`)
  check('H 删的是前台那条 → 自动切到剩下那条', s.work && s.workTitle.includes('共享单车'), `title=${(s.workTitle || '').slice(0, 20)}`)

  /* ── I. 空任务仓直开 #/work + 异常 ────────────────────────────────── */
  const cdp2 = await newPage()
  await cdp2.send('Page.navigate', { url: `${BASE}/?probe=empty${Date.now()}#/work` })
  await sleep(5000)
  const g = await evaluate(cdp2, SNAP)
  check('I 空任务仓直开 #/work 时落在首页', g.home && !g.work, `hash=${g.hash}`)
  check('I 并把 URL 收成 #/（不再出现「地址是 #/work 页面是首页」）', g.hash === '#/' || g.hash === '', `hash=${g.hash}`)

  const errs = pageErrors(cdp)
  check('I 全流程无未捕获异常 / console.error', errs.length === 0, errs.slice(0, 3).join(' | '))
} catch (e) {
  check('探针本身跑通', false, e instanceof Error ? e.message : String(e))
} finally {
  chrome.kill()
  await sleep(300)
}

const failed = results.filter((r) => !r.ok)
console.log(`\n合计 ${results.length} 项，通过 ${results.length - failed.length}，失败 ${failed.length}`)
if (shot) console.log(`截图：${shot}`)
process.exit(failed.length ? 1 : 0)
