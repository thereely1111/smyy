/**
 * 结构化执行解析器单测（零第三方依赖，用 node --test 跑）
 *
 * 运行：node scripts/test-parse-exec.mjs
 *
 * 为什么要单测：解析器面对的是「后端事件名与字段结构未完全确认」的输入，
 * 是最容易静默出错的一环。抽不出结构时必须降级而非返回空 —— 这个行为要被钉住。
 *
 * 转译方式：用 TypeScript 官方 compiler（项目 devDependency）在内存里转译，
 * 比正则剥类型可靠得多（正则漏掉 `as X` 断言会直接语法报错）。
 */

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const here = dirname(fileURLToPath(import.meta.url))

/** 定位 typescript 包（优先本项目，回落到模板工程） */
function loadTs() {
  const candidates = [
    'typescript',
    resolve(here, '../../node_modules/typescript'),
    'E:/象数演易/模板/kuaike/node_modules/typescript',
  ]
  for (const c of candidates) {
    try {
      return require(c)
    } catch {
      /* try next */
    }
  }
  throw new Error('找不到 typescript 包，请先 pnpm install')
}

const ts = loadTs()

const srcPath = resolve(here, '../../src/utils/parseToolExec.ts')
const source = readFileSync(srcPath, 'utf8')

const { outputText } = ts.transpileModule(source, {
  compilerOptions: {
    module: ts.ModuleKind.ESNext,
    target: ts.ScriptTarget.ES2020,
    removeComments: false,
  },
  fileName: 'parseToolExec.ts',
})

const mod = await import('data:text/javascript;base64,' + Buffer.from(outputText).toString('base64'))
const {
  parseToolCall,
  parseToolResult,
  ExecCollector,
  isToolCallEvent,
  isToolResultEvent,
  decodeContent,
  splitExecText,
} = mod

/* ── 真实样本回放（scripts/fixtures/real-sse-exec.txt 来自开发环境 30100） ── */
const FIXTURE = resolve(here, 'fixtures/real-sse-exec.txt')
/** 从真实 SSE 文本里取出所有事件对象 */
function readFixtureEvents() {
  const txt = readFileSync(FIXTURE, 'utf8')
  const out = []
  for (const line of txt.split(/\r?\n/)) {
    const t = line.trim()
    if (!t.startsWith('data:')) continue
    try {
      out.push(JSON.parse(t.slice(5).trim()))
    } catch {
      /* 忽略 */
    }
  }
  return out
}

test('真实 SSE 样本：解析出类型、threadId、runId', () => {
  const evs = readFixtureEvents()
  assert.ok(evs.length >= 8, `样本事件数应 >= 8，实际 ${evs.length}`)
  assert.equal(evs[0].type, 'RUN_STARTED')
  assert.equal(evs[evs.length - 1].type, 'RUN_FINISHED')
  assert.equal(evs[0].threadId, 'main-probe-003')
  assert.equal(evs[0].runId, 'run-probe-003')
})

test('真实样本：ExecCollector 端到端抽出命令与退出码', () => {
  const c = new ExecCollector()
  const evs = readFixtureEvents()

  let t = 1000
  let produced = false
  for (const e of evs) {
    t += 100
    if (c.push(e, t)) produced = true
  }

  assert.equal(produced, true, '应产出至少一条执行结果')
  const r = c.last()
  assert.equal(r.toolCallId, 'call_9df4e433d4b54fa8bdeed2cc')
  assert.equal(r.toolName, 'execute')
  assert.equal(r.command, 'pwd && echo hello')
  assert.equal(r.exitCode, 0)
  assert.equal(r.stdout, 'workspace/n')
  assert.equal(r.isError, false)
})

/* ── 真实文本协议：`Exit code: N\n\n{body}` ────────────────────── */

test('decodeContent 先剥一层 JSON 字符串字面量', () => {
  // 后端 SSE 里 content 的真实形态：外层是带转义的 JSON 字符串
  assert.equal(decodeContent('"Exit code: 0\\n\\nworkspace/n"'), 'Exit code: 0\n\nworkspace/n')
})

test('splitExecText 拆成功用例（有输出）', () => {
  const { exitCode, body } = splitExecText('Exit code: 0\n\nworkspace/n')
  assert.equal(exitCode, 0)
  assert.equal(body, 'workspace/n')
})

test('splitExecText 拆失败用例（<no output> 占位符要还原成空）', () => {
  const { exitCode, body } = splitExecText('Exit code: 3\n\n<no output>\n\nExit code: 3')
  assert.equal(exitCode, 3)
  assert.equal(body, '', '<no output> 必须被吃掉，不能显示给用户')
})

