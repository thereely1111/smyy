/**
 * 分析报告卡探针（P2，2026-10-10）
 *
 * 全链路的**存根打法**：`/agui/run/`、`/artifacts`、`/artifacts/content`、`/export`
 * 四个端点都在页面里拦掉，回合成响应。于是「组件挂载、flow、挑主稿、渲染、按钮」
 * 全是真的，只有网络是造的 —— 不烧模型、秒级出结果、不要求后端真的能跑分析。
 *
 * 三部分：
 *   第一部分 · 判据（真模块）—— 在页面里 import `analysis/report` 与
 *             `analysis/api/exportDoc` 的真实模块，断言「挑哪一份当主稿」与
 *             「下载文件名怎么解析」。这两处是纯逻辑，最容易悄悄错。
 *   第二部分 · 接线（真流程）—— 首页写题面 → 开始演易 → 追卡。
 *             断言：报告卡比产物卡**先**出现、成品态有「下载 Word」、点它可以真的
 *             触发一次保存（拦 `URL.createObjectURL` 与 `a.click` 取证）。
 *   第三部分 · 如实说 —— 产物里一份 Markdown 都没有时，卡片必须写明「还没有
 *             Markdown 分析稿」，而不是转到天荒地老或假装成功。
 *
 * 运行：node scripts/port/probe-report.mjs [baseUrl] [user] [pass]
 */

import { spawn } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 18711 // 避开本机动态端口区（1024–15001）
const BASE = process.argv[2] || 'http://localhost:5181'
const USER = process.argv[3] || 'smyui0929test'
const PASS = process.argv[4] || 'Test0929a'
const OUT_DIR = process.env.SMYY_OUT_DIR || resolve(import.meta.dirname, '../../../outputs')

/** 探针用的题面：短，不依赖附件 —— 只为把工作台开起来 */
const PROBLEM = '某工厂要在四周内安排生产计划，已知每周产能上限与需求，求成本最小的排产方案。'

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

/* ==================================================================
   页面里装的东西：四个端点的存根

   用函数 + toString 注入，不在字符串里手写转义 —— 手写转义是这类探针
   最容易出的错（引号、反斜杠、中文）。
   ================================================================== */

