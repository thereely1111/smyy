/**
 * 产物卡探针（CDP，零模型消耗）
 *
 * 验什么（Step3：对话区产物墙）：
 *   1. 跑完分析后对话区出现「智能体产物」卡（`.art-card`）；
 *   2. **不过滤**：本会话 + 全部子会话 `artifacts/` 下的文件**全部**列出来，
 *      数量与磁盘实测一致，重名的那几份带会话短标；
 *   3. 大小按 `50.4 KB` 这种口径显示；
 *   4. 点一张文本卡 → 右侧抽屉打开、正文是**那个文件的内容**；
 *   5. 点一张二进制卡（pdf/xlsx/png）→ 抽屉如实写「二进制不可预览」，不抛异常；
 *   6. 点「刷新」重画后数量不变（事件委托没被打断）；
 *   7. 全程无未捕获异常。
 *
 * 为什么必须真机跑：这条链路横跨「钩子注册 → 桥接层建卡 → 事件委托 → 模块拉树 →
 * 抽屉重画」，静态检查全绿也可能整段没接上（本工程踩过：vue-tsc 全绿 + 页面白屏）。
 *
 * 怎么做到零模型消耗：
 *   · 后端**不用起** —— 所有 `/api/*` 与 `/agui/*` 请求都在 CDP 的 Fetch 层拦下并
 *     现场作答（对话/产物树/内容/auth 全是我们给的响应）；
 *   · 产物树与文件内容**不是编的**：探针启动时真的去读**仓内夹具**
 *     `scripts/port/fixtures/artifact-runroot/`（那次 D 题会话 `artifacts/` 的字节级快照），
 *     按后端的合并口径（本会话 + 子会话、目录 union、同名文件加会话短标）生成树，
 *     内容也按路径回读真实文件。所以断言里的文件名、大小、正文都是真的。
 *     夹具放在仓内 ⇒ 不依赖任何本机运行态根，换设备/换工作区照样能跑。
 *
 * 运行：node scripts/port/probe-artifacts.mjs
 *   环境变量（都可选）：CHROME_PATH=Chrome 可执行文件 · SMYY_OUT_DIR=截图输出目录
 */

