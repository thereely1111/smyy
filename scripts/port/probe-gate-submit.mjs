/**
 * 闸门「转录重建路径」提交回执探针（CDP，无第三方依赖）
 *
 * 为什么单独一个探针（2026-10-16）：用户报「前端确定闸门之后，后端接收不到」。
 * 根因是**重建路径**上的一条 bail —— `AdoptedGate.runId` 恒为空串（平台
 * form-interrupts 登记表的 record 里根本没这个字段），`resumeAdopted` 于是
 * 「没 runId 就不发了」直接返回：**一个 HTTP 包都没发**，而卡片已经被同步定格成
 * 「已提交，智能体继续处理…」。这套缺陷 probe-gate.mjs 抓不到 —— 那边走的是
 * **SSE 路径**（前端手里有 runId，不需要重建）。
 *
 * 本探针验四件事：
 *   1. 重建出来的闸门（`state.gate.adopt`）点提交，**必须真的发出** `POST /agui/run`
 *      且带 `resume[{interruptId, status:'resolved', payload:{output}}]`；
 *   2. 那个请求的 `runId` 是前端**自造**的（`resume-…`）—— 证明「没 runId 就 bail」
 *      这条老逻辑真的被拆了；
 *   3. 后端在流里报 `must cover all open interrupts` 时，卡片**不定格**、如实写
 *      「没提交成功」并恢复可交互（P1 的回执回写）；
 *   4. 回执 ok 的那条路（模块级调用）返回 `{ok:true}`，且第二次提交也真的发出去了；
 *   5. 右栏那张**已提交定格**的卡不许霸占槽位 —— 新闸门来了必须顶掉它（2026-10-16
 *      用户报「智能体说已重渲染闸门，右栏没反应」，就是被这张定格卡挡住的）。
 *
 * 造场景的办法：拦浏览器网络层（`Fetch.enable`）——
 *   · `/agui/run`：喂一段「正常收尾」的 SSE（拦下真模型调用）；
 *   · `/transcript`：喂一条**没有配对 tool_result** 的 `tool_call:ui_form_advanced`
 *     （重建判据第 1、3 条）；
 *   · `/form-interrupts`：喂一条登记（**故意不带 runId**，与后端 record 一致）。
 * 站点代码一行都不动，走的全是真实链路。
 *
 * 运行：node scripts/port/probe-gate-submit.mjs [baseUrl] [user] [pass]
 */

import { spawn } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 18719 // 避开本机动态端口区（1024–15001），也别撞 probe-gate 的 18718
const BASE = process.argv[2] || 'http://localhost:5181'
const USER = process.argv[3] || 'smyui0929test'
const PASS = process.argv[4] || 'Test0929a'
const OUT_DIR = process.env.SMYY_OUT_DIR || resolve(import.meta.dirname, '../../../outputs')

const PROBLEM = '某物流公司要在三座城市之间安排运输路线，已知各城市间的距离和每辆车的载重上限，求总里程最小的调度方案。'

/* ── 伪造的闸门：两张（成功场景 / 被拒场景），toolCallId 不同才不会互相顶掉 ──── */

const GATE_INPUT = {
  title: '动手之前先定两个口径',
  description: '这两处写法会影响后面所有的工作，请你拍板。',
  submitLabel: '确认并继续',
  pages: [
    {
      key: 'p1',
      title: '建模口径',
      items: [
        { key: 'route', label: '模型路线', kind: 'radio-group', options: ['微分方程模型', '离散优化模型'], required: true },
        { key: 'note', label: '补充说明', kind: 'textarea', required: false },
      ],
    },
  ],
}

/** 每一张闸门：转录里的 toolCallId、登记表里的 interruptId、以及要不要被拒 */
const SCENES = {
  a: { toolCallId: 'tc-probe-a', interruptId: 'reply-probe-a:tc-probe-a', reject: true },
  b: { toolCallId: 'tc-probe-b', interruptId: 'reply-probe-b:tc-probe-b', reject: false },
  /* c/d：验「定格卡霸占右栏」——c 先被 DOM 提交（卡定格），d 是新冒出来的闸门 */
  c: { toolCallId: 'tc-probe-c', interruptId: 'reply-probe-c:tc-probe-c', reject: false },
  d: { toolCallId: 'tc-probe-d', interruptId: 'reply-probe-d:tc-probe-d', reject: false },
}

