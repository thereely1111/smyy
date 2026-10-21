/**
 * 「把没人答的中断消费掉」的离线用例（零模型调用、零后端）。
 *
 * 覆盖 `api/interruptRelease.ts` —— 它修的是 2026-10-13 用户报的那个错：
 * 对话区弹出
 *   `Thread has unresolved interrupts; RunAgentInput.resume must address all of them`
 * 然后「这次没有拿到分析结果」，而右栏「闸门」里什么都没有。
 *
 * 根因与判据（框架 `AguiResumeCoordinator.validate` 反编译核实）：
 *   ① 线程上还挂着未决中断时，**不带 resume 的 run 一律被拒** → 就是上面那句文案；
 *   ② 带 resume 时，`resume[]` 的 interruptId 集合必须与未决集合**精确相等**
 *      （少一条报同一句，多一条报 `does not match`）；
 *   ③ 中断只登记在框架**进程内存**里 → 不主动消费，这条会话之后每次 run 都被拒。
 * 所以取消 / 认不出表单 / 连问太多次都必须发 `status:'cancelled'`；转录重建出来的
 * 闸门只对应一张卡，提交时也要把线程上其余未决一并交掉。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import path from 'node:path'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const { build } = require(require.resolve('esbuild', { paths: [require.resolve('vite')] }))
const root = path.resolve(import.meta.dirname, '..')
globalThis.window = globalThis

/** 记下发出去的每一次 run；`fail` 为真时抛错，用来看「失败也不炸」 */
globalThis.__ir = { calls: [], forms: [], formsError: false, fail: false }

const mocks = {
  '@/api/agui': `
    export async function runAgui(agentId, input, signal){
      const g = globalThis.__ir
      g.calls.push({ agentId, input })
      if (g.fail) throw new Error('网络断了')
      return { ok: true }
    }
    /* 按「这是第几次 run」消费 __ir.streamRounds 里对应的一轮事件（默认一轮都不造） */
    export async function parseAguiStream(res, onEvent, signal){
      const g = globalThis.__ir
      const rounds = g.streamRounds || []
      const round = g.calls.length - 1
      for (const ev of (rounds[round] || [])) onEvent(ev)
    }
    /* 与真实实现同判据：RUN_ERROR / RUN_FINISHED(outcome.type=error) 才算错 */
    export function runEventError(event){
      const type = String((event && event.type) || '').toUpperCase()
      const outcome = event && event.outcome
      const finishError = type === 'RUN_FINISHED' && !!outcome
        && typeof outcome === 'object' && outcome.type === 'error'
      if (type !== 'RUN_ERROR' && !finishError) return ''
      const msg = (event && (event.message || event.error))
        || (outcome && typeof outcome === 'object' ? outcome.message : '')
      return msg || '智能体运行失败'
    }`,
  '@/api/formInterrupt': `
    export async function listPendingInterrupts(agentId, sessionId){
      const g = globalThis.__ir
      if (g.formsError) throw new Error('registry unavailable')
      return g.forms
    }
    export async function listLiveInterruptIds(agentId, sessionId){
      const g = globalThis.__ir
      if (g.liveError) throw new Error('live snapshot unavailable (老后端 404)')
      return g.live || []
    }`,
}

const bundled = await build({
  stdin: {
    contents: `export {
      isUnresolvedInterruptsError, humanizeFailure, UNRESOLVED_HINT,
      pendingInterruptIds, releaseInterrupts, releasePendingInterrupts, resumeCoveringAll,
      parseMissingInterruptIds, probePendingInterruptIds, submitResumeCoveringAll,
    } from '@/api/interruptRelease';`,
    resolveDir: root,
    loader: 'ts',
  },
  bundle: true,
  platform: 'browser',
  format: 'esm',
  write: false,
  define: { 'process.env.NODE_ENV': '"production"' },
  plugins: [{
    name: 'test-alias',
    setup(api) {
      api.onResolve({ filter: /^@\// }, (a) => (mocks[a.path]
        ? { path: a.path, namespace: 'mock' }
        : { path: path.join(root, 'src', a.path.slice(2)) + '.ts' }))
      api.onLoad({ filter: /.*/, namespace: 'mock' }, (a) => ({ contents: mocks[a.path], loader: 'js' }))
    },
  }],
})
const mod = await import('data:text/javascript;base64,' + Buffer.from(bundled.outputFiles[0].text).toString('base64'))

