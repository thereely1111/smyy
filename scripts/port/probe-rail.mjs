/**
 * 工作台右栏（闸门 + 产物）· 端到端探针（CDP，无第三方依赖）
 *
 * 验的是 2026-10-12 用户定版的版式：**左对话 / 右（闸门 + 产物）**，左栏任务列表
 * 收成窄条（56px，hover 展开 236px）。
 *
 * 刻意的取舍：**不跑真分析、也不依赖后端**。
 *  - 后端（:8080）不在跑：用 CDP 的 Fetch 域把 `/api/auth/*` 就地应答，并直种
 *    localStorage 登录态（见 fakeApi），站点停在「real 模式 + 已登录」；
 *  - 开题那条链会真烧模型调用，这里直接往 localStorage 种一条任务，壳挂载时
 *    workSessionResume 会把它贴回来（只读产物，不发 run）。
 *
 * 八组断言：
 *   A. 三栏成立：左任务条 56px、中对话、右栏 420px，且左右顺序对
 *   B. 右栏结构：三个 tab（闸门 / 产物 / 演示）、闸门 pane 默认在前、产物 pane 里装着清单 + 原型抽屉
 *   C. 左栏折叠：默认 56px；鼠标移上去展开到 236px；移开收回
 *   D. tab 切换：点「产物」→ 只剩产物 pane；点「闸门」→ 换回来
 *   E. 产物清单：对话区出现 .arts .acard 后，右栏清单跟着列出；点右栏那一行
 *      **等于点原卡**（原卡的 onclick 真的被触发）
 *   F. 抽屉联动：openArt → 右栏切预览态、tab 跳到产物；closeDrawer → 收回清单
 *   G. 刷新后仍是三栏（右栏没被快照机制吃掉）
 *   H. 全流程无未捕获异常
 *
 * ⚠️ CDP 端口一律 187xx：本机 TCP 动态端口区实测 1024–15001。
 * ⚠️ 本机挂着系统代理（6854）：Chrome 必须 `--no-proxy-server`，地址写 127.0.0.1。
 * ⚠️ dev server 得先用 run_in_background 起（`&` 会被回收）：`vite --port 5181`。
 * 运行：node scripts/port/probe-rail.mjs [baseUrl] [user] [pass]
 */

import { spawn } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 18717 // 避开本机动态端口区
/* ⚠️ 本机挂着系统代理（HTTP_PROXY=127.0.0.1:6854）：Chrome 会继承它，localhost 也照样
   走代理 → 代理回 502 → 页面变成 chrome-error://chromewebdata，一切断言全灭。
   所以必须 --no-proxy-server，并用 127.0.0.1 而不是 localhost 写死地址。 */
const BASE = process.argv[2] || 'http://127.0.0.1:5181'
const USER = process.argv[3] || 'smyui0929test'
const PASS = process.argv[4] || 'Test0929a'
const OUT_DIR = process.env.SMYY_OUT_DIR || resolve(import.meta.dirname, '../../../outputs')

const profile = mkdtempSync(join(tmpdir(), 'cdp-rail-'))
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
      '--no-proxy-server',
      '--disable-gpu',
      '--window-size=1600,1000',
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
        const h = this.handlers.get(msg.method)
        if (h) h(msg.params)
      }
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

let SEED_SRC = ''

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
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: SEED_SRC })
  return cdp
}

async function evaluate(cdp, expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (r.exceptionDetails) {
    const d = r.exceptionDetails
    const desc = d.exception?.description || d.text || ''
    throw new Error('页面求值异常：' + String(desc).split('\n').slice(0, 3).join(' ‖ ').slice(0, 400))
  }
  return r.result.value
}

/**
 * 就地应答 /api —— 只为摆脱对后端的依赖。
 *
 * 本机 Java 后端（:8080）此刻没起，而站点跑的是 `VITE_AUTH_MODE=real`：路由守卫会
 * 拿 token 去 /api/auth/me 换用户信息，拿不到就把人踢回登录页，工作台根本进不去。
 * 这里用 CDP 的 Fetch 域在那几个口上直接回假响应，让站点停在「real 模式 + 已登录」，
 * 于是可以只验纯前端版式，不必为了看一眼布局去把 Java 后端 + 库 + Redis 全拉起来。
 * 不改仓库、不动 .env.local、不占端口。
 */
