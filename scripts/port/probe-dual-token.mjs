/**
 * 双 token 探针（CDP，无第三方依赖）
 *
 * 验的是「业务 token 与智能体 token 各走各的」这条链是否真的成立：
 * 两端 JWT 密钥不同、同账号 userId 也不同，一个 token 打两边必然 401。
 *
 * 判据：
 *  1. 登录后 localStorage 里有两份不同的 access_token（业务 / 智能体）
 *  2. 两份 token 的 sub 不同，且 user_id 用的是智能体那份（会话归属表在 8084 库里）
 *  3. /api/agents 带的是智能体 token → 200
 *  4. /api/smyy/* 带的是业务 token → 非 401
 *  5. 抹掉 agent token 后 ensureAgentSession() 能用 agent refresh 续回来
 *
 * 运行：node scripts/port/probe-dual-token.mjs [baseUrl]
 * 凭证：默认读 %TEMP%/smyy-probe-cred.txt（两行：账号 / 密码），避免明文进命令行
 */

import { spawn } from 'node:child_process'
import { mkdtempSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 18711
const BASE = process.argv[2] || 'http://localhost:5181'

const credFile = join(tmpdir(), 'smyy-probe-cred.txt')
const [USERNAME, PASSWORD] = readFileSync(credFile, 'utf8')
  .split(/\r?\n/)
  .map((s) => s.trim())
  .filter(Boolean)

const profile = mkdtempSync(join(tmpdir(), 'cdp-dual-'))
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
  await cdp.send('Network.enable')
  return cdp
}

async function evaluate(cdp, expression) {
  const r = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (r.exceptionDetails) {
    throw new Error('页面求值异常：' + JSON.stringify(r.exceptionDetails).slice(0, 400))
  }
  return r.result.value
}

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

/** 从 CDP 网络事件里取某个路径最后一次请求的 Authorization */
function lastAuthHeader(cdp, pathFragment) {
  let found = ''
  for (const e of cdp.events) {
    if (e.method !== 'Network.requestWillBeSent') continue
    const { request } = e.params
    if (!request.url.includes(pathFragment)) continue
    const h = request.headers || {}
    found = h.Authorization || h.authorization || ''
  }
  return found
}

const CHROME_PROC = launch()

