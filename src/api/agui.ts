/**
 * AG-UI 协议客户端
 *
 * 端点：`POST /agui/run/{agentId}`（SSE 流式），由后端
 * agentscope-agui-spring-boot-starter 自动暴露。同一个端点同时承担 HITL resume。
 *
 * 流程：
 *   1. 前端 POST { threadId, runId, messages:[userMsg] }
 *   2. 后端 SSE 推送 RUN_STARTED / TEXT_MESSAGE_* / TOOL_CALL_* / TOOL_RESULT_*
 *      需要用户确认时，末尾是 RUN_FINISHED { outcome:{ type:'interrupt', interrupts:[...] } }
 *   3. resume 时**复用同一 (threadId, runId)**，body 带 resume:[{interruptId,status,payload}]
 *   4. 继续推送直至 RUN_FINISHED { outcome:{ type:'success' } }
 *
 * 解析是纯函数，不碰 DOM、不碰 store，便于单测。
 */

import { httpStream } from '@/api/http'
import type { AguiEvent, AguiInterrupt, AguiRunRequest, AguiMessage } from '@/types/agui'

export type { AguiEvent }

/** 对齐 agent-platform/frontend/src/api/agui.ts 的 RunAgentInput。 */
export function userMessage(id: string, text: string): AguiMessage {
  return { id, role: 'user', content: [{ type: 'text', text }] }
}

/** 后端有时以 HTTP 200 + 运行错误事件表示请求失败。 */
export function runEventError(event: AguiEvent): string {
  const type = String(event.type || '').toUpperCase()
  const outcome = event['outcome']
  const finishError = type === 'RUN_FINISHED' && !!outcome &&
    typeof outcome === 'object' && (outcome as { type?: string }).type === 'error'
  const rawError = type === 'RAW' && !!event['event'] &&
    typeof event['event'] === 'object' && typeof (event['event'] as Record<string, unknown>)['error'] === 'string'
  if (type !== 'RUN_ERROR' && !finishError && !rawError) return ''
  const raw = event['event']
  const fields: unknown[] = [event['message'], event['error'],
    raw && typeof raw === 'object' ? (raw as Record<string, unknown>)['error'] : null,
    outcome && typeof outcome === 'object' ? (outcome as Record<string, unknown>)['message'] : null]
  return fields.find((v): v is string => typeof v === 'string' && !!v) || '智能体运行失败'
}

/** 发起一次 AG-UI run（或 resume），返回原始 Response 供读流 */
export function runAgui(
  agentId: string,
  body: AguiRunRequest,
  signal?: AbortSignal,
): Promise<Response> {
  return httpStream(`/agui/run/${agentId}`, body, signal)
}

/**
 * 解析 SSE 流，每解析出一个 JSON 事件回调一次。
 *
 * 后端默认每行 `data: {...}`，也存在纯 JSON 行；统一裁掉 `data:` 前缀，
 * 跳过 `event:` / `:heartbeat` 等非 JSON 控制行。
 *
 * ⚠️ 未知事件一律静默跳过 —— 后端加新事件类型时不能把整条流打断。
 */
export async function parseAguiStream(
  response: Response,
  onEvent: (event: AguiEvent) => void,
  signal?: AbortSignal,
): Promise<void> {
  if (!response.ok || !response.body) {
    throw new Error(`请求失败（${response.status}）`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  const cancelled = (): DOMException => new DOMException('运行已停止', 'AbortError')
  const onAbort = (): void => { void reader.cancel().catch(() => {}) }
  signal?.addEventListener('abort', onAbort, { once: true })
  try {
    if (signal?.aborted) { onAbort(); throw cancelled() }
    for (;;) {
      const { done, value } = await reader.read()
      if (signal?.aborted) throw cancelled()
      if (done) break

      buffer += decoder.decode(value, { stream: true })

      let sep: number
      while ((sep = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, sep).trim()
        buffer = buffer.slice(sep + 1)
        if (line) processJsonLine(line, onEvent)
      }

      if (signal?.aborted) throw cancelled()
    }

    buffer += decoder.decode()
    // 收尾：处理最后一段没有换行符的残留
    if (buffer.trim()) processJsonLine(buffer.trim(), onEvent)
  } finally {
    signal?.removeEventListener('abort', onAbort)
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

/** 单行 → 事件（纯函数，可单测） */
export function processJsonLine(line: string, onEvent: (event: AguiEvent) => void): void {
  let jsonStr = line
  if (jsonStr.startsWith('data:')) jsonStr = jsonStr.slice(5).trim()

  // 只解析以 { 或 [ 开头的行，SSE 的 event: / : 注释行直接忽略
  const first = jsonStr.charCodeAt(0)
  if (first !== 123 /* { */ && first !== 91 /* [ */) return

  let event: AguiEvent
  try {
    event = JSON.parse(jsonStr) as AguiEvent
  } catch {
    return // 格式错误行忽略；回调异常不能被误吞。
  }
  if (event && typeof event === 'object' && !Array.isArray(event)) onEvent(event)
}

/** 从 run 结束事件里取中断列表（HITL） */
export function extractInterrupts(event: AguiEvent): AguiInterrupt[] {
  const outcome = event['outcome'] as { type?: string; interrupts?: unknown } | undefined
  if (outcome?.type && outcome.type !== 'interrupt') return []

  // RUN_FINISHED is the canonical shape. A few starter versions put the
  // array directly on the event, so accept that shape as well without making
  // the stream parser version-dependent.
  const raw = Array.isArray(outcome?.interrupts)
    ? outcome.interrupts
    : Array.isArray(event['interrupts'])
      ? event['interrupts']
      : []

  return raw.filter((item): item is AguiInterrupt => {
    if (!item || typeof item !== 'object') return false
    const value = item as Record<string, unknown>
    return typeof value['id'] === 'string' || typeof value['interruptId'] === 'string'
  })
}
