#!/usr/bin/env node
/**
 * 硬删后端会话（前端只删了本地任务时用）。
 *
 *   node scripts/ops/purge-sessions.mjs --user u --pass p            # 列出，不删
 *   node scripts/ops/purge-sessions.mjs --user u --pass p --yes      # 真删
 *   node scripts/ops/purge-sessions.mjs --token <jwt> --match 关键词 --yes
 *
 * --base 默认 192.168.1.245:8084；--agent 按名字找智能体，--agent-id 直接给 UUID；
 * --match 只删标题含关键词的（不传 = 全部）。默认只列出，加 --yes 才删，不可逆。
 */

const args = process.argv.slice(2)

function arg(name, dflt = '') {
  const i = args.indexOf(`--${name}`)
  return i >= 0 && args[i + 1] && !args[i + 1].startsWith('--') ? args[i + 1] : dflt
}
const flag = (name) => args.includes(`--${name}`)

const BASE = arg('base', process.env.SMYY_BACKEND || 'http://192.168.1.245:8084').replace(/\/+$/, '')
const AGENT_NAME = arg('agent', '数学建模解答助手')
const AGENT_ID = arg('agent-id', '')
const MATCH = arg('match', '')
const USER = arg('user', process.env.SMYY_USER || '')
const PASS = arg('pass', process.env.SMYY_PASS || '')
const YES = flag('yes')

let token = arg('token', process.env.SMYY_TOKEN || '')

async function call(path, opts = {}) {
  const res = await fetch(BASE + path, {
    ...opts,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(opts.headers || {}),
    },
  })
  const text = await res.text()
  let json = null
  try {
    json = text ? JSON.parse(text) : null
  } catch {
    /* 网关错误页不是 JSON */
  }
  if (!res.ok) {
    throw new Error(`${opts.method || 'GET'} ${path} → ${res.status} ${json?.message || text.slice(0, 200)}`)
  }
  return json
}

async function login() {
  if (token) return
  if (!USER || !PASS) throw new Error('需要 --token，或 --user 与 --pass')
  const r = await call('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username: USER, password: PASS }),
  })
  token = r?.accessToken || ''
  if (!token) throw new Error('登录成功但没拿到 accessToken')
  console.log(`已登录：${r.username || USER}`)
}

async function resolveAgentId() {
  if (AGENT_ID) return AGENT_ID
  const page = await call('/api/agents?size=200')
  const list = page?.content || page?.items || (Array.isArray(page) ? page : [])
  const hit = list.find((a) => a.name === AGENT_NAME)
  if (!hit) throw new Error(`没找到名为「${AGENT_NAME}」的智能体，现有：${list.map((a) => a.name).join('、')}`)
  return hit.id
}

async function listSessions(agentId) {
  const r = await call(`/api/agents/${agentId}/sessions`)
  return (Array.isArray(r) ? r : r?.content || []).map((s) => ({
    id: s.sessionId || s.id, // 后端字段叫 sessionId
    title: s.title || s.summary || '(无标题)',
    updatedAt: s.updatedAt || '',
  }))
}

function fmt(t) {
  if (!t) return ''
  const d = new Date(t)
  return Number.isNaN(d.getTime()) ? String(t) : d.toLocaleString('zh-CN')
}

async function main() {
  await login()
  const agentId = await resolveAgentId()
  const all = await listSessions(agentId)
  const targets = MATCH ? all.filter((s) => (s.title || '').includes(MATCH)) : all

  console.log(`\n后端 ${BASE}`)
  console.log(`智能体 ${AGENT_NAME} (${agentId})`)
  console.log(`会话 ${all.length} 条，命中 ${targets.length} 条${MATCH ? `（关键词「${MATCH}」）` : ''}\n`)
  for (const s of targets) console.log(`  ${s.id}  ${fmt(s.updatedAt)}  ${s.title}`)

  if (!targets.length) return
  if (!YES) {
    console.log('\n这是预览。确认无误后加 --yes 真正删除（不可逆）。')
    return
  }

  let ok = 0
  let bad = 0
  for (const s of targets) {
    try {
      await call(`/api/agents/${agentId}/sessions/${s.id}/clear`, { method: 'POST' })
      ok += 1
      console.log(`  删 ${s.id}`)
    } catch (e) {
      bad += 1
      console.log(`  失败 ${s.id}  ${e.message}`)
    }
  }
  // 以删后列表为准：clear 返回 204 不等于一定没残留
  const left = await listSessions(agentId)
  console.log(`\n删掉 ${ok} 条，失败 ${bad} 条；后端现存 ${left.length} 条会话。`)
}

main().catch((e) => {
  console.error('失败：' + (e?.message || e))
  process.exit(1)
})
