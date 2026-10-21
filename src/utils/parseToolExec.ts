/**
 * 从 AG-UI SSE 事件里抽取结构化的执行结果。
 *
 * 为什么需要它：既有的 `execute` 工具只把结果当**文本**回传，
 * 后端不给现成的 `{stdout, stderr, exitCode}`。要结构化只能前端自己抽。
 *
 * 三条纪律（写在 docs/smyy 配置清单.md §10.3）：
 *   1. 纯函数，可单测 —— 不碰 DOM、不碰 store、不发请求
 *   2. 解析失败降级为原始文本，**绝不返回空** —— 宁可难看不可空白
 *   3. 事件名以**后端实际发出**为准 —— 这里的匹配是「宽匹配」，
 *      不写死某一个名字，以便后端事件名微调时不至于全盘失效
 *
 * ✅ 事件名已于 2026-09-28 从开发环境 30100 的前端产物里核对（真实契约）：
 *      TOOL_CALL_START / TOOL_CALL_ARGS / TOOL_CALL_DELTA / TOOL_CALL_END
 *      TOOL_CALL_RESULT / TOOL_RESULT_END
 *    注意 TOOL_CALL_ARGS 与 TOOL_CALL_DELTA 都是**分片**，同一 toolCallId 可能来多条，
 *    因此参数必须「累积拼接」后再解析，逐条解析会得到半截 JSON。
 *
 * ⚠️ 若接上后端后发现事件名不符，只改下面的 EVENT 常量，不要动解析逻辑。
 */

import type { AguiEvent } from '@/types/agui'

/** 后端真实事件名（与 AG-UI 官方协议一致） */
export const EVENT = {
  TOOL_CALL_START: 'TOOL_CALL_START',
  TOOL_CALL_ARGS: 'TOOL_CALL_ARGS',
  TOOL_CALL_DELTA: 'TOOL_CALL_DELTA',
  TOOL_CALL_END: 'TOOL_CALL_END',
  TOOL_CALL_RESULT: 'TOOL_CALL_RESULT',
  TOOL_RESULT_END: 'TOOL_RESULT_END',
} as const

/** 结构化执行结果 */
export interface ExecResult {
  /** 本次执行对应的工具调用 id */
  toolCallId: string
  /** 工具名（execute / 其他） */
  toolName: string
  /** 正在执行的命令（从 tool call 入参里抽） */
  command: string
  stdout: string
  stderr: string
  /** 退出码；抽不到为 null —— **不要伪造 0** */
  exitCode: number | null
  /** 耗时 ms；拿不到时间戳则为 null */
  elapsedMs: number | null
  /** 是否为错误结果 */
  isError: boolean
  /** 解析失败时的原始文本（兜底展示用） */
  raw: string
}

/**
 * 后端 `execute` 工具结果的**真实文本协议**（2026-09-28 实测抓取）：
 *
 *   "Exit code: 0\n\nworkspace/n"                      ← 成功且有输出
 *   "Exit code: 3\n\n<no output>\n\nExit code: 3"      ← 失败且无输出
 *
 * 要点：
 *   1. 这不是 JSON —— 是后端拼好的**纯文本**，只能用正则抽
 *   2. SSE 的 `content` 字段是「JSON 字符串字面量」（外层带引号、\n 转义），
 *      必须先 JSON.parse 一次才拿到上面的文本
 *   3. 无输出时是 `<no output>` 占位符，不是空串 —— 要还原成空，
 *      否则 UI 上会显示一行无意义的拟态文字
 */
const RE_EXIT_HEAD = /^\s*Exit code:\s*(-?\d+)\s*\n?/i
const RE_EXIT_TAIL = /\n\s*Exit code:\s*(-?\d+)\s*$/i
/** 后端用来表示「没有输出」的占位符 */
const NO_OUTPUT = '<no output>'

