/** Pending HITL forms: register on interrupt, rehydrate after refreshing the sandbox. */
import { http } from '@/api/http'
import type { AguiInterrupt } from '@/types/agui'

const base = (agentId: string, sessionId: string) =>
  `/api/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sessionId)}/form-interrupts`

interface PendingForm {
  interruptId: string
  replyId?: string
  toolCallId: string
  toolName?: string
  toolInput?: string
  createdAt?: string
  runId?: string
}

export async function registerInterrupts(agentId: string, sessionId: string, interrupts: AguiInterrupt[], runId?: string): Promise<void> {
  await Promise.all(interrupts.map(async (interrupt) => {
    const interruptId = interrupt.id || interrupt.interruptId || ''
    const toolCallId = interrupt.toolCallId || interruptId.split(':').slice(1).join(':')
    if (!interruptId || !toolCallId) return
    const raw = interrupt.metadata?.toolInput ?? interrupt.metadata?.toolContent ?? {}
    await http.put<void>(base(agentId, sessionId), {
      interruptId,
      runId,
      toolCallId,
      replyId: interruptId.split(':')[0],
      toolName: interrupt.metadata?.toolName || '',
      toolInput: typeof raw === 'string' ? raw : JSON.stringify(raw),
      createdAt: new Date().toISOString(),
    } satisfies PendingForm)
  }))
}

export async function listPendingInterrupts(agentId: string, sessionId: string): Promise<AguiInterrupt[]> {
  const items = await http.get<PendingForm[]>(base(agentId, sessionId))
  return items.map((item) => ({
    id: item.interruptId,
    toolCallId: item.toolCallId,
    message: '运行等待确认，请完成以下表单后继续',
    metadata: { toolName: item.toolName, toolInput: item.toolInput, runId: item.runId },
  }))
}

export function clearPendingInterrupts(agentId: string, sessionId: string): Promise<void> {
  return http.delete<void>(base(agentId, sessionId))
}

/** 框架侧活中断（后端 `AgentManagementController.LiveInterrupt` record）。 */
interface LiveInterrupt {
  interruptId?: string
  id?: string
  toolCallId?: string
  toolName?: string | null
}

const liveBase = (agentId: string, sessionId: string) =>
  `/api/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sessionId)}/interrupts`

/**
 * 线程上**框架认账**的全部未决中断 id（权威快照）。
 *
 * 与 `listPendingInterrupts` 的关键差别：那个走平台登记表（`/form-interrupts`），
 * 自后端 2026-10-06 起返回的是「登记表 ∩ 框架映射」，且只含 `ui_gate` /
 * `ui_form_advanced` 两个 HITL 工具 —— **框架仍持锁、但登记表里没有的中断
 * （如工具权限确认这类非白名单中断）永远枚举不到**，于是发不出 cancel、新 run
 * 一直被 `Thread has unresolved interrupts` 打死（后端 2026-10-12 实测场景）。
 *
 * 本接口直接把框架 `pendingInterruptsByThread` 按会话暴露出来（只读、带归属校验），
 * 是「发送前对账 + cancel 解锁」的**正确枚举源**。后端 2026-10-12 新增，
 * 老后端会 404 —— 调用方需与 `/form-interrupts` 取并集并各自兜错。
 */
export async function listLiveInterruptIds(agentId: string, sessionId: string): Promise<string[]> {
  const items = await http.get<LiveInterrupt[]>(liveBase(agentId, sessionId))
  return [
    ...new Set(
      (items || [])
        .map((it) => (it.interruptId || it.id || '').trim())
        .filter(Boolean),
    ),
  ]
}