function installStub(cfg) {
  window.__stub = { agui: 0, artTree: 0, artContent: 0, export: 0, exportPath: '', exports: [] }
  const orig = window.fetch

  /* 一份能过 OOXML「看起来像 docx」的假字节：只要前两字节是 PK，前端只关心 size */
  const fakeDocx = () => {
    const b = new Uint8Array(64)
    b[0] = 0x50
    b[1] = 0x4b
    b[2] = 0x03
    b[3] = 0x04
    return b
  }

  const json = (obj, status = 200) =>
    new Response(JSON.stringify(obj), { status, headers: { 'Content-Type': 'application/json' } })

  /**
   * 把整棵树的 updatedAt 盖上「此刻」。
   *
   * 必须**每次请求时**盖，不能在配置里写死：flow 收产物时会带 `since`（本轮开始时刻），
   * 只认这个时刻之后落盘的文件。配置里的时间戳是页面加载那一刻的，永远早于本轮 run，
   * 于是 harvest 一直判「产物不新鲜」→ 状态永远不到 done → 报告卡永远不出现。
   * 这个坑的症状很像「卡片没接上」，其实只是存根的时间戳过时了。
   */
  const stamp = (list) =>
    list.map((n) =>
      n.type === 'dir'
        ? Object.assign({}, n, { updatedAt: Date.now(), children: n.children ? stamp(n.children) : [] })
        : Object.assign({}, n, { updatedAt: Date.now() }),
    )

  window.fetch = function (input, init) {
    const url = typeof input === 'string' ? input : (input && input.url) || ''
    const method = ((init && init.method) || (input && input.method) || 'GET').toUpperCase()

    /* ① 模型调用：合成立刻成功的一段流 */
    if (/\/agui\/run\//.test(url)) {
      window.__stub.agui += 1
      const sse =
        'data: ' + JSON.stringify({ type: 'RUN_STARTED', threadId: 'probe', runId: 'probe' }) + '\n\n' +
        'data: ' + JSON.stringify({ type: 'TEXT_MESSAGE_START', messageId: 'pm1', role: 'assistant' }) + '\n\n' +
        'data: ' + JSON.stringify({ type: 'TEXT_MESSAGE_CONTENT', messageId: 'pm1', delta: '（探针合成答复）' }) + '\n\n' +
        'data: ' + JSON.stringify({ type: 'TEXT_MESSAGE_END', messageId: 'pm1' }) + '\n\n' +
        'data: ' + JSON.stringify({ type: 'RUN_FINISHED', outcome: { type: 'success' } }) + '\n\n'
      return Promise.resolve(new Response(sse, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }))
    }

    /* ② 产物树 */
    if (/\/artifacts(\?|$)/.test(url) && !/\/artifacts\/content/.test(url)) {
      window.__stub.artTree += 1
      return Promise.resolve(json(stamp(cfg.tree)))
    }

    /* ③ 产物正文：**按 path 参数**取对应的那份文本。
       回一份固定文本（或回裸文本）都会让上层静默吞掉 —— 收产物、挑主稿两处判据
       都靠内容，喂错内容会得出「没产物」的假结论。 */
    if (/\/artifacts\/content/.test(url)) {
      window.__stub.artContent += 1
      let p = ''
      try {
        p = new URL(url, location.origin).searchParams.get('path') || ''
      } catch (e) {
        p = ''
      }
      const content = Object.prototype.hasOwnProperty.call(cfg.files, p) ? cfg.files[p] : ''
      return Promise.resolve(json({ path: p, content: content, size: content.length }))
    }

    /* ④ 导出：回 docx 字节流 + 中文文件名（走 RFC 5987），并记下请求体 */
    if (/\/export$/.test(url) && method === 'POST') {
      window.__stub.export += 1
      window.__stub.exportPath = (init && init.body) ? String(init.body) : ''
      window.__stub.exports.push(window.__stub.exportPath)
      return Promise.resolve(
        new Response(fakeDocx(), {
          status: 200,
          headers: {
            'Content-Type': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
            'Content-Disposition':
              'attachment; filename="analysis_v1.0.docx"; filename*=UTF-8\'\'%E5%88%86%E6%9E%90%E6%8A%A5%E5%91%8A.docx',
            /* 与后端一致：这个头**只能放 ASCII**（中文原样放进去浏览器判非法头值，
               整个导出会抛）。所以是百分号编码，前端负责 decodeURIComponent。 */
            'X-Export-Path': 'exports/%E5%88%86%E6%9E%90%E6%8A%A5%E5%91%8A.docx',
          },
        }),
      )
    }

    return orig.apply(this, arguments)
  }

  /* 下载取证：点「下载 Word」应当真的走 createObjectURL + a.click */
  window.__dl = []
  const origCreate = URL.createObjectURL.bind(URL)
  URL.createObjectURL = function (b) {
    window.__dl.push({ size: b && b.size ? b.size : 0 })
    return origCreate(b)
  }
  window.__origAnchorClick = HTMLAnchorElement.prototype.click
  HTMLAnchorElement.prototype.click = function () {
    window.__dl.push({ clicked: this.download || '' })
  }
}

