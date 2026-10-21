/** AG-UI 流结束不代表异步子智能体已写完产物；只读轮询，不重复发 run。 */
export async function waitForArtifact<T>(
  collect: () => Promise<T | null>,
  deadline: number,
  signal?: AbortSignal,
  intervalMs = 10_000,
): Promise<T | null> {
  while (!signal?.aborted) {
    try {
      const result = await collect()
      if (signal?.aborted) return null
      if (result !== null) return result
    } catch {
      // 单次产物读取失败（如子会话尚未注册）不应结束整个阶段。
    }
    const remaining = deadline - Date.now()
    if (remaining <= 0) return null
    await new Promise<void>((resolve) => {
      const timer = setTimeout(done, Math.min(intervalMs, remaining))
      function done(): void {
        clearTimeout(timer)
        signal?.removeEventListener('abort', done)
        resolve()
      }
      signal?.addEventListener('abort', done, { once: true })
      if (signal?.aborted) done()
    })
  }
  return null
}
