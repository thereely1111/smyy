/**
 * 闸门卡片「选项行为 + 排版」探针 —— 离线，不登录、不发 run、不烧模型。
 *
 * 覆盖 2026-10-13 用户报的三件事：
 *   ① **单选组能多选** —— radio 少了 name，浏览器把每个圆点当独立控件；
 *   ② **选错了退不回去** —— 原生 radio 给不了「都不选」这个状态，要点第二下撤选；
 *   ③ **排版** —— 原来胶囊（border-radius:999px）+ align-items:center，长选项一换行
 *      就成了「圆点吊在文字中间」的怪样。
 *
 * 做法：从**生成物 core.ts 里现抽**这几个函数（esc / gateInit* / gateFieldHTML /
 * gateCardHTML / gateReadValues / gateMissing / paintGateOpts / gateFreeze / bindGateCard），
 * 配真实样式表渲进 headless Chrome，用**真实鼠标事件**点，量状态与几何。
 * 抽的是线上跑的那份代码，所以它验证的是真行为，不是探针自己写的一套。
 *
 * 端口 18725（187xx 是本站点探针的保留段）。
 */
import { spawn } from 'node:child_process'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { setTimeout as sleep } from 'node:timers/promises'

const PORT = 18725
const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const ROOT = fileURLToPath(new URL('../../', import.meta.url))
const profile = mkdtempSync(join(tmpdir(), 'cdp-gatecard-'))

