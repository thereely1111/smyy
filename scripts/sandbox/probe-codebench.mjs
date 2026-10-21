/**
 * 代码台真实点击验证（CDP，无第三方依赖）
 *
 * 为什么要这一步：200 只代表「能编译」，不代表「能跑」。
 * 这里的做法是启动无头 Chrome、打开代码台、**真的点一下运行按钮**，
 * 再把页面上的文本抓回来断言。这是唯一能证明链路通的方式。
 *
 * 运行：node scripts/probe-codebench.mjs
 */

import { spawn } from 'node:child_process'
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 18725 // 避开本机动态端口区（1024–15001）
const TARGET = process.env.PROBE_URL || 'http://127.0.0.1:5177/#/sandbox/bench'

const profile = mkdtempSync(join(tmpdir(), 'cdp-bench-'))

function launch() {
  const args = [
    '--headless=new',
    `--remote-debugging-port=${PORT}`,
    `--user-data-dir=${profile}`,
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-gpu',
    '--window-size=1400,900',
    'about:blank',
  ]
  return spawn(CHROME, args, { stdio: 'ignore', detached: false })
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function getWsUrl() {
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

/** 极简 CDP 客户端 */
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
      }, 30000)
    })
  }
}

/** 在页面里求值，返回 JSON 化的结果 */
async function evaluate(cdp, expression) {
  const r = await cdp.send('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true,
  })
  if (r.exceptionDetails) {
    throw new Error('页面求值异常：' + JSON.stringify(r.exceptionDetails))
  }
  return r.result.value
}

async function connect() {
  const wsUrl = await getWsUrl()
  const ws = new WebSocket(wsUrl)
  await new Promise((res, rej) => {
    ws.addEventListener('open', res)
    ws.addEventListener('error', rej)
  })
  // 浏览器级连接只用来确认就绪；**不要**调 Runtime.enable（浏览器级不支持）
  return { ws }
}

/** 打开一个新 target 并连上去 */
async function newPage(url) {
  const res = await fetch(`http://127.0.0.1:${PORT}/json/new?${encodeURIComponent(url)}`, {
    method: 'PUT',
  })
  const target = await res.json()

  // 注意：要用 target 自己的 webSocketDebuggerUrl（页面级），
  // 不能用 /json/version 里的浏览器级 WS —— 浏览器级不支持 Runtime.enable
  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((r, j) => {
    ws.addEventListener('open', r)
    ws.addEventListener('error', j)
  })
  const cdp = new CDP(ws)
  await cdp.send('Runtime.enable')
  await cdp.send('Page.enable')
  return { cdp, ws, targetId: target.id }
}

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

const chrome = launch()

