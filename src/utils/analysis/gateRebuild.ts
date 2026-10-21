/**
 * 从后端 transcript 重建「挂起的闸门」（移植自 kuaike `stores/chat.ts:758 detectPendingGate`）
 *
 * 要解决的问题：闸门是后端 run 挂起时才存在的瞬时态。若那条 run 是**后端自己唤醒**的
 * （子智能体完成 → wakeup 续跑 / 看门狗补投递 / 唤醒轮里挂起的 HITL），它**不在**
 * 前端那条 `POST /agui/run` 的 SSE 里 —— 前端收不到任何事件，页面上就永远看不到闸门。
 * kuaike 的做法是轮询 transcript 把它「扫」回来，本模块把那套判据搬过来。
 *
 * 判据（三条，缺一不可）：
 *   1. 是表单类工具（`ui_form_advanced` / `ui_gate` / 旧 `ui_form`）的 `tool_call` 块；
 *   2. 入参里有可用的 `pages`（`ui_gate` 没有 pages 时由 gate.ts 补默认字段）；
 *   3. transcript 里**不存在任何**配对 `tool_result` —— 有就说明主编已提交过。
 *
 * ⚠️ 第 3 条不要用 `state === 'success'` 过滤（kuaike 2026-09-20 实测的坑）：
 *   HITL_RECOVERY 反复重发失败时 transcript 会留下一批 `state=error` 的 tool_result，
 *   只排除 success 会把这批**死闸门**全部当待填渲染，刷新后弹出多张重复卡。
 *   「出现过任何 tool_result 即已解决」才收敛得住。
 *
 * ⚠️ 入参可能是**截断预览**（框架审计层对超长入参写
 *   `{"_preview":…,"_truncated":true,"originalSize":N}`），此时 `pages` 缺失。
 *   回退到后端 form-interrupts 登记表里的完整 `toolInput`（未截断）。
 *   kuaike 的 P-01 就是栽在这上面：终审闸门入参 947/952 字节被截断，而那条闸门
 *   **只可能**走转录重建路径（唤醒 run 没有 per-run SSE）⇒ 闸门永远弹不出来。
 *
 * 本模块只出数据、不发 run、不碰 DOM：调用方决定怎么呈现、要不要 resume。
 */

import { listPendingInterrupts } from '@/api/formInterrupt'
import { fetchSessionTranscript } from '@/api/session'
import type { AguiInterrupt } from '@/types/agui'
import { gateFromInterrupts, type GateSpec } from './gate'

/** 表单类工具白名单 —— 与 `utils/analysis/gate.ts` 认的两套协议对齐 */
export const FORM_TOOL_NAMES = ['ui_form_advanced', 'ui_gate', 'ui_form'] as const

/** transcript 里一个块的实际形态（后端把 tool_call / tool_result 也写在 content 里） */
interface RawBlock {
  type?: unknown
  id?: unknown
  name?: unknown
  input?: unknown
  state?: unknown
}

/** 重建出来的挂起闸门 */
export interface PendingGate {
  /** 可直接画卡片的归一化描述 */
  spec: GateSpec
  /** resume 要用的中断 id（登记表里有复合 id 就用它，否则退化为裸 toolCallId） */
  interruptId: string
  /** 待续跑的那条 run；登记表里没有就为空串（调用方据此判断「没法替用户提交」） */
  runId: string
  /** 裸 toolCallId —— 去重键，用来避免每轮轮询重复渲染同一张卡 */
  toolCallId: string
  /** 表单工具名（ui_form_advanced / ui_gate / ui_form） */
  toolName: string
  /**
   * 重建用的**完整入参**（已过截断回退）。
   *
   * 为什么要把原始入参也带出来：沙箱那条对话（`ChatPanel.vue`）自己认平台的
   * `pages` 协议、不经 `gate.ts` 归一化，所以它需要原件而不是归一化后的字段表；
   * 分析通道那条则直接用 `spec`。两条路各取所需，判据同一套。
   */
  input: Record<string, unknown>
}

/** 转录里一条消息的宽松形状（调用方可能已经把转录拉回来了，见 preset） */
export interface TranscriptLike {
  content?: Array<{ type: string }>
}

function blocksOf(message: TranscriptLike): RawBlock[] {
  return (message.content ?? []) as RawBlock[]
}

/** 收集 transcript 里所有**已解决**的工具调用 id —— 只要出现配对 tool_result 即视为已解决 */
export function collectResolvedToolCallIds(messages: TranscriptLike[]): Set<string> {
  const resolved = new Set<string>()
  for (const m of messages ?? []) {
    for (const b of blocksOf(m)) {
      if (b.type !== 'tool_result') continue
      const id = b.id
      if (typeof id === 'string' && id) resolved.add(id)
    }
  }
  return resolved
}