const results = []
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}  ${detail ? '— ' + detail : ''}`)
}

/* ── 1. 从生成物里抽函数 ─────────────────────────────────────────────────── */
const OLD = !!process.env.SMY_GATE_OLD   /* 反证开关：把三处修复就地还原成修复前的样子 */
let coreSrc = readFileSync(join(ROOT, 'src/legacy/prototype/core.ts'), 'utf8')
let scss = readFileSync(join(ROOT, 'src/styles/prototype.scss'), 'utf8')
if (OLD) {
  coreSrc = coreSrc
    .replace(/var nameAttr=type==='radio'\?[^\n]*;/, "var nameAttr='';")
    .replace(/if\(card\.__pick\[key\]===inp\.value\)\{/, 'if(false){')
  scss = scss
    .replace('.gate-card .gate-opts { display: flex; flex-direction: column; gap: 6px; }',
      '.gate-card .gate-opts { display: flex; flex-wrap: wrap; gap: 8px; }')
    .replace(/\n\.gate-card \.gate-opt \{[^\n]*\}/,
      '\n.gate-card .gate-opt { display: inline-flex; align-items: center; gap: 5px; padding: 5px 10px; border: 1px solid var(--line, #d8e8e8); border-radius: 999px; background: #fff; color: var(--ink-2, #3d6570); font-size: 12px; cursor: pointer; }')
}
const core = coreSrc

/** 按名字把 `function NAME(...) {...}` 整段抠出来（花括号配对，字符串里的括号不管） */
function extractFn(src, name) {
  const at = src.indexOf(`function ${name}(`)
  if (at < 0) throw new Error(`core.ts 里找不到 function ${name}(`)
  let depth = 0
  for (let i = src.indexOf('{', at); i < src.length; i += 1) {
    if (src[i] === '{') depth += 1
    else if (src[i] === '}') {
      depth -= 1
      if (depth === 0) return src.slice(at, i + 1)
    }
  }
  throw new Error(`${name} 的花括号没配平`)
}

const NEEDED = [
  'esc', 'gateInitText', 'gateInitChecked', 'gateFieldHTML', 'gateCardHTML',
  'gateReadValues', 'gateMissing', 'paintGateOpts', 'gateGroupPick', 'gateFreeze', 'bindGateCard',
]
const impl = NEEDED.map((n) => extractFn(core, n)).join('\n\n')
/* 自检：抽出来的确实是「带 name」的那版实现（反证模式下故意没有，跳过） */
if (!OLD && !/name=/.test(impl)) throw new Error('抽出来的 gateFieldHTML 里没有 name= —— 单选组互斥修复没生效？')

const RADIO_FIELD = {
  key: 'figure',
  label: '图1/图2 取径口径',
  kind: 'radio-group',
  required: true,
  options: [
    '允许：按题面文字推导，分析中显式标注「图形未经视觉核验」',
    '我补充图形信息：我把图1/图2 内容文字描述给你（或上传裁剪图/另存为可读格式）后再定',
    '暂缓：q01 的「覆盖圆」结论标为待确认，不作为已证结论',
  ],
}
const CHECK_FIELD = {
  key: 'extra',
  label: '补充项',
  kind: 'checkbox-group',
  options: ['甲', '乙'],
}

const html = `<!doctype html><html><head><meta charset="utf-8"><style>${scss}</style></head>
<body style="margin:0;padding:18px;background:#f3fafa;font-family:system-ui,sans-serif">
  <div id="host"></div>
  <script>
    window.$ = function(id){ return document.getElementById(id) };
    function toast(){}
    function railGateDecide(){}
    ${impl}
    /* 事件序列（只在失败时打出来看，定位「点圆点撤不掉」这类问题）。
       监听挂在冒泡末端（不加 capture），此时卡片自己的 handler 已经跑过，
       defaultPrevented 才有意义。 */
    window.__log = [];
    ['mousedown','click','change'].forEach(function(t){
      document.addEventListener(t, function(e){
        var x = e.target;
        var card = document.querySelector('.gate-card');
        window.__log.push(t + ':' + (x.tagName || '?') + (x.type ? '/' + x.type : '') +
          ':checked=' + (!!x.checked) + ':prevented=' + e.defaultPrevented +
          ':pick=' + JSON.stringify(card ? card.__pick : null));
      });
    });
    window.__make = function(field, cardId){
      var g = { interruptId: cardId || 'reply:t1', title: '确认样章', message: '', fields: [field], submitLabel: '提交' };
      var host = $('host');
      host.innerHTML = gateCardHTML(g);
      var card = host.querySelector('.gate-card');
      bindGateCard(card, g, 'global', 0, {
        gateCancel: function(){ window.__canceled = 1 },
        gateSubmit: function(v){ window.__submitted = v }
      });
      return card;
    };
    window.__opts = function(){
      var out = [];
      var all = document.querySelectorAll('.gate-opt');
      for (var i = 0; i < all.length; i++) {
        var inp = all[i].querySelector('input');
        out.push({ on: all[i].classList.contains('on'), checked: !!inp.checked, name: inp.name, type: inp.type });
      }
      return out;
    };
    window.__values = function(){
      var card = document.querySelector('.gate-card');
      return gateReadValues(card);
    };
    window.__missing = function(){
      var card = document.querySelector('.gate-card');
      return gateMissing(card);
    };
    window.__layout = function(){
      var all = document.querySelectorAll('.gate-opt');
      var first = all[0];
      var inp = first.querySelector('input'), sp = first.querySelector('span');
      var ir = inp.getBoundingClientRect(), sr = sp.getBoundingClientRect();
      var widths = [];
      for (var i = 0; i < all.length; i++) widths.push(Math.round(all[i].getBoundingClientRect().width));
      return {
        radius: getComputedStyle(first).borderRadius,
        align: getComputedStyle(first).alignItems,
        dotTopDelta: Math.round(ir.top - sr.top),
        dotSize: Math.round(ir.width),
        widths: widths,
        scrollX: document.documentElement.scrollWidth,
      };
    };
  </script>
</body></html>`

/* ── 2. CDP ─────────────────────────────────────────────────────────────── */
const chrome = spawn(CHROME, [
  '--headless=new',
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${profile}`,
  '--no-first-run', '--no-default-browser-check',
  '--window-size=900,900',
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

/** 在某个 .gate-opt 的中心派发真实鼠标事件 */
async function clickOpt(index) {
  const box = await evaluate(`(function(){
    var l = document.querySelectorAll('.gate-opt')[${index}];
    var r = l.getBoundingClientRect();
    return { x: r.x + Math.min(30, r.width / 2), y: r.y + r.height / 2 };
  })()`)
  await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: box.x, y: box.y, button: 'left', clickCount: 1 })
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: box.x, y: box.y, button: 'left', clickCount: 1 })
  await sleep(120)
}

