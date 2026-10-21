/**
 * 闸门转录重建 + 打字机的离线用例（零模型调用、零后端）。
 *
 * 覆盖 `utils/analysis/gateRebuild.ts` 的三条判据与两类回退 —— 这套逻辑是
 * 「后端唤醒的闸门能不能回到前端」的唯一自愈路径，出错的表现是
 * 「对话区一片安静、用户以为没反应」，且**没有任何前端报错**，所以必须单测兜住：
 *   ① 未解决的表单工具调用 → 重建；
 *   ② 有配对 tool_result（含 state=error 的死闸门）→ 不重建；
 *   ③ 转录入参被截断（_preview/_truncated）→ 回退登记表的完整 toolInput；
 *   ④ 登记表不可达 → 退化为裸 toolCallId（只要转录入参完整，照样重建）。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import path from 'node:path'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const { build } = require(require.resolve('esbuild', { paths: [require.resolve('vite')] }))
const root = path.resolve(import.meta.dirname, '..')
globalThis.window = globalThis

const mocks = {
  '@/api/session': `
    export async function fetchSessionTranscript(){
      const g = globalThis.__gr
      if (g.transcriptError) throw new Error('transcript unavailable')
      return { messages: g.transcript }
    }`,
  '@/api/formInterrupt': `
    export async function listPendingInterrupts(){
      const g = globalThis.__gr
      if (g.formsError) throw new Error('registry unavailable')
      return g.forms
    }`,
}

const bundled = await build({
  stdin: {
    contents: `export {rebuildPendingGate, collectResolvedToolCallIds} from '@/utils/analysis/gateRebuild';
      export {Typewriter} from '@/utils/typewriter';`,
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

/* ── 构造件 ─────────────────────────────────────────────────────────────── */

const PAGES = [{ items: [{ key: 'decision', label: '审核结论', kind: 'radio-group', options: ['通过', '不通过'], required: true }] }]
/** transcript 里的一条消息 */
const msg = (blocks) => ({ role: 'assistant', content: blocks })
/** 表单类工具的 tool_call 块 */
const call = (id, input, name = 'ui_form_advanced') => ({ type: 'tool_call', id, name, input })
/** 配对结果块（state 不论 success / error 都算已解决） */
const result = (id, state = 'success') => ({ type: 'tool_result', id, state })
/** 后端 form-interrupts 登记表的一条 */
const registered = (toolCallId, toolInput, runId = 'run-1') => ({
  id: 'reply:' + toolCallId,
  toolCallId,
  message: '运行等待确认',
  metadata: { toolName: 'ui_form_advanced', toolInput, runId },
})

function env(over = {}) {
  globalThis.__gr = { transcript: [], forms: [], transcriptError: false, formsError: false, ...over }
  return globalThis.__gr
}

/* ── 判据 ───────────────────────────────────────────────────────────────── */

test('未解决的表单工具调用 → 从转录重建出可渲染的闸门', async () => {
  env({
    transcript: [msg([call('t1', JSON.stringify({ title: '确认样章', pages: PAGES }))])],
    forms: [registered('t1', JSON.stringify({ title: '确认样章', pages: PAGES }))],
  })
  const got = await mod.rebuildPendingGate('agent', 's1')
  assert.ok(got, '应当重建出闸门')
  assert.equal(got.toolCallId, 't1')
  // resume 必须用复合 interruptId，裸 toolCallId 后端认不出中断
  assert.equal(got.interruptId, 'reply:t1')
  assert.equal(got.runId, 'run-1')
  assert.equal(got.spec.title, '确认样章')
  assert.equal(got.spec.fields.length, 1)
  assert.equal(got.spec.fields[0].key, 'decision')
  // 沙箱那条通道要吃原始 pages 协议，所以原件也要带出来
  assert.equal(got.toolName, 'ui_form_advanced')
  assert.ok(Array.isArray(got.input.pages))
})

test('已提交过的闸门（有配对 tool_result）不再重建', async () => {
  env({ transcript: [msg([call('t1', JSON.stringify({ pages: PAGES })), result('t1')])] })
  assert.equal(await mod.rebuildPendingGate('agent', 's1'), null)
})

test('state=error 的死闸门也算已解决 —— 不许当待填反复弹卡', async () => {
  env({ transcript: [msg([call('t1', JSON.stringify({ pages: PAGES })), result('t1', 'error')])] })
  assert.equal(await mod.rebuildPendingGate('agent', 's1'), null)
})

