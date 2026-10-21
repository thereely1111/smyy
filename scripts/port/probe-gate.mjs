/**
 * 闸门（HITL）端到端探针（CDP，无第三方依赖）
 *
 * 验什么（2026-10-09 新增能力，改过就以这一版为准）：
 *   1. 后端把 run 挂起（RUN_FINISHED{outcome:{type:'interrupt'}}）时，
 *      工作台对话区**真的画出一张闸门卡**（`.gate-card`），字段按 kind 渲染；
 *   2. 必填项为空时点提交**被拦下**（卡片不定格）；
 *   3. 填完提交后，发出的第二条请求是**同一 (threadId, runId)**，
 *      且带 `resume:[{interruptId,status:'resolved',payload:{output}}]`；
 *   4. 提交后卡片定格成 `.gate-card.done`（留在对话里当记录，不被轮询重画掉）；
 *   5. 全程无未捕获异常。
 *
 * 为什么非真跑不可：这条链路横跨「SSE 事件 → gate.ts 归一化 → flow 的 promise 槽
 * → bridge 的同步绘制 → 用户点击 → resume 请求」，任何一环断掉静态检查都看不出来
 * （vue-tsc 全绿也会整条链路哑掉）。真机造一个 HITL 中断成本太高（要模型自己愿意发
 * `ui_form_advanced`），所以这里用 CDP 的 Fetch 域**在浏览器网络层**拦下
 * `/agui/run/*`，直接喂一段构造好的 SSE —— 站点侧一行代码都不用改。
 *
 * ⚠️ 拦的是浏览器发出的请求（同源 localhost:5181，本来由 vite 代理转发到后端）。
 *    因此本探针**不依赖后端在跑**，只依赖 vite dev server 起得来（登录那一步仍打真接口）。
 *
 * 运行：node scripts/port/probe-gate.mjs [baseUrl] [user] [pass]
 */

import { spawn } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 18718 // 避开本机动态端口区（1024–15001）
const BASE = process.argv[2] || 'http://localhost:5181'
const USER = process.argv[3] || 'smyui0929test'
const PASS = process.argv[4] || 'Test0929a'
const OUT_DIR = process.env.SMYY_OUT_DIR || resolve(import.meta.dirname, '../../../outputs')

/** 探针用的题面：短，不依赖附件 —— 只为把 run 打起来 */
const PROBLEM =
  '某物流公司要在三座城市之间安排运输路线，已知各城市间的距离和每辆车的载重上限，求总里程最小的调度方案。'

/* ── 构造的 HITL 场景 ─────────────────────────────────────────────────── */

const INTERRUPT_ID = 'int-gate-probe-1'
const TOOL_CALL_ID = 'tc-gate-probe-1'

/** 假闸门表单：四种 kind 各来一个，覆盖渲染分支 */
const GATE_INPUT = {
  title: '动手之前先定两个口径',
  description: '这道题有两处写法会影响后面所有的工作，请你拍板。',
  submitLabel: '确认并继续',
  pages: [
    {
      key: 'p1',
      title: '建模口径',
      items: [
        {
          key: 'route',
          label: '模型路线',
          kind: 'radio-group',
          options: ['微分方程模型', '离散优化模型', '统计回归模型'],
          required: true,
        },
        { key: 'calendar', label: '时间口径', kind: 'select', options: ['按工作日', '按自然日'], required: false },
        { key: 'tol', label: '数值容差', kind: 'number', required: false, initialValue: 1e-6 },
        { key: 'note', label: '补充说明', kind: 'textarea', required: false, placeholder: '有话可以写在这里' },
      ],
    },
  ],
}

const sseLine = (o) => 'data: ' + JSON.stringify(o) + '\n\n'

/** 极端短流：只把会话登记下来就收尾（附件通道的 READY run 会走这里） */
function readyStream(threadId, runId) {
  return [
    { type: 'RUN_STARTED', threadId, runId },
    { type: 'RUN_FINISHED', threadId, runId, outcome: { type: 'success' } },
  ]
    .map(sseLine)
    .join('')
}