/** 转录：一条 tool_call:ui_form_advanced，**没有**配对 tool_result（＝还没人答过） */
function transcriptFor(scene) {
  return {
    messages: [
      { id: 'msg-u1', role: 'user', content: [{ type: 'text', text: PROBLEM }] },
      {
        id: 'msg-a1',
        role: 'assistant',
        content: [
          { type: 'text', text: '有两个口径要你先定。' },
          { type: 'tool_call', id: scene.toolCallId, name: 'ui_form_advanced', input: GATE_INPUT, state: 'allowed' },
        ],
      },
    ],
  }
}

/** 登记表：字段与后端 record 一致 —— 注意**没有 runId**（这正是故障根因） */
function registeredFor(scene) {
  return [
    {
      interruptId: scene.interruptId,
      replyId: scene.interruptId.split(':')[0],
      toolCallId: scene.toolCallId,
      toolName: 'ui_form_advanced',
      toolInput: JSON.stringify(GATE_INPUT),
      createdAt: new Date().toISOString(),
    },
  ]
}

const sseLine = (o) => 'data: ' + JSON.stringify(o) + '\n\n'
const sse = (list) => list.map(sseLine).join('')

/** 后端没起时的占位 token：形状像 JWT 就行，前端只做存储与透传 */
function fakeJwt() {
  const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url')
  return `${b64({ alg: 'HS256', typ: 'JWT' })}.${b64({ sub: 'probe-user', exp: Math.floor(Date.now() / 1000) + 86400 })}.probe`
}

/** 首轮 run：正常收尾（骗过 analysisBoot，让它进入「跟随」阶段 —— 那时的槽位是空的） */
function okStream(threadId, runId) {
  return sse([
    { type: 'RUN_STARTED', threadId, runId },
    { type: 'TEXT_MESSAGE_START', messageId: 'm-probe-1', role: 'assistant' },
    { type: 'TEXT_MESSAGE_CONTENT', messageId: 'm-probe-1', delta: '收到，先把题面过一遍。' },
    { type: 'TEXT_MESSAGE_END', messageId: 'm-probe-1' },
    { type: 'RUN_FINISHED', threadId, runId, outcome: { type: 'success' } },
  ])
}

/** 被拒流：平台把「resume 集合不匹配」写在流里（HTTP 仍然 200） */
/**
 * 被拒流：平台把「resume 集合不匹配」写在流里（HTTP 仍然 200）。
 *
 * ⚠ 没有 RUN_STARTED —— 契约拒绝发生在 run 开始**之前**。2026-10-19 实证：
 * `agui_events` 里被拒的 run_id 一条事件都没有，受理的那条从 RUN_STARTED 起。
 * 这里若按「先 STARTED 再 ERROR」造假，就会把「第一个非错误事件＝已受理」这条
 * 判据验收成假绿（前端会先把卡片定格，再收到错误）。
 */
function rejectStream(threadId, runId) {
  return sse([
    {
      type: 'RUN_ERROR',
      threadId,
      runId,
      error: 'RunAgentInput.resume must cover all open interrupts. missing=[zz-probe-ghost:tc-ghost], unknown=[]',
    },
    { type: 'RUN_FINISHED', threadId, runId, outcome: { type: 'error' } },
  ])
}

/* ── CDP 骨架（与 probe-gate.mjs 同构）────────────────────────────────────── */

const profile = mkdtempSync(join(tmpdir(), 'cdp-gate-submit-'))
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
  }
  return out
}

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}
function skip(name, why) {
  results.push({ name, ok: true, skipped: true, detail: why })
  console.log(`SKIP  ${name}  — ${why}`)
}