async function fakeApi(cdp) {
  const json = (body) => ({
    responseCode: 200,
    responseHeaders: [{ name: 'Content-Type', value: 'application/json' }],
    body: Buffer.from(JSON.stringify(body)).toString('base64'),
  })
  cdp.handlers.set('Fetch.requestPaused', (p) => {
    const url = p.request.url
    /* ⚠️ 必须按 **路径** 判，不能按 `url.includes('/api/')`：dev 服务器把源码也当普通
       静态文件发，`/src/api/http.ts` 同样命中通配，一拦就把整个模块图打断（白屏）。 */
    let path = ''
    try {
      path = new URL(url).pathname
    } catch {
      path = ''
    }
    if (!path.startsWith('/api/')) {
      cdp.send('Fetch.continueRequest', { requestId: p.requestId }).catch(() => undefined)
      return
    }
    const reply = (data) =>
      cdp.send('Fetch.fulfillRequest', { requestId: p.requestId, ...json(data) }).catch(() => undefined)
    if (path === '/api/auth/login') {
      return void reply({ accessToken: 'probe-token', refreshToken: 'probe-refresh', userId: 'probe-user', username: USER, role: 'admin' })
    }
    if (path === '/api/auth/me') {
      return void reply({ userId: 'probe-user', username: USER, role: 'admin' })
    }
    /* ⚠️ 案例库必须回「空列表」这个**形状**，不能落进下面的空对象：
       前端读的是 `(await http.get(...)).items`，回 {} 会让 `cases.value` 变成
       undefined，随后 caseByProtoId 那个 computed 一 `for...of` 就抛
       「cases.value is not iterable」—— 它是 onMounted 里同步触发的，
       一抛就把后面的 `snapTimer = setInterval(snapshotWork, 1500)` 整段跳过，
       右栏产物清单从此没人对账（E 段会假红，且与产物逻辑毫无关系）。
       2026-10-14 实测：补上这一行，probe-rail 从 14/17 回到 25/25。 */
    if (path.indexOf('/api/smyy/cases') === 0) {
      return void reply({ items: [], total: 0 })
    }
    void reply({}) // 其余 /api 一律空对象，省掉一片 502 噪声
  })
  await cdp.send('Fetch.enable', { patterns: [{ urlPattern: '*/api/*' }] })
}

function pageErrors(cdp, from = 0) {
  const out = []
  for (const e of cdp.events.slice(from)) {
    if (e.method === 'Runtime.exceptionThrown') {
      const d = e.params?.exceptionDetails
      out.push('uncaught: ' + (d?.exception?.description || d?.text || '').split('\n')[0])
    }
    if (e.method === 'Runtime.consoleAPICalled' && e.params?.type === 'error') {
      out.push(
        'console.error: ' +
          (e.params.args || [])
            .map((a) => a.value ?? a.description ?? '')
            .join(' ')
            .slice(0, 160),
      )
    }
  }
  return out
}

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

