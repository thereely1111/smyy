/**
 * AG-UI 协议类型
 *
 * 事件名与字段以 **后端实际发出** 为准（agent-platform 的
 * `agentscope-agui-spring-boot-starter` 暴露的 `POST /agui/run/{agentId}`）。
 * 本文件只描述沙箱用得到的那部分，不做全量建模。
 *
 * ⚠️ 纪律：不要照推断的事件名写解析器。落地后先用一次真实运行抓原始 SSE 核实，
 *    再回来校正这里。解析器（utils/parseAgui.ts）对未知事件必须静默跳过而非抛错。
 */

/** 一次 run 的请求体 */
export interface AguiRunRequest {
  threadId: string
  runId: string
  messages: AguiMessage[]
  /** resume 时携带；新消息时省略 */
  resume?: AguiResumeItem[]
}

export interface AguiMessage {
  id: string
  role: 'user' | 'assistant' | 'tool'
  /** AG-UI v2 ContentBlock[]; plain strings are not valid RunAgentInput. */
  content: Array<{ type: 'text'; text: string }>
  [key: string]: unknown
}

export interface AguiResumeItem {
  interruptId: string
  status: 'resolved' | 'cancelled'
  payload?: {
    approved?: boolean
    editedArgs?: Record<string, unknown>
    output?: string
    [key: string]: unknown
  }
  metadata?: Record<string, unknown>
}

/** 中断项（HITL） */
export interface AguiInterrupt {
  /** AG-UI v2 uses id; older server builds exposed interruptId. */
  id?: string
  interruptId?: string
  reason?: string
  message?: string
  toolCallId?: string
  metadata?: {
    toolName?: string
    toolInput?: Record<string, unknown> | string
    toolContent?: string
    [key: string]: unknown
  }
  [key: string]: unknown
}

/** SSE 事件：保留 unknown 兜底，避免未知事件导致类型报错 */
export interface AguiEvent {
  type: string
  [key: string]: unknown
}

/** text message 增量 */
export interface AguiTextDelta {
  type: 'TEXT_MESSAGE_CONTENT' | string
  messageId?: string
  delta?: string
  [key: string]: unknown
}

/* ── 下面两组是沙箱的命脉：工具调用与工具结果 ─────────────────────── */

/** 工具调用开始：从这里拿「正在执行什么命令」 */
export interface AguiToolCall {
  type: string
  toolCallId?: string
  toolCallName?: string
  /** 入参，可能是对象或 JSON 字符串 */
  delta?: string
  args?: unknown
  [key: string]: unknown
}

/** 工具结果：从这里拿 stdout / stderr / 退出码 */
export interface AguiToolResult {
  type: string
  toolCallId?: string
  content?: unknown
  result?: unknown
  isError?: boolean
  [key: string]: unknown
}

/** run 结束 */
export interface AguiRunFinished {
  type: string
  outcome?: {
    type?: 'success' | 'interrupt' | string
    interrupts?: AguiInterrupt[]
    [key: string]: unknown
  }
  [key: string]: unknown
}
