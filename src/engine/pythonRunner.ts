export interface PythonFile { path: string; content: string | Uint8Array }
export interface PythonOutputFile { path: string; bytes: Uint8Array }
export interface PythonResult { exitCode: number; elapsedMs: number; files: PythonOutputFile[] }
export interface PythonHooks {
  output: (stream: 'stdout' | 'stderr', text: string) => void
  status: (text: string) => void
  ready: (version: string) => void
}
/** A new client belongs to exactly one mounted session. Stop terminates CPU-bound code. */
export class PythonRunner {
  private worker?: Worker
  private pending?: { resolve: (result: PythonResult) => void; reject: (error: Error) => void }
  private timer?: ReturnType<typeof setTimeout>
  constructor(private hooks: PythonHooks) {}
  private execute(data: Record<string, unknown>): Promise<PythonResult> {
    if (this.pending) return Promise.reject(new Error('Python 正在执行，请等待或停止'))
    if (!this.worker) {
      this.worker = new Worker(new URL(`${import.meta.env.BASE_URL}python-worker.js`, document.baseURI))
      const worker = this.worker
      this.worker.onmessage = ({ data: event }) => {
        if (this.worker !== worker) return
        if (event.type === 'output') this.hooks.output(event.stream, event.text)
        else if (event.type === 'status') this.hooks.status(event.text)
        else if (event.type === 'ready') this.hooks.ready(event.version)
        else if (event.type === 'done') {
          clearTimeout(this.timer)
          const pending = this.pending
          this.pending = undefined
          pending?.resolve(event)
        }
      }
      this.worker.onerror = event => { if (this.worker === worker) this.stop(event.message || 'Python 工作线程加载失败') }
    }
    return new Promise((resolve, reject) => {
      this.pending = { resolve, reject }
      this.timer = setTimeout(() => this.stop('运行超过 120 秒，已停止；可精简数据后重试'), 120_000)
      this.worker!.postMessage({ ...data, indexURL: new URL(`${import.meta.env.BASE_URL}python/`, document.baseURI).href })
    })
  }
  init() { return this.execute({ type: 'init' }) }
  run(code: string, path: string, files: PythonFile[]) { return this.execute({ type: 'run', code, path, files }) }
  repl(code: string) { return this.execute({ type: 'repl', code }) }
  stop(message = '已停止 Python 运行，解释器将在下次执行时重新初始化') {
    clearTimeout(this.timer)
    this.worker?.terminate()
    this.worker = undefined
    const pending = this.pending
    this.pending = undefined
    pending?.reject(new Error(message))
  }
}