/** 三栏 + 右栏内部结构快照 */
const SNAP = `(() => {
  const px = (el) => (el ? Math.round(el.getBoundingClientRect().width) : -1)
  const left = (el) => (el ? Math.round(el.getBoundingClientRect().left) : -1)
  const work = document.getElementById('view-work')
  const on = (el) => !!(el && el.classList.contains('on'))
  const side = work ? work.querySelector('.wside') : null
  const chat = work ? work.querySelector('.chat') : null
  const rail = document.getElementById('wrail')
  const tabs = rail ? [...rail.querySelectorAll('.rail-tab')] : []
  const paneGate = document.getElementById('rail-pane-gate')
  const paneArt = document.getElementById('rail-pane-art')
  const drawer = document.getElementById('drawer')
  const list = document.getElementById('rail-art-list')
  const rows = list ? [...list.querySelectorAll('.ra-row')] : []
  return {
    hash: location.hash,
    workOn: on(work),
    sideW: px(side), chatW: px(chat), railW: px(rail),
    sideLeft: left(side), chatLeft: left(chat), railLeft: left(rail),
    railRight: rail ? Math.round(rail.getBoundingClientRect().right) : -1,
    railInArt: !!(paneArt && drawer && paneArt.contains(drawer)),
    gateInRail: !!(paneGate && rail && rail.contains(paneGate)),
    tabs: tabs.map((t) => t.textContent.trim()),
    tabGateOn: tabs.length ? tabs[0].classList.contains('on') : false,
    tabArtOn: tabs.length ? tabs[1].classList.contains('on') : false,
    paneGateOn: on(paneGate), paneArtOn: on(paneArt),
    preview: on(paneArt) ? paneArt.classList.contains('preview') : false,
    drawerOn: on(drawer),
    drawerVisible: drawer ? getComputedStyle(drawer).display !== 'none' : false,
    listVisible: list ? getComputedStyle(list).display !== 'none' : false,
    rowCount: rows.length,
    rowNames: rows.map((r) => (r.querySelector('b') || {}).textContent || ''),
    rowActive: rows.findIndex((r) => r.classList.contains('on')),
    artBadge: (document.getElementById('rail-art-badge') || {}).textContent || '',
    gateBadge: (document.getElementById('rail-gate-badge') || {}).textContent || '',
    railMin: !!(rail && rail.classList.contains('min')),
  }
})()`

const dropFakeArt = `(() => {
  const slot = document.getElementById('stream-in')
  const host = (slot && slot.querySelector('[data-work-task]')) || slot
  if (!host) return -1
  const wrap = document.createElement('div')
  wrap.innerHTML = '<div class="msg ai"><div class="bubble"><div class="txt"><div class="arts">' +
    '<button class="acard" id="art-rail-0" data-probe-hit="0">' +
    '<span class="ai" style="background:#12a594">A</span>' +
    '<span class="at"><b>测试产物</b><span>会话产物 · demo.md</span></span>' +
    '<span class="arrow">查看</span></button></div></div></div></div>'
  const card = wrap.querySelector('.acard')
  card.addEventListener('click', function () { card.setAttribute('data-probe-hit', '1') })
  host.appendChild(wrap)
  return 1
})()`