/** 右栏那张闸门卡的状态快照 */
const GATE_SNAPSHOT = `(() => {
  const card = document.querySelector('#rail-gate-list .gate-card') || document.querySelector('.gate-card')
  const note = card ? card.querySelector('.gate-note') : null
  const btns = card ? [...card.querySelectorAll('.gate-acts button')] : []
  return {
    hasCard: !!card,
    gateId: card ? card.getAttribute('data-gate') : '',
    /* 右栏里一共有几张卡：>1 说明旧的没被摘干净（dropGateCard 漏了外壳） */
    allIds: [...document.querySelectorAll('.gate-card')].map((c) => c.getAttribute('data-gate')),
    done: card ? card.classList.contains('done') : false,
    locked: card ? card.getAttribute('data-locked') === '1' : false,
    busy: card ? card.getAttribute('data-busy') === '1' : false,
    disabled: btns.length ? btns.every((b) => b.disabled) : false,
    note: note ? (note.textContent || '').trim() : '',
  }
})()`

/* ── 主流程 ───────────────────────────────────────────────────────────────── */

const chrome = launch()
/** 拦下来的 `/agui/run` 请求（按到达顺序） */
const hits = []
let cdpRef = null
/** 当前生效的场景（提交第 1 次后切成 b） */
let scene = SCENES.a
let submitCount = 0
/** 被拒场景：第 2 条带 resume 的 run 回错误流 */
let rejectNext = true
let shotPath = ''

function fulfill(params, body, type) {
  return cdpRef.send('Fetch.fulfillRequest', {
    requestId: params.requestId,
    responseCode: 200,
    responseHeaders: [
      { name: 'Content-Type', value: type },
      { name: 'Cache-Control', value: 'no-cache' },
    ],
    body: Buffer.from(body, 'utf8').toString('base64'),
  })
}

function onRequestPaused(params) {
  const url = params.request.url
  try {
    if (url.includes('/transcript')) {
      void fulfill(params, JSON.stringify(transcriptFor(scene)), 'application/json').catch(() => {})
      return
    }
    if (url.includes('/form-interrupts')) {
      void fulfill(params, JSON.stringify(registeredFor(scene)), 'application/json').catch(() => {})
      return
    }
    if (url.includes('/agui/run')) {
      let body = {}
      try {
        body = JSON.parse(params.request.postData || '{}')
      } catch {
        /* 非 JSON 按空体处理 */
      }
      const resume = Array.isArray(body.resume) ? body.resume : []
      const rec = {
        threadId: typeof body.threadId === 'string' ? body.threadId : '',
        runId: typeof body.runId === 'string' ? body.runId : '',
        resume,
        submittedAt: Date.now(),
      }
      hits.push(rec)
      /* 第一段：不带 resume 的首轮 run；带 resume 的按场景回「被拒」或「正常收尾」 */
      const rejected = resume.length && rejectNext
      const stream = resume.length
        ? rejected
          ? rejectStream(rec.threadId, rec.runId)
          : okStream(rec.threadId, rec.runId)
        : okStream(rec.threadId, rec.runId)
      /* 延迟 800ms 再回 —— 面板上要抓得到「提交中…」那一帧（gateBusy 的可见证据） */
      setTimeout(() => {
        void fulfill(params, stream, 'text/event-stream').catch(() => {})
      }, resume.length ? 800 : 0)
      return
    }
    void cdpRef.send('Fetch.continueRequest', { requestId: params.requestId }).catch(() => {})
  } catch {
    /* 拦截器出错也必须放行，否则请求挂死、页面白屏得莫名其妙 */
    void cdpRef.send('Fetch.continueRequest', { requestId: params.requestId }).catch(() => {})
  }
}

/** 触发守望层的补扫（rebuildGateFor）：从隐藏切回可见 */
const POKE_VISIBILITY = `(() => {
  try { Object.defineProperty(document, 'hidden', { get: () => false, configurable: true }) } catch (e) {}
  document.dispatchEvent(new Event('visibilitychange'))
  return true
})()`

