/**
 * 文件编辑与运行 —— 真实点击验证（CDP，无第三方依赖）
 *
 * 验证的是本轮新加的那条闭环：
 *   打开产物文件 → 改代码 → 点运行 → 看到结果 → 保存前弹确认
 *
 * 为什么要真的点：这套逻辑散在模板、store、引擎三处，
 * 类型检查只能证明它们各自自洽，证明不了接起来能用。
 *
 * 前置：mock 模式（.env.local 里 VITE_SANDBOX_MODE=mock），dev server 已起。
 * 运行：PROBE_URL=http://127.0.0.1:5177/#/sandbox/editor/2026-C node scripts/sandbox/probe-file-editor.mjs
 */

import { spawn } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 18724 // 避开本机动态端口区（1024–15001）
const TARGET =
  process.env.PROBE_URL || 'http://127.0.0.1:5177/#/sandbox/editor/2026-C'

const profile = mkdtempSync(join(tmpdir(), 'cdp-editor-'))

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

async function newPage(url) {
  const res = await fetch(`http://127.0.0.1:${PORT}/json/new?${encodeURIComponent(url)}`, {
    method: 'PUT',
  })
  const target = await res.json()
  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((r, j) => {
    ws.addEventListener('open', r)
    ws.addEventListener('error', j)
  })
  const cdp = new CDP(ws)
  await cdp.send('Runtime.enable')
  await cdp.send('Page.enable')
  return { cdp, ws }
}

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

const chrome = launch()