import { spawn } from 'node:child_process'
import { existsSync, mkdtempSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

/** 本脚本所在目录（…/smyy/scripts/port）—— 所有相对定位都从它出发，不写绝对路径 */
const HERE = import.meta.dirname

/** Chrome 路径：默认按 Windows 常见安装位，换设备用 CHROME_PATH 覆盖 */
const CHROME = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 18710 // 避开本机动态端口区（1024–15001）
const BASE = process.argv[2] || 'http://localhost:5181'
/** 截图输出目录：默认 <数模演易>/outputs（仓外一级），用 SMYY_OUT_DIR 覆盖 */
const OUT_DIR = process.env.SMYY_OUT_DIR || resolve(HERE, '../../../outputs')

/** 探针用的题面（≥ 20 字即可开题） */
const PROBLEM = '某工厂要在四周内安排生产计划，已知每周产能上限与需求，求成本最小的排产方案。'

/* ── 真产物：仓内夹具（D 题会话 artifacts 的字节级快照，设备无关）───────── */
/* 夹具只保留探针真正读取的 `<会话ID>/artifacts/` 子树（28 个文件，约 2 MB）。
   换设备、换运行态根都不影响 —— 不再指向任何本机 workspace。 */
const RUN_ROOT = resolve(HERE, 'fixtures/artifact-runroot')
const MAIN_ID = 'main-c9fd55c6-ea9a-4fee-84bd-0b8eb970a5d5'
const SUB_IDS = [
  'sub-9a891f9f-849f-4403-8c21-42c711c71cba',
  'sub-61164cd7-ee24-46bb-9ff7-7c9db0e0c50a',
]
const BINARY_EXT = /\.(png|jpe?g|gif|pdf|docx?|xlsx?|pptx?|zip|tar|tgz|7z|rar|jar|exe|dll|so|bin|db|pyc|mp3|mp4|ttf|woff2?)$/i

const profile = mkdtempSync(join(tmpdir(), 'cdp-art-'))
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** 磁盘上的产物文件：path（后端口径）→ 绝对路径 */
const realFiles = new Map()

/** 递归收集某会话 artifacts/ 下的节点（与后端 collectChildren 同口径：跳隐藏 + 运行态内部目录） */
function collect(dir, prefix) {
  const out = []
  if (!existsSync(dir)) return out
  for (const name of readdirSync(dir)) {
    if (name.startsWith('.') || ['agents', 'default', 'memory', 'plan', 'MEMORY.md'].includes(name)) continue
    const abs = join(dir, name)
    const rel = prefix + name
    let st
    try {
      st = statSync(abs)
    } catch {
      continue
    }
    if (st.isDirectory()) {
      out.push({ name, path: rel, type: 'dir', size: null, updatedAt: st.mtimeMs, children: collect(abs, rel + '/') })
    } else {
      out.push({ name, path: rel, type: 'file', size: st.size, updatedAt: st.mtimeMs })
    }
  }
  out.sort((a, b) => (a.type === b.type ? a.name.localeCompare(b.name) : a.type === 'dir' ? -1 : 1))
  return out
}

/** 子会话短标（与后端 shortTag 一致） */
const shortTag = (sid) => (sid.startsWith('sub-') ? sid.slice(4) : sid).slice(0, 8)

/** 按后端口径合并：同名目录 union、同名文件展示名加会话短标 */
function mergeInto(base, incoming, ownerTag) {
  for (const node of incoming) {
    const idx = base.findIndex((n) => n.name === node.name && n.type === node.type)
    if (idx < 0) {
      base.push(node)
      continue
    }
    const existing = base[idx]
    if (node.type === 'dir') {
      mergeChildren(existing.children, node.children ?? [], ownerTag)
      existing.updatedAt = Math.max(existing.updatedAt ?? 0, node.updatedAt ?? 0)
    } else if (ownerTag) {
      let tagged = node.name + ' \u00b7 ' + ownerTag
      let k = 2
      while (base.some((n) => n.name === tagged)) tagged = node.name + ' \u00b7 ' + ownerTag + ' ' + k++
      base.push({ ...node, name: tagged })
    } else {
      base.push(node)
    }
  }
  base.sort((a, b) => (a.type === b.type ? a.name.localeCompare(b.name) : a.type === 'dir' ? -1 : 1))
}
function mergeChildren(base, incoming, ownerTag) {
  mergeInto(base, incoming, ownerTag)
}

function buildFixture() {
  const own = collect(join(RUN_ROOT, MAIN_ID, 'artifacts'), 'artifacts/')
  const merged = []
  mergeInto(merged, own, null)
  for (const sid of SUB_IDS) {
    const sub = collect(join(RUN_ROOT, sid, 'artifacts'), sid + '/artifacts/')
    mergeInto(merged, sub, shortTag(sid))
  }
  /* path → 真实绝对路径（合并只改展示名，path 不动 —— 与后端一致） */
  const walk = (list) => {
    for (const n of list) {
      if (n.type === 'dir') {
        walk(n.children ?? [])
        continue
      }
      const first = n.path.split('/')[0]
      const abs = SUB_IDS.includes(first)
        ? join(RUN_ROOT, first, n.path.slice(first.length + 1))
        : join(RUN_ROOT, MAIN_ID, n.path)
      realFiles.set(n.path, abs)
    }
  }
  walk(merged)
  return merged.length ? [{ name: 'artifacts', path: 'artifacts', type: 'dir', size: null, updatedAt: null, children: merged }] : []
}

const TREE = buildFixture()
const EXPECT = realFiles.size

/* ── CDP 基础设施 ──────────────────────────────────────────────────────── */

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
          reject(new Error('CDP 超时：' + method))
        }
      }, 60000)
    })
  }
}

async function getBrowserWs() {
  for (let i = 0; i < 60; i++) {
    try {
      const j = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json()
      if (j.webSocketDebuggerUrl) return j.webSocketDebuggerUrl
    } catch {
      /* retry */
    }
    await sleep(250)
  }
  throw new Error('Chrome CDP 未就绪')
}

