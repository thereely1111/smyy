/**
 * 站点集成探针（CDP，无第三方依赖）
 *
 * 为什么要这一步：Vue SFC 里的模板错误（多一个 `</script>`、id 写错、条件写反）
 * 都不会让 `vue-tsc -b` 报错，但会让整页白屏。静态检查抓不到，只能真跑。
 *
 * 判据取「原型逻辑是不是真的动了」：原型那 162 个函数全靠 id 找 DOM，
 * 所以 `#grid` 里有卡片、`#car-track` 里有滑块，才说明 core 逻辑接上了；
 * 只看到 `.view` 存在是不够的（那可能只是模板渲染了、逻辑没跑）。
 *
 * 运行：node scripts/port/probe-site.mjs [baseUrl]
 */

import { spawn } from 'node:child_process'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 18707 // 避开本机动态端口区（1024–15001，实测 9347 绑不上）
const BASE = process.argv[2] || 'http://localhost:5181'

const profile = mkdtempSync(join(tmpdir(), 'cdp-site-'))
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
  if (r.exceptionDetails) throw new Error('页面求值异常：' + JSON.stringify(r.exceptionDetails).slice(0, 300))
  return r.result.value
}

/** 只关心真异常与 console.error，warning 不记 */
function pageErrors(cdp) {
  const out = []
  for (const e of cdp.events) {
    if (e.method === 'Runtime.exceptionThrown') {
      const d = e.params?.exceptionDetails
      out.push('uncaught: ' + (d?.exception?.description || d?.text || '').split('\n')[0])
    }
    if (e.method === 'Runtime.consoleAPICalled' && e.params?.type === 'error') {
      out.push('console.error: ' + (e.params.args || []).map((a) => a.value ?? a.description ?? '').join(' ').slice(0, 160))
    }
  }
  return out
}

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

/* ── 页内探针 ───────────────────────────────────────────────── */
const PROBE_HOME = `(() => {
  const vis = (el) => !!el && el.classList.contains('on')
  return {
    title: document.title,
    homeOn: vis(document.querySelector('#view-home')),
    cards: document.querySelectorAll('#grid .gcard').length,
    slides: document.querySelector('#car-track') ? document.querySelector('#car-track').children.length : 0,
    /* 封面图（2026-10-12）：轮播卡片应渲染 coverImage 实拍图而不是 SVG 占位图；
       loading=lazy，视口外的可能还没解码，所以「解码数」只要求 > 0（证不是坏链）。 */
    coverImgs: document.querySelectorAll('#car-track .pcard img.cover-image').length,
    coverLoaded: [...document.querySelectorAll('#car-track .pcard img.cover-image')]
      .filter((i) => i.complete && i.naturalWidth > 0).length,
    coverCss: (() => {
      const el = document.querySelector('#car-track .pcard img.cover-image')
      if (!el) return ''
      const s = getComputedStyle(el)
      return s.position + '/' + s.objectFit
    })(),
    brand: (document.querySelector('.brand-name') || {}).textContent || '',
    loginBtn: [...document.querySelectorAll('.nav-right button')].map(b => b.textContent.trim()),
  }
})()`

const PROBE_DETAIL = `(() => {
  const v = document.querySelector('#view-detail')
  return {
    detailOn: !!v && v.classList.contains('on'),
    homeOn: !!document.querySelector('#view-home.on'),
    title: (document.querySelector('#d-title') || {}).textContent || '',
    tabs: document.querySelectorAll('#d-tabs .tab').length,
    guideLen: (document.querySelector('#panel-guide') || {}).innerHTML ? document.querySelector('#panel-guide').innerHTML.length : 0,
    hash: location.hash,
  }
})()`

const PROBE_LOGIN = `(() => {
  const a = document.querySelector('.auth')
  return {
    loginOn: !!a,
    hash: location.hash,
    inputs: document.querySelectorAll('.auth input').length,
    buttons: [...document.querySelectorAll('.auth button')].map(b => b.textContent.trim()).filter(Boolean).slice(0, 8),
    textLen: a ? a.innerText.trim().length : 0,
    // 反向断言用：体验账号展示块与相关样式必须已从登录页移除
    pubTip: !!document.querySelector('.pub-tip'),
    pubCss: !!document.querySelector('.pub-c') || !!document.querySelector('.pub-fill'),
    mentionsPublic: a ? a.innerText.includes('体验账号') : false,
  }
})()`

const PROBE_SANDBOX = `(() => ({
  shell: !!document.querySelector('.app-top'),
  backSite: !!document.querySelector('.back-site'),
  loginOn: location.hash.startsWith('#/login') || !!document.querySelector('input[type=password]'),
  hash: location.hash,
  textLen: document.body.innerText.trim().length,
}))()`