try {
  const ws0 = await getWsUrl()
  const wsB = new WebSocket(ws0)
  await new Promise((r, j) => {
    wsB.addEventListener('open', r)
    wsB.addEventListener('error', j)
  })

  const page = await newPage(TARGET)
  const p = page.cdp

  // 等应用挂载（收集页面异常）
  await evaluate(
    p,
    `window.__pe=[]; window.addEventListener('error',e=>window.__pe.push(String(e.message))); true`,
  )
  for (let i = 0; i < 40; i++) {
    const ready = await evaluate(p, `!!document.querySelector('.editor-view')`)
    if (ready) break
    await sleep(300)
  }
  await sleep(900)

  // 1. 页面骨架
  check('编辑页已挂载', await evaluate(p, `!!document.querySelector('.editor-view')`))

  // 2. 左栏列出产物文件
  const fileCount = await evaluate(p, `document.querySelectorAll('.tree .node').length`)
  check('左栏列出产物文件', fileCount > 0, `${fileCount} 个`)

  // 3. 可运行文件带「跑」标记
  const runnableCount = await evaluate(
    p,
    `document.querySelectorAll('.tree .node .ext.code').length`,
  )
  check('可运行文件带「跑」标记', runnableCount >= 1, `${runnableCount} 个`)

  // 4. 点一个可运行的 .js 文件
  const picked = await evaluate(
    p,
    `(() => {
       const nodes = [...document.querySelectorAll('.tree .node')];
       const t = nodes.find(n => /\\.js$/.test(n.innerText));
       if (!t) return '';
       t.click();
       return t.innerText.trim();
     })()`,
  )
  check('选中了 .js 产物文件', picked.length > 0, picked)

  // 等文件内容加载进编辑器
  let codeLen = 0
  for (let i = 0; i < 30; i++) {
    await sleep(250)
    codeLen = await evaluate(p, `(document.querySelector('textarea.editor')||{}).value?.length || 0`)
    if (codeLen > 20) break
  }
  check('代码已载入编辑器', codeLen > 20, `${codeLen} 字符`)

  // 5. 有「运行」按钮
  const hasRun = await evaluate(
    p,
    `[...document.querySelectorAll('.p-head .btn')].some(b => /运行/.test(b.textContent))`,
  )
  check('编辑器顶栏有「运行」按钮', hasRun === true)

  // 6. 点运行
  await evaluate(
    p,
    `(() => {
       const b = [...document.querySelectorAll('.p-head .btn')].find(x => /运行/.test(x.textContent));
       b && b.click();
     })()`,
  )
  let out = ''
  for (let i = 0; i < 40; i++) {
    await sleep(250)
    out = await evaluate(
      p,
      `(document.querySelector('.out .std')||{}).innerText || ''`,
    )
    if (out && out.length > 8) break
  }
  check('运行后有输出', out.length > 8, out.split('\n')[0]?.slice(0, 60) || '')

  // 7. 证明代码真的读到了同目录的 csv（文件视图生效）
  check(
    '代码读到了沙箱内的数据文件',
    /列名|净负荷|购电费/.test(out),
    (out.match(/(列名|净负荷合计|全天购电费)[^\n]*/) || [''])[0],
  )

  // 8. 运行标记为成功
  const okMark = await evaluate(
    p,
    `(document.querySelector('.out-head b')||{}).innerText || ''`,
  )
  check('运行结果显示成功', /成功/.test(okMark), okMark)

  // 9. 改代码 → 出现「未保存」
  await evaluate(
    p,
    `(() => {
       const ta = document.querySelector('textarea.editor');
       ta.value = ta.value + '\\nconsole.log("我改过了");';
       ta.dispatchEvent(new Event('input', { bubbles: true }));
     })()`,
  )
  await sleep(400)
  const dirtyShown = await evaluate(
    p,
    `!!document.querySelector('.p-head .dirty')`,
  )
  check('改代码后出现「未保存」标记', dirtyShown === true)

  // 10. 再跑一次，输出应含改动内容（证明跑的是编辑器里的内容）
  await evaluate(
    p,
    `(() => {
       const b = [...document.querySelectorAll('.p-head .btn')].find(x => /运行/.test(x.textContent));
       b && b.click();
     })()`,
  )
  let out2 = ''
  for (let i = 0; i < 40; i++) {
    await sleep(250)
    out2 = await evaluate(
      p,
      `(document.querySelector('.out .std')||{}).innerText || ''`,
    )
    if (/我改过了/.test(out2)) break
  }
  check('改动后的代码被真正执行了', /我改过了/.test(out2))

  // 11. 保存前弹确认（覆盖的是智能体生成的文件）
  await evaluate(
    p,
    `(() => {
       const b = [...document.querySelectorAll('.p-head .btn')].find(x => /保存/.test(x.textContent));
       b && b.click();
     })()`,
  )
  await sleep(400)
  const dlg = await evaluate(
    p,
    `(document.querySelector('.dialog .d-ttl')||{}).innerText || ''`,
  )
  check('保存前弹出覆盖确认', /覆盖/.test(dlg), dlg)

  // 12. 取消后弹窗消失
  await evaluate(
    p,
    `(() => {
       const b = [...document.querySelectorAll('.dialog .d-act .btn')].find(x => /取消/.test(x.textContent));
       b && b.click();
     })()`,
  )
  await sleep(300)
  const gone = await evaluate(p, `!document.querySelector('.dialog')`)
  check('取消后确认弹窗关闭', gone === true)

  // 13. 数据文件：能打开，但明确告知不能运行
  const dataPicked = await evaluate(
    p,
    `(() => {
       const nodes = [...document.querySelectorAll('.tree .node')];
       const t = nodes.find(n => /\\.csv$/i.test(n.innerText));
       if (!t) return '';
       t.click();
       return t.innerText.trim();
     })()`,
  )
  await sleep(700)
  const noRun = await evaluate(
    p,
    `(document.querySelector('.norun')||{}).innerText || ''`,
  )
  check(
    '数据文件打开后说明「不能运行」',
    dataPicked.length > 0 && noRun.length > 0,
    noRun.slice(0, 60),
  )

  // 14. 无 JS 异常
  const errs = await evaluate(p, `JSON.stringify(window.__pe || [])`)
  check('页面无 JS 异常', errs === '[]', errs)

  page.ws.close()
  wsB.close()
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