/**
 * 解析 `content` 为真实文本。
 *
 * `content` 一般是被 JSON 转义过的字符串字面量，例如 `"\"Exit code: 0\\n\\nx\""`。
 * 先尝试 JSON.parse；解析不出来就原样使用（后端将来直发纯文本也能兼容）。
 */
export function decodeContent(raw: unknown): string {
  if (raw == null) return ''
  if (typeof raw !== 'string') {
    // 已经是对象：交给结构化分支处理，这里给一个可读的降级文本
    try {
      return JSON.stringify(raw)
    } catch {
      return String(raw)
    }
  }
  const t = raw.trim()
  // 只有看起来像被引号包起来的字符串才尝试解析
  if (t.charCodeAt(0) === 34 || t.charCodeAt(0) === 123 || t.charCodeAt(0) === 91) {
    try {
      const parsed = JSON.parse(t)
      if (typeof parsed === 'string') return parsed
    } catch {
      /* 落回原文 */
    }
  }
  return raw
}

/**
 * 从 `Exit code: N\n\n{body}\n\nExit code: N` 这种文本里拆出退出码与正文。
 *
 * 返回的 `body` 已去掉头尾的 `Exit code` 行与 `<no output>` 占位符。
 * 抽不到退出码时 `exitCode` 为 null（**不伪造 0**）。
 */
export function splitExecText(text: string): { exitCode: number | null; body: string } {
  let body = text
  let exitCode: number | null = null

  const head = body.match(RE_EXIT_HEAD)
  if (head) {
    exitCode = Number(head[1])
    body = body.slice(head[0].length)
  }

  const tail = body.match(RE_EXIT_TAIL)
  if (tail) {
    if (exitCode === null) exitCode = Number(tail[1])
    body = body.slice(0, body.length - tail[0].length)
  }

  body = body.trim()
  // `<no output>` 是后端的占位符，还原成空串
  if (body === NO_OUTPUT) body = ''

  return { exitCode, body }
}

/**
 * 依据后端事件名做的宽匹配（大小写不敏感、允许前后缀差异）
 *
 * ⚠️ 两个正则的先后关系是关键：`TOOL_CALL_RESULT` 同时含 CALL 与 RESULT，
 *    必须先判结果再判调用，否则结果事件会被误当成调用开始（曾踩过）。
 */
const RE_TOOL_CALL = /TOOL[_\-]?CALL/i
/**
 * 结果事件。真实后端会发两种：
 *   `TOOL_CALL_RESULT`（带 CALL）与 `TOOL_RESULT_END`（**不带 CALL**）
 * 所以不能只匹配 `TOOL_CALL_RESULT`，必须放宽到「含 RESULT / RETURN 即结果」。
 */
const RE_TOOL_RESULT = /RESULT|RETURN/i
/** 事件是否是参数分片（需累积拼接后再解析） */
export const RE_TOOL_ARGS_CHUNK = /^TOOL_CALL_(ARGS|DELTA)$/i

/** 事件是否是工具调用开始（排除结果事件，避免复合名误判） */
export function isToolCallEvent(type: string): boolean {
  if (RE_TOOL_RESULT.test(type)) return false
  return RE_TOOL_CALL.test(type)
}

/** 事件是否是工具结果 */
export function isToolResultEvent(type: string): boolean {
  return RE_TOOL_RESULT.test(type)
}

/** 逐个 UTF-8 解码后的 JSON 字符串安全解析 */
function tryParse(v: unknown): unknown {
  if (typeof v !== 'string') return v
  const t = v.trim()
  if (!t) return v
  const first = t.charCodeAt(0)
  if (first !== 123 && first !== 91 && first !== 34) return v
  try {
    return JSON.parse(t)
  } catch {
    return v
  }
}