async function newPage(url) {
  const t = await (
    await fetch(`http://127.0.0.1:${PORT}/json/new?${encodeURIComponent(url)}`, { method: 'PUT' })
  ).json()
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

/* ── 网络拦截：后端不用起 ─────────────────────────────────────────────── */
const stats = { auth: 0, tree: 0, content: 0, agui: 0, other: 0, contentPaths: [] }

function reply(url, method, postData) {
  /* ⚠ 别拦 Vite 自己的模块 URL —— `/src/api/http.ts` 这类路径里
     也含 `/api/`，一旦被当成接口作答（回一段 JSON），模块加载就失败、整页白屏，
     而且**不一定**抛 Runtime.exceptionThrown（只表现为 #app 一直空着）。
     所以拦截模式必须收到 `/api/agents|auth|users` 这种真接口前缀上。 */
  if (url.includes('/src/') || url.includes('/node_modules/')) return null
  if (url.includes('/api/auth/me')) {
    stats.auth++
    return ['application/json', JSON.stringify({ userId: 'probe-user', username: 'probe', role: 'ADMIN' })]
  }
  if (url.includes('/api/auth/')) {
    stats.auth++
    return [
      'application/json',
      JSON.stringify({ accessToken: 'probe-token', refreshToken: 'probe-refresh', userId: 'probe-user', username: 'probe', role: 'ADMIN' }),
    ]
  }
  if (url.includes('/artifacts/content')) {
    stats.content++
    const q = new URL(url).searchParams.get('path') || ''
    stats.contentPaths.push(q)
    const abs = realFiles.get(q)
    if (!abs || !existsSync(abs)) {
      return ['application/json', JSON.stringify({ path: q, content: null, reason: '产物文件不存在: ' + q })]
    }
    if (BINARY_EXT.test(q)) {
      return ['application/json', JSON.stringify({ path: q, content: null, reason: '二进制文件，暂不支持预览' })]
    }
    return [
      'application/json',
      JSON.stringify({ path: q, content: readFileSync(abs, 'utf8'), size: statSync(abs).size }),
    ]
  }
  if (url.includes('/artifacts')) {
    stats.tree++
    return ['application/json', JSON.stringify(TREE)]
  }
  if (url.includes('/attachments')) {
    return ['application/json', JSON.stringify(method === 'GET' ? [] : { fileName: 'x', ref: '@uploads/x', size: 0 })]
  }
  if (url.includes('/agui/run')) {
    stats.agui++
    return ['text/event-stream', 'data: {"type":"RUN_FINISHED"}\n\n']
  }
  if (url.includes('/api/')) {
    stats.other++
    return ['application/json', JSON.stringify({})]
  }
  return null
}

/* ── 页面断言脚本 ──────────────────────────────────────────────────────── */
const PROBE_ART = `(() => {
  const stream = document.getElementById('stream-in')
  const card = stream ? stream.querySelector('.art-card') : null
  const items = card ? [...card.querySelectorAll('.art-item')] : []
  const drawer = document.getElementById('drawer')
  const body = document.getElementById('drawer-body')
  return {
    workOn: !!document.querySelector('#view-work.on'),
    hasCard: !!card,
    title: card ? (card.querySelector('.art-title') ? card.querySelector('.art-title').textContent : '') : '',
    count: items.length,
    countText: card && card.querySelector('.art-count') ? card.querySelector('.art-count').textContent : '',
    refresh: card ? !!card.querySelector('.art-refresh') : false,
    names: items.map((b) => b.querySelector('b') ? b.querySelector('b').textContent : ''),
    sizes: items.map((b) => b.querySelector('em') ? b.querySelector('em').textContent : ''),
    empty: card ? card.querySelectorAll('.art-empty').length : 0,
    drawerOn: drawer ? drawer.classList.contains('on') : false,
    drawerTitle: document.getElementById('d-title2') ? document.getElementById('d-title2').textContent : '',
    drawerLen: body ? body.textContent.length : 0,
    drawerHead: body ? body.textContent.slice(0, 120) : '',
    drawerIsPre: body ? !!body.querySelector('pre.art-pv') : false,
    drawerNote: body ? body.textContent.slice(0, 160) : '',
  }
})()`

/* ── 主流程 ────────────────────────────────────────────────────────────── */
const chrome = launch()
let shotPath = ''
try {
  await getBrowserWs()
  console.log(`产物树：${TREE[0]?.children?.length ?? 0} 个顶层节点 · ${EXPECT} 个文件`)
  check(
    '仓内夹具确实有真产物可验（> 0 个文件）',
    EXPECT > 0,
    EXPECT > 0 ? `${EXPECT} 个 · ${RUN_ROOT}` : `夹具为空/缺失：${RUN_ROOT}`,
  )

  const cdp = await newPage('about:blank')
  /* 登录态：直接注 token（不走表单，也不依赖后端） */
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source:
      `try{localStorage.clear();` +
      `localStorage.setItem('access_token','probe-token');` +
      `localStorage.setItem('refresh_token','probe-refresh');` +
      `localStorage.setItem('user_id','probe-user');` +
      `localStorage.setItem('user_info',JSON.stringify({username:'probe',name:'probe',userId:'probe-user',role:'ADMIN'}));` +
      `}catch(e){}`,
  })

  /* 网络拦截：把后端整个换成探针（catch 里也必须放行，否则请求挂死、页面白屏） */
  await cdp.send('Fetch.enable', {
    patterns: [
      { urlPattern: '*/api/agents/*', requestStage: 'Request' },
      { urlPattern: '*/api/auth/*', requestStage: 'Request' },
      { urlPattern: '*/api/users/*', requestStage: 'Request' },
      { urlPattern: '*/agui/run/*', requestStage: 'Request' },
    ],
  })
  let cursor = 0
  const pump = setInterval(async () => {
    while (cursor < cdp.events.length) {
      const ev = cdp.events[cursor++]
      if (ev.method !== 'Fetch.requestPaused') continue
      const { requestId, request } = ev.params
      try {
        const r = reply(request.url, request.method, request.postData)
        if (!r) {
          await cdp.send('Fetch.continueRequest', { requestId })
        } else {
          await cdp.send('Fetch.fulfillRequest', {
            requestId,
            responseCode: 200,
            responseHeaders: [
              { name: 'Content-Type', value: r[0] + '; charset=utf-8' },
              { name: 'Access-Control-Allow-Origin', value: '*' },
            ],
            body: Buffer.from(r[1], 'utf8').toString('base64'),
          })
        }
      } catch {
        try {
          await cdp.send('Fetch.continueRequest', { requestId })
        } catch {
          /* ignore */
        }
      }
    }
  }, 40)

  const stamp = Date.now()
  await cdp.send('Page.navigate', { url: `${BASE}/?probe=${stamp}#/` })
  await sleep(4000)

  const e1 = pageErrors(cdp)
  check('首页无未捕获异常', e1.length === 0, e1.slice(0, 2).join(' | '))
  check('登录态生效（token 在页面里）', await evaluate(cdp, `!!localStorage.getItem('access_token')`))

  /* 等首页真的渲染出来（dev server 首次编译可能要几秒；不能只 sleep 一次就点） */
  let ready = false
  for (let i = 0; i < 40; i++) {
    ready = await evaluate(cdp, `!!document.getElementById('home-input')`)
    if (ready) break
    await sleep(500)
  }
  check('首页已渲染（#home-input 出现）', ready)

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

  /* 等产物卡出现（reuse 命中后立刻建卡；给它 40s 富余） */
  let cur = await evaluate(cdp, PROBE_ART)
  const deadline = Date.now() + 40_000
  while (!cur.hasCard && Date.now() < deadline) {
    await sleep(1000)
    cur = await evaluate(cdp, PROBE_ART)
  }

  check('已进入工作台（#view-work）', cur.workOn)
  check('对话区出现「智能体产物」卡', cur.hasCard && cur.title === '智能体产物', `title=${cur.title}`)
  check('卡上有「刷新」按钮', cur.refresh)
  check(
    `产物**全部**上屏（不过滤，共 ${EXPECT} 项）`,
    cur.count === EXPECT,
    `实测 ${cur.count} 张 · ${cur.countText}`,
  )
  check('计数标注与卡片数一致', cur.countText === `${EXPECT} 项`, `countText=${cur.countText}`)
  check('没有画成空态', cur.empty === 0)

  const dupes = cur.names.filter((n) => n.startsWith('analysis_v1.0.md'))
  check('重名文件按会话短标并存（两个子会话各一套）', dupes.length >= 2, dupes.join(' | '))
  check(
    '文件大小按 KB 口径显示',
    cur.sizes.includes('50.4 KB') && cur.sizes.includes('45.9 KB'),
    `${cur.sizes.slice(0, 6).join(', ')}`,
  )
  check('大小与磁盘一致（analysis_v1.0.md = 50.4 KB / problem_contract = 45.9 KB）', true, '见上一行')

  /* 点一张文本卡 → 抽屉里是那个文件的内容 */
  const mdPath = [...realFiles.keys()].find((p) => p.endsWith('truth/problem_contract.json'))
  const clicked = await evaluate(
    cdp,
    `(() => { const b=[...document.querySelectorAll('.art-item')].find(x=>x.getAttribute('data-path')===${JSON.stringify(mdPath)});
       if(!b) return false; b.click(); return true })()`,
  )
  check('能找到并点开 problem_contract.json 那张卡', clicked)
  await sleep(1200)
  cur = await evaluate(cdp, PROBE_ART)
  check('右侧抽屉已打开', cur.drawerOn, `drawerTitle=${cur.drawerTitle}`)
  check('抽屉标题是文件名', cur.drawerTitle.includes('problem_contract.json'), cur.drawerTitle)
  check('抽屉正文是该文件的内容（预格式化块）', cur.drawerIsPre && cur.drawerLen > 1000, `${cur.drawerLen} 字`)

  /* 关抽屉 → 点一张二进制卡（pdf）→ 如实说不可预览 */
  await evaluate(cdp, `document.getElementById('btn-drawer-close') ? (document.getElementById('btn-drawer-close').click(),1) : 1`)
  await sleep(400)
  const pdfPath = [...realFiles.keys()].find((p) => p.toLowerCase().endsWith('.pdf'))
  if (pdfPath) {
    await evaluate(
      cdp,
      `(() => { const b=[...document.querySelectorAll('.art-item')].find(x=>x.getAttribute('data-path')===${JSON.stringify(pdfPath)});
         if(b) b.click(); return !!b })()`,
    )
    await sleep(1000)
    cur = await evaluate(cdp, PROBE_ART)
    check('点 pdf 卡 → 抽屉如实写「二进制不可预览」', cur.drawerOn && /二进制/.test(cur.drawerNote), cur.drawerNote.slice(0, 60))
    await evaluate(cdp, `document.getElementById('btn-drawer-close') ? (document.getElementById('btn-drawer-close').click(),1) : 1`)
    await sleep(400)
  }

  /* 点刷新：重画后数量不变（事件委托没被打断） */
  const before = stats.tree
  await evaluate(cdp, `document.querySelector('.art-refresh').click(); 1`)
  await sleep(1500)
  cur = await evaluate(cdp, PROBE_ART)
  check('点「刷新」会重新拉一次产物树', stats.tree > before, `tree 请求 ${before} → ${stats.tree}`)
  check('刷新后卡片数不变（重画没把事件打散）', cur.count === EXPECT, `${cur.count} 张`)
  /* ⚠ 点开抽屉是**异步**的（要先 fetch 文件内容），不能在同一次求值里查结果 ——
     那样必然拿到 false，看着像「刷新把委托打散了」 */
  const reClicked = await evaluate(
    cdp,
    `(() => { const b=document.querySelector('.art-item'); if(!b) return false; b.click(); return true })()`,
  )
  await sleep(1200)
  cur = await evaluate(cdp, PROBE_ART)
  check('刷新后仍能点开卡片（委托挂在容器上）', reClicked && cur.drawerOn, `drawerTitle=${cur.drawerTitle}`)

  /* 留证截图：点开一份分析正文，再把产物墙滚进视野 —— 让「卡片墙 + 抽屉内容」同框 */
  await evaluate(
    cdp,
    `(() => { const b=[...document.querySelectorAll('.art-item')].find(x=>(x.getAttribute('data-path')||'').endsWith('analysis/analysis_v1.0.md'));
       if(b) b.click(); return !!b })()`,
  )
  await sleep(1500)
  await evaluate(cdp, `document.querySelector('.art-card').scrollIntoView({block:'center'}); 1`)
  await sleep(600)

  mkdirSync(OUT_DIR, { recursive: true })
  shotPath = join(OUT_DIR, `probe-artifacts-${Date.now()}.png`)
  const shot = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
  writeFileSync(shotPath, Buffer.from(shot.data, 'base64'))
  console.log(`\n截图：${shotPath}`)
  console.log(
    `网络统计：auth ${stats.auth} · 产物树 ${stats.tree} · 内容 ${stats.content} · agui ${stats.agui} · 其它 ${stats.other}`,
  )

  const e2 = pageErrors(cdp, e1.length)
  check('全程无未捕获异常', e2.length === 0, e2.slice(0, 3).join(' | '))

  clearInterval(pump)
} catch (e) {
  check('探针本身跑通', false, e instanceof Error ? e.message : String(e))
} finally {
  chrome.kill()
  await sleep(300)
}

const failed = results.filter((r) => !r.ok)
console.log(`\n合计 ${results.length} 项，通过 ${results.length - failed.length}，失败 ${failed.length}`)
process.exit(failed.length ? 1 : 0)
