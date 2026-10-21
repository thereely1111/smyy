/**
 * 「对话进行中被热更新重载打回首页」取证（CDP，无第三方依赖）
 *
 * 背景：Vite 在模块图里的文件被改写时会给浏览器发 full-reload，页面整体重载。
 * 工作台状态只在内存里（state.workTitle 等），重载即归零 → applyRoute('#/work')
 * 调 go('work') 被 `if(!state.workTitle) return` 挡下 → 视图停在首页。
 * 本探针把这条链路实测一遍：工作台对话跑着，中途改一个在模块图里的文件，
 * 看启动次数（sessionStorage 记的 __bootCount）有没有 +1、视图有没有掉回首页。
 *
 * 运行：node scripts/port/probe-hmr-home.mjs [baseUrl] [user] [pass] [touchFile]
 */

import { spawn } from 'node:child_process'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 18711 // 避开本机动态端口区（1024–15001）
const BASE = process.argv[2] || 'http://localhost:5181'
const USER = process.argv[3] || 'smyui0929test'
const PASS = process.argv[4] || 'Test0929a'
const TOUCH = process.argv[5] || resolve(import.meta.dirname, '../../src/legacy/prototype/core.ts')

const PROBLEM = '某工厂要在四周内安排生产计划，已知每周产能上限与需求，求成本最小的排产方案。'

const profile = mkdtempSync(join(tmpdir(), 'cdp-hmr-'))
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

  const res = await fetch(`http://127.0.0.1:${PORT}/json/new?${encodeURIComponent('about:blank')}`, { method: 'PUT' })
  const target = await res.json()
  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((r, j) => {
    ws.addEventListener('open', r)
    ws.addEventListener('error', j)
  })
  const cdp = new CDP(ws)
  await cdp.send('Runtime.enable')
  await cdp.send('Page.enable')

  const info = JSON.stringify({ userId: tokens.userId, username: tokens.username ?? USER, role: tokens.role ?? '' })
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source:
      `try{localStorage.clear();` +
      `localStorage.setItem('access_token',${JSON.stringify(tokens.accessToken)});` +
      `localStorage.setItem('refresh_token',${JSON.stringify(tokens.refreshToken || '')});` +
      `localStorage.setItem('user_id',${JSON.stringify(tokens.userId || '')});` +
      `localStorage.setItem('user_info',${JSON.stringify(info)});` +
      `window.__bootCount=(Number(sessionStorage.getItem('__bootCount')||'0')+1);` +
      `sessionStorage.setItem('__bootCount',String(window.__bootCount));}catch(e){}`,
  })

  const SNAP = `(() => {
    const on = (id) => { const el = document.getElementById(id); return !!(el && el.classList.contains('on')) }
    return {
      hash: location.hash, home: on('view-home'), work: on('view-work'),
      boot: window.__bootCount || sessionStorage.getItem('__bootCount'),
      streamLen: ((document.getElementById('stream-in') || {}).textContent || '').length,
    }
  })()`

  const evaluate = async (expr) => {
    const r = await cdp.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })
    if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails).slice(0, 200))
    return r.result.value
  }

  await cdp.send('Page.navigate', { url: `${BASE}/?probe=hmr${Date.now()}#/` })
  await sleep(4000)
  await evaluate(
    `(() => {
       const ta = document.getElementById('home-input')
       ta.value = ${JSON.stringify(PROBLEM)}
       ta.dispatchEvent(new Event('input', { bubbles: true }))
       document.getElementById('home-send').click()
       return 1
     })()`,
  )
  await sleep(3500)
  const before = await evaluate(SNAP)
  console.log('热更新前          →', JSON.stringify(before))

  /* 改一个在模块图里的文件（只动 mtime / 内容等价），触发 Vite 热更新 */
  const src = readFileSync(TOUCH, 'utf8')
  writeFileSync(TOUCH, src + '\n')
  console.log(`已改写：${TOUCH}（追加一个换行，触发 Vite）`)
  await sleep(1500)
  writeFileSync(TOUCH, src) /* 立刻改回去，不留痕 */
  console.log('已还原内容')
  await sleep(5000)

  const after = await evaluate(SNAP).catch((e) => ({ err: String(e).slice(0, 120) }))
  console.log('热更新后          →', JSON.stringify(after))

  const navigations = cdp.events.filter((e) => e.method === 'Page.frameNavigated').map((e) => e.params?.frame?.url || '')
  console.log('本页发生过的顶层导航：', navigations.length, navigations.slice(0, 4))
  if (before.boot !== after.boot) console.log(`⚠ 页面被整体重载（boot ${before.boot} → ${after.boot}）`)
  if (before.work && after.home) console.log('⚠ 重载后掉回首页 —— 与「对话时跳回首页」现象一致')
  else if (before.work && after.work) console.log('重载后仍在工作台（说明这条路径不是根因）')
} catch (e) {
  console.log('探针异常：' + (e instanceof Error ? e.message : String(e)))
} finally {
  chrome.kill()
  await sleep(300)
}
