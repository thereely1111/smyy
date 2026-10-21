/**
 * 站点对话 ↔ 沙箱 的交接单
 *
 * 场景：站点那条对话（#/work）里代码生成好之后，用户可以选择「进入沙箱运行」，
 * 跑完的产物要回到那条对话里。可这两处是两个路由、两个组件实例，中间还隔着一次
 * 卸载 —— 组件里的东西过不去。
 *
 * 所以用「交接单」中转，分两份：
 *   request  去程：对话写下「哪道题 / 哪一问 / 跑哪个文件」，沙箱进来自取
 *   receipt  回程：沙箱跑完写下「产物清单 / 退出码」，对话回来时取走
 *
 * 为什么这两份走 sessionStorage、而对话快照走内存变量：
 *   request / receipt 要扛住「在沙箱里按了 F5」—— 刷新后交接单还得在，
 *   否则回来时对话那边什么也接不到。对话快照只服务于「同一页内往返」，
 *   一旦刷新，原型的状态本来就已重置，这时把旧 DOM 贴回去反而与 state 对不上，
 *   所以它只活在内存里，刷新即失效，正好。
 *
 * ⚠️ 单标签页语义：在另一个标签页打开沙箱取不到交接单，会退化成「没有来路的
 *    普通沙箱」，不影响沙箱本身可用。
 */

const REQ_KEY = 'smyy.sandbox_handoff_request'
const RCP_KEY = 'smyy.sandbox_handoff_receipt'

/** 去程：对话交给沙箱的那件事 */
export interface HandoffRequest {
  /** 原型题号（p3） */
  protoId: string
  /** 沙箱题号（2026-C） */
  sandboxId: string
  /** 原型 QUIZ 下标 —— 回执里要带回来，好知道是哪一问 */
  qn: number
  /** 「问题一」这种给人看的说法 */
  qLabel: string
  /** 待运行的文件名（main.py） */
  codeName: string
  /** 给沙箱输入框预填的指令 */
  instruction: string
  at: number
}

export interface HandoffFile {
  path: string
  size: number
}

/** 回程：沙箱跑完交还给对话的凭据 */
export interface HandoffReceipt {
  sandboxId: string
  qLabel: string
  command: string
  exitCode: number | null
  files: HandoffFile[]
  at: number
}

function readJson<T>(key: string): T | null {
  try {
    const raw = sessionStorage.getItem(key)
    if (!raw) return null
    return JSON.parse(raw) as T
  } catch {
    // 损坏就当作没有 —— 比抛出去把页面弄崩好
    return null
  }
}

function writeJson(key: string, val: unknown | null): void {
  try {
    if (val === null) sessionStorage.removeItem(key)
    else sessionStorage.setItem(key, JSON.stringify(val))
  } catch {
    // 隐私模式 / 存储配额满：本次往返拿不到东西，但页面照常用
  }
}

/* ── 去程 ─────────────────────────────────────────────────────────── */

export function putRequest(req: HandoffRequest): void {
  writeJson(REQ_KEY, req)
}

export function readRequest(): HandoffRequest | null {
  return readJson<HandoffRequest>(REQ_KEY)
}

export function clearRequest(): void {
  writeJson(REQ_KEY, null)
}

/**
 * 取这一道题该认的交接单。
 *
 * 按 sandboxId 过滤：带着 A 题的交接单进了 B 题的工作台时，
 * 不该把 A 题的来路挂在 B 题上。
 */
export function requestFor(sandboxId: string): HandoffRequest | null {
  const req = readRequest()
  return req && req.sandboxId === sandboxId ? req : null
}

/* ── 回程 ─────────────────────────────────────────────────────────── */

export function putReceipt(rcp: HandoffReceipt): void {
  writeJson(RCP_KEY, rcp)
}

/**
 * 取走回执（取完即清）。
 *
 * 必须是「取走」而不是「读取」—— 否则每次进 #/work 都会把同一条产物消息
 * 再追加一次，对话会被刷屏。
 */
export function takeReceipt(): HandoffReceipt | null {
  const rcp = readJson<HandoffReceipt>(RCP_KEY)
  writeJson(RCP_KEY, null)
  return rcp
}

/* ── 对话快照（只活在内存里，见文件头说明） ─────────────────────────── */

let streamSnapshot: string | null = null

/** 离开对话去沙箱前，把对话区存一份 */
export function saveStreamSnapshot(html: string): void {
  streamSnapshot = html
}

/** 回到对话时取回；取走即清，避免下次进 #/work 又贴一遍旧内容 */
export function takeStreamSnapshot(): string | null {
  const s = streamSnapshot
  streamSnapshot = null
  return s
}
