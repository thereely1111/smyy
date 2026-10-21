/**
 * AG-UI 事件流 → 「对话块」
 *
 * 站点那条工作台对话吃的是 HTML 片段，而智能体吐的是 AG-UI 事件流。
 * 这一层把事件归约成一组稳定的**块**（思考 / 工具 / 文本 / 错误），
 * 上层（index.ts）负责把块渲染进对话区。
 *
 * 为什么要有这一层：早先这条通道**只收产物、把事件流丢掉**，于是智能体
 * 在平台那边看得到的「思考了多久、跑了哪些工具、逐段说了什么」，站点这边
 * 一概看不见。块列表就是把这些补回来。
 *
 * 三条纪律：
 *   1. **未知事件静默丢弃** —— 后端加新事件类型时不能把整条流打断。
 *   2. **字段名做候选匹配** —— 不同 starter 版本给的名字不一样（toolCallName /
 *      toolName / name），只认一套必翻车。
 *   3. 纯数据、不碰 DOM，可单测。
 */

import type { AguiEvent } from '@/types/agui'

export type LiveBlockKind = 'text' | 'thinking' | 'tool' | 'error'

/**
 * 助手正文（markdown 原文，渲染时才转 HTML）
 *
 * ⚠️ `pending` 与 `text` 是两个字段（2026-10-13，移植 kuaike 打字机）：
 *   - `pending` —— **刚收到、还没吐出去**的增量。归约层只管往这里堆，不碰 text。
 *   - `text`    —— 已经**逐字吐出来**的部分，渲染层只读它。
 * 分开的理由：打字机（`@/utils/typewriter`）是个带定时器的有状态消费者，属于渲染层
 * （`live/index.ts`）；本文件那三条纪律要求它是**纯数据、可单测**的，所以节奏化这件事
 * 不能塞进来。index.ts 每次喂完事件把 `pending` 搬到打字机，再由它按节奏写回 `text`。
 */
export interface LiveTextBlock {
  kind: 'text'
  id: string
  /** 已吐出的正文 */
  text: string
  /** 待吐出的正文增量（无打字机时会一次性并入 text） */
  pending: string
  done: boolean
}

/** 一次思考段（「已思考 Ns」的来源） */
export interface LiveThinkingBlock {
  kind: 'thinking'
  id: string
  text: string
  /** 起点毫秒 */
  startedAt: number
  /** 终点毫秒；0 = 还在想 */
  endedAt: number
}

export type LiveToolStatus = 'running' | 'done' | 'error'

/** 一次工具调用（Bash / Read / Write / Edit / ui_form_advanced …） */
export interface LiveToolBlock {
  kind: 'tool'
  id: string
  name: string
  /** 入参原文：可能是 JSON 增量拼起来的，不保证随时可解析 */
  args: string
  /** 结果文本（已从 {stdout,stderr} 之类的壳里剥出来） */
  result: string
  status: LiveToolStatus
  startedAt: number
  endedAt: number
}

/** 流内错误（RUN_ERROR / RAW.event.error）—— 必须让用户看见，别吞掉 */
export interface LiveErrorBlock {
  kind: 'error'
  id: string
  text: string
}

export type LiveBlock = LiveTextBlock | LiveThinkingBlock | LiveToolBlock | LiveErrorBlock

export interface LiveStream {
  blocks: LiveBlock[]
  /** 已收到的事件数（进度用） */
  events: number
  startedAt: number
  /** 流已收到 RUN_FINISHED */
  closed: boolean
  /** 触发这一轮的用户输入（对话回显用；首批 run 由站点自动发起时为空） */
  userText: string
}

export function emptyStream(): LiveStream {
  return { blocks: [], events: 0, startedAt: 0, closed: false, userText: '' }
}

/* ── 小工具 ──────────────────────────────────────────────────────────────── */

let seq = 0
function uid(prefix: string): string {
  seq += 1
  return `${prefix}${seq}`
}

function str(v: unknown): string {
  return typeof v === 'string' ? v : ''
}