const env = (over = {}) => {
  globalThis.__ir = {
    calls: [], forms: [], formsError: false, live: [], liveError: false, fail: false, ...over,
  }
  return globalThis.__ir
}
const registered = (toolCallId, runId = 'run-1') => ({
  id: 'reply:' + toolCallId,
  toolCallId,
  metadata: { toolName: 'ui_form_advanced', runId },
})

/* ── 判据 ───────────────────────────────────────────────────────────────── */

test('认得出「未决中断」的三种报错形状（第三种曾是漏网的）', () => {
  assert.equal(mod.isUnresolvedInterruptsError(
    'Thread has unresolved interrupts; RunAgentInput.resume must address all of them'), true)
  assert.equal(mod.isUnresolvedInterruptsError(
    'RunAgentInput.resume does not match any open interrupt'), true)
  assert.equal(mod.isUnresolvedInterruptsError(
    'RunAgentInput.resume must cover all open interrupts. missing=[reply:a], unknown=[]'), true,
  '2026-10-14 用户报的就是这一种：必须认出来，否则兜底清理永不触发')
  assert.equal(mod.isUnresolvedInterruptsError('该会话有正在执行的任务'), false)
  assert.equal(mod.isUnresolvedInterruptsError(''), false)
})

test('英文原文换成有出路的中文说法，别的错误原样带出', () => {
  const zh = mod.humanizeFailure('Thread has unresolved interrupts; RunAgentInput.resume must address all of them')
  assert.equal(zh, mod.UNRESOLVED_HINT)
  assert.ok(/右栏/.test(zh), '要告诉用户去哪儿处理')
  assert.equal(mod.humanizeFailure('等待超过 8 分钟仍没有完整结果，已放弃等待'), '等待超过 8 分钟仍没有完整结果，已放弃等待')
  assert.equal(mod.humanizeFailure(''), '')
})

test('提交时带上线程上全部未决：答的那条 resolved，其余 cancelled', () => {
  const got = mod.resumeCoveringAll('reply:a', ['reply:a', 'reply:b'], { output: '{"decision":"通过"}' })
  assert.equal(got.length, 2)
  const a = got.find((r) => r.interruptId === 'reply:a')
  const b = got.find((r) => r.interruptId === 'reply:b')
  assert.equal(a.status, 'resolved')
  assert.deepEqual(a.payload, { output: '{"decision":"通过"}' })
  assert.equal(b.status, 'cancelled', '没有卡片可填的那条不能假装答了')
})

test('线程上只有自己那一条时，resume 只发一条（不多发）', () => {
  const got = mod.resumeCoveringAll('reply:a', ['reply:a'], { output: '{}' })
  assert.equal(got.length, 1)
  assert.equal(got[0].status, 'resolved')
})

test('要答的那条不在登记表里也照样发；重复 id 去重', () => {
  const got = mod.resumeCoveringAll('reply:x', ['reply:a', 'reply:a', 'reply:x'], { output: '{}' })
  assert.deepEqual(got.map((r) => r.interruptId).sort(), ['reply:a', 'reply:x'])
  assert.equal(got.find((r) => r.interruptId === 'reply:x').status, 'resolved')
})

test('取消一条中断：发出去的是 cancelled + approved:false', async () => {
  const g = env()
  const ok = await mod.releaseInterrupts('agent', 'main-1', 'run-9', ['reply:a', 'reply:b'])
  assert.equal(ok, true)
  assert.equal(g.calls.length, 1)
  const input = g.calls[0].input
  assert.equal(input.threadId, 'main-1')
  assert.equal(input.runId, 'run-9', 'runId 要原样透传（后端认这一对）')
  assert.deepEqual(input.resume.map((r) => r.interruptId), ['reply:a', 'reply:b'])
  for (const r of input.resume) {
    assert.equal(r.status, 'cancelled')
    assert.deepEqual(r.payload, { approved: false })
  }
})

test('没有中断 / 没有会话 时不发请求', async () => {
  const g = env()
  assert.equal(await mod.releaseInterrupts('agent', 'main-1', 'run-1', []), false)
  assert.equal(await mod.releaseInterrupts('agent', '', 'run-1', ['reply:a']), false)
  assert.equal(g.calls.length, 0)
})

