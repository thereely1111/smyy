#!/usr/bin/env node
/**
 * 「删掉旧任务后，同一道题再输一遍就报错」—— 现场取证探针（纯 HTTP，无 CDP、无第三方依赖）
 *
 * 背景（2026-10-13 用户报）：工作台里把上一道题的任务删掉，再把**同一道题**输一遍，
 * 对话区弹出 `智能体这条流报了错 / 该会话已被删除，请新建会话`，产物卡说没有分析稿。
 *
 * 待验的四条后端事实（缺一条这套「换新会话」的修法就不成立）：
 *   ① 一条 run 请求就能把会话登记出来（归属行落库）⇒ 会话出现在会话列表里；
 *   ② `POST /sessions/{sid}/clear` 之后，会话**从列表里消失**（列表按 deleted_at 过滤）
 *      —— 这就是前端 `judgeSessionUsable` 能识破墓碑会话的依据；
 *   ③ clear 之后**归属行还在**（置墓碑不删行）⇒ 老判据（拿附件接口探活）拿到 200、
 *      把墓碑会话读成「可用」，于是紧接着的 run 撞 410 —— 这就是本 bug 的根因；
 *   ④ clear 之后**同一个 sessionId 再发 run 必被终态拒绝 410**「该会话已被删除，请新建会话」
 *      —— 删除不可复活，前端只能换一条全新会话（就是本修复做的事）。
 *
 * ⚠️ 会真发两次 run（第二次必被拒，不烧模型）。用探针账号跑，别拿生产账号。
 * 运行：node scripts/port/probe-session-gone.mjs [baseUrl] [user] [pass]
 *       baseUrl 默认 http://localhost:8080（直连后端；也可给 http://localhost:5181 走 vite 代理）
 */

const BASE = (process.argv[2] || 'http://localhost:8080').replace(/\/+$/, '')
const USER = process.argv[3] || 'smyui0929test'
const PASS = process.argv[4] || 'Test0929a'
const AGENT_NAME = '数学建模解答助手'

/* 预建会话的提示词：只让它回两个字，把这次调用压到最小 */
const READY_PROMPT =
  '（站点自动消息）我接下来要给你一道数学建模题和几个附件。请只回复「就绪」两个字，不要做任何分析。'