/** 第一段：说到一半把 run 挂起，交出一张 `ui_form_advanced` 表单 */
function interruptStream(threadId, runId) {
  return [
    { type: 'RUN_STARTED', threadId, runId },
    { type: 'TEXT_MESSAGE_START', messageId: 'm-probe-1', role: 'assistant' },
    {
      type: 'TEXT_MESSAGE_CONTENT',
      messageId: 'm-probe-1',
      delta: '这道题有两处口径要先跟你确认，我先把要问的列出来。',
    },
    { type: 'TEXT_MESSAGE_END', messageId: 'm-probe-1' },
    { type: 'TOOL_CALL_START', toolCallId: TOOL_CALL_ID, toolCallName: 'ui_form_advanced' },
    { type: 'TOOL_CALL_ARGS', toolCallId: TOOL_CALL_ID, delta: '{}' },
    { type: 'TOOL_CALL_END', toolCallId: TOOL_CALL_ID },
    {
      type: 'RUN_FINISHED',
      threadId,
      runId,
      outcome: {
        type: 'interrupt',
        interrupts: [
          {
            id: INTERRUPT_ID,
            toolCallId: TOOL_CALL_ID,
            message: '确认之后我接着往下做，产物按你定的口径生成。',
            metadata: { toolName: 'ui_form_advanced', toolInput: GATE_INPUT },
          },
        ],
      },
    },
  ]
    .map(sseLine)
    .join('')
}

/** 第二段：用户填完，run 续跑并正常收尾 */
function successStream(threadId, runId) {
  return [
    { type: 'RUN_STARTED', threadId, runId },
    { type: 'TEXT_MESSAGE_START', messageId: 'm-probe-2', role: 'assistant' },
    { type: 'TEXT_MESSAGE_CONTENT', messageId: 'm-probe-2', delta: '收到，按你定的口径往下做。' },
    { type: 'TEXT_MESSAGE_END', messageId: 'm-probe-2' },
    { type: 'RUN_FINISHED', threadId, runId, outcome: { type: 'success' } },
  ]
    .map(sseLine)
    .join('')
}

/* ── CDP 骨架（与 probe-live 同构，多一个事件订阅）────────────────────── */

const profile = mkdtempSync(join(tmpdir(), 'cdp-gate-'))
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

/* 闸门卡状态快照 */
const PROBE_GATE = `(() => {
  const card = document.querySelector('.gate-card')
  const acts = card ? card.querySelector('.gate-acts') : null
  const btn = acts ? acts.querySelector('button[data-act=submit]') : null
  const stream = document.getElementById('stream-in')
  return {
    workOn: !!document.querySelector('#view-work.on'),
    hasCard: !!card,
    done: card ? card.classList.contains('done') : false,
    locked: card ? card.getAttribute('data-locked') === '1' : false,
    gateId: card ? card.getAttribute('data-gate') : '',
    title: card ? ((card.querySelector('.gate-t') || {}).textContent || '').trim() : '',
    fields: card ? card.querySelectorAll('.gate-field').length : 0,
    opts: card ? card.querySelectorAll('.gate-opt').length : 0,
    selects: card ? card.querySelectorAll('.gate-field select').length : 0,
    textareas: card ? card.querySelectorAll('.gate-field textarea').length : 0,
    numbers: card ? card.querySelectorAll('.gate-field input[type=number]').length : 0,
    texts: card ? card.querySelectorAll('.gate-field input[type=text]').length : 0,
    /* 单选组每个 input 都带 data-req，所以按 data-key 去重才是「几个必填字段」 */
    required: card ? [...new Set([...card.querySelectorAll('[data-req="1"]')].map((n) => n.getAttribute('data-key')))] : [],
    submitLabel: btn ? (btn.textContent || '').trim() : '',
    /* 卡片必须挂进气泡的**内容流**（.txt）—— 挂在 .msg 上会跑到气泡右边去
       （.msg 是 flex 行：头像 + 气泡），看着像突然多出一列侧栏 */
    /* 2026-10-12 定版：闸门卡改挂**右栏**（左对话 / 右闸门+产物），容器是 .rail-gate-card，
       不再是对话气泡里的 .txt —— 见 probe-rail 的「闸门容器在右栏里」。 */
    inRail: card ? !!(card.parentElement && card.parentElement.classList.contains('rail-gate-card')) : false,
    parentCls: card && card.parentElement ? card.parentElement.className : '',
    streamLen: stream ? stream.textContent.length : 0,
  }
})()`

/* ── 主流程 ───────────────────────────────────────────────────────────── */

const chrome = launch()
/** 拦下来的 agui/run 请求（按到达顺序） */
const hits = []
let cdpRef = null
let shotPath = ''

/** 这条请求是不是「登记会话」用的 READY run */
function isReadyRun(body) {
  const t = body?.messages?.[0]?.content?.[0]?.text
  return typeof t === 'string' && (t.includes('站点自动消息') || t.includes('就绪'))
}

