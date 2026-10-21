import test from 'node:test'
import assert from 'node:assert/strict'
import path from 'node:path'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const { build } = require(require.resolve('esbuild', { paths: [require.resolve('vite')] }))

const root = path.resolve(import.meta.dirname, '..')
globalThis.window = globalThis
const mocks = {
  '@/api/http': String.raw`export async function httpStream(_path, body) {
    globalThis.__api.body = body
    return new Response(globalThis.__api.events.map(e => 'data: ' + JSON.stringify(e) + '\n\n').join(''),
      { headers: { 'Content-Type': 'text/event-stream' } })
  }
  export const http = {
    put: async (path, body) => { globalThis.__api.registered.push({path, body}) },
    get: async () => [], delete: async () => {}
  }`,
  '@/api/artifact': `export async function fetchArtifactTree() { return globalThis.__api.tree }
    export async function fetchArtifactContent(_a, _b, p) { return { content: globalThis.__api.content[p] ?? '' } }`,
  '@/config': `export const AGENT_ID = 'test-agent'`,
  '@/api/analysisRun': `export function flattenArtifacts(nodes) {
    return nodes.flatMap(n => n.type === 'dir' ? flattenArtifacts(n.children ?? []) : [n])
  }`,
}
const loader = {
  name: 'test-imports',
  setup(api) {
    api.onResolve({ filter: /^@\// }, args => mocks[args.path]
      ? { path: args.path, namespace: 'mock' }
      : { path: path.join(root, 'src', args.path.slice(2)) + '.ts' })
    api.onLoad({ filter: /.*/, namespace: 'mock' }, args => ({ contents: mocks[args.path], loader: 'js' }))
  },
}
async function load(entry) {
  const out = await build({ entryPoints: [path.join(root, entry)], bundle: true, platform: 'node',
    format: 'esm', write: false, plugins: [loader] })
  return import('data:text/javascript;base64,' + Buffer.from(out.outputFiles[0].text).toString('base64'))
}
const animation = await load('src/api/animRun.ts')
const code = await load('src/api/codeRun.ts')
const agui = await load('src/api/agui.ts')
const wait = await load('src/api/waitForArtifact.ts')
const node = (p, updatedAt) => ({ path: p, name: path.basename(p), type: 'file', updatedAt })
const setup = (tree, content, events = [{ type: 'RUN_FINISHED', outcome: { type: 'success' } }]) => {
  globalThis.__api = { tree, content, events, body: null, registered: [] }
}

test('AG-UI v2 消息使用文本块，并识别流内终态失败', () => {
  assert.deepEqual(agui.userMessage('u1', '题目'),
    { id: 'u1', role: 'user', content: [{ type: 'text', text: '题目' }] })
  assert.equal(agui.runEventError({ type: 'RUN_ERROR', message: '拒绝运行' }), '拒绝运行')
  assert.equal(agui.runEventError({ type: 'RUN_FINISHED', outcome: { type: 'error', message: '失败' } }), '失败')
  assert.equal(agui.runEventError({ type: 'RAW', event: { error: '会话忙' } }), '会话忙')
  assert.equal(agui.runEventError({ type: 'RAW', event: { message: '普通进度' } }), '')
})

test('S2 收新动画而不把旧产物当本轮结果', async () => {
  const old = 'old/artifacts/case/animation/01.html'
  const fresh = 'new/artifacts/case/animation/01.html'
  setup([node(old, Date.now() - 60_000), node(fresh, Date.now())],
    { [old]: '旧', [fresh]: '新' })
  const result = await animation.runAnimationStage({ case_id: 'case', title: '题' },
    { threadId: 'thread', runId: 'r1', timeoutMs: 300 })
  assert.equal(result.anims[0].html, '新')
  assert.deepEqual(globalThis.__api.body.messages[0].content[0].type, 'text')
})

test('S2 RUN_ERROR 即失败，不能继续用历史动画', async () => {
  setup([node('old/artifacts/case/animation/01.html', Date.now() - 60_000)], {},
    [{ type: 'RUN_ERROR', message: '当前会话忙' }, { type: 'RUN_FINISHED', outcome: { type: 'error' } }])
  const result = await animation.runAnimationStage({ case_id: 'case', title: '题' },
    { threadId: 'thread', runId: 'r2', timeoutMs: 300 })
  assert.equal(result.status, 'BLOCKED')
  assert.equal(result.failureNote, '当前会话忙')
})

test('S3 按问读取本轮代码，不串其它问题', async () => {
  const q1 = 'artifacts/case/code/q01_m01_a01/main.py'
  const q2 = 'artifacts/case/code/q02_m01_a01/main.py'
  setup([node(q1, Date.now()), node(q2, Date.now())], { [q1]: '问题一', [q2]: '问题二' })
  const result = await code.runCodeStage({ case_id: 'case', title: '题', question_id: 2,
    model: { name: '模型A' } }, { threadId: 'thread', runId: 'r3', timeoutMs: 300 })
  assert.equal(result.pkg?.mainText, '问题二')
  assert.equal(result.pkg?.model, '模型A')
})

test('S3 未收到终态时直接报告断流，不等待旧产物', async () => {
  setup([node('artifacts/case/code/q01_m01_a01/main.py', Date.now())], {},
    [{ type: 'TEXT_MESSAGE_CONTENT', delta: '开始' }])
  const result = await code.runCodeStage({ case_id: 'case', title: '题', question_id: 1,
    model: { name: '模型A' } }, { threadId: 'thread', runId: 'r4', timeoutMs: 300 })
  assert.match(result.failureNote, /RUN_FINISHED/)
})

test('后台轮询支持晚到产物与取消', async () => {
  let attempt = 0
  const value = await wait.waitForArtifact(async () => ++attempt === 2 ? '产物' : null,
    Date.now() + 1000, undefined, 5)
  assert.equal(value, '产物')
  const ctl = new AbortController()
  setTimeout(() => ctl.abort(), 10)
  const cancelled = await wait.waitForArtifact(async () => null, Date.now() + 1000, ctl.signal, 500)
  assert.equal(cancelled, null)
})
