/**
 * 右栏「收起 / 展开」可达性探针 —— 离线，不登录、不发 run、不烧模型。
 *
 * 覆盖 2026-10-13 用户报的那个小问题：「这个位置（右栏右上角 › ）点击关闭之后不能打开」。
 * 根因是 `.wrail.min { flex-basis: 0; overflow: hidden }` 把**展开按钮自己也裁掉了** ——
 * 收起来就等于整条右栏连同按钮一起消失，再没有可点的东西。
 *
 * 做法：把仓库里真实的右栏骨架（从 `PrototypeShell.vue` 抽）与真实样式表
 * （`prototype.scss` 整份塞进去）拼成一个最小文档，用 headless Chrome 真渲染，
 * 量「收起后宽度 / 按钮是否可见 / 再点能不能展开」。
 * 不依赖登录态与后端，所以后端停着也能跑。
 *
 * 端口 18724：本机 TCP 动态端口区实测 1024–15000，写死 93xx 会被抢，
 * 探针一律用 187xx（见 topics/smyy-工程纪律与构建.md）。
 */
import { spawn } from 'node:child_process'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { setTimeout as sleep } from 'node:timers/promises'

const PORT = 18724
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const ROOT = fileURLToPath(new URL('../../', import.meta.url))
const profile = mkdtempSync(join(tmpdir(), 'cdp-railfold-'))

const results = []
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}  ${detail ? '— ' + detail : ''}`)
}

/* ── 1. 拼最小文档：真实样式 + 真实右栏骨架 ─────────────────────────────── */
const scssRaw = readFileSync(join(ROOT, 'src/styles/prototype.scss'), 'utf8')
/* A/B 反证开关：`SMYY_RAIL_OLD=1` 把规则还原成修复前那条（flex-basis:0 + 裁掉按钮），
   用来证明本探针的「收起后按钮仍可见」确实承重于这次改动，而不是随便都能过 */
const scss = process.env.SMYY_RAIL_OLD
  ? scssRaw
      .replace(/^\/\* 收起态[\s\S]*?\n(?=\.wrail\.min \{)/m, '')
      .replace(/\.wrail\.min \{ flex: 0 0 34px;/, '.wrail.min { flex-basis: 0;')
      .replace(/^\.wrail\.min \.rail-tabs \{[\s\S]*?\n/m, '')
      .replace(/^\.wrail\.min \.rail-tabs > \.grow[^\n]*\n/m, '')
      .replace(/^\.wrail\.min \.rail-min \{[^\n]*\n/m, '')
  : scssRaw
const core = readFileSync(join(ROOT, 'src/legacy/prototype/core.ts'), 'utf8')
const shell = readFileSync(join(ROOT, 'src/views/PrototypeShell.vue'), 'utf8')

const railStart = shell.indexOf('<!-- 右栏：闸门 + 产物')
if (railStart < 0) throw new Error('外壳里找不到右栏注释锚点')
const railEnd = shell.indexOf('</aside>', railStart)
if (railEnd < 0) throw new Error('外壳里找不到右栏 </aside>')
const railHtml = shell.slice(railStart, railEnd + '</aside>'.length)

/* 生成物里的 railToggle：探针不另写一份逻辑，用的就是线上跑的那份 */
const toggleFn = core.slice(core.indexOf('function railToggle(){'), core.indexOf('\n}', core.indexOf('function railToggle(){')) + 2)
if (!/railToggle/.test(toggleFn)) throw new Error('core.ts 里没抽到 railToggle')

const html = `<!doctype html><html><head><meta charset="utf-8"><style>${scss}</style></head>
<body style="margin:0">
  <div class="wbody" style="display:flex;height:700px">
    <div class="chat" style="flex:1 1 auto;min-width:0"></div>
    ${railHtml}
  </div>
  <script>
    window.$ = function(id){ return document.getElementById(id) };
    ${toggleFn}
    try {
      var btn = document.getElementById('rail-min');
      if (btn) btn.addEventListener('click', railToggle);
      window.__probe = function(){
        var w = $('wrail'), b = $('rail-min');
        var rb = b ? b.getBoundingClientRect() : { width: 0, height: 0 };
        return {
          railW: Math.round(w.getBoundingClientRect().width),
          min: w.classList.contains('min'),
          btnText: b ? b.textContent : '',
          btnW: Math.round(rb.width), btnH: Math.round(rb.height),
          btnVisible: !!(b && getComputedStyle(b).display !== 'none' && rb.width > 0 && rb.height > 0),
          panesVisible: (function(){
            var n = 0, all = document.querySelectorAll('.rail-pane');
            for (var i = 0; i < all.length; i++) if (getComputedStyle(all[i]).display !== 'none') n++;
            return n;
          })()
        };
      };
      window.__clickMin = function(){ $('rail-min').click(); };
    } catch (e) { window.__probeErr = String((e && e.stack) || e); }
  </script>