test('转录入参被截断 → 回退登记表的完整 toolInput', async () => {
  const truncated = JSON.stringify({ _preview: '……', _truncated: true, originalSize: 952 })
  env({
    transcript: [msg([call('t1', truncated)])],
    forms: [registered('t1', JSON.stringify({ title: '闸门终审', pages: PAGES }))],
  })
  const got = await mod.rebuildPendingGate('agent', 's1')
  assert.ok(got, '截断入参也要能重建（kuaike 的 P-01 就是栽在这里）')
  assert.equal(got.spec.title, '闸门终审')
  assert.equal(got.interruptId, 'reply:t1')
})

test('截断且登记表也没给完整入参 → 认不出就返回 null', async () => {
  env({ transcript: [msg([call('t1', JSON.stringify({ _preview: '……', _truncated: true }))])] })
  assert.equal(await mod.rebuildPendingGate('agent', 's1'), null)
})

test('登记表不可达时退化为裸 toolCallId，仍能重建', async () => {
  env({
    transcript: [msg([call('t1', JSON.stringify({ title: '确认目录', pages: PAGES }))])],
    formsError: true,
  })
  const got = await mod.rebuildPendingGate('agent', 's1')
  assert.ok(got)
  assert.equal(got.interruptId, 't1')
  assert.equal(got.runId, '')
})

test('非表单类工具与缺 id 的块一律跳过', async () => {
  env({
    transcript: [msg([
      call('t1', JSON.stringify({ pages: PAGES }), 'bash'),
      { type: 'tool_call', name: 'ui_gate', input: JSON.stringify({ pages: PAGES }) },
    ])],
  })
  assert.equal(await mod.rebuildPendingGate('agent', 's1'), null)
})

test('ui_gate 没有 pages 时由 gate.ts 补默认审批字段，并反写回 pages', async () => {
  env({ transcript: [msg([call('t1', JSON.stringify({ title: '闸门1' }), 'ui_gate')])] })
  const got = await mod.rebuildPendingGate('agent', 's1')
  assert.ok(got)
  assert.deepEqual(got.spec.fields.map((f) => f.key), ['decision', 'feedback'])
  // 沙箱那条通道只认 pages —— 补出来的字段必须反写进 input，否则画出来是空卡
  assert.equal(got.input.pages[0].items.length, 2)
  assert.equal(got.input.pages[0].items[0].key, 'decision')
})

test('转录不可达 / 空会话 → 静默返回 null，不抛错', async () => {
  env({ transcriptError: true })
  assert.equal(await mod.rebuildPendingGate('agent', 's1'), null)
  env({ transcript: [] })
  assert.equal(await mod.rebuildPendingGate('agent', 's1'), null)
  assert.equal(await mod.rebuildPendingGate('agent', ''), null)
})

test('多张挂起闸门只取第一张（一次只让用户填一张）', async () => {
  env({
    transcript: [msg([
      call('t1', JSON.stringify({ title: '第一张', pages: PAGES })),
      call('t2', JSON.stringify({ title: '第二张', pages: PAGES })),
    ])],
  })
  const got = await mod.rebuildPendingGate('agent', 's1')
  assert.equal(got.toolCallId, 't1')
})

test('collectResolvedToolCallIds 收全部 tool_result（不看 state）', () => {
  const ids = mod.collectResolvedToolCallIds([
    msg([result('a'), result('b', 'error'), { type: 'tool_call', id: 'c', name: 'x' }]),
  ])
  assert.deepEqual([...ids].sort(), ['a', 'b'])
})

/* ── 打字机 ─────────────────────────────────────────────────────────────── */

test('打字机：flushNow 一次吐完，顺序与内容不丢', () => {
  const out = []
  const tw = new mod.Typewriter({ onDrain: (c) => out.push(c) })
  tw.append('abc')
  tw.append('def')
  tw.flushNow()
  assert.equal(out.join(''), 'abcdef')
})

test('打字机：cancel 之后剩余字符一律丢弃', () => {
  const out = []
  const tw = new mod.Typewriter({ onDrain: (c) => out.push(c) })
  tw.append('abcdef')
  tw.cancel()
  tw.flushNow()
  assert.equal(out.join(''), '')
})

test('打字机：finish 后最终文本 === 所有 delta 依序拼接（零丢失零重复）', async () => {
  const out = []
  const tw = new mod.Typewriter({ onDrain: (c) => out.push(c) })
  const deltas = ['第一段', '，', '第二段', '。', 'x'.repeat(400)]
  for (const d of deltas) tw.append(d)
  await tw.finish()
  assert.equal(out.join(''), deltas.join(''))
  assert.equal(tw.pending, 0)
})