function rec(v: unknown): Record<string, unknown> | null {
  return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null
}

/** 从事件里挑工具名（不同版本字段名不同，挨个试；再退到 metadata） */
function toolNameOf(ev: AguiEvent): string {
  for (const k of ['toolCallName', 'toolName', 'name']) {
    const v = ev[k]
    if (typeof v === 'string' && v) return v
  }
  const meta = rec(ev['metadata'])
  if (meta && typeof meta['toolName'] === 'string') return meta['toolName'] as string
  return ''
}

function toolCallIdOf(ev: AguiEvent): string {
  for (const k of ['toolCallId', 'id']) {
    const v = ev[k]
    if (typeof v === 'string' && v) return v
  }
  const meta = rec(ev['metadata'])
  if (meta && typeof meta['toolCallId'] === 'string') return meta['toolCallId'] as string
  return ''
}

/**
 * 工具结果可能是字符串、`{stdout, stderr, exitCode}` 结构化壳、或任意对象。
 * 统一剥成文本 —— 渲染层只认文本。
 */
export function resultText(v: unknown): string {
  if (typeof v === 'string') return v
  const o = rec(v)
  if (!o) return ''
  const out = str(o['stdout'])
  const err = str(o['stderr'])
  if (out || err) return [out, err].filter(Boolean).join('\n')
  for (const k of ['text', 'content', 'output', 'message']) {
    const t = o[k]
    if (typeof t === 'string' && t) return t
  }
  try {
    return JSON.stringify(v)
  } catch {
    return ''
  }
}

/** RAW 事件里包着的错误（平台把「拒绝」写成这个形状） */
function rawError(ev: AguiEvent): string {
  const inner = rec(ev['event'])
  if (!inner) return ''
  for (const k of ['error', 'message']) {
    const v = inner[k]
    if (typeof v === 'string' && v) return v
  }
  return ''
}

/* ── 块查找 ──────────────────────────────────────────────────────────────── */

/** 最后一个块（只在「紧接着的同类型增量」场景用，避免跨块错配） */
function tail<T extends LiveBlock>(s: LiveStream, kind: T['kind']): T | null {
  const b = s.blocks.length ? s.blocks[s.blocks.length - 1] : null
  return b && b.kind === kind ? (b as T) : null
}

function openText(s: LiveStream, id = ''): LiveTextBlock {
  const found = findText(s, id)
  if (found) return found
  const last = tail<LiveTextBlock>(s, 'text')
  if (!id && last && !last.done) return last
  const b: LiveTextBlock = { kind: 'text', id: id || uid('t'), text: '', pending: '', done: false }
  s.blocks.push(b)
  return b
}

/** 按 messageId 找正文块（回放去重用的；没带 id 时返回 null） */
function findText(s: LiveStream, id: string): LiveTextBlock | null {
  if (!id) return null
  for (let i = s.blocks.length - 1; i >= 0; i--) {
    const b = s.blocks[i]
    if (b.kind === 'text' && b.id === id) return b
  }
  return null
}

function findThinking(s: LiveStream, id: string): LiveThinkingBlock | null {
  if (!id) return null
  for (let i = s.blocks.length - 1; i >= 0; i--) {
    const b = s.blocks[i]
    if (b.kind === 'thinking' && b.id === id) return b
  }
  return null
}

function openThinking(s: LiveStream, id = ''): LiveThinkingBlock {
  const found = findThinking(s, id)
  if (found) return found
  const last = tail<LiveThinkingBlock>(s, 'thinking')
  if (!id && last && !last.endedAt) return last
  const b: LiveThinkingBlock = { kind: 'thinking', id: id || uid('k'), text: '', startedAt: Date.now(), endedAt: 0 }
  s.blocks.push(b)
  return b
}