test('splitExecText 多行输出不误伤正文里的 Exit code 字样', () => {
  const { exitCode, body } = splitExecText('Exit code: 1\n\nline1\nExit code: 1 is not ok\nline2')
  assert.equal(exitCode, 1)
  assert.ok(body.includes('is not ok'), '正文中的 Exit code 不应被当作尾部标记')
})

test('非零退出码自动标记为错误', () => {
  const { result } = parseToolResult({
    type: 'TOOL_CALL_RESULT',
    toolCallId: 'c1',
    content: '"Exit code: 2\\n\\nboom"',
  })
  assert.equal(result.exitCode, 2)
  assert.equal(result.isError, true)
  assert.equal(result.stdout, 'boom')
})

test('真实格式不认识时才降级为原始文本（绝不空白）', () => {
  const { result } = parseToolResult({
    type: 'TOOL_CALL_RESULT',
    toolCallId: 'c1',
    content: '"这是一段完全非结构化的输出"',
  })
  assert.equal(result.stdout, '这是一段完全非结构化的输出')
  assert.equal(result.exitCode, null)
})

/* ── 旧的结构化 JSON 分支仍要兼容（后端将来可能改） ────────────── */
test('结构化 JSON 分支仍可用', () => {
  const { result } = parseToolResult({
    type: 'TOOL_CALL_RESULT',
    toolCallId: 'c1',
    content: { stdout: 'hello', stderr: 'warn', exitCode: 0 },
  })
  assert.equal(result.stdout, 'hello')
  assert.equal(result.stderr, 'warn')
  assert.equal(result.exitCode, 0)
})

/* ── 事件名识别：必须是宽匹配，不能写死 ─────────────────────────── */
test('工具调用/结果事件名宽匹配', () => {
  assert.equal(isToolCallEvent('TOOL_CALL_START'), true)
  assert.equal(isToolCallEvent('TOOL_CALL_ARGS'), true)
  assert.equal(isToolCallEvent('tool-call'), true)
  assert.equal(isToolCallEvent('TEXT_MESSAGE_CONTENT'), false)
  // 结果事件不应被误判成调用
  assert.equal(isToolCallEvent('TOOL_RESULT'), false)
  assert.equal(isToolResultEvent('TOOL_RESULT'), true)
  assert.equal(isToolResultEvent('TOOL_CALL_RESULT'), true)
  assert.equal(isToolResultEvent('TEXT_MESSAGE_CONTENT'), false)
})

/* ── 抽出命令 ────────────────────────────────────────────────── */
test('从 args 抽 command', () => {
  const got = parseToolCall({ type: 'TOOL_CALL_START', toolCallId: 'c1', args: { command: 'python a.py' } })
  assert.equal(got.command, 'python a.py')
  assert.equal(got.toolCallId, 'c1')
})

test('从 JSON 字符串形式的 arguments 抽 command', () => {
  const got = parseToolCall({
    type: 'TOOL_CALL_START',
    toolCallId: 'c2',
    args: JSON.stringify({ command: 'ls -la' }),
  })
  assert.equal(got.command, 'ls -la')
})

test('delta 包装形态 {name, arguments} 也能抽到 command', () => {
  const got = parseToolCall({
    type: 'TOOL_CALL_ARGS',
    toolCallId: 'c4',
    delta: JSON.stringify({ name: 'execute', arguments: JSON.stringify({ command: 'echo hi' }) }),
  })
  assert.equal(got.command, 'echo hi')
})

test('非执行类工具调用返回 null', () => {
  const got = parseToolCall({ type: 'TOOL_CALL_START', toolCallId: 'c3', args: { query: 'x' } })
  assert.equal(got, null)
})

/* ── 结构化结果 ──────────────────────────────────────────────── */
test('抽出 stdout / stderr / exitCode', () => {
  const { result } = parseToolResult({
    type: 'TOOL_RESULT',
    toolCallId: 'c1',
    content: { stdout: 'hello', stderr: 'warn', exitCode: 0 },
  })
  assert.equal(result.stdout, 'hello')
  assert.equal(result.stderr, 'warn')
  assert.equal(result.exitCode, 0)
})

test('嵌套一层的输出也能抽到', () => {
  const { result } = parseToolResult({
    type: 'TOOL_RESULT',
    toolCallId: 'c1',
    content: { result: { output: 'ok', exit_code: 2 } },
  })
  assert.equal(result.stdout, 'ok')
  assert.equal(result.exitCode, 2)
})

test('exitCode 抽不到必须是 null，不能伪造 0', () => {
  const { result } = parseToolResult({
    type: 'TOOL_RESULT',
    toolCallId: 'c1',
    content: { stdout: 'done' },
  })
  assert.equal(result.exitCode, null)
})