/** 取页面真实加载的那份 analysis 模块（dev server 会给导入方加 HMR 时间戳，
    裸路径会拿到**另一个实例**，那样这里读到/写到的都不是页面那份） */
const IMPORT_ANALYSIS = `(async () => {
  const names = performance.getEntriesByType('resource').map((e) => e.name)
  const hits = names.filter((n) => n.includes('/src/stores/analysis.ts'))
  const url = hits.length ? hits[hits.length - 1] : '/src/stores/analysis.ts'
  const m = await import(url)
  window.__probeAnalysis = m
  return { url, hasGateSubmit: typeof m.gateSubmit === 'function', gate: typeof m.gateOf === 'function' ? !!m.gateOf() : null }
})()`

try {
  await getBrowserWs()

  /* 1. 登录拿 token。后端没起时退化成**占位 token** —— 本探针要验的接口
        （run / transcript / form-interrupts）全在网络层被拦，页面只要进得去工作台就够了 */
  const loginRes = await fetch(BASE + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER, password: PASS }),
  }).catch(() => null)
  const tokens = (await loginRes?.json().catch(() => ({}))) || {}
  const canLogin = !!loginRes?.ok && !!tokens.accessToken
  if (canLogin) check('登录接口返回 token', true, `HTTP ${loginRes.status}`)
  else {
    skip('登录接口返回 token', `HTTP ${loginRes?.status ?? '未连上'}（后端没起？改用占位 token，只验前端逻辑）`)
    tokens.accessToken = fakeJwt()
    tokens.userId = 'probe-user'
    tokens.refreshToken = ''
  }

  /* 2. 注入 token（导航前）。顺带清 localStorage —— hash 路由的同文档导航会让
        reuse() 命中旧产物、一次 run 都不发，看着像「闸门压根没触发」 */
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

  /* 3. 拦三类请求：run（伪造流）、transcript 与 form-interrupts（伪造「有挂起闸门」） */
  await cdp.send('Fetch.enable', {
    patterns: [
      { urlPattern: '*agui/run*', requestStage: 'Request' },
      { urlPattern: '*transcript*', requestStage: 'Request' },
      { urlPattern: '*form-interrupts*', requestStage: 'Request' },
    ],
  })

  const stamp = Date.now()
  await cdp.send('Page.navigate', { url: `${BASE}/?probe=${stamp}#/` })
  await sleep(4000)
  check('token 已注入页面', await evaluate(cdp, `!!localStorage.getItem('access_token')`))
  const e1 = pageErrors(cdp)
  check('首页无未捕获异常', e1.length === 0, e1.slice(0, 2).join(' | '))

  /* 4. 首页写题面 → 开始演易（首轮 run 被拦下，回正常收尾流 → 进入「跟随」阶段） */
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

  /* 5. 等进入工作台（首轮 run 的流要收尾，会话才落地、watchSession 才登记） */
  let workOn = false
  const workDeadline = Date.now() + 30_000
  while (Date.now() < workDeadline) {
    workOn = await evaluate(cdp, `!!document.querySelector('#view-work.on')`)
    if (workOn && hits.length) break
    await sleep(700)
  }
  check('已进入工作台（#view-work）', workOn)
  check('首轮 run 已发出（被拦下，未打真模型）', hits.length >= 1, `实得 ${hits.length} 条`)

  /* 6. 让守望层补扫 → 重建出闸门（state.gate.adopt；runId 空 —— 后端登记表就没这个字段） */
  await evaluate(cdp, POKE_VISIBILITY)

  let card = null
  const cardDeadline = Date.now() + 20_000
  while (Date.now() < cardDeadline) {
    card = await evaluate(cdp, GATE_SNAPSHOT)
    if (card.hasCard) break
    await sleep(700)
  }
  check(
    '右栏出现**转录重建**的闸门卡（.gate-card）',
    !!(card && card.hasCard),
    card ? `gateId=${card.gateId || '(空)'}` : '没等到卡片',
  )
  if (card && card.hasCard) {
    check('卡片挂的是伪造的那条中断 id', card.gateId === SCENES.a.interruptId, `实得 ${card.gateId}`)
    check('重建出来的闸门还没被定格', !card.done && !card.locked)
  }

  /* 7. 场景 a：填必填项 → 提交。后端回**被拒流**（平台把契约拒绝写在流里）
        这一步同时验两件事：
          · P0 —— 提交真的发出去了（以前 runId 空 ⇒ 一个包都不发）
          · P1 —— 失败回执必须回写卡片（不定格、如实报错、恢复可交互） */
  let beforeSubmit = hits.length
  let clicked = { ok: false, why: 'no card' }
  if (card && card.hasCard) {
    clicked = await evaluate(
      cdp,
      `(() => {
         const card = document.querySelector('.gate-card')
         if (!card) return { ok: false, why: 'no card' }
         const radio = card.querySelector('input[type=radio][data-key="route"][value="离散优化模型"]')
         if (!radio) return { ok: false, why: 'no radio' }
         radio.checked = true
         radio.dispatchEvent(new Event('change', { bubbles: true }))
         const ta = card.querySelector('textarea[data-key="note"]')
         if (ta) ta.value = '口径按自然日算。'
         card.querySelector('button[data-act="submit"]').click()
         return { ok: true }
       })()`,
    )
    check('可把选项填进重建出来的闸门卡', clicked.ok, clicked.why || '')
  } else {
    /* 卡片没画出来（跟随提前收手 → 轮询停了）也不算功能问题：下面走模块级提交，
       照样能验「提交必须发出去」。 */
    skip('DOM 提交（重建闸门卡未在窗口内出现）', '改走模块级 gateSubmit()')
    await evaluate(cdp, IMPORT_ANALYSIS)
    const mod = await evaluate(
      cdp,
      `(async () => {
         const m = window.__probeAnalysis
         if (!m || typeof m.gateSubmit !== 'function') return { ok: false, why: 'no gateSubmit' }
         if (!m.gateOf()) return { ok: false, why: 'gate 槽位是空的（重建没成功）' }
         window.__probeResult = null
         m.gateSubmit({ route: '离散优化模型', note: '口径按自然日算。' }).then((r) => { window.__probeResult = r })
         return { ok: true }
       })()`,
    )
    check('模块级 gateSubmit 可调用（重建闸门在槽位里）', mod.ok, mod.why || '')
    clicked = mod
  }

  /* 7a. 「提交中…」这一帧：gateBusy 的可见证据 */
  await sleep(250)
  const busy = await evaluate(cdp, GATE_SNAPSHOT)
  if (card && card.hasCard) {
    check('点下去先显示「提交中…」并锁住按钮（不是立刻假装已提交）', busy.busy && busy.disabled, `busy=${busy.busy} disabled=${busy.disabled}`)
    check('「提交中」时卡片**没有**定格', !busy.done && !busy.locked)
  }

  /* 7b. 【P0 核心】提交必须真的发出请求，且 resume 形状正确 */
  await sleep(2500)
  const gateHits = hits.filter((h) => h.resume.length)
  check('提交发出了带 resume 的 /agui/run 请求（P0：以前一个包都不发）', gateHits.length >= 1, `实得 ${gateHits.length} 条`)
  if (gateHits.length) {
    const r0 = gateHits[0]
    const first = r0.resume[0] || {}
    check('resume.interruptId 与重建出来的中断一致', first.interruptId === SCENES.a.interruptId, `实得 ${first.interruptId || '(空)'}`)
    check('resume.status 是 resolved', first.status === 'resolved', `实得 ${first.status || '(空)'}`)
    check('payload.output 是 JSON 字符串（平台约定的回传形状）', typeof first.payload?.output === 'string', typeof first.payload?.output)
    let payload = null
    try {
      payload = JSON.parse(String(first.payload?.output ?? '{}'))
    } catch {
      payload = null
    }
    check('用户填的值原样回传', payload?.route === '离散优化模型', String(payload?.route))
    check(
      '这条请求的 runId 是前端自造的（resume- 前缀）—— 证明「没 runId 就 bail」被拆了',
      typeof r0.runId === 'string' && r0.runId.startsWith('resume-'),
      `runId=${r0.runId || '(空)'}`,
    )
  }

  /* 7c. 【P1】被拒流 → 卡片不定格、如实报错、恢复可交互 */
  if (card && card.hasCard) {
    const after = await evaluate(cdp, GATE_SNAPSHOT)
    check('后端拒绝时卡片**没有**定格成已提交', !after.done && !after.locked, `done=${after.done} locked=${after.locked}`)
    check('失败后卡片还在（没被轮询收掉）', after.hasCard, after.hasCard ? '' : '卡片消失了 —— 用户刚点的东西凭空没了')
    check('卡片如实写出失败原因', after.note.includes('没提交成功'), `note=“${after.note}”`)
    check('按钮恢复可交互（能当场重试）', !after.disabled, `disabled=${after.disabled}`)
  } else {
    await sleep(500)
    const r = await evaluate(cdp, `window.__probeResult || null`)
    check('被拒时模块级回执是 ok:false（P1：失败不再无声）', !!r && r.ok === false, r ? `ok=${r.ok} reason=${String(r.reason || '').slice(0, 60)}` : '没拿到回执')
    check('回执带可读原因', !!r && typeof r.reason === 'string' && r.reason.length > 0, r ? String(r.reason).slice(0, 60) : '')
  }

  /* 8. 换一张闸门（场景 b，toolCallId 不同）走**成功**回执：ok 必须为 true，
        且这一次也真的发出了请求 */
  scene = SCENES.b
  rejectNext = false
  const before = hits.length
  await evaluate(cdp, POKE_VISIBILITY)
  await sleep(4500)
  const modB = await evaluate(cdp, IMPORT_ANALYSIS)
  const okB = await evaluate(
    cdp,
    `(async () => {
       const m = window.__probeAnalysis
       if (!m || !m.gateOf()) return { ok: false, why: '第二张闸门没重建出来' }
       window.__probeResultB = null
       m.gateSubmit({ route: '离散优化模型' }).then((r) => { window.__probeResultB = r })
       await new Promise((r) => setTimeout(r, 1500))
       return { ok: true }
     })()`,
  )
  if (okB.ok) {
    const rb = await evaluate(cdp, `window.__probeResultB || null`)
    check('受理成功时回执 ok:true（卡片才该定格）', !!rb && rb.ok === true, rb ? `ok=${rb.ok}` : '没拿到回执')
    check('第二张闸门同样真的发出了 resume 请求', hits.length > before, `${before} → ${hits.length}`)
  } else {
    skip('成功回执（第二张闸门没重建出来）', okB.why || '')
  }

  /* 9. 【回归】定格卡霸占右栏 → 新闸门必须能顶掉它
        用户报「智能体说重渲染了闸门、等你提交，右栏却没反应」：右栏只剩上一张
        **已提交**的卡（data-locked=1，按钮禁用），新闸门被 syncGate 的早退挡在门外，
        永远画不出来。（2026-10-16 的根因之一，与 runId bail 分开算。） */
  scene = SCENES.c
  rejectNext = false
  {
    /* 进场景 c 前先看一眼模块态：这是「守望层能不能扫」的前置条件 */
    const d = await evaluate(
      cdp,
      `(() => {
         const m = window.__probeAnalysis
         return {
           status: m && typeof m.status === 'function' ? m.status() : '(没拿到模块)',
           hasGate: !!(m && typeof m.gateOf === 'function' && m.gateOf()),
           progress: m && typeof m.progressText === 'function' ? String(m.progressText()).slice(0, 90) : '',
         }
       })()`,
    )
    console.log(`  · 进场景 c 前：status=${d.status} 槽位=${d.hasGate ? '有' : '空'} 进度=${d.progress}`)
  }
  await evaluate(cdp, POKE_VISIBILITY)
  {
    /* 时间线采样：只想看清「c 到底有没有出现过、什么时候被谁换回去」 */
    const seq = []
    for (let i = 0; i < 9; i += 1) {
      await sleep(500)
      const s = await evaluate(
        cdp,
        `(() => {
           const c = document.querySelector('.gate-card')
           const m = window.__probeAnalysis
           return {
             id: c ? c.getAttribute('data-gate') : '',
             locked: c ? c.getAttribute('data-locked') === '1' : false,
             slot: !!(m && typeof m.gateOf === 'function' && m.gateOf()),
           }
         })()`,
      )
      seq.push(`${i}:${s.id ? s.id.replace('reply-probe-', '') : '(无卡)'}${s.locked ? '锁' : ''}${s.slot ? '槽' : ''}`)
    }
    console.log(`  · 场景 c 采样（500ms 一格）：${seq.join(' → ')}`)
  }
  const cardC = await evaluate(cdp, GATE_SNAPSHOT)
  check(
    '新闸门能顶掉上一张卡（右栏换成新中断）',
    cardC.gateId === SCENES.c.interruptId,
    `实得 ${cardC.gateId || '(空)'}；右栏共 ${(cardC.allIds || []).length} 张：[${(cardC.allIds || []).join(' , ')}]`,
  )
  if (cardC.hasCard && cardC.gateId === SCENES.c.interruptId) {
    /* DOM 提交它 → 卡写成「已提交」并锁死（这一步是下一项的**前提**） */
    await evaluate(
      cdp,
      `(() => {
         const card = document.querySelector('.gate-card')
         const radio = card.querySelector('input[type=radio][data-key="route"][value="离散优化模型"]')
         if (radio) { radio.checked = true; radio.dispatchEvent(new Event('change', { bubbles: true })) }
         card.querySelector('button[data-act="submit"]').click()
         return true
       })()`,
    )
    await sleep(2500)
    const frozen = await evaluate(cdp, GATE_SNAPSHOT)
    check('提交成功后卡片定格成「已提交」（locked）', frozen.locked, `locked=${frozen.locked} note=“${frozen.note}”`)

    /* 再冒一张全新闸门：定格卡不许挡路 */
    scene = SCENES.d
    await evaluate(cdp, POKE_VISIBILITY)
    await sleep(4500)
    const next = await evaluate(cdp, GATE_SNAPSHOT)
    check(
      '定格卡不挡新闸门：右栏换成了新中断',
      next.gateId === SCENES.d.interruptId,
      next.gateId === SCENES.c.interruptId ? '还是那张已提交的旧卡（用户点它没反应）' : `实得 ${next.gateId || '(空)'}`,
    )
    check('新卡可交互（没被 data-locked / disabled 锁死）', next.hasCard && !next.locked && !next.disabled, `locked=${next.locked} disabled=${next.disabled}`)
    check(
      '新卡带得出可填字段（画的是表单不是空壳）',
      await evaluate(cdp, `!!document.querySelector('.gate-card input[type=radio], .gate-card textarea, .gate-card select')`),
    )
  } else {
    skip('定格卡顶掉回归（第 3 张闸门没重建出来）', '前一步的条件没成立')
  }

  /* 9b. 把拦下来的每条 run 的 resume 明细打出来 —— 排查「卡片 id 对不上」时，
         光看 DOM 会以为是画错了，其实得看包里到底答的是哪条中断 */
  console.log('  · 拦下的 run 请求：')
  hits.forEach((h, i) => {
    const ids = h.resume.map((r) => `${r.interruptId}:${r.status}`).join(', ')
    console.log(`    #${i} ${h.runId || '(无 runId)'} resume=[${ids}]`)
  })

  /* 10. 截图留证 */
  mkdirSync(OUT_DIR, { recursive: true })
  shotPath = join(OUT_DIR, `probe-gate-submit-${Date.now()}.png`)
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
