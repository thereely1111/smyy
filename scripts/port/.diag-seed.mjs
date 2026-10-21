/**
 * 诊断 2：预置任务仓（不跑模型）→ 反复切换，看对话区显示对不对
 * 手法：导航前把 syMy.work.tasks 写进 localStorage（每题一份不同快照），
 *       然后切换 / 刷新 / 同题面 等场景逐个量。
 */
import { spawn } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9359
const BASE = 'http://localhost:5181'
const USER = 'smyui0929test'
const PASS = 'Test0929a'
const OUT = 'E:/数模演易/outputs/diag-seed'

const profile = mkdtempSync(join(tmpdir(), 'cdp-sd-'))
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
  if (r.exceptionDetails) throw new Error('求值异常：' + JSON.stringify(r.exceptionDetails).slice(0, 400))
  return r.result.value
}

const PROBE = `(() => {
  const slot = document.getElementById('stream-in')
  const host = slot ? slot.querySelector('[data-work-task]') : null
  const rows = [...document.querySelectorAll('.wside .ws-row')]
  const name = (r) => ((r.querySelector('.ws-name') || {}).textContent || '')
  const body = document.body.innerHTML
  const count = (s) => body.split(s).length - 1
  return {
    hash: location.hash,
    home: document.getElementById('view-home').classList.contains('on'),
    work: document.getElementById('view-work').classList.contains('on'),
    detail: document.getElementById('view-detail').classList.contains('on'),
    workTitle: (document.getElementById('w-title') || {}).textContent || '',
    wTag: (document.getElementById('w-tag') || {}).textContent || '',
    mounted: host ? host.getAttribute('data-work-task') : '',
    hostText: host ? host.textContent.slice(0, 90) : '',
    slotKids: slot ? slot.children.length : -1,
    hit1: count('甲甲甲'), hit2: count('乙乙乙'), hit3: count('丙丙丙'),
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

function snap(text, tag) {
  return `<div class="msg me"><div class="bubble"><div class="txt"><p>${tag}</p></div></div></div>` +
    `<div class="msg ai"><div class="bubble"><div class="txt"><p>${text}</p></div></div></div>`
}

const TASKS = [
  { id: 'tA', title: '甲甲甲这道题', protoId: '', fromDetail: false, stage: '等待你的指令' },
  { id: 'tB', title: '乙乙乙这道题', protoId: '', fromDetail: false, stage: '问题一' },
  { id: 'tC', title: '甲甲甲这道题', protoId: '', fromDetail: false, stage: '论文' }, // 与 tA 同题面
]
const seedTasks = JSON.stringify({
  currentId: 'tA',
  items: TASKS.map((t, i) => ({
    ...t, createdAt: 1000 + i, updatedAt: 3000 - i, stream: snap(t.title, t.title), threadId: '', paused: false,
  })),
})

try {
  await browserWs()
  const lr = await fetch(BASE + '/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: USER, password: PASS }) })
  const tk = await lr.json()
  const info = JSON.stringify({ userId: tk.userId, username: USER, role: tk.role ?? '' })
  const seed =
    `try{` +
    `localStorage.setItem('access_token',${JSON.stringify(tk.accessToken)});` +
    `localStorage.setItem('refresh_token','');` +
    `localStorage.setItem('user_id',${JSON.stringify(tk.userId || '')});` +
    `localStorage.setItem('user_info',${JSON.stringify(info)});` +
    `if(!sessionStorage.getItem('__seeded')){localStorage.setItem('smyy.work.tasks',${JSON.stringify(seedTasks)});sessionStorage.setItem('__seeded','1');}` +
    `}catch(e){}`

  const res = await fetch(`http://127.0.0.1:${PORT}/json/new?${encodeURIComponent('about:blank')}`, { method: 'PUT' })
  const t = await res.json()
  const ws = new WebSocket(t.webSocketDebuggerUrl)
  await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j) })
  const cdp = new CDP(ws)
  await cdp.send('Runtime.enable'); await cdp.send('Page.enable')
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: seed })
  await cdp.send('Page.navigate', { url: `${BASE}/?diag=seed${Date.now()}#/work` })
  await sleep(5000)

  mkdirSync(OUT, { recursive: true })
  const shot = async (n) => {
    const png = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
    writeFileSync(join(OUT, n + '.png'), Buffer.from(png.data, 'base64'))
  }

  let s = await evalIn(cdp, PROBE)
  console.log('开局(#/work, 预置3条) :', JSON.stringify(s))
  await shot('01-open')

  for (const [i, kw] of [['1', '乙乙乙'], ['2', '甲甲甲这道题'], ['3', '论文'], ['4', '乙乙乙']]) {
    await evalIn(cdp, clickRow(kw))
    await sleep(1400)
    s = await evalIn(cdp, PROBE)
    console.log(`切 ${i} 次(点含「${kw}」的行) :`, JSON.stringify(s))
    await shot(`02-switch-${i}`)
  }

  // 同题面的两条（tA / tC）能不能切
  await evalIn(cdp, `(() => {
    const rows=[...document.querySelectorAll('.wside .ws-row')]
    const hit=rows.filter(r=>((r.querySelector('.ws-name')||{}).textContent||'').includes('甲甲甲'))
    if(hit[0]) hit[0].click(); return hit.length })()`)
  await sleep(1200)
  s = await evalIn(cdp, PROBE)
  console.log('切同题面第 1 条 :', JSON.stringify(s))
  await shot('03-fakeA')
  await evalIn(cdp, `(() => {
    const rows=[...document.querySelectorAll('.wside .ws-row')]
    const hit=rows.filter(r=>((r.querySelector('.ws-name')||{}).textContent||'').includes('甲甲甲'))
    if(hit[1]) hit[1].click(); return hit.length })()`)
  await sleep(1200)
  s = await evalIn(cdp, PROBE)
  console.log('切同题面第 2 条 :', JSON.stringify(s))
  await shot('04-fakeC')

  // 回首页再经顶栏回工作台
  await evalIn(cdp, `document.getElementById('nv-home').click(); 1`); await sleep(1200)
  await evalIn(cdp, `document.getElementById('nv-work').click(); 1`); await sleep(1500)
  s = await evalIn(cdp, PROBE)
  console.log('首页→顶栏工作台 :', JSON.stringify(s))
  await shot('05-back')

  // 刷新
  await cdp.send('Page.reload', { ignoreCache: false }); await sleep(6000)
  s = await evalIn(cdp, PROBE)
  console.log('刷新后 :', JSON.stringify(s))
  await shot('06-reload')
} catch (e) {
  console.log('诊断出错：', e instanceof Error ? e.message : e)
} finally {
  chrome.kill()
}
