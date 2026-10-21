/**
 * 诊断：任务切换后对话区到底发生了什么（临时脚本，不进仓库）
 * 手法：不跑真模型 —— 用 window.pushMsg 往每条任务的对话区打标记，
 *       然后反复切换，量「标记出现在哪、出现几次」。
 */
import { spawn } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9357
const BASE = 'http://localhost:5181'
const USER = 'smyui0929test'
const PASS = 'Test0929a'
const OUT = 'E:/数模演易/outputs/diag-switch'

const PA = '某工厂要在四周内安排生产计划，已知每周产能上限与需求，求成本最小的排产方案。'
const PB = '某城市要在地铁沿线布置共享单车投放点，已知各站点日均需求与调度成本，求总成本最小的投放方案。'

const profile = mkdtempSync(join(tmpdir(), 'cdp-dg-'))
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const chrome = spawn(CHROME, [
  '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`,
  '--no-first-run', '--no-default-browser-check', '--disable-gpu', '--window-size=1440,1100', 'about:blank',
], { stdio: 'ignore' })

class CDP {
  constructor(ws) {
    this.ws = ws; this.id = 0; this.pending = new Map(); this.events = []
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(typeof ev.data === 'string' ? ev.data : ev.data.toString())
      if (msg.method) this.events.push(msg)
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id); this.pending.delete(msg.id)
        if (msg.error) reject(new Error(JSON.stringify(msg.error))); else resolve(msg.result)
      }
    })
  }
  send(method, params = {}) {
    const id = ++this.id
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
      setTimeout(() => { if (this.pending.has(id)) { this.pending.delete(id); reject(new Error('CDP 超时：' + method)) } }, 60000)
    })
  }
}

async function browserWs() {
  for (let i = 0; i < 60; i++) {
    try { const j = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json(); if (j.webSocketDebuggerUrl) return j.webSocketDebuggerUrl } catch {}
    await sleep(250)
  }
  throw new Error('CDP 未就绪')
}

async function evalIn(cdp, expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (r.exceptionDetails) throw new Error('求值异常：' + JSON.stringify(r.exceptionDetails).slice(0, 300))
  return r.result.value
}

const PROBE = `(() => {
  const slot = document.getElementById('stream-in')
  const hosts = slot ? [...slot.querySelectorAll('[data-work-task]')] : []
  const rows = [...document.querySelectorAll('.wside .ws-row')]
  const name = (r) => ((r.querySelector('.ws-name') || {}).textContent || '')
  const body = document.body.innerHTML
  const count = (s) => body.split(s).length - 1
  return {
    hash: location.hash,
    workTitle: (document.getElementById('w-title') || {}).textContent || '',
    slotKids: slot ? slot.children.length : -1,
    hostsMounted: hosts.map((h) => h.getAttribute('data-work-task')),
    hostKids: hosts.length ? hosts[0].children.length : -1,
    hostChildInfo: hosts.length ? [...hosts[0].children].map((c) => (c.className || '') + '#' + c.textContent.length) : [],
    markA1: count('标记A一'), markA2: count('标记A二'),
    markB1: count('标记B一'), markB2: count('标记B二'),
    rows: rows.map((r) => name(r) + (r.classList.contains('on') ? '*' : '')),
  }
})()`

const clickRow = (kw) => `(() => {
  const rows = [...document.querySelectorAll('.wside .ws-row')]
  for (const r of rows) {
    if (((r.querySelector('.ws-name') || {}).textContent || '').includes(${JSON.stringify(kw)})) { r.click(); return 1 }
  }
  return -1
})()`

const push = (who, text) => `(() => { window.pushMsg({ who: ${JSON.stringify(who)}, html: '<p>${text}</p>' }); return 1 })()`

try {
  await browserWs()
  const loginRes = await fetch(BASE + '/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER, password: PASS }),
  })
  const tk = await loginRes.json()
  const info = JSON.stringify({ userId: tk.userId, username: USER, role: tk.role ?? '' })
  const seed =
    `try{if(!sessionStorage.getItem('__d')){localStorage.clear();sessionStorage.setItem('__d','1');}` +
    `localStorage.setItem('access_token',${JSON.stringify(tk.accessToken)});` +
    `localStorage.setItem('refresh_token','');` +
    `localStorage.setItem('user_id',${JSON.stringify(tk.userId || '')});` +
    `localStorage.setItem('user_info',${JSON.stringify(info)});}catch(e){}`

  const res = await fetch(`http://127.0.0.1:${PORT}/json/new?${encodeURIComponent('about:blank')}`, { method: 'PUT' })
  const t = await res.json()
  const ws = new WebSocket(t.webSocketDebuggerUrl)
  await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j) })
  const cdp = new CDP(ws)
  await cdp.send('Runtime.enable'); await cdp.send('Page.enable')
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: seed })
  await cdp.send('Page.navigate', { url: `${BASE}/?diag=${Date.now()}#/` })
  await sleep(4500)

  mkdirSync(OUT, { recursive: true })
  const shot = async (n) => {
    const png = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
    writeFileSync(join(OUT, n + '.png'), Buffer.from(png.data, 'base64'))
  }
  const writeAndStart = (text) => evalIn(cdp, `(() => {
    const ta = document.getElementById('home-input')
    ta.value = ${JSON.stringify(text)}
    ta.dispatchEvent(new Event('input', { bubbles: true }))
    document.getElementById('home-send').click(); return 1 })()`)

  await writeAndStart(PA); await sleep(5000)
  let s = await evalIn(cdp, PROBE)
  const idA = s.hostsMounted[0]
  // 立刻打标记（趁真跑还在准备）
  await evalIn(cdp, push('user', '标记A一'))
  await evalIn(cdp, push('ai', '标记A二'))
  await sleep(500)
  s = await evalIn(cdp, PROBE)
  console.log('A 打标后 :', JSON.stringify(s))
  await shot('01-A')

  await evalIn(cdp, `document.getElementById('nv-home').click(); 1`); await sleep(1200)
  await writeAndStart(PB); await sleep(5000)
  s = await evalIn(cdp, PROBE)
  const idB = s.hostsMounted[0]
  await evalIn(cdp, push('user', '标记B一'))
  await evalIn(cdp, push('ai', '标记B二'))
  await sleep(500)
  s = await evalIn(cdp, PROBE)
  console.log('B 打标后 :', JSON.stringify(s), `idA=${idA} idB=${idB}`)
  await shot('02-B')

  for (let i = 1; i <= 4; i++) {
    const kw = i % 2 === 1 ? '工厂' : '共享单车'
    await evalIn(cdp, clickRow(kw))
    await sleep(1500)
    s = await evalIn(cdp, PROBE)
    console.log(`切第 ${i} 次(→${kw}) :`, JSON.stringify(s))
    await shot(`03-${String(i).padStart(2, '0')}-${i % 2 === 1 ? 'A' : 'B'}`)
  }
} catch (e) {
  console.log('探针出错：', e instanceof Error ? e.message : e)
} finally {
  chrome.kill()
}