try {
  await getBrowserWs() // 等 Chrome CDP 就绪，再建页
  const cdp = await newPage(`${BASE}/#/login`)
  await sleep(1500)

  // ── 1. 真登录（直接调前端 authApi，走 vite 编译后的真实模块） ─────────────
  const loginRes = await evaluate(
    cdp,
    `(async () => {
      const mod = await import('/src/api/auth.ts')
      try {
        const t = await mod.authApi.login(${JSON.stringify(USERNAME)}, ${JSON.stringify(PASSWORD)})
        return { ok: true, userId: t.userId, username: t.username }
      } catch (e) {
        return { ok: false, error: String(e && e.message || e) }
      }
    })()`,
  )
  check('前端登录成功', loginRes?.ok === true, loginRes?.error || loginRes?.username)

  // ── 2. 两份 token ────────────────────────────────────────────────────────
  const store = await evaluate(
    cdp,
    `(() => {
      const dec = (t) => { try { return JSON.parse(atob(t.split('.')[1])) } catch { return null } }
      const biz = localStorage.getItem('access_token') || ''
      const agent = localStorage.getItem('agent_access_token') || ''
      return {
        bizLen: biz.length,
        agentLen: agent.length,
        same: biz === agent,
        bizSub: dec(biz)?.sub || '',
        agentSub: dec(agent)?.sub || '',
        userId: localStorage.getItem('user_id') || '',
        agentUserId: localStorage.getItem('agent_user_id') || '',
        refreshLen: (localStorage.getItem('refresh_token') || '').length,
        agentRefreshLen: (localStorage.getItem('agent_refresh_token') || '').length,
      }
    })()`,
  )
  check('业务 token 已落盘', store.bizLen > 100, `len=${store.bizLen}`)
  check('智能体 token 已落盘', store.agentLen > 100, `len=${store.agentLen}`)
  check('两份 token 不相同', !!store.bizLen && !!store.agentLen && !store.same)
  check(
    '两份 token 的 sub 不同（跨后端真实身份差异）',
    !!store.bizSub && !!store.agentSub && store.bizSub !== store.agentSub,
    `${store.bizSub.slice(0, 8)} vs ${store.agentSub.slice(0, 8)}`,
  )
  check('user_id 采用智能体侧 userId', store.userId === store.agentSub, `user_id=${store.userId.slice(0, 8)}`)
  check('两份 refresh_token 均在', store.refreshLen > 100 && store.agentRefreshLen > 100)

  // ── 3. 智能体接口带 agent token → 200 ────────────────────────────────────
  const agentApi = await evaluate(
    cdp,
    `(async () => {
      const http = await import('/src/api/http.ts')
      try {
        const r = await http.http.get('/api/agents', { size: '5' })
        return { ok: true, count: (r && r.content ? r.content.length : 0) }
      } catch (e) { return { ok: false, error: String(e && e.message || e) } }
    })()`,
  )
  check('GET /api/agents 通过（智能体通道）', agentApi?.ok === true, agentApi?.error || `agents=${agentApi?.count}`)

  // ── 4. 业务接口带业务 token → 非 401 ────────────────────────────────────
  const bizApi = await evaluate(
    cdp,
    `(async () => {
      const http = await import('/src/api/http.ts')
      try {
        const r = await http.http.get('/api/smyy/cases')
        const arr = Array.isArray(r) ? r : (r && r.content) || []
        return { ok: true, count: arr.length }
      } catch (e) {
        return { ok: false, status: e && e.status, error: String(e && e.message || e) }
      }
    })()`,
  )
  check('GET /api/smyy/cases 通过（业务通道）', bizApi?.ok === true, bizApi?.error || `cases=${bizApi?.count}`)

  // ── 5. 网络层：两条路径带的 Authorization 必须是不同的两份 ────────────────
  const agentHdr = lastAuthHeader(cdp, '/api/agents')
  const bizHdr = lastAuthHeader(cdp, '/api/smyy/cases')
  check(
    '/api/agents 带的是智能体 token',
    !!agentHdr && agentHdr.replace(/^Bearer\s+/i, '') === (await evaluate(cdp, `localStorage.getItem('agent_access_token')||''`)),
    agentHdr ? agentHdr.slice(0, 20) + '…' : '无 Authorization',
  )
  check(
    '/api/smyy 带的是业务 token',
    !!bizHdr && bizHdr.replace(/^Bearer\s+/i, '') === (await evaluate(cdp, `localStorage.getItem('access_token')||''`)),
    bizHdr ? bizHdr.slice(0, 20) + '…' : '无 Authorization',
  )
  check('两条路径的 token 不相同', !!agentHdr && !!bizHdr && agentHdr !== bizHdr)

  // ── 6. 抹掉 agent token 后能用 refresh 续回来 ───────────────────────────
  const revived = await evaluate(
    cdp,
    `(async () => {
      localStorage.removeItem('agent_access_token')
      const mod = await import('/src/api/auth.ts')
      const ok = await mod.authApi.ensureAgentSession()
      const token = localStorage.getItem('agent_access_token') || ''
      const http = await import('/src/api/http.ts')
      let apiOk = false, err = ''
      try { await http.http.get('/api/agents', { size: '1' }); apiOk = true } catch (e) { err = String(e && e.message || e) }
      return { ok, len: token.length, apiOk, err }
    })()`,
  )
  check('agent token 丢失后可用 refresh 续期', revived?.ok === true && revived?.len > 100, `len=${revived?.len}`)
  check('续期后智能体接口仍通', revived?.apiOk === true, revived?.err)

  const errs = cdp.events
    .filter((e) => e.method === 'Runtime.exceptionThrown')
    .map((e) => (e.params?.exceptionDetails?.exception?.description || '').split('\n')[0])
    .filter(Boolean)
  check('页面无未捕获异常', errs.length === 0, errs.slice(0, 2).join(' | '))

  const fail = results.filter((r) => !r.ok).length
  console.log(`\n${fail === 0 ? 'ALL PASS' : fail + ' FAILED'}  (${results.length} 项)`)
  process.exitCode = fail === 0 ? 0 : 1
} finally {
  try {
    CHROME_PROC.kill()
  } catch {
    /* ignore */
  }
}