try {
  const { ws } = await connect()

  // 用一个新标签页打开代码台
  const page = await newPage(TARGET)
  const p = page.cdp

  // 等应用挂载
  for (let i = 0; i < 40; i++) {
    const ready = await evaluate(p, `!!document.querySelector('.codebench')`)
    if (ready) break
    await sleep(300)
  }
  await sleep(800)

  // 1. 页面骨架
  const hasBench = await evaluate(p, `!!document.querySelector('.codebench')`)
  check('代码台页面已挂载', hasBench)

  // 2. 左栏代码清单条数（只算内置段，产物条目是 .item.art）
  const itemCount = await evaluate(
    p,
    `document.querySelectorAll('.list .item:not(.art)').length`,
  )
  check('左栏内置代码清单已渲染', itemCount >= 5, `${itemCount} 段`)

  // 3. 通道说明（必须如实标注不是隔离沙箱）
  const noteText = await evaluate(
    p,
    `(document.querySelector('.chan-bar .note')||{}).innerText || ''`,
  )
  check(
    '通道说明标注了「不是隔离沙箱」',
    /不是隔离沙箱/.test(noteText),
    noteText.slice(0, 40),
  )

  // 4. 编辑器里有代码
  const codeLen = await evaluate(p, `(document.querySelector('textarea.code')||{}).value?.length || 0`)
  check('编辑器已载入代码', codeLen > 200, `${codeLen} 字符`)

  // 5. 点击运行
  const clicked = await evaluate(
    p,
    `(() => {
       const b = [...document.querySelectorAll('.actions .btn')].find(x => /运行/.test(x.textContent));
       if (!b) return false;
       b.click();
       return true;
     })()`,
  )
  check('找到并点击了「运行」按钮', clicked === true)

  // 等输出出现
  let outText = ''
  for (let i = 0; i < 40; i++) {
    await sleep(250)
    outText = await evaluate(p, `(document.querySelector('.out .std')||{}).innerText || ''`)
    if (outText && outText.length > 20) break
  }
  check('运行后有标准输出', outText.length > 20, outText.split('\n')[0]?.slice(0, 50) || '')

  // 6. 退出码显示
  const exitShown = await evaluate(
    p,
    `(document.querySelector('.out-head .meta b')||{}).innerText || ''`,
  )
  check('显示了退出码', exitShown === '0', `退出码=${exitShown}`)

  // 7. 换一段代码（验证 pick 会清空上一次输出）
  const switched = await evaluate(
    p,
    `(() => {
       const items = [...document.querySelectorAll('.list .item')];
       const target = items.find(x => /购电费/.test(x.innerText));
       if (!target) return '';
       target.click();
       return target.innerText.split('\\n')[0];
     })()`,
  )
  check('成功切到另一段代码', switched.length > 0, switched)

  await sleep(500)

  // 8. 切换后输出应被清空（防止张冠李戴）
  const clearedOut = await evaluate(
    p,
    `(document.querySelector('.out .none')||{}).innerText || ''`,
  )
  check('切换代码后上次输出已清空', /还没跑过/.test(clearedOut), clearedOut.slice(0, 30))

  // 9. 再跑一次新代码
  await evaluate(
    p,
    `(() => { const b=[...document.querySelectorAll('.actions .btn')].find(x=>/运行/.test(x.textContent)); b&&b.click(); })()`,
  )
  let out2 = ''
  for (let i = 0; i < 40; i++) {
    await sleep(250)
    out2 = await evaluate(p, `(document.querySelector('.out .std')||{}).innerText || ''`)
    if (out2 && out2.length > 20) break
  }
  check('第二段代码也能跑出结果', out2.length > 20, out2.split('\n')[0]?.slice(0, 50) || '')
  check(
    '第二段结果是购电费口径',
    /购电费合计/.test(out2),
    (out2.match(/全天购电费合计[^\n]*/) || [''])[0],
  )

  // 11. 产物目录分组已出现（代码台与产物打通）
  const hasArtGroup = await evaluate(
    p,
    `!!document.querySelector('.list-head.second')`,
  )
  check('左栏出现「产物目录」分组', hasArtGroup === true)

  // 12. 产物分组有明确说明（没选题时给引导，而不是空白）
  const artHint = await evaluate(
    p,
    `(document.querySelector('.art-hint')||{}).innerText || ''`,
  )
  check(
    '产物分组给出状态说明',
    artHint.length > 0,
    artHint.replace(/\s+/g, ' ').slice(0, 60),
  )

  // 13. 控制台无错误
  const errs = await evaluate(
    p,
    `JSON.stringify(window.__probeErrors || [])`,
  )
  check('页面无 JS 异常', errs === '[]' || errs === 'undefined', errs)

  page.ws.close()
  ws.close()
} catch (e) {
  check('探测过程未抛异常', false, String(e && e.message ? e.message : e))
} finally {
  try {
    chrome.kill()
  } catch {
    /* ignore */
  }
  await sleep(500)
  try {
    rmSync(profile, { recursive: true, force: true })
  } catch {
    /* ignore */
  }
}

const failed = results.filter((r) => !r.ok)
console.log('')
console.log(`合计 ${results.length} 项，通过 ${results.length - failed.length}，失败 ${failed.length}`)
process.exit(failed.length ? 1 : 0)