/** 页面读数：一律看**可见文字与 DOM**，不去猜模块内部状态 */
const READ = `(() => {
  const stream = document.getElementById('stream-in')
  const txt = stream ? stream.textContent : ''
  const rep = stream ? stream.querySelector('.rep-card') : null
  const art = stream ? stream.querySelector('.art-card') : null
  /* 两卡的先后：比 DOM 顺序，不比时间戳 */
  let repFirst = null
  if (rep && art && rep !== art) {
    const pos = art.compareDocumentPosition(rep)
    repFirst = !!(pos & Node.DOCUMENT_POSITION_PRECEDING)
  }
  return {
    hasInput: !!document.getElementById('work-input'),
    repCards: stream ? stream.querySelectorAll('.rep-card').length : 0,
    artCards: stream ? stream.querySelectorAll('.art-card').length : 0,
    repFirst: repFirst,
    repText: rep ? rep.textContent : '',
    ready: !!(rep && rep.querySelector('.rep-file')),
    missing: !!(rep && rep.querySelector('.rep-note.warn')),
    docxName: rep && rep.querySelector('.rep-meta b') ? rep.querySelector('.rep-meta b').textContent : '',
    srcNote: rep && rep.querySelector('.rep-meta em') ? rep.querySelector('.rep-meta em').textContent : '',
    btns: rep ? [...rep.querySelectorAll('button[data-report]')].map(b => b.getAttribute('data-report') + ':' + b.textContent.trim()) : [],
    hasStreamText: txt.length > 0,
    /* 诊断用：卡没出来时先看这三样，别瞎猜 */
    msgs: stream ? stream.querySelectorAll('.msg').length : 0,
    lvText: stream && stream.querySelector('.lv-stream') ? stream.querySelector('.lv-stream').textContent.slice(0, 200) : '',
    stub: window.__stub ? JSON.parse(JSON.stringify(window.__stub)) : null,
  }
})()`

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/* ==================================================================
   浏览器脚手架（与 probe-intent 同一套）
   ================================================================== */

const profile = mkdtempSync(join(tmpdir(), 'cdp-report-'))

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

/** 把某一页开到工作台（首页写题面 → 开始演易），返回该页 CDP */
async function openWorkbench(tokens, cfg) {
  const cdp = await newPage('about:blank')
  const info = JSON.stringify({ userId: tokens.userId, username: tokens.username ?? USER, role: tokens.role ?? '' })
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source:
      `(${installStub.toString()})(${JSON.stringify(cfg)});` +
      `try{localStorage.clear();` +
      `localStorage.setItem('access_token',${JSON.stringify(tokens.accessToken)});` +
      `localStorage.setItem('refresh_token',${JSON.stringify(tokens.refreshToken || '')});` +
      `localStorage.setItem('user_id',${JSON.stringify(tokens.userId || '')});` +
      `localStorage.setItem('user_info',${JSON.stringify(info)});}catch(e){}`,
  })
  await cdp.send('Page.navigate', { url: `${BASE}/?probe=${Date.now()}#/` })
  await sleep(3500)

  const started = await evaluate(
    cdp,
    `(() => {
       const ta = document.getElementById('home-input')
       if (!ta) return { ok:false, why:'no #home-input' }
       ta.value = ${JSON.stringify(PROBLEM)}
       ta.dispatchEvent(new Event('input', { bubbles:true }))
       const btn = document.getElementById('home-send')
       if (!btn) return { ok:false, why:'no #home-send' }
       btn.click()
       return { ok:true }
     })()`,
  )
  return { cdp, started }
}

/* ==================================================================
   开跑
   ================================================================== */

/* 存根要长得**跟真产物一样**：flow 收产物时按 `analysis_result.json` +
   `truth/*.json` + `analysis/analysis_v*.md` 四份判「拿到了没有」，缺了就到不了
   「分析完成」，报告卡也就永远不会被追出来。updatedAt 由 installStub 每次请求盖新。 */
const MD_HAPPY = [
  '# 分析报告',
  '',
  '本题是**组合优化**问题，目标是最小化四周内的总排产成本。',
  '',
  '| 选项 | 说明 |',
  '| --- | --- |',
  '| A | 线性规划 |',
  '| B | 动态规划 |',
  '',
].join('\n')

const RESULT_HAPPY = JSON.stringify({
  status: 'COMPLETED',
  primary_artifacts: ['artifacts/analysis/analysis_v1.0.md'],
})