/** 解析工具入参（可能是 JSON 字符串，也可能是对象） */
function inputOf(raw: unknown): Record<string, unknown> {
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) return raw as Record<string, unknown>
  if (typeof raw === 'string' && raw.length) {
    try {
      const parsed = JSON.parse(raw) as unknown
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed as Record<string, unknown>
    } catch {
      /* 截断预览 / 非 JSON：按空处理，交给登记表回退 */
    }
  }
  return {}
}

/** pages 是否可用（非空数组） */
function pagesOf(obj: Record<string, unknown>): unknown[] | null {
  const pages = obj['pages']
  return Array.isArray(pages) && pages.length ? pages : null
}

/**
 * 扫一遍 transcript，重建**第一张**挂起的闸门；没有则返回 null。
 *
 * 只返回第一张：站点一个任务同时最多挂一张闸门（run 串行），多张同屏会让用户
 * 不知道该先填哪个。其余留到下一轮（提交后轮询会再扫一次）。
 *
 * 任何一步失败都返回 null —— 重建是**自愈路径**，不该把正常流程拖下水。
 *
 * @param preset 调用方**已经拉回来的**转录消息。沙箱那条 `syncSession` 本来就在
 *   `Promise.allSettled` 里拉 transcript，再拉一次纯属浪费；传进来就省一趟请求。
 *   不传则本函数自己拉。
 */
export async function rebuildPendingGate(
  agentId: string,
  sessionId: string,
  preset?: TranscriptLike[],
): Promise<PendingGate | null> {
  if (!sessionId) return null

  let messages: TranscriptLike[] = preset ?? []
  if (!messages.length) {
    try {
      const transcript = await fetchSessionTranscript(agentId, sessionId)
      messages = (transcript?.messages ?? []) as TranscriptLike[]
    } catch {
      return null
    }
  }
  if (!messages.length) return null

  const resolved = collectResolvedToolCallIds(messages)

  /* 登记表：① 拿复合 interruptId（resume 必须用它，裸 toolCallId 后端会报
     "does not match any open interrupt"）② 拿完整 toolInput（转录入参被截断时回退）③ 拿 runId */
  const byToolCall = new Map<string, AguiInterrupt>()
  try {
    for (const item of await listPendingInterrupts(agentId, sessionId)) {
      const tcid = item.toolCallId
      if (typeof tcid === 'string' && tcid) byToolCall.set(tcid, item)
    }
  } catch {
    /* 登记表不可达（后端重启后内存清空）：退化为裸 toolCallId 渲染 */
  }

  for (const m of messages) {
    for (const b of blocksOf(m)) {
      if (b.type !== 'tool_call') continue
      const name = b.name
      if (typeof name !== 'string' || !(FORM_TOOL_NAMES as readonly string[]).includes(name)) continue
      const id = b.id
      if (typeof id !== 'string' || !id) continue
      /* 已解决（提交过、出现过 tool_result）→ 不再渲染 */
      if (resolved.has(id)) continue

      const hit = byToolCall.get(id)

      let obj = inputOf(b.input)
      let pages = pagesOf(obj)
      /* 入参被截断 → 用登记表里的完整入参重建 */
      if (!pages && hit) {
        const full = inputOf(hit.metadata?.toolInput)
        const fullPages = pagesOf(full)
        if (fullPages) {
          obj = full
          pages = fullPages
        }
      }
      /* 没有 pages 也**不是**绝对的死路：`ui_gate` 允许不带 pages，由 gate.ts 补一套
         默认审批字段（decision + feedback）。其余工具没 pages 就真的画不出东西，跳过。 */
      const gateLike = /gate/i.test(name)
      if (!pages && !gateLike) continue

      /* 复用 gate.ts 的归一化：把「转录里的一对 (name, input)」包成它认的中断形状，
         两条路径（SSE 中断 / 转录重建）走同一套解析，字段规则不会分叉。 */
      const synthetic: AguiInterrupt = {
        id: (hit?.id || hit?.interruptId || id).trim(),
        toolCallId: id,
        metadata: { toolName: name, toolInput: obj },
      }
      const spec = gateFromInterrupts([synthetic])
      if (!spec) continue

      /* pages 是 gate.ts 补出来的（ui_gate 不带 pages 那一档）→ 把字段反写回 input。
         为什么必须反写：沙箱那条通道（`ChatPanel.vue`）自己认平台 `pages` 协议、
         不经 gate.ts 归一化，拿不到 pages 就一行输入框都画不出来 —— 用户看到一张
         空的确认卡，提交出去是空载荷。分析通道用 spec，不吃这一份。 */
      const input = pages
        ? obj
        : {
            ...obj,
            pages: [{
              items: spec.fields.map((f) => ({
                key: f.key,
                label: f.label,
                kind: f.kind,
                options: f.options,
                required: f.required,
                placeholder: f.placeholder,
              })),
            }],
          }

      const rawRunId = hit?.metadata?.['runId']
      const runId = typeof rawRunId === 'string' ? rawRunId : ''
      return { spec, interruptId: spec.interruptId, runId, toolCallId: id, toolName: name, input }
    }
  }

  return null
}