/** 按 toolCallId 找工具块；id 缺失时退到「最后一个还在跑的工具」 */
function toolBlock(s: LiveStream, callId: string): LiveToolBlock | null {
  if (callId) {
    for (let i = s.blocks.length - 1; i >= 0; i--) {
      const b = s.blocks[i]
      if (b.kind === 'tool' && b.id === callId) return b
    }
  }
  for (let i = s.blocks.length - 1; i >= 0; i--) {
    const b = s.blocks[i]
    if (b.kind === 'tool' && b.status === 'running') return b
  }
  return null
}

function pushError(s: LiveStream, text: string): void {
  if (!text) return
  const last = tail<LiveErrorBlock>(s, 'error')
  if (last && last.text === text) return
  s.blocks.push({ kind: 'error', id: uid('e'), text })
}

/** 收尾：把所有还开着的块按「流断了」处理，别让它们一直转圈 */
export function closeOpenBlocks(s: LiveStream): void {
  const now = Date.now()
  for (const b of s.blocks) {
    if (b.kind === 'text') b.done = true
    else if (b.kind === 'thinking') { if (!b.endedAt) b.endedAt = now }
    else if (b.kind === 'tool') {
      if (b.status === 'running') { b.status = 'done'; b.endedAt = now }
    }
  }
}

/**
 * 取走所有「待吐出」的正文增量（打字机路径）。
 *
 * 返回 [块, 增量] 对，并把块的 `pending` 清空 —— 归约层只出数据，搬去哪、按什么
 * 节奏写回，全由渲染层（`live/index.ts`）决定。没有容器可画时走 `settlePending`。
 */
export function drainPending(s: LiveStream): Array<{ block: LiveTextBlock; text: string }> {
  const out: Array<{ block: LiveTextBlock; text: string }> = []
  for (const b of s.blocks) {
    if (b.kind !== 'text' || !b.pending) continue
    out.push({ block: b, text: b.pending })
    b.pending = ''
  }
  return out
}

/** 收尾：把还没吐出的增量**一次性**并入 `text`（降级路径，不走打字机） */
export function settlePending(s: LiveStream): void {
  for (const b of s.blocks) {
    if (b.kind !== 'text' || !b.pending) continue
    b.text += b.pending
    b.pending = ''
  }
}

/* ── 归约 ────────────────────────────────────────────────────────────────── */

/**
 * 一个 AG-UI 事件 → 块列表的增量。
 *
 * 就地修改 `s`（流式场景下每秒可能十几个事件，不值得每步复制整棵结构）。
 */