test('清理失败不抛错（自愈路径不能把正常流程拖下水）', async () => {
  env({ fail: true })
  assert.equal(await mod.releaseInterrupts('agent', 'main-1', 'run-1', ['reply:a']), false)
})

test('登记表拿不到 → 空数组（不炸）', async () => {
  env({ formsError: true })
  assert.deepEqual(await mod.pendingInterruptIds('agent', 'main-1'), [])
  assert.equal(await mod.releasePendingInterrupts('agent', 'main-1'), 0)
})

test('登记表没有条目 → 一条请求都不发', async () => {
  const g = env({ forms: [] })
  assert.equal(await mod.releasePendingInterrupts('agent', 'main-1'), 0)
  assert.equal(g.calls.length, 0)
})

test('登记表还有未决 → 查回来一并取消（这是「会话中毒」的自救）', async () => {
  const g = env({ forms: [registered('t1'), registered('t2')] })
  const n = await mod.releasePendingInterrupts('agent', 'main-1', 'run-1')
  assert.equal(n, 2)
  assert.equal(g.calls.length, 1)
  assert.deepEqual(g.calls[0].input.resume.map((r) => `${r.interruptId}:${r.status}`),
    ['reply:t1:cancelled', 'reply:t2:cancelled'])
})

/* ── 枚举源：框架权威快照（/interrupts）与登记表取并集 ───────────────────── */

test('只有框架快照有（登记表为空）→ 仍能解锁（这正是 2026-10-12 那条漏网的）', async () => {
  const g = env({ forms: [], live: ['reply:perm-1'] })
  assert.deepEqual(await mod.pendingInterruptIds('agent', 'main-1'), ['reply:perm-1'])
  assert.equal(await mod.releasePendingInterrupts('agent', 'main-1', 'run-1'), 1)
  assert.deepEqual(g.calls[0].input.resume.map((r) => `${r.interruptId}:${r.status}`),
    ['reply:perm-1:cancelled'])
})

test('两个源取并集且去重（同一条两处都有）', async () => {
  env({ forms: [registered('t1')], live: ['reply:t1', 'reply:t9'] })
  assert.deepEqual(await mod.pendingInterruptIds('agent', 'main-1'), ['reply:t1', 'reply:t9'])
})

test('老后端没有 /interrupts（404）→ 回落登记表，不炸', async () => {
  const g = env({ forms: [registered('t1')], liveError: true })
  assert.deepEqual(await mod.pendingInterruptIds('agent', 'main-1'), ['reply:t1'])
  assert.equal(await mod.releasePendingInterrupts('agent', 'main-1', 'run-1'), 1)
  assert.equal(g.calls.length, 1)
})

test('登记表坏了但快照好 → 只靠快照解锁', async () => {
  env({ formsError: true, live: ['reply:t7'] })
  assert.deepEqual(await mod.pendingInterruptIds('agent', 'main-1'), ['reply:t7'])
})

test('两个源都拿不到 → 空数组（自愈路径不做事）', async () => {
  env({ formsError: true, liveError: true })
  assert.deepEqual(await mod.pendingInterruptIds('agent', 'main-1'), [])
  assert.equal(await mod.releasePendingInterrupts('agent', 'main-1'), 0)
})

/* ── 2026-10-14：提交 resume 的「全量覆盖 + 从报错里补 missing」────────────────
   现场：会话 owner 是 A 账号，却用 B 账号操作 —— `/interrupts` 被归属校验挡成 404、
   `/form-interrupts` 静默返回空数组 ⇒ 前端只凑出 1 条 resume ⇒ 框架
   `must cover all open interrupts` 整体拒绝，且三条未决一条都消费不掉（死锁）。
   ────────────────────────────────────────────────────────────────────────── */

test('从框架报错里捞出 missing 列表（跨账号时唯一的解药）', () => {
  const err = 'RunAgentInput.resume must cover all open interrupts. missing=[reply:a, reply:b], unknown=[]'
  assert.deepEqual(mod.parseMissingInterruptIds(err), ['reply:a', 'reply:b'])
  assert.deepEqual(mod.parseMissingInterruptIds('missing=[id1], unknown=[id2]'), ['id1'])
  assert.deepEqual(mod.parseMissingInterruptIds('missing=[], unknown=[]'), [])
  assert.deepEqual(mod.parseMissingInterruptIds('Thread has unresolved interrupts'), [])
  assert.deepEqual(mod.parseMissingInterruptIds(''), [])
})