const chrome = launch()
let shot = ''
try {
  await getBrowserWs()

  const userInfo = { username: USER, name: USER, userId: 'probe-user', role: 'admin' }
  /* 直给登录态（token + 用户信息）—— 不再调后端登录接口，见 fakeApi() 的说明。
     stream 里先放一段文本，避免对话区空着不好看结构。 */
  const task = {
    id: 'tprobe-rail-0001',
    title: '探针用题：某工厂四周排产，求成本最小方案。',
    protoId: 'p3',
    fromDetail: true,
    createdAt: Date.now(),
    updatedAt: Date.now(),
    stage: '等待你的指令',
    step: 0,
    stream: '<div class="msg ai"><div class="bubble"><div class="txt"><p>这里是对话区。右栏的闸门与产物不会出现在这一侧。</p></div></div></div>',
    rail: '',
    caseId: '2026-C',
    threadId: '',
    paused: false,
  }
  /* ⚠️ 种数据只在**首个文档**做（sessionStorage 打标记）：这段脚本挂在每一个新文档上，
     不加标记的话 G 那一步刷新会把任务仓清掉，刷新恢复必然假失败。 */
  SEED_SRC =
    `try{if(!sessionStorage.getItem('smyy.probe.seeded')){sessionStorage.setItem('smyy.probe.seeded','1');` +
    `localStorage.clear();` +
    `localStorage.setItem('access_token','probe-token');` +
    `localStorage.setItem('refresh_token','probe-refresh');` +
    `localStorage.setItem('user_id','probe-user');` +
    `localStorage.setItem('user_info',${JSON.stringify(JSON.stringify(userInfo))});` +
    `localStorage.setItem('smyy.work.tasks',${JSON.stringify(
      JSON.stringify({ currentId: task.id, items: [task] }),
    )});}}catch(e){}`

  const cdp = await newPage()
  await fakeApi(cdp)
  /* 先落首页再把「工作台」点开 —— 与 probe-workbench 同法。
     直接开 #/work 不行：原型视图是 go() 切类名切的，路由只管 URL 可分享，
     直开时没人调 go('work')，#view-work 没有 .on，量出来的宽全是 0。 */
  await cdp.send('Page.navigate', { url: `${BASE}/?probe=rail${Date.now()}#/` })
  await sleep(4500)
  await evaluate(cdp, `document.getElementById('nv-work').click(); 1`)
  await sleep(1500)
  const errBase = cdp.events.length
  let s = await evaluate(cdp, SNAP)

  /* ── A. 三栏 ───────────────────────────────────────────────────────── */
  check('A 落在工作台', s.workOn, `hash=${s.hash}`)
  check(
    'A 三栏成立：左任务条 | 中对话 | 右栏（左右顺序对，且右栏贴着容器右缘）',
    s.sideLeft >= 0 && s.chatLeft > s.sideLeft && s.railLeft > s.chatLeft && s.railW > 300,
    `左 ${s.sideLeft}/宽${s.sideW} · 中 ${s.chatLeft}/宽${s.chatW} · 右 ${s.railLeft}/宽${s.railW}`,
  )
  check('A 对话区明显比右栏宽（对话是主区）', s.chatW > s.railW, `对话 ${s.chatW} vs 右栏 ${s.railW}`)

  /* ── B. 右栏结构 ───────────────────────────────────────────────────── */
  check('B 右栏三个 tab（闸门 / 产物 / 演示）', s.tabs.length === 3 && s.tabs[0].includes('闸门') && s.tabs[1].includes('产物') && s.tabs[2].includes('演示'), s.tabs.join(' | '))
  check('B 默认停在「闸门」页', s.tabGateOn && s.paneGateOn && !s.paneArtOn, `gate=${s.tabGateOn} art=${s.tabArtOn}`)
  check('B 闸门容器在右栏里（不是对话区）', s.gateInRail)
  check('B 原型抽屉搬进了右栏的产物页', s.railInArt)
  check('B 未打开预览时，抽屉是收起的（清单占位）', !s.drawerVisible, `drawerVisible=${s.drawerVisible}`)

  /* ── C. 左栏折叠 ───────────────────────────────────────────────────── */
  check('C 左栏默认是窄条（56px）', s.sideW > 40 && s.sideW <= 60, `宽 ${s.sideW}`)
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: s.sideLeft + 20, y: 300, buttons: 0 })
  await sleep(500)
  const hov = await evaluate(cdp, SNAP)
  check('C 鼠标移上去展开到约 236px', hov.sideW >= 220 && hov.sideW <= 260, `宽 ${hov.sideW}`)
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: Math.round((hov.railLeft + hov.railRight) / 2), y: 300, buttons: 0 })
  await sleep(500)
  const unhov = await evaluate(cdp, SNAP)
  check('C 鼠标移开又收回去', unhov.sideW > 40 && unhov.sideW <= 60, `宽 ${unhov.sideW}`)

  /* ── D. tab 切换 ───────────────────────────────────────────────────── */
  await evaluate(cdp, `document.querySelectorAll('#wrail .rail-tab')[1].click(); 1`)
  await sleep(400)
  s = await evaluate(cdp, SNAP)
  check('D 点「产物」切到产物页', s.tabArtOn && s.paneArtOn && !s.paneGateOn, `art=${s.paneArtOn} gate=${s.paneGateOn}`)
  await evaluate(cdp, `document.querySelectorAll('#wrail .rail-tab')[0].click(); 1`)
  await sleep(400)
  s = await evaluate(cdp, SNAP)
  check('D 点「闸门」切回来', s.tabGateOn && s.paneGateOn && !s.paneArtOn)

  /* ── E. 产物清单（对账） ───────────────────────────────────────────── */
  const dropped = await evaluate(cdp, dropFakeArt)
  check('E 往对话区放了一张产物卡', dropped === 1)
  await sleep(2200) // 等 1.5 秒的对账节拍
  s = await evaluate(cdp, SNAP)
  check('E 右栏清单列出了它', s.rowCount === 1 && (s.rowNames[0] || '').includes('测试产物'), `rows=${s.rowCount} → ${s.rowNames.join(' | ')}`)
  check('E 产物 tab 有数量徽标', s.artBadge === '1', `badge=${s.artBadge}`)
  await evaluate(cdp, `document.querySelectorAll('#wrail .rail-tab')[1].click(); 1`)
  await sleep(300)
  await evaluate(cdp, `document.querySelector('#rail-art-list .ra-row').click(); 1`)
  await sleep(300)
  const hit = await evaluate(cdp, `document.getElementById('art-rail-0').getAttribute('data-probe-hit')`)
  check('E 点右栏那一行 = 点原来那张卡（原卡的事件真被触发）', hit === '1', `data-probe-hit=${hit}`)

  /* ── F. 抽屉联动 ───────────────────────────────────────────────────── */
  /* 走真实入口 openArt（产物卡 onclick 里就是它）而不是 openArtifactView —— 后者是
     对话回执专用的内部函数，没挂到 window 上。openArt → openDrawer → railArtView(true)。 */
  await evaluate(cdp, `openArt('analysis','rail-0'); 1`)
  await sleep(500)
  s = await evaluate(cdp, SNAP)
  check('F 打开产物预览 → 右栏进预览态、清单让位', s.preview && s.drawerVisible, `preview=${s.preview} drawer=${s.drawerVisible}`)
  check('F 打开预览会自动跳到产物页（不用手点 tab）', s.tabArtOn, `art=${s.tabArtOn}`)
  await evaluate(cdp, `closeDrawer(); 1`)
  await sleep(400)
  s = await evaluate(cdp, SNAP)
  check('F 关掉预览 → 回到清单', !s.preview && !s.drawerVisible, `preview=${s.preview} drawer=${s.drawerVisible}`)

  /* 截图留证 */
  mkdirSync(OUT_DIR, { recursive: true })
  shot = join(OUT_DIR, `probe-rail-${Date.now()}.png`)
  const png = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
  writeFileSync(shot, Buffer.from(png.data, 'base64'))

  /* ── G. 刷新后仍是三栏 ─────────────────────────────────────────────── */
  await cdp.send('Page.reload', { ignoreCache: false })
  await sleep(6000)
  s = await evaluate(cdp, SNAP)
  check('G 刷新后仍在工作台', s.workOn, `hash=${s.hash}`)
  check('G 刷新后后台/右栏骨架还在（不是只活一次）', s.railW > 300 && s.tabs.length === 3, `railW=${s.railW} tabs=${s.tabs.length}`)
  check('G 刷新后左栏仍是窄条', s.sideW > 40 && s.sideW <= 60, `宽 ${s.sideW}`)
  check('G 刷新后闸门区按任务贴回（容器在）', s.gateInRail)

  /* ── H. 无未捕获异常 ───────────────────────────────────────────────── */
  const errs = pageErrors(cdp, errBase)
  check('H 全流程无未捕获异常', errs.length === 0, errs.slice(0, 3).join(' ‖ ') || '0 条')
} catch (e) {
  check('探针执行到底', false, String(e && e.message ? e.message : e).slice(0, 300))
} finally {
  try {
    chrome.kill()
  } catch {
    /* ignore */
  }
  const bad = results.filter((r) => !r.ok)
  console.log(`\n${results.length - bad.length}/${results.length} 通过`)
  if (shot) console.log('截图：' + shot)
  process.exit(bad.length ? 1 : 0)
}