export function reduce(s: LiveStream, ev: AguiEvent): void {
  const type = String(ev['type'] ?? '')
  const T = type.toUpperCase()
  s.events += 1

  switch (T) {
    case 'TEXT_MESSAGE_START': {
      /* 同 messageId 已开过块就不重开 —— 回放（见 api/aguiEvents.ts）从水位线起跟，
         而水位线取的是「已知最大序号」，落库是异步批量 flush（间隔 500ms），
         SSE 收尾那一刻可能还有一批没写库，于是那一批会被再放一遍。同一 messageId
         重开会让同一条正文在对话区出现两份文本块。 */
      const id = str(ev['messageId'])
      if (id && findText(s, id)) return
      s.blocks.push({ kind: 'text', id: id || uid('t'), text: '', pending: '', done: false })
      return
    }

    case 'TEXT_MESSAGE_CONTENT':
    case 'TEXT_MESSAGE_DELTA': {
      const delta = typeof ev['delta'] === 'string' ? ev['delta'] : str(ev['text'])
      if (!delta) return
      const id = str(ev['messageId'])
      const found = findText(s, id)
      /* 已收尾的同 id 正文不再追加 —— 回放重放 SSE 尾部那一批时，正文不会出现两份。
         代价：若同一 messageId 发两段（END 之后又 START），第二段会被丢。AG-UI 里
         messageId 就是一条消息的身份，正常协议不会这么发，所以取这个取舍。 */
      if (found) {
        if (!found.done) found.pending += delta
        return
      }
      openText(s, id).pending += delta
      return
    }

    case 'TEXT_MESSAGE_END': {
      const b = findText(s, str(ev['messageId'])) ?? tail<LiveTextBlock>(s, 'text')
      if (b) b.done = true
      return
    }

    case 'REASONING_MESSAGE_START':
    case 'THINKING_BLOCK_START': {
      const id = str(ev['messageId']) || str(ev['blockId'])
      /* 同 id 已开过就不重开（回放重复批）—— 否则思考段会在折叠条里多出一条 */
      if (id && findThinking(s, id)) return
      openThinking(s, id)
      return
    }

    case 'REASONING_MESSAGE_CONTENT':
    case 'THINKING_BLOCK_DELTA':
    case 'REASONING_MESSAGE_DELTA': {
      const delta = typeof ev['delta'] === 'string' ? ev['delta'] : str(ev['text'])
      if (!delta) return
      const id = str(ev['messageId']) || str(ev['blockId'])
      const found = findThinking(s, id)
      if (found) {
        if (!found.endedAt) found.text += delta
        return
      }
      openThinking(s, id).text += delta
      return
    }

    case 'REASONING_MESSAGE_END':
    case 'THINKING_BLOCK_END': {
      const b = findThinking(s, str(ev['messageId']) || str(ev['blockId'])) ?? tail<LiveThinkingBlock>(s, 'thinking')
      if (b && !b.endedAt) b.endedAt = Date.now()
      return
    }

    case 'TOOL_CALL_START': {
      const callId = toolCallIdOf(ev) || uid('c')
      /* 同 toolCallId 已开过块就不重开（理由同 TEXT_MESSAGE_START）：
         否则回放重复的那批会在折叠条里把同一项操作列两遍。 */
      const exists = s.blocks.some((b) => b.kind === 'tool' && b.id === callId)
      if (exists) return
      s.blocks.push({
        kind: 'tool',
        id: callId,
        name: toolNameOf(ev) || '工具',
        args: str(ev['args']),
        result: '',
        status: 'running',
        startedAt: Date.now(),
        endedAt: 0,
      })
      return
    }

    case 'TOOL_CALL_ARGS':
    case 'TOOL_CALL_DELTA': {
      const b = toolBlock(s, toolCallIdOf(ev))
      if (!b) return
      const delta = str(ev['delta']) || str(ev['args'])
      if (delta) b.args += delta
      const name = toolNameOf(ev)
      if (name && (b.name === '工具' || !b.name)) b.name = name
      return
    }

    case 'TOOL_CALL_END': {
      const b = toolBlock(s, toolCallIdOf(ev))
      if (b && !b.args) b.args = str(ev['args'])
      return
    }

    case 'TOOL_CALL_RESULT':
    case 'TOOL_RESULT_END':
    case 'TOOL_CALL_END_WITH_RESULT': {
      const text = resultText(ev['content'] ?? ev['result'] ?? ev['output'])
      const isError = ev['isError'] === true || ev['error'] === true
      const b = toolBlock(s, toolCallIdOf(ev))
      if (b) {
        if (text) b.result = text
        b.status = isError ? 'error' : 'done'
        if (!b.endedAt) b.endedAt = Date.now()
      } else if (text) {
        s.blocks.push({
          kind: 'tool',
          id: toolCallIdOf(ev) || uid('c'),
          name: toolNameOf(ev) || '工具',
          args: '',
          result: text,
          status: isError ? 'error' : 'done',
          startedAt: Date.now(),
          endedAt: Date.now(),
        })
      }
      return
    }

    case 'RUN_ERROR': {
      const text = str(ev['message']) || str(ev['error']) || '智能体运行失败'
      pushError(s, text)
      return
    }

    case 'RAW': {
      pushError(s, rawError(ev))
      return
    }

    case 'RUN_FINISHED': {
      const outcome = rec(ev['outcome'])
      const kind = outcome ? str(outcome['type']) : ''
      if (kind === 'error') pushError(s, str(outcome && outcome['message']) || '智能体运行失败')
      closeOpenBlocks(s)
      s.closed = true
      return
    }

    default:
      return
  }
}