/** 深度找第一个名字匹配的字段（后端包装层数不确定，放宽） */
function pick(obj: unknown, keys: string[], depth = 3): unknown {
  if (obj == null || depth < 0) return undefined
  if (typeof obj === 'string') {
    const parsed = tryParse(obj)
    if (parsed !== obj) return pick(parsed, keys, depth - 1)
    return undefined
  }
  if (typeof obj !== 'object') return undefined
  const rec = obj as Record<string, unknown>
  for (const k of keys) {
    if (rec[k] !== undefined) return rec[k]
  }
  for (const v of Object.values(rec)) {
    const found = pick(v, keys, depth - 1)
    if (found !== undefined) return found
  }
  return undefined
}

function asString(v: unknown): string {
  if (v == null) return ''
  if (typeof v === 'string') return v
  if (Array.isArray(v)) return v.map(asString).filter(Boolean).join('\n')
  return String(v)
}

/**
 * 从工具调用事件抽出「执行什么命令」。
 * 返回 null 表示这个工具调用不是 shell 执行（沙箱只关心执行类工具）。
 *
 * 真实字段（2026-09-28 实测）：
 *   TOOL_CALL_START → { toolCallId, toolCallName: "execute" }
 *   TOOL_CALL_ARGS  → { toolCallId, delta: "{\"command\": \"...\"}" }
 */
export function parseToolCall(event: AguiEvent): { toolCallId: string; command: string } | null {
  const toolCallId = asString(event['toolCallId'] ?? event['tool_call_id'] ?? event['id'])

  // 入参可能在 delta / args / arguments，且可能还是 JSON 字符串
  let args = tryParse(event['args'] ?? event['arguments'] ?? event['delta'] ?? event['input'])
  // delta 常见形态是 {"name":"execute","arguments":"{...}"} 之类的包装
  if (args && typeof args === 'object' && !Array.isArray(args)) {
    const rec = args as Record<string, unknown>
    if (rec['command'] === undefined && rec['arguments'] !== undefined) {
      args = tryParse(rec['arguments'])
    }
  }

  const command = asString(pick(args, ['command', 'cmd', 'script']))
  if (!command) return null
  return { toolCallId, command }
}

/**
 * 从工具结果事件抽结构化结果。
 *
 * 两条路径，先真后宽：
 *   1. **真实文本协议**（后端现状）：`content` 是 `"Exit code: N\n\n..."` —— 用正则拆
 *   2. **结构化 JSON**（后端将来可能改）：`content` 里含 stdout/stderr/exitCode —— 用字段抽
 *
 * 两条都失败时**不返回 null**，而是把原始文本塞进 stdout，保证 UI 永远有东西可显示。
 */
export function parseToolResult(event: AguiEvent): { toolCallId: string; result: ExecResult } | null {
  const toolCallId = asString(event['toolCallId'] ?? event['tool_call_id'] ?? event['id'])
  const toolName = asString(event['toolCallName'] ?? event['tool_name'] ?? event['name'])
  const isError = event['isError'] === true || event['is_error'] === true

  // 结果本体可能在 content / result / output / data
  const rawBody = event['content'] ?? event['result'] ?? event['output'] ?? event['data'] ?? event['message']
  // 先解一层 JSON 字符串字面量，拿到后端实际写出的文本
  const text = decodeContent(rawBody)

  // ── 路径 1：真实文本协议 `Exit code: N\n\n{body}` ──
  const { exitCode: textExit, body } = splitExecText(text)
  if (textExit !== null) {
    return {
      toolCallId,
      result: {
        toolCallId,
        toolName,
        command: '',
        stdout: body,
        stderr: '',
        exitCode: textExit,
        elapsedMs: null,
        // 后端不会显式标错误，非 0 退出码即视为失败
        isError: isError || textExit !== 0,
        raw: text,
      },
    }
  }

  // ── 路径 2：结构化 JSON（兼容后端未来变更） ──
  const parsed = tryParse(rawBody)
  const stdout = asString(pick(parsed, ['stdout', 'std_out', 'out', 'output']))
  const stderr = asString(pick(parsed, ['stderr', 'std_err', 'err', 'error']))

  let exitCode: number | null = null
  const ec = pick(parsed, ['exitCode', 'exit_code', 'code', 'returncode', 'status_code'])
  if (typeof ec === 'number' && Number.isFinite(ec)) exitCode = ec
  else if (typeof ec === 'string' && /^-?\d+$/.test(ec.trim())) exitCode = Number(ec.trim())

  const structured = Boolean(stdout || stderr) || exitCode !== null

  return {
    toolCallId,
    result: {
      toolCallId,
      toolName,
      command: '',
      stdout: structured ? stdout : text,
      stderr: structured ? stderr : '',
      exitCode,
      elapsedMs: null,
      isError: isError || (exitCode !== null && exitCode !== 0),
      raw: text,
    },
  }
}

