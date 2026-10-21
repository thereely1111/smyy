/**
 * 「刷新 / 重载后掉回首页」取证（CDP，无第三方依赖）
 *
 * 有据可查的机制（读 core.ts / PrototypeShell.vue 得出，这里做实测确认）：
 *   工作台的状态（state.workTitle / detailId / 当前 view）**只活在内存里**，
 *   刷新后一切归零。而 PrototypeShell 挂载时只做 applyRoute(route.path)
 *   → core.go('work')，go() 第一行就是：
 *       if(v==='work' && !state.workTitle){ toast('先在首页输入题目'); return; }
 *   → 视图留在首页，URL 却还是 #/work。表现就是「聊着聊着跳回首页」。
 *
 * 本探针跑三段，每段独立开一页，互不干扰：
 *   A. 直接打开 #/work（等价于刷新/分享链接）
 *   B. 正常进工作台后 Page.reload（等价于 Vite 热更新全量重载 / 用户按 F5）
 *   C. 正常进工作台后什么都不做（对照组：证明不刷新就不会掉）
 *
 * 运行：node scripts/port/probe-reload-home.mjs [baseUrl] [user] [pass]
 */

import { spawn } from 'node:child_process'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 18710 // 避开本机动态端口区（1024–15001）
const BASE = process.argv[2] || 'http://localhost:5181'
const USER = process.argv[3] || 'smyui0929test'
const PASS = process.argv[4] || 'Test0929a'

const PROBLEM = '某工厂要在四周内安排生产计划，已知每周产能上限与需求，求成本最小的排产方案。'

const profile = mkdtempSync(join(tmpdir(), 'cdp-reload-'))
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
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(typeof ev.data === 'string' ? ev.data : ev.data.toString())
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
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: TOKEN_SRC })
  return cdp
}

async function evaluate(cdp, expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (r.exceptionDetails) throw new Error('页面求值异常：' + JSON.stringify(r.exceptionDetails).slice(0, 300))
  return r.result.value
}

const SNAP = `(() => {
  const on = (id) => { const el = document.getElementById(id); return !!(el && el.classList.contains('on')) }
  const t = document.getElementById('w-title')
  const toast = document.getElementById('toast')
  return {
    hash: location.hash,
    home: on('view-home'), work: on('view-work'),
    workTitle: t ? t.textContent : '',
    toast: toast && toast.classList.contains('on') ? toast.textContent : '',
    streamLen: (document.getElementById('stream-in') || {}).textContent?.length ?? -1,
  }
})()`

const chrome = launch()
try {
  await getBrowserWs()

  const loginRes = await fetch(BASE + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER, password: PASS }),
  })
  const tokens = await loginRes.json().catch(() => ({}))
  if (!tokens.accessToken) throw new Error(`登录失败 HTTP ${loginRes.status}`)
  const info = JSON.stringify({ userId: tokens.userId, username: tokens.username ?? USER, role: tokens.role ?? '' })
  TOKEN_SRC =
    `try{localStorage.clear();` +
    `localStorage.setItem('access_token',${JSON.stringify(tokens.accessToken)});` +
    `localStorage.setItem('refresh_token',${JSON.stringify(tokens.refreshToken || '')});` +
    `localStorage.setItem('user_id',${JSON.stringify(tokens.userId || '')});` +
    `localStorage.setItem('user_info',${JSON.stringify(info)});}catch(e){}`

  /* ── A. 直接打开 #/work（等价刷新/分享链接） ── */
  {
    const cdp = await newPage('about:blank')
    await cdp.send('Page.navigate', { url: `${BASE}/?probe=a${Date.now()}#/work` })
    await sleep(4500)
    const s = await evaluate(cdp, SNAP)
    console.log('A 直接打开 #/work      →', JSON.stringify(s))
    console.log(`   ${s.work ? '✅ 落在工作台' : '❌ 掉回首页（' + s.hash + ' 却是 ' + (s.home ? '首页亮' : '?') + '）'}`)
    await cdp.send('Target.closeTarget', { targetId: (await cdp.send('Target.getTargets')).targetInfos.at(-1).targetId }).catch(() => {})
  }

  /* ── B. 正常进工作台 → reload ── */
  {
    const cdp = await newPage('about:blank')
    await cdp.send('Page.navigate', { url: `${BASE}/?probe=b${Date.now()}#/` })
    await sleep(4000)
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
    await sleep(3000)
    const before = await evaluate(cdp, SNAP)
    console.log('B reload 前            →', JSON.stringify(before))
    await cdp.send('Page.reload', { ignoreCache: false })
    await sleep(5000)
    const after = await evaluate(cdp, SNAP)
    console.log('B reload 后            →', JSON.stringify(after))
    console.log(`   ${after.work ? '✅ 仍留在工作台' : '❌ 掉回首页' + (after.toast ? '，并弹了提示「' + after.toast + '」' : '')}`)
  }

  /* ── C. 对照组：进工作台后不动 ── */
  {
    const cdp = await newPage('about:blank')
    await cdp.send('Page.navigate', { url: `${BASE}/?probe=c${Date.now()}#/` })
    await sleep(4000)
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
    await sleep(3000)
    const s = await evaluate(cdp, SNAP)
    console.log('C 不刷新（对照）        →', JSON.stringify(s))
    console.log(`   ${s.work ? '✅ 留在工作台' : '❌ 掉回首页'}`)
  }
} catch (e) {
  console.log('探针异常：' + (e instanceof Error ? e.message : String(e)))
} finally {
  chrome.kill()
  await sleep(300)
}