/* ── 主流程 ─────────────────────────────────────────────────── */
const chrome = launch()
try {
  await getBrowserWs()

  /* 1. 首页 */
  const cdp = await newPage(BASE + '/')
  await sleep(3000)
  const home = await evaluate(cdp, PROBE_HOME)
  const e1 = pageErrors(cdp)

  check('首页无未捕获异常 / console.error', e1.length === 0, e1.slice(0, 2).join(' | '))
  check('首页视图已激活', home.homeOn)
  check('题目卡片已渲染（原型 renderGrid 已跑）', home.cards > 0, `${home.cards} 张`)
  check('轮播已渲染（原型 renderCarousel 已跑）', home.slides > 0, `${home.slides} 个滑块`)
  /* P1（2026-10-12）：首页封面图 —— 数据（coverImage）→ 渲染（img.cover-image）
     → 样式（绝对定位 + object-fit）三处都在脚本规则里，任何一处回退这里都会红。 */
  /* 轮播是按题面复制铺满的（滑块数 ≥ 题目数），所以断言「至少覆盖全部题目」而不再钉死 11 */
  check('轮播卡片用上实拍封面图（coverImage → img）', home.coverImgs >= 11 && home.coverImgs > 0, `${home.coverImgs} 张`)
  check('封面图真实解码成功（不是坏链）', home.coverLoaded > 0, `${home.coverLoaded}/${home.coverImgs} 张已解码`)
  check('封面图样式生效（absolute/cover 铺满）', home.coverCss === 'absolute/cover', home.coverCss)
  check('品牌名已渲染', home.brand === '数模演易', `“${home.brand}”`)
  check('未登录时顶栏出现登录入口', home.loginBtn.includes('登录'), home.loginBtn.join(' / '))
  check('页面标题正确', home.title.includes('数模演易'), home.title)

  /* 2. 点卡片 → 详情页（顺带验 go/openDetail 真的接上了） */
  const detailData = await evaluate(
    cdp,
    `(() => {
       const b = document.querySelector('#grid .gcard')
       if (!b) return null
       b.dispatchEvent(new MouseEvent('click', { bubbles: true }))
       return b.getAttribute('onclick')
     })()`,
  )
  await sleep(1800)
  const detail = await evaluate(cdp, PROBE_DETAIL)
  const e2 = pageErrors(cdp).slice(e1.length)
  check('点卡片进入详情页（openDetail 已接上）', detail.detailOn, detailData || '')
  check('详情页标题已填充', detail.title.length > 0, detail.title.slice(0, 40))
  check('详情页四个 Tab 在位', detail.tabs === 4, `${detail.tabs} 个`)
  check('指导书面板有内容', detail.guideLen > 500, `${detail.guideLen} 字符`)
  check('详情页无异常', e2.length === 0, e2.slice(0, 2).join(' | '))
  check('URL 已同步为题目路径（刷新不丢位置）', /#\/p\//.test(detail.hash), detail.hash)

  /* 3. 未登录访问 /mine → 应被守卫弹去登录页 */
  await evaluate(cdp, `location.hash = '#/mine'`)
  await sleep(1800)
  const guarded = await evaluate(cdp, PROBE_LOGIN)
  check('未登录访问「我的成果」被拦到登录页', guarded.loginOn, `hash=${guarded.hash}`)
  check('登录页表单已渲染', guarded.inputs >= 2 && guarded.textLen > 200, `${guarded.inputs} 个输入框 / ${guarded.textLen} 字符`)
  const e3 = pageErrors(cdp).slice(e1.length + e2.length)
  check('登录页无异常', e3.length === 0, e3.slice(0, 2).join(' | '))
  check('登录页已移除「体验账号」展示块', !guarded.pubTip && !guarded.pubCss, `pubTip=${guarded.pubTip} pubCss=${guarded.pubCss}`)
  check('登录页文案不再出现「体验账号」', !guarded.mentionsPublic)
  check('登录页已移除「一键填入」按钮', !guarded.buttons.includes('一键填入'), guarded.buttons.join(' / '))

  /* 4. 沙箱模块回归（App.vue 改动的直接影响面）
     原「沙箱环境列表」页已随预留题目数据一并删除（见 src/router/index.ts 注释），
     且沙箱子路由均 requiresAuth；未登录访问应被守卫拦到登录页。
     沙箱外壳（.app-top / .back-site）只在已登录、真正进入沙箱时渲染，
     由需登录的 probe-workbench / probe-attach 覆盖。 */
  await evaluate(cdp, `location.hash = '#/sandbox/work/probe-regression'`)
  await sleep(1800)
  const sb = await evaluate(cdp, PROBE_SANDBOX)
  const e4 = pageErrors(cdp).slice(e1.length + e2.length + e3.length)
  check('未登录访问沙箱子页被守卫拦到登录页', sb.loginOn, `hash=${sb.hash}`)
  check('沙箱页无异常', e4.length === 0, e4.slice(0, 2).join(' | '))

  /* 5. 首页直开（刷新等价）——确认不是靠内存状态活着 */
  const cdp2 = await newPage(BASE + '/#/p/p3')
  await sleep(3000)
  const direct = await evaluate(cdp2, PROBE_DETAIL)
  const e5 = pageErrors(cdp2)
  check('直接打开题目 URL 能落到详情页', direct.detailOn, `hash=${direct.hash} title=${direct.title.slice(0, 24)}`)
  check('直开题目 URL 无异常', e5.length === 0, e5.slice(0, 2).join(' | '))
} finally {
  chrome.kill()
  await sleep(300)
}

const failed = results.filter((r) => !r.ok)
console.log(`\n合计 ${results.length} 项，通过 ${results.length - failed.length}，失败 ${failed.length}`)
process.exit(failed.length ? 1 : 0)