/**
 * 把一次 run 的事件流收敛成执行结果列表（纯函数，可单测）。
 *
 * 与直接逐个 parse 的区别：这里维护「调用 → 结果」的配对与计时，
 * 让调用方能一次拿到完整快照。
 */
export class ExecCollector {
  /** toolCallId → 累积中的调用（argsBuf 拼接所有分片） */
  private calls = new Map<
    string,
    { toolName: string; command: string; argsBuf: string; startedAt: number }
  >()
  private results: ExecResult[] = []

  /** 喂一个事件；返回本次是否产生了新结果 */
  push(event: AguiEvent, now: number = Date.now()): boolean {
    const type = String(event['type'] ?? '')

    // 结果事件优先判定（TOOL_CALL_RESULT 同时含 CALL 与 RESULT）
    if (isToolResultEvent(type)) {
      const parsed = parseToolResult(event)
      if (!parsed) return false
      const call = this.calls.get(parsed.toolCallId)
      this.results.push({
        ...parsed.result,
        // toolName / command 只出现在调用事件上，结果事件里没有，必须回填
        toolName: call?.toolName || parsed.result.toolName,
        command: call?.command ?? parsed.result.command,
        elapsedMs: call ? now - call.startedAt : null,
      })
      return true
    }

    if (isToolCallEvent(type)) {
      const id = asString(event['toolCallId'] ?? event['tool_call_id'] ?? event['id'])
      const name = asString(event['toolCallName'] ?? event['tool_name'] ?? event['name'])
      const existed = this.calls.get(id)

      // 参数分片：累积拼接，**不立即解析**（半截 JSON 解析必失败）
      if (RE_TOOL_ARGS_CHUNK.test(type)) {
        const chunk = asString(event['delta'] ?? event['args'] ?? event['arguments'] ?? '')
        if (!existed) {
          this.calls.set(id, {
            toolName: name,
            command: '',
            argsBuf: chunk,
            startedAt: now,
          })
          return false
        }
        existed.argsBuf += chunk
        if (name) existed.toolName = name
        const cmd = commandFromBuf(existed.argsBuf)
        if (cmd) existed.command = cmd
        return false
      }

      // 开始 / 结束事件：按完整入参解析一次
      if (!existed) {
        const call = parseToolCall(event)
        this.calls.set(id, {
          toolName: name,
          command: call?.command ?? '',
          argsBuf: '',
          startedAt: now,
        })
      } else if (name) {
        existed.toolName = name
      }
      return false
    }

    return false
  }

  list(): ExecResult[] {
    return this.results.slice()
  }

  /** 最近一次执行结果（UI 主视图用） */
  last(): ExecResult | null {
    return this.results.length ? this.results[this.results.length - 1] : null
  }

  reset(): void {
    this.calls.clear()
    this.results = []
  }
}

/** 把累积的 args 文本解析成命令；失败返回空串（不是 null，调用方只做覆盖判断） */
function commandFromBuf(buf: string): string {
  if (!buf) return ''
  const parsed = tryParse(buf)
  if (parsed === buf) return ''
  const cmd = asString(pick(parsed, ['command', 'cmd', 'script']))
  return cmd
}