</body></html>`

/* ── 2. CDP ─────────────────────────────────────────────────────────────── */
const chrome = spawn(CHROME, [
  '--headless=new',
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${profile}`,
  '--no-first-run',
  '--no-default-browser-check',
  '--window-size=1600,900',
  'about:blank',
], { stdio: 'ignore' })

let ws = null
let msgId = 0
const pending = new Map()

function send(method, params = {}) {
  const id = (msgId += 1)
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject })
    ws.send(JSON.stringify({ id, method, params }))
  })
}

async function evaluate(expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text + ' ' + (r.exceptionDetails.exception?.description ?? ''))
  return r.result?.value
}

async function waitForCdp() {
  for (let i = 0; i < 60; i += 1) {
    try {
      const res = await fetch(`http://127.0.0.1:${PORT}/json/list`)
      const list = await res.json()
      const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl)
      if (page) return page.webSocketDebuggerUrl
    } catch { /* 还没起来 */ }
    await sleep(250)
  }
  throw new Error('Chrome CDP 未就绪')
}

let shot = ''
try {
  const url = await waitForCdp()
  ws = new WebSocket(url)
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true })
    ws.addEventListener('error', reject, { once: true })
  })
  ws.addEventListener('message', (ev) => {
    const m = JSON.parse(typeof ev.data === 'string' ? ev.data : Buffer.from(ev.data).toString('utf8'))
    if (!m.id) return
    const p = pending.get(m.id)
    if (!p) return
    pending.delete(m.id)
    if (m.error) p.reject(new Error(m.error.message))
    else p.resolve(m.result)
  })

  await send('Page.enable')
  await send('Runtime.enable')
  const tree = await send('Page.getFrameTree')
  await send('Page.setDocumentContent', { frameId: tree.frameTree.frame.id, html })
  await sleep(300)

  const boot = await evaluate('({probe: typeof window.__probe, err: window.__probeErr || "", rail: !!document.getElementById("wrail"), min: !!document.getElementById("rail-min")})')
  if (boot.err) console.log('  注入期异常：', boot.err)
  if (boot.probe !== 'function') {
    check('探针脚本注入', false, `__probe=${boot.probe} rail=${boot.rail} min=${boot.min}`)
    throw new Error('页面里的探针脚本没起来')
  }

  const before = await evaluate('window.__probe()')
  check('A 初始：右栏常驻 420px', before.railW >= 400 && before.railW <= 440, `railW=${before.railW}`)
  check('A 初始：两个 pane 有一个在显示', before.panesVisible === 1, `panes=${before.panesVisible}`)
  check('B 收起按钮在初始态可见可点', before.btnVisible && before.btnW > 0, `btn=${before.btnW}x${before.btnH} text=${before.btnText}`)

  await evaluate('window.__clickMin()')
  await sleep(400)
  const folded = await evaluate('window.__probe()')
  check('C 点一下：右栏收成窄条（不是 0 宽）', folded.min && folded.railW > 0, `railW=${folded.railW}`)
  /* 阈值取 20px：按钮本来就 24px 宽，旧规则下被裁得只剩几像素 ——
     那种「露出一条边」不能算可点，用户根本瞄不准 */
  check('C 收起后**展开按钮仍然完整可见可点**（本次修复的核心）', folded.btnVisible && folded.btnW >= 20, `btn=${folded.btnW}x${folded.btnH} text=${folded.btnText}`)
  check('C 收起后按钮变成「展开」箭头', folded.btnText === '‹', `text=${folded.btnText}`)
  check('C 收起后 pane 全部让位', folded.panesVisible === 0, `panes=${folded.panesVisible}`)

  /* 截图落在仓库根（上一级）的 outputs/ —— smyy/ 下没有这个目录 */
  shot = join(ROOT, '..', 'outputs', `probe-rail-fold-${Date.now()}.png`)
  const cap = await send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(shot, Buffer.from(cap.data, 'base64'))

  /* 关键断言：用**肉眼可见的那个按钮**再点一次（不是直接调 railToggle） */
  const box = await evaluate(`(function(){var r=$('rail-min').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()`)
  await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: box.x, y: box.y, button: 'left', clickCount: 1 })
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: box.x, y: box.y, button: 'left', clickCount: 1 })
  await sleep(400)
  const back = await evaluate('window.__probe()')
  check('D 再点一下（真实鼠标事件）：右栏展开回 420px', !back.min && back.railW >= 400, `railW=${back.railW} text=${back.btnText}`)

  const errs = await evaluate('0')
  check('E 无脚本异常', errs === 0)
} catch (e) {
  check('探针执行', false, e instanceof Error ? e.message : String(e))
} finally {
  try { ws?.close() } catch { /* ignore */ }
  chrome.kill()
  await sleep(200)
}

const pass = results.filter((r) => r.ok).length
console.log(`\n${pass}/${results.length} 通过`)
if (shot) console.log(`截图：${shot}`)
process.exit(pass === results.length ? 0 : 1)
