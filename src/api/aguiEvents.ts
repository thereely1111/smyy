import { http } from '@/api/http'
import type { AguiEvent } from '@/types/agui'

export interface AguiEventPage {
  sessionId: string
  count: number
  nextAfterSequence: number
  events: Array<{ eventSequence: number; eventType?: string; runId?: string; recordedAt?: string; payload: AguiEvent }>
}
export interface AguiEventWatermark { sessionId: string; nextAfterSequence: number; lastRunStartSequence?: number; latest?: boolean }
export function fetchAguiEventWatermark(sessionId: string): Promise<AguiEventWatermark> {
  return http.get('/api/agui-events', { sessionId, latest: 'true' })
}
export function fetchAguiEvents(sessionId: string, afterSequence: number, limit = 500): Promise<AguiEventPage> {
  return http.get('/api/agui-events', { sessionId, afterSequence: String(afterSequence), limit: String(limit) })
}
/** SSE 收尾后跟随 Java 后台仍在落库的事件；不可用时静默降级到产物轮询。 */
export async function followAguiEvents(sessionId: string, afterSequence: number, onEvent: (event: AguiEvent) => void, isStopped: () => boolean, intervalMs = 2000): Promise<void> {
  try {
    let cursor = afterSequence
    while (!isStopped()) {
      const page = await fetchAguiEvents(sessionId, cursor)
      for (const item of page.events ?? []) if (item?.payload && typeof item.payload === 'object') onEvent(item.payload)
      cursor = page.nextAfterSequence ?? cursor
      await new Promise<void>((resolve) => window.setTimeout(resolve, intervalMs))
    }
  } catch {
    // 老版本后端、权限或网络异常不影响原有产物同步
  }
}