test('探测结果带「来源失联」标记 —— 两个源都坏就是 degraded', async () => {
  env({ formsError: true, liveError: true })
  const probe = await mod.probePendingInterruptIds('agent', 'main-1')
  assert.deepEqual(probe.ids, [])
  assert.equal(probe.degraded, true)
})

test('来源正常 → degraded=false（别把「真的没有」误报成查不到）', async () => {
  env({ forms: [registered('t1')] })
  const probe = await mod.probePendingInterruptIds('agent', 'main-1')
  assert.deepEqual(probe.ids, ['reply:t1'])
  assert.equal(probe.degraded, false)
})

test('查得到全量 → 一轮发到位，不补发', async () => {
  const g = env({ forms: [registered('other')], live: ['reply:other'] })
  const attempt = await mod.submitResumeCoveringAll({
    agentId: 'agent', threadId: 'main-1', runId: 'run-1',
    answerId: 'reply:mine', payload: { output: '{}' }, onEvent: () => {},
  })
  assert.equal(attempt.error, '')
  assert.equal(attempt.repaired, false)
  assert.equal(attempt.degraded, false)
  assert.equal(g.calls.length, 1)
  assert.deepEqual(g.calls[0].input.resume.map((r) => `${r.interruptId}:${r.status}`),
    ['reply:other:cancelled', 'reply:mine:resolved'])
})

test('查不到全量被拒 → 从报错补齐 missing 重发，一次即通过', async () => {
  const g = env({
    formsError: true, liveError: true,
    streamRounds: [
      [{ type: 'RUN_ERROR', message: 'RunAgentInput.resume must cover all open interrupts. missing=[reply:o1, reply:o2], unknown=[]' }],
      [{ type: 'TEXT_MESSAGE_CONTENT', delta: '好了' }],
    ],
  })
  const seen = []
  const attempt = await mod.submitResumeCoveringAll({
    agentId: 'agent', threadId: 'main-1', runId: 'run-1',
    answerId: 'reply:mine', payload: { output: '{}' },
    onEvent: (ev) => seen.push(ev),
  })
  assert.equal(attempt.error, '')
  assert.equal(attempt.repaired, true, '是靠补 missing 才过的')
  assert.equal(attempt.degraded, true, '两个来源都失联要如实标出来')
  assert.equal(g.calls.length, 2)
  assert.deepEqual(g.calls[0].input.resume.map((r) => r.interruptId), ['reply:mine'])
  assert.deepEqual(
    g.calls[1].input.resume.map((r) => `${r.interruptId}:${r.status}`),
    ['reply:o1:cancelled', 'reply:o2:cancelled', 'reply:mine:resolved'],
    '补进来的那些没有卡片可填，一律 cancelled',
  )
  assert.deepEqual(seen.map((e) => e.type), ['TEXT_MESSAGE_CONTENT'], '被拒那一轮的错误事件不能外抛')
})

test('补过仍失败 → 错误事件照旧交给调用方（语义等同「一次就失败」）', async () => {
  const g = env({
    formsError: true, liveError: true,
    streamRounds: [
      [{ type: 'RUN_ERROR', message: 'RunAgentInput.resume must cover all open interrupts. missing=[reply:a], unknown=[]' }],
      [{ type: 'RUN_ERROR', message: '模型崩了' }],
    ],
  })
  const seen = []
  const attempt = await mod.submitResumeCoveringAll({
    agentId: 'agent', threadId: 'main-1', runId: 'run-1',
    answerId: 'reply:mine', payload: { output: '{}' },
    onEvent: (ev) => seen.push(ev),
  })
  assert.equal(attempt.error, '模型崩了')
  assert.equal(g.calls.length, 2)
  assert.equal(seen.length, 1, '只有最终失败那次的事件才交出去')
})

test('非「集合不匹配」的错误不浪费重试', async () => {
  const g = env({
    forms: [registered('other')],
    streamRounds: [[{ type: 'RUN_ERROR', message: '模型崩了' }]],
  })
  const attempt = await mod.submitResumeCoveringAll({
    agentId: 'agent', threadId: 'main-1', runId: 'run-1',
    answerId: 'reply:mine', payload: { output: '{}' }, onEvent: () => {},
  })
  assert.equal(attempt.error, '模型崩了')
  assert.equal(g.calls.length, 1, '补了也没用，不该再发')
})