const fails = []
function check(name, ok, detail = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '\n        ' + detail : ''}`)
  if (!ok) fails.push(name)
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function req(path, { method = 'GET', token, body, timeoutMs = 30000, keepStream = false } = {}) {
  const headers = { Accept: 'application/json, text/event-stream' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(BASE + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs),
  })
  if (keepStream) return res
  const text = await res.text()
  let json = null
  try { json = text ? JSON.parse(text) : null } catch { /* 非 JSON 就留原文 */ }
  return { status: res.status, text, json }
}

/* ── 1. 登录 ──────────────────────────────────────────────────────── */
const login = await req('/api/auth/login', {
  method: 'POST',
  body: { username: USER, password: PASS },
})
if (login.status !== 200 || !login.json?.accessToken) {
  console.error(`登录失败（HTTP ${login.status}）：${login.text?.slice(0, 300)}`)
  process.exit(1)
}
const token = login.json.accessToken
console.log(`已登录 ${USER}（userId=${login.json.userId}）`)

/* ── 2. 按名字解析 agentId（不猜前端推导值） ───────────────────────── */
const agents = await req('/api/agents', { token })
/* 后端是 Spring 分页体：{ content: [...], page, size, totalElements } */
const list = Array.isArray(agents.json)
  ? agents.json
  : (agents.json?.content ?? agents.json?.items ?? agents.json?.data ?? [])
const agent = list.find((a) => a?.name === AGENT_NAME)
if (!agent) {
  console.error(`没找到智能体「${AGENT_NAME}」，拿到的名字：${list.map((a) => a?.name).join(' / ')}`)
  process.exit(1)
}
const agentId = agent.id
console.log(`agentId = ${agentId}`)

const sid = `main-${crypto.randomUUID()}`
const sessionPath = `/api/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sid)}`
const runPath = `/agui/run/${encodeURIComponent(agentId)}`
const runBody = (threadId) => ({
  threadId,
  runId: `run-${crypto.randomUUID()}`,
  messages: [{ id: `u-${crypto.randomUUID()}`, role: 'user', content: [{ type: 'text', text: READY_PROMPT }] }],
})

/* ── 3. 发一条 run 把会话登记出来（等它跑完，状态行才会落库） ─────── */
console.log(`\n会话 ${sid}\n① 发一条最小 run（登记会话，等它跑完）…`)
const first = await req(runPath, { method: 'POST', token, body: runBody(sid), timeoutMs: 120000 })
const firstBody = first.text || ''
check(
  '① run 被受理并跑完（流里有 RUN_FINISHED ⇒ 状态行落库）',
  first.status === 200 && /RUN_FINISHED/.test(firstBody),
  `HTTP ${first.status} · ${firstBody.length} 字节`,
)
await sleep(1500)

/* ── 4. 会话应出现在列表里 ───────────────────────────────────────── */
const sessionsOf = async (includeArchived = true) => {
  const r = await req(`/api/agents/${encodeURIComponent(agentId)}/sessions?includeArchived=${includeArchived}`, { token })
  const arr = Array.isArray(r.json) ? r.json : (r.json?.items ?? r.json?.data ?? [])
  return arr.map((s) => s.sessionId ?? s.id)
}
const before = await sessionsOf()
check('② 登记后会话出现在会话列表里（列表是有效判据）', before.includes(sid), `列表 ${before.length} 条，含本会话=${before.includes(sid)}`)

/* ── 5. 删会话（置墓碑 + 清产物） ─────────────────────────────────── */
console.log('\n② 删掉这座会话（等价于工作台点「删除」）…')
const cleared = await req(`${sessionPath}/clear`, { method: 'POST', token })
check('③ clear 成功（HTTP 2xx）', cleared.status >= 200 && cleared.status < 300, `HTTP ${cleared.status}`)

/* ── 6. 删后：列表里没有它了（墓碑不在列表） ──────────────────────── */
const after = await sessionsOf()
check('④ 删后会话从列表里消失 ⇒ 前端判据能识破墓碑', !after.includes(sid), `列表 ${after.length} 条，含本会话=${after.includes(sid)}`)

/* ── 7. 删后：老判据（附件接口）会不会照回 200 ────────────────────── */
const att = await req(`${sessionPath}/attachments`, { token })
const attOk = att.status === 200
check(
  '⑤ 删后附件接口仍照回 200 ⇒ 旧探活把墓碑读成「可用」（本 bug 的根因）',
  attOk,
  attOk ? 'HTTP 200（归属行还在，SessionAccessGuard 放行）' : `HTTP ${att.status} —— 注意：这条不再成立，请回看根因链`,
)

/* ── 8. 删后：同一个 sessionId 再发 run 必被拒 ────────────────────── */
console.log('\n③ 拿同一个 sessionId 再发一遍 run（复刻用户现场）…')
const again = await req(runPath, { method: 'POST', token, body: runBody(sid), timeoutMs: 60000 })
const againBody = again.text || ''
const againMsg = again.json?.message || again.json?.error || ''
const goneHttp = again.status === 410
const goneStream = /已被删除/.test(againBody)
check(
  '⑥ 复用墓碑会话被拒（HTTP 410，或流内直接指认「已被删除」）',
  goneHttp || goneStream,
  `HTTP ${again.status} · ${String(againMsg || againBody).slice(0, 200).replace(/\s+/g, ' ')}`,
)

console.log(
  fails.length
    ? `\n✗ ${fails.length} 条不成立：\n   - ${fails.join('\n   - ')}\n`
    : '\n✓ 四条后端事实全部成立 ⇒ 「会话键复用墓碑会话」就是本 bug 的根因，\n  前端按会话列表判「已删 ⇒ 换新会话」（utils/analysis/sessionProbe.ts）即可自愈。\n',
)
process.exit(fails.length ? 1 : 0)