/** 点圆点本身（不是文字）—— 两条出口都要能撤选 */
async function clickDot(index) {
  const box = await evaluate(`(function(){
    var i = document.querySelectorAll('.gate-opt')[${index}].querySelector('input');
    var r = i.getBoundingClientRect();
    return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
  })()`)
  await send('Input.dispatchMouseEvent', { type: 'mousePressed', x: box.x, y: box.y, button: 'left', clickCount: 1 })
  await send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: box.x, y: box.y, button: 'left', clickCount: 1 })
  const immediate = await evaluate('window.__opts()')
  await sleep(120)
  return { immediate, later: await evaluate('window.__opts()') }
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
  await sleep(250)

  /* ── 单选组 ── */
  await evaluate(`window.__make(${JSON.stringify(RADIO_FIELD)}, 'reply:t1')`)
  const htmlSnap = await evaluate(`document.querySelector('.gate-card').innerHTML.slice(0, 4000)`)
  const names = await evaluate(`(function(){
    var ns = [], all = document.querySelectorAll('.gate-opt input');
    for (var i = 0; i < all.length; i++) ns.push(all[i].name);
    return ns;
  })()`)
  check('A 三个圆点共用同一个 name（互斥的前提）',
    names.length === 3 && names[0] && names[0] === names[1] && names[1] === names[2],
    `names=${JSON.stringify(names)}`)
  check('A name 里带卡片 id（多张卡不串台）', String(names[0] || '').includes('reply:t1'), `name=${names[0]}`)
  check('A 选项区是竖向排列（排版）', await evaluate(`getComputedStyle(document.querySelector('.gate-opts')).flexDirection`) === 'column')

  const s0 = await evaluate('window.__opts()')
  check('B 初始：三个都没选中', s0.every((o) => !o.checked), JSON.stringify(s0.map((o) => o.checked)))

  await clickOpt(0)
  const s1 = await evaluate('window.__opts()')
  check('B 点第 1 项：只有它选中', s1[0].checked && !s1[1].checked && !s1[2].checked, JSON.stringify(s1.map((o) => o.checked)))
  check('B 描边跟着走（.on 只在这一项）', s1[0].on && !s1[1].on && !s1[2].on, JSON.stringify(s1.map((o) => o.on)))
  check('B 读值正确', JSON.stringify(await evaluate('window.__values()')) === '{"figure":"允许：按题面文字推导，分析中显式标注「图形未经视觉核验」"}',
    JSON.stringify(await evaluate('window.__values()')))
  check('B 必填已满足（不再拦提交）', (await evaluate('window.__missing()')) === '')

  await clickOpt(1)
  const s2 = await evaluate('window.__opts()')
  check('C **单选互斥**：点第 2 项后只剩它选中（本次修复核心 #1）',
    !s2[0].checked && s2[1].checked && !s2[2].checked, JSON.stringify(s2.map((o) => o.checked)))

  await clickOpt(1)
  const s3 = await evaluate('window.__opts()')
  check('D **点第二下撤选**：单选组能回到「都不选」（本次修复核心 #2）',
    !s3[0].checked && !s3[1].checked && !s3[2].checked, JSON.stringify(s3.map((o) => o.checked)))
  check('D 撤选后描边也清掉了', !s3[0].on && !s3[1].on && !s3[2].on, JSON.stringify(s3.map((o) => o.on)))
  check('D 撤选后读值为空（必填会被拦）', JSON.stringify(await evaluate('window.__values()')) === '{}',
    JSON.stringify(await evaluate('window.__values()')))
  check('D 撤选后必填项被识别为缺失', (await evaluate('window.__missing()')) === 'figure')

  await clickOpt(2)
  await evaluate('window.__log = []')
  const dot = await clickDot(2)
  const s4 = dot.later
  const dotOk = !s4[2].checked
  check('D 点圆点本身也能撤选（文字/圆点两条出口一致）',
    dotOk, JSON.stringify(s4.map((o) => o.checked)))
  if (!dotOk) {
    console.log('   事件序列：', JSON.stringify(await evaluate('window.__log')))
    console.log('   点击后立刻：', JSON.stringify(dot.immediate.map((o) => o.checked)))
    console.log('   120ms 后：', JSON.stringify(dot.later.map((o) => o.checked)))
  }

  /* ── 多选组：不能被误伤 ── */
  await evaluate(`window.__make(${JSON.stringify(CHECK_FIELD)}, 'reply:t2')`)
  await clickOpt(0)
  await clickOpt(1)
  const s5 = await evaluate('window.__opts()')
  check('E 多选组仍可多选（对照组，没被互斥误伤）', s5[0].checked && s5[1].checked, JSON.stringify(s5.map((o) => o.checked)))
  check('E 多选组不带 name（本来就该各自独立）', !s5[0].name, `name=${JSON.stringify(s5[0].name)}`)

  /* ── 排版 ── */
  await evaluate(`window.__make(${JSON.stringify(RADIO_FIELD)}, 'reply:t3')`)
  const lay = await evaluate('window.__layout()')
  check('F 圆角改回 10px（不再是胶囊）', lay.radius === '10px', `radius=${lay.radius}`)
  check('F 圆点与文字首行对齐', Math.abs(lay.dotTopDelta) <= 5, `Δtop=${lay.dotTopDelta}px`)
  check('F 圆点尺寸统一 14px', lay.dotSize === 14, `dot=${lay.dotSize}`)
  check('F 三个选项等宽（竖向铺满）',
    lay.widths.length === 3 && new Set(lay.widths).size === 1, `widths=${JSON.stringify(lay.widths)}`)
  check('F 没有横向溢出', lay.scrollX <= 900, `scrollWidth=${lay.scrollX}`)

  shot = join(ROOT, '..', 'outputs', `probe-gate-card-${Date.now()}.png`)
  const cap = await send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(shot, Buffer.from(cap.data, 'base64'))

  check('G 注入的实现里确实有 name=（防「探针自己造了一份」）', /name=/.test(htmlSnap), `html 长度=${htmlSnap.length}`)
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