/** 一篇分析主稿 + 三份配套 JSON，外加一份用户自己传的原题（用来验排除） */
const CFG_HAPPY = {
  tree: [
    {
      type: 'dir', name: 'input', path: 'artifacts/input',
      children: [{ type: 'file', name: '题目.md', path: 'artifacts/input/题目.md', size: 900 }],
    },
    {
      type: 'dir', name: 'truth', path: 'artifacts/truth',
      children: [
        { type: 'file', name: 'problem_contract.json', path: 'artifacts/truth/problem_contract.json', size: 300 },
        { type: 'file', name: 'solution_map.json', path: 'artifacts/truth/solution_map.json', size: 300 },
      ],
    },
    { type: 'file', name: 'analysis_result.json', path: 'artifacts/analysis_result.json', size: 200 },
    {
      type: 'dir', name: 'analysis', path: 'artifacts/analysis',
      children: [{ type: 'file', name: 'analysis_v1.0.md', path: 'artifacts/analysis/analysis_v1.0.md', size: 24718 }],
    },
  ],
  files: {
    'artifacts/analysis/analysis_v1.0.md': MD_HAPPY,
    'artifacts/analysis_result.json': RESULT_HAPPY,
    'artifacts/truth/problem_contract.json': JSON.stringify({ case_id: 'probe', questions: [] }),
    'artifacts/truth/solution_map.json': JSON.stringify({ questions: [] }),
    'artifacts/input/题目.md': '# 用户自己传的原题\n',
  },
}

/** 有配套 JSON、**唯独没有 Markdown 正文** —— 第三部分用它验「如实说」 */
const CFG_NO_MD = {
  tree: [
    {
      type: 'dir', name: 'input', path: 'artifacts/input',
      children: [{ type: 'file', name: '题目.md', path: 'artifacts/input/题目.md', size: 900 }],
    },
    {
      type: 'dir', name: 'truth', path: 'artifacts/truth',
      children: [
        { type: 'file', name: 'problem_contract.json', path: 'artifacts/truth/problem_contract.json', size: 300 },
      ],
    },
    { type: 'file', name: 'analysis_result.json', path: 'artifacts/analysis_result.json', size: 200 },
    {
      type: 'dir', name: 'analysis', path: 'artifacts/analysis',
      children: [{ type: 'file', name: 'notes.json', path: 'artifacts/analysis/notes.json', size: 300 }],
    },
  ],
  files: {
    'artifacts/analysis_result.json': RESULT_HAPPY,
    'artifacts/truth/problem_contract.json': JSON.stringify({ case_id: 'probe', questions: [] }),
    'artifacts/analysis/notes.json': '{"note":"只有中间件，没有正文"}',
    'artifacts/input/题目.md': '# 用户自己传的原题\n',
  },
}