test('解析失败降级为原始文本，绝不空白', () => {
  const { result } = parseToolResult({
    type: 'TOOL_RESULT',
    toolCallId: 'c1',
    content: '这是一段完全非结构化的输出',
  })
  assert.equal(result.stdout, '这是一段完全非结构化的输出')
  assert.equal(result.raw, '这是一段完全非结构化的输出')
})

test('未知事件不抛错（解析器只认自己关心的）', () => {
  assert.doesNotThrow(() => parseToolCall({ type: 'RUN_STARTED' }))
  assert.doesNotThrow(() => parseToolResult({ type: 'RUN_STARTED' }))
})

/* ── 收集器：配对与计时 ────────────────────────────────────────── */
test('ExecCollector 把调用与结果配对', () => {
  const c = new ExecCollector()
  c.push({ type: 'TOOL_CALL_START', toolCallId: 'c1', args: { command: 'python a.py' } }, 1000)
  const produced = c.push({ type: 'TOOL_RESULT', toolCallId: 'c1', content: { stdout: 'ok', exitCode: 0 } }, 2500)

  assert.equal(produced, true)
  const r = c.last()
  assert.equal(r.command, 'python a.py')
  assert.equal(r.stdout, 'ok')
  assert.equal(r.elapsedMs, 1500)
})

test('ExecCollector 忽略无关事件', () => {
  const c = new ExecCollector()
  assert.equal(c.push({ type: 'TEXT_MESSAGE_CONTENT', delta: 'hi' }), false)
  assert.equal(c.list().length, 0)
})

/* ── 真实契约（2026-09-28 从 30100 前端产物核对） ────────────────── */
test('TOOL_RESULT_END 必须被认成结果（它不含 CALL 字样）', () => {
  assert.equal(isToolResultEvent('TOOL_RESULT_END'), true)
  assert.equal(isToolCallEvent('TOOL_RESULT_END'), false)
})

test('TOOL_CALL_ARGS 分片累积后才解析出命令（逐条解析会得到半截 JSON）', () => {
  const c = new ExecCollector()
  // 后端把 {"command":"python 数据处理.py"} 拆成三条下发
  c.push({ type: 'TOOL_CALL_START', toolCallId: 'c9' }, 1000)
  c.push({ type: 'TOOL_CALL_ARGS', toolCallId: 'c9', delta: '{"comm' }, 1100)
  c.push({ type: 'TOOL_CALL_ARGS', toolCallId: 'c9', delta: 'and":"python ' }, 1200)
  const produced = c.push({ type: 'TOOL_CALL_ARGS', toolCallId: 'c9', delta: '数据处理.py"}' }, 1300)
  // 分片阶段不应产出结果
  assert.equal(produced, false)

  c.push({ type: 'TOOL_RESULT_END', toolCallId: 'c9', content: { stdout: 'ok', exitCode: 0 } }, 2000)
  const r = c.last()
  assert.equal(r.command, 'python 数据处理.py')
  assert.equal(r.stdout, 'ok')
  assert.equal(r.elapsedMs, 1000)
})

test('单条完整 TOOL_CALL_ARGS 也能解析（不依赖多条分片）', () => {
  const c = new ExecCollector()
  c.push({ type: 'TOOL_CALL_START', toolCallId: 'cA' }, 0)
  c.push({ type: 'TOOL_CALL_ARGS', toolCallId: 'cA', delta: '{"command":"ls -la"}' }, 10)
  c.push({ type: 'TOOL_CALL_RESULT', toolCallId: 'cA', content: { stdout: 'x', exitCode: 0 } }, 20)
  assert.equal(c.last().command, 'ls -la')
})

test('两个并发工具调用不会串味（按 toolCallId 分别累积）', () => {
  const c = new ExecCollector()
  c.push({ type: 'TOOL_CALL_START', toolCallId: 'x1' }, 0)
  c.push({ type: 'TOOL_CALL_START', toolCallId: 'x2' }, 0)
  c.push({ type: 'TOOL_CALL_ARGS', toolCallId: 'x1', delta: '{"command":"echo A"}' }, 10)
  c.push({ type: 'TOOL_CALL_ARGS', toolCallId: 'x2', delta: '{"command":"echo B"}' }, 10)
  c.push({ type: 'TOOL_CALL_RESULT', toolCallId: 'x1', content: { stdout: 'A', exitCode: 0 } }, 20)
  c.push({ type: 'TOOL_CALL_RESULT', toolCallId: 'x2', content: { stdout: 'B', exitCode: 0 } }, 20)

  const list = c.list()
  assert.equal(list.length, 2)
  assert.equal(list[0].command, 'echo A')
  assert.equal(list[1].command, 'echo B')
})
