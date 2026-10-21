/**
 * 演示模块外置验证（CDP，无第三方依赖）
 *
 * 为什么要这一步：把 1.82M 单文件里的 5 个内联演示抽成独立页面时，做了两个
 * 有风险的替换 —— `:host` → `html`（原来是 Shadow DOM 宿主选择器）、以及
 * 把 `<script type="text/plain">` 改成真脚本（原型是靠 runAnimScript 取出来 eval 的）。
 * 这两个替换都「看着对」，但只有真跑起来才知道。
 *
 * 判「画没画」用页内 getImageData 数非背景像素 —— 不数像素就没有证据，
 * 截图会把人骗过去（带 transition 的元素会停在过渡起点）。
 *
 * 运行：node scripts/port/probe-anims.mjs
 */

import { spawn } from 'node:child_process'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 18701 // 避开本机动态端口区（1024–15001）
const ROOT = resolve(import.meta.dirname, '../../public/anims')
const MODS = [0, 1, 2, 3, 4]

const profile = mkdtempSync(join(tmpdir(), 'cdp-anim-'))
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
      '--window-size=1440,900',
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
      }, 30000)
    })
  }
}

async function getBrowserWs() {
  for (let i = 0; i < 40; i++) {
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
  return { cdp, targetId: t.id }
}

async function evaluate(cdp, expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (r.exceptionDetails) throw new Error('页面求值异常：' + JSON.stringify(r.exceptionDetails))
  return r.result.value
}

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

/** 页内探针：数 canvas 非背景像素 + 抓关键 UI。与原型自己的绘图同源，不另立判据。 */
const PAGE_PROBE = `(() => {
  const out = { canvases: [], scenes: 0, title: '', dots: 0, prog: '', err: [] }
  const cs = [...document.querySelectorAll('canvas')]
  for (const cv of cs) {
    let ink = 0
    try {
      const g = cv.getContext('2d')
      const w = cv.width, h = cv.height
      if (w && h) {
        const d = g.getImageData(0, 0, w, h).data
        for (let i = 3; i < d.length; i += 4 * 17) { if (d[i] > 8) ink++ }
      }
    } catch (e) { out.err.push('getImageData: ' + e.message) }
    out.canvases.push({
      w: cv.width, h: cv.height,
      css: Math.round(cv.getBoundingClientRect().width) + 'x' + Math.round(cv.getBoundingClientRect().height),
      ink,
    })
  }
  out.scenes = document.querySelectorAll('.scene').length
  const t = document.querySelector('#scTitle')
  out.title = t ? (t.textContent || '').trim().slice(0, 40) : ''
  out.dots = document.querySelectorAll('#dots .dot, #dots > *').length
  const p = document.querySelector('#prog')
  out.prog = p ? (p.style.width || p.textContent || '').trim().slice(0, 24) : ''
  out.bodyInk = document.body.innerText.trim().length
  return out
})()`

const chrome = launch()
try {
  await getBrowserWs()

  for (const k of MODS) {
    const file = pathToFileURL(join(ROOT, `mod-${k}.html`)).href
    const { cdp } = await newPage(file)
    // 等启动脚本跑完（原型自己也是 rAF 逐拍推进，给它真实时钟）
    await sleep(2500)
    const r = await evaluate(cdp, PAGE_PROBE)
    const errs = cdp.events.filter((e) => e.method === 'Runtime.exceptionThrown')
    const drawnCanvas = r.canvases.filter((c) => c.ink > 50).length

    check(
      `mod-${k} 启动无未捕获异常`,
      errs.length === 0,
      errs.length ? JSON.stringify(errs[0].params.exceptionDetails?.exception?.description || '').slice(0, 120) : '',
    )
    check(`mod-${k} 场景已渲染`, r.scenes > 0, `${r.scenes} 个 .scene`)
    check(`mod-${k} 标题已填充`, r.title.length > 0, r.title)
    check(`mod-${k} 进度点已生成`, r.dots > 0, `${r.dots} 个`)
    check(
      `mod-${k} canvas 真的画了内容`,
      r.canvases.length === 0 ? false : drawnCanvas >= 1,
      r.canvases.map((c) => `${c.css} ink=${c.ink}`).join(' | ') || '页面无 canvas',
    )
    if (r.err.length) check(`mod-${k} 像素读取无异常`, false, r.err.join(';'))
  }
} catch (e) {
  check('探针执行', false, e.message)
} finally {
  chrome.kill()
}

const failed = results.filter((r) => !r.ok)
console.log(`\n共 ${results.length} 项，失败 ${failed.length} 项`)
process.exit(failed.length ? 1 : 0)