const chrome = spawn(
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

let shotPath = ''
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

  /* ── 第一部分 · 判据（真模块，页面里 import） ───────────────────────── */
  console.log('')
  console.log('—— 第一部分：判据（analysis/report + analysis/api/exportDoc）——')

  const { cdp, started } = await openWorkbench(tokens, CFG_HAPPY)
  check('首页可输入题面并点「开始演易」', started.ok, started.why || '')
  check('fetch 打桩已就位', await evaluate(cdp, `!!window.__stub`))
  const errs0 = pageErrors(cdp)
  check('开题过程无未捕获异常', errs0.length === 0, errs0.slice(0, 2).join(' | '))

  /** 取页面真实请求过的模块 URL —— 裸路径会拿到另一个模块实例（HMR 时间戳） */
  const moduleUrl = (tail) => `(() => {
    const names = performance.getEntriesByType('resource').map(e => e.name)
    const hits = names.filter(n => n.includes(${JSON.stringify(tail)}))
    return hits.length ? hits[hits.length - 1] : ${JSON.stringify(tail)}
  })()`

  const repUrl = await evaluate(cdp, moduleUrl('/src/utils/analysis/report.ts'))
  const docUrl = await evaluate(cdp, moduleUrl('/src/api/exportDoc.ts'))
  check('拿到报告卡模块的真实 URL', /report\/index\.ts/.test(repUrl), repUrl)

  const pure = await evaluate(
    cdp,
    `(async () => {
       const R = await import(${JSON.stringify(repUrl)})
       const D = await import(${JSON.stringify(docUrl)})
       const file = (name, path, updatedAt) => ({ name, path, size: 100, updatedAt })
       const pick = (files) => { const r = R.pickReportSource(files); return r ? r.path : null }
       return {
         /* 主稿优先：analysis/ 目录 + analysis_vN 命名 */
         prefersMain: pick([
           file('constraints.md', 'artifacts/truth/constraints.md', 9),
           file('analysis_v1.0.md', 'artifacts/analysis/analysis_v1.0.md', 3),
         ]),
         /* 用户自己传的原题不算分析结果 */
         excludesInput: pick([file('题目.md', 'artifacts/input/题目.md', 9), file('notes.md', 'artifacts/notes.md', 1)]),
         /* 同分取更新的一份 */
         newerWins: pick([
           file('analysis_v1.0.md', 'artifacts/analysis/analysis_v1.0.md', 5),
           file('analysis_v1.0.md', 'sub-aaaa/artifacts/analysis/analysis_v1.0.md', 7),
         ]),
         /* 一份 Markdown 都没有 → 不硬导 */
         noneNoMd: pick([file('题目.pdf', 'artifacts/input/题目.pdf', 9), file('notes.json', 'artifacts/analysis/notes.json', 9)]),
         /* 文件名解析：RFC 5987 优先，退回 filename=，再退回兜底 */
         fnameStar: D.fileNameOf('attachment; filename="a.docx"; filename*=UTF-8\\'\\'%E5%88%86%E6%9E%90%E6%8A%A5%E5%91%8A.docx', 'x.docx'),
         fnamePlain: D.fileNameOf('attachment; filename="plain.docx"', 'x.docx'),
         fnameFallback: D.fileNameOf(null, 'fallback.docx'),
       }
     })()`,
  )

  check('挑主稿：analysis/ 下的 analysis_vN 优先于 truth/',
    pure.prefersMain === 'artifacts/analysis/analysis_v1.0.md', String(pure.prefersMain))
  check('挑主稿：排除 input/（用户自己传的原题不是分析结果）',
    pure.excludesInput === 'artifacts/notes.md', String(pure.excludesInput))
  check('挑主稿：同分取更新的一份', pure.newerWins === 'sub-aaaa/artifacts/analysis/analysis_v1.0.md', String(pure.newerWins))
  check('挑主稿：没有 Markdown 时不硬导', pure.noneNoMd === null, String(pure.noneNoMd))
  check('下载文件名：优先 RFC 5987 的 filename*（中文名不丢字）',
    pure.fnameStar === '分析报告.docx', String(pure.fnameStar))
  check('下载文件名：退回 filename="…"', pure.fnamePlain === 'plain.docx', String(pure.fnamePlain))
  check('下载文件名：都没有时用源文件名兜底', pure.fnameFallback === 'fallback.docx', String(pure.fnameFallback))

  /* ── 第二部分 · 接线（真流程追卡） ─────────────────────────────────── */
  console.log('')
  console.log('—— 第二部分：接线（分析跑完 → 报告卡）——')

  let cur = null
  for (let i = 0; i < 30; i++) {
    cur = await evaluate(cdp, READ)
    if (cur.repCards > 0) break
    await sleep(700)
  }
  if (cur.repCards === 0) {
    console.log(`   ⚠ 诊断：msgs=${cur.msgs} art=${cur.artCards} stub=${JSON.stringify(cur.stub)}`)
    console.log(`   ⚠ 对话区文本：${String(cur.lvText).replace(/\s+/g, ' ')}`)
    console.log(`   ⚠ 页面报错：${pageErrors(cdp).slice(0, 3).join(' | ') || '（无）'}`)
  }
  check('已进入工作台且对话区可用', cur.hasInput)
  check('分析结束后追了一张分析报告卡', cur.repCards === 1, `repCards=${cur.repCards}`)
  check('报告卡排在产物卡**前**面（成品先行、明细在后）', cur.repFirst === true, `repFirst=${cur.repFirst} art=${cur.artCards}`)
  check('报告卡进入成品态（有文件行）', cur.ready, cur.repText.slice(0, 120))
  check('成品态写明 docx 文件名', /\.docx$/.test(cur.docxName), cur.docxName)
  check('成品态写明来源与落盘位置（中文路径已从百分号编码还原）',
    cur.srcNote.includes('exports/分析报告.docx'), cur.srcNote)
  check('成品态给「下载 Word」与「预览原稿」两颗按钮',
    cur.btns.includes('download:下载 Word') && cur.btns.includes('preview:预览原稿'), cur.btns.join(' / '))

  const stubState = await evaluate(cdp, `JSON.parse(JSON.stringify(window.__stub))`)
  check('导出请求真的发出去了（POST .../export）', stubState.export >= 1, `export=${stubState.export}`)
  check('导出请求体带的是**产物树里的原样路径**',
    stubState.exportPath.includes('artifacts/analysis/analysis_v1.0.md'), stubState.exportPath)

  /* 点「重新生成」→ 幂等：再导一次，不攒副本（同一会话同一源） */
  await evaluate(cdp, `(() => { const b = document.querySelector('#stream-in .rep-card button[data-report="refresh"]'); if (b) b.click(); return !!b })()`)
  await sleep(1500)
  const after2 = await evaluate(cdp, `JSON.parse(JSON.stringify(window.__stub))`)
  check('「重新生成」再走一遍导出（幂等覆盖，不是攒副本）', after2.export === stubState.export + 1,
    `export ${stubState.export} → ${after2.export}`)

  /* 点「下载 Word」→ 必须真的走一次保存 */
  const dl = await evaluate(
    cdp,
    `(async () => {
       window.__dl.length = 0
       const b = document.querySelector('#stream-in .rep-card button[data-report="download"]')
       if (!b) return { ok:false, why:'no download button' }
       b.click()
       await new Promise(r => setTimeout(r, 800))
       return { ok:true, log: window.__dl }
     })()`,
  )
  check('「下载 Word」触发一次保存（createObjectURL + a.click）',
    dl.ok && Array.isArray(dl.log) && dl.log.some((x) => x.size > 0) && dl.log.some((x) => /\.docx$/.test(x.clicked || '')),
    JSON.stringify(dl))
  check('下载用的正是中文文件名（预览过 RFC 5987 解析）',
    Array.isArray(dl.log) && dl.log.some((x) => x.clicked === '分析报告.docx'), JSON.stringify(dl))

  const errs1 = pageErrors(cdp)
  check('报告卡全流程无未捕获异常 / console.error', errs1.length === 0, errs1.slice(0, 2).join(' | '))

  const shot = await cdp.send('Page.captureScreenshot', { format: 'png' })
  try {
    mkdirSync(OUT_DIR, { recursive: true })
    shotPath = join(OUT_DIR, `probe-report-${Date.now()}.png`)
    writeFileSync(shotPath, Buffer.from(shot.data, 'base64'))
  } catch {
    shotPath = ''
  }

  /* ── 第三部分 · 如实说（没有 Markdown 时不装成功） ──────────────────── */
  console.log('')
  console.log('—— 第三部分：如实说（产物里没有 Markdown）——')

  const second = await openWorkbench(tokens, CFG_NO_MD)
  check('第二条会话也能开到工作台', second.started.ok, second.started.why || '')

  let cur2 = null
  for (let i = 0; i < 30; i++) {
    cur2 = await evaluate(second.cdp, READ)
    if (cur2.repCards > 0) break
    await sleep(700)
  }
  check('没有主稿时仍出卡（不静默消失）', cur2.repCards === 1, `repCards=${cur2.repCards}`)
  check('写明「还没有 Markdown 分析稿」而不是空着', /没有 Markdown 分析稿/.test(cur2.repText), cur2.repText.slice(0, 140))
  check('说明里给出下一步（过会儿点重新生成 / 去产物卡看）',
    /重新生成/.test(cur2.repText) && /产物/.test(cur2.repText), cur2.repText.slice(0, 140))
  check('这一路**不**发导出请求（没东西可导就不导）', (await evaluate(second.cdp, `window.__stub.export`)) === 0)
  const errs2 = pageErrors(second.cdp)
  check('第二条会话无未捕获异常', errs2.length === 0, errs2.slice(0, 2).join(' | '))
} catch (e) {
  check('探针执行未中断', false, String(e && e.message).slice(0, 300))
} finally {
  try {
    chrome.kill()
  } catch {
    /* ignore */
  }
}

const pass = results.filter((r) => r.ok).length
console.log('')
if (shotPath) console.log(`截图：${shotPath}`)
console.log(`合计 ${results.length} 项，通过 ${pass}，失败 ${results.length - pass}`)
process.exit(results.length - pass === 0 ? 0 : 1)