function onRequestPaused(params) {
  let body = {}
  try {
    body = JSON.parse(params.request.postData || '{}')
  } catch {
    /* 不是 JSON（理论上不会）—— 按空体处理 */
  }
  const resume = Array.isArray(body.resume) ? body.resume : []
  const ready = isReadyRun(body)
  const rec = {
    url: params.request.url,
    threadId: typeof body.threadId === 'string' ? body.threadId : '',
    runId: typeof body.runId === 'string' ? body.runId : '',
    resume,
    ready,
    msgCount: Array.isArray(body.messages) ? body.messages.length : 0,
  }
  hits.push(rec)

  const sse = ready
    ? readyStream(rec.threadId, rec.runId)
    : resume.length
      ? successStream(rec.threadId, rec.runId)
      : interruptStream(rec.threadId, rec.runId)

  void cdpRef
    .send('Fetch.fulfillRequest', {
      requestId: params.requestId,
      responseCode: 200,
      responseHeaders: [
        { name: 'Content-Type', value: 'text/event-stream' },
        { name: 'Cache-Control', value: 'no-cache' },
      ],
      body: Buffer.from(sse, 'utf8').toString('base64'),
    })
    .catch(() => {
      /* 页面已经取消这条请求（例如用户点了跳过）—— 不算错 */
    })
}

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

  /* 2. 注入 token（必须在导航前）。顺带清掉会话映射 —— 否则 hash 路由的同文档
        导航会让 reuse() 命中旧产物，一次 run 都不发，看着像「闸门没触发」 */
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

  /* 3. 在浏览器网络层截住 /agui/run/* —— 站点代码一行不动 */
  await cdp.send('Fetch.enable', {
    patterns: [{ urlPattern: '*agui/run*', requestStage: 'Request' }],
  })

  const stamp = Date.now()
  await cdp.send('Page.navigate', { url: `${BASE}/?probe=${stamp}#/` })
  await sleep(4000)

  const logged = await evaluate(cdp, `!!localStorage.getItem('access_token')`)
  check('token 已注入页面', logged)
  const e1 = pageErrors(cdp)
  check('首页无未捕获异常', e1.length === 0, e1.slice(0, 2).join(' | '))

  /* 4. 首页写题面 → 开始演易（这一步会真的发 run，被上面那层拦下） */
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

  /* 5. 等闸门卡出现（bridge 的 700ms 对账）*/
  let cur = null
  const gateDeadline = Date.now() + 40_000
  while (Date.now() < gateDeadline) {
    cur = await evaluate(cdp, PROBE_GATE)
    if (cur.hasCard) break
    await sleep(700)
  }
  check('已进入工作台（#view-work）', !!(cur && cur.workOn))
  check('run 被挂起后对话区出现闸门卡（.gate-card）', !!(cur && cur.hasCard), cur ? `gateId=${cur.gateId}` : '')
  if (!cur || !cur.hasCard) throw new Error('闸门卡没出现，后续断言没有意义')

  /* 5a. 字段按 kind 渲染 */
  check('闸门卡标题来自工具入参（不是写死文案）', cur.title === GATE_INPUT.title, `“${cur.title}”`)
  check('四个字段都画出来了', cur.fields === 4, `实得 ${cur.fields}`)
  check('单选组画成选项胶囊（3 个候选）', cur.opts === 3, `实得 ${cur.opts}`)
  check('select 画成下拉', cur.selects === 1, `实得 ${cur.selects}`)
  check('textarea 画成多行输入', cur.textareas === 1, `实得 ${cur.textareas}`)
  check('number 画成数字输入（且未被降级成 text）', cur.numbers === 1 && cur.texts === 0, `number ${cur.numbers} · text ${cur.texts}`)
  check(
    '必填标记落到对应字段上（data-req）',
    cur.required.length === 1 && cur.required[0] === 'route',
    `实得 [${cur.required.join(', ')}]`,
  )
  check('提交按钮用工具给的文案', cur.submitLabel === GATE_INPUT.submitLabel, `“${cur.submitLabel}”`)
  check('卡片挂在右栏闸门容器里（.rail-gate-card），不是散落的兄弟节点', cur.inRail, `父节点 .${cur.parentCls || '?'}`)
  check('闸门卡还没被定格（等用户填）', !cur.done && !cur.locked)

  /* 5b. 必填项为空时提交必须被拦下 */
  await evaluate(cdp, `document.querySelector('.gate-card button[data-act="submit"]').click(); 1`)
  await sleep(900)
  const blank = await evaluate(cdp, PROBE_GATE)
  check('必填项为空时提交被拦下（卡片不定格）', !blank.done && !blank.locked)
  check('被拦下时没有发出 resume 请求', hits.filter((h) => !h.ready).length === 1, `实得 ${hits.filter((h) => !h.ready).length} 条`)

  /* 6. 填完再提交 → 卡片定格 + 发 resume */
  const filled = await evaluate(
    cdp,
    `(() => {
       const card = document.querySelector('.gate-card')
       if (!card) return { ok: false, why: 'no card' }
       const radio = card.querySelector('input[type=radio][data-key="route"][value="离散优化模型"]')
       if (!radio) return { ok: false, why: 'no radio' }
       radio.checked = true
       radio.dispatchEvent(new Event('change', { bubbles: true }))
       const sel = card.querySelector('select[data-key="calendar"]')
       if (sel) { sel.value = '按自然日'; sel.dispatchEvent(new Event('change', { bubbles: true })) }
       const ta = card.querySelector('textarea[data-key="note"]')
       if (ta) ta.value = '时间口径按自然日算。'
       card.querySelector('button[data-act="submit"]').click()
       return { ok: true }
     })()`,
  )
  check('可把单选 / 下拉 / 文本填进闸门卡', filled.ok, filled.why || '')
  await sleep(1200)

  const after = await evaluate(cdp, PROBE_GATE)
  check('提交后卡片定格成 .gate-card.done', after.done && after.locked)
  check('定格后卡片留在对话里当记录（没被轮询重画掉）', after.hasCard && after.gateId === INTERRUPT_ID, `gateId=${after.gateId}`)

  /* 7. 续跑请求：同一 (threadId, runId) + resume */
  const gateHits = hits.filter((h) => !h.ready)
  check('共发出两条闸门相关请求（首轮 + 续跑）', gateHits.length === 2, `实得 ${gateHits.length} 条`)
  if (gateHits.length >= 2) {
    const a = gateHits[0]
    const b = gateHits[1]
    check('首轮请求不带 resume', a.resume.length === 0)
    check('续跑请求带 1 条 resume', b.resume.length === 1, `实得 ${b.resume.length}`)
    const r = b.resume[0] || {}
    check('resume.interruptId 与挂起的一致', r.interruptId === INTERRUPT_ID, `实得 ${r.interruptId || '(空)'}`)
    check('resume.status 是 resolved', r.status === 'resolved', `实得 ${r.status || '(空)'}`)
    check('续跑复用同一 threadId', !!b.threadId && b.threadId === a.threadId, `${a.threadId} → ${b.threadId}`)
    check('续跑复用同一 runId', !!b.runId && b.runId === a.runId, `${a.runId} → ${b.runId}`)
    let payload = null
    try {
      payload = JSON.parse(String(r.payload?.output ?? '{}'))
    } catch {
      payload = null
    }
    check('payload.output 是 JSON 字符串（平台约定的回传形状）', typeof r.payload?.output === 'string', typeof r.payload?.output)
    check('用户填的单选值原样回传', payload?.route === '离散优化模型', String(payload?.route))
    check('用户填的下拉值原样回传', payload?.calendar === '按自然日', String(payload?.calendar))
    check('数字字段回传成 Number 而非字符串', payload?.tol === 0.000001, `${typeof payload?.tol}:${payload?.tol}`)
    check('文本域回传原文', payload?.note === '时间口径按自然日算。', String(payload?.note))
  }

  /* 8. 截图留证 */
  mkdirSync(OUT_DIR, { recursive: true })
  shotPath = join(OUT_DIR, `probe-gate-${Date.now()}.png`)
  const shot = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
  writeFileSync(shotPath, Buffer.from(shot.data, 'base64'))
  console.log(`\n截图：${shotPath}`)

  const e2 = pageErrors(cdp, e1.length)
  check('闸门全程无未捕获异常', e2.length === 0, e2.slice(0, 3).join(' | '))
} catch (e) {
  check('探针本身跑通', false, e instanceof Error ? e.message : String(e))
} finally {
  chrome.kill()
  await sleep(300)
}

const failed = results.filter((r) => !r.ok)
console.log(`\n合计 ${results.length} 项，通过 ${results.length - failed.length}，失败 ${failed.length}`)
process.exit(failed.length ? 1 : 0)
