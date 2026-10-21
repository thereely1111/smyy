/**
 * 沙箱执行引擎 —— 不经过 LLM、也不依赖后端的自带执行能力。
 *
 * 为什么需要它：
 *   智能体（AG-UI 那条路）依赖后端有可用的 agent，而 agent 还没落地。
 *   但沙箱本身的价值是「写好代码 → 看到结果」，这件事不该被 agent 卡住。
 *   所以沙箱提供两条执行通道：
 *     local ——（本文件）纯前端，用户点一下就跑，不经任何模型
 *     agent ——（stores/sandbox.ts）走 AG-UI，由模型决定执行什么
 *   两者产出同一种结果结构（ExecResult），UI 不必区分。
 *
 * 执行什么：
 *   1. 内置的解题代码（src/data/builtinCode.ts）
 *   2. **用户在沙箱里打开并改过的产物文件**（.js/.ts）
 *   每段都用 `new Function` 在当前页面里跑，把 console 输出收进 stdout。
 *   零依赖、零安装、离线可用。
 *
 * 虚拟文件系统（2026-09-28 新增）：
 *   产物文件里的代码常常要读同目录的数据文件（附件 csv 等）。沙箱引擎
 *   通过 `sandbox.readFile(path)` 暴露一个**只读内存视图** —— 调用方把
 *   沙箱内已有的文件内容传进来，代码就能读，但**碰不到真实磁盘**。
 *   这样做的好处是执行完全可预测：读到什么由传入的快照决定。
 *
 * ⚠️ 安全边界（务必如实告知使用者）：
 *   本地通道是「同页面内执行」，**不是**沙箱隔离。它能被调用方注入的
 *   文件视图读取，但不应被当作安全边界 —— 页面内的代码理论上仍可触达
 *   同页面的其它对象。只用于跑**用户自己写的、可信的**代码；
 *   要跑来路不明的代码，请走后端 execute（进程隔离 + SESSION 路径策略）。
 *   UI 上必须写清这一点。
 */

import type { ExecResult } from '@/utils/parseToolExec'

/** 沙箱内一个可读文件（内存快照，不是真实磁盘文件） */
export interface SandboxFile {
  /** 沙箱内路径，如 `data/附件1.csv` */
  path: string
  content: string
}

/** 传给被执行的代码的文件视图 —— 只读、只暴露列出的文件 */
export interface SandboxFS {
  /** 读一个文件；不存在时抛错（错误进 stderr） */
  readFile: (path: string) => string
  /** 文件是否存在 */
  exists: (path: string) => boolean
  /** 列出全部可读文件路径 */
  list: () => string[]
}

/** 内置代码的执行产物 */
export interface LocalRunOutput {
  result: ExecResult
  /** 被捕获的 console 调用序列（保留级别，便于 UI 分色） */
  logs: Array<{ level: string; args: string }>
  /** 是否超时被杀 */
  timedOut: boolean
}

/** 执行选项 */
export interface RunLocalOptions {
  timeoutMs?: number
  now?: () => number
  /**
   * 沙箱内的可读文件（内存快照）。
   *
   * 传入后，被执行的代码可以用 `sandbox.readFile('data/x.csv')` 读到。
   * 不传则 `sandbox` 对象仍然存在，但读任何文件都会得到「文件不存在」。
   */
  files?: SandboxFile[]
  /**
   * 执行入口文件的沙箱内路径（如 `artifacts/src/solve_q1.js`）。
   *
   * 用途：解析代码里的**相对路径**。用户在 `src/solve_q1.js` 里写
   * `readFile('data/x.csv')`，指的是 `data/` 与自己同级 —— 靠这个基准
   * 才能算出 `artifacts/data/x.csv`。不传则只按绝对路径与文件名匹配。
   */
  entryPath?: string
}

/** 硬超时（ms）。内置代码都是小算例，超过即是死循环 */
const DEFAULT_TIMEOUT = 8000

/** 路径归一：统一用 `/` 分隔、去掉开头的 `./` 与 `/`，便于按名匹配 */
function normPath(p: string): string {
  return String(p).replace(/\\/g, '/').replace(/^\.?\//, '')
}

/**
 * 把相对路径解析成绝对路径（相对 `entryPath` 所在目录）。
 *
 * 为什么需要：用户在 `artifacts/src/solve_q1.js` 里写
 *   `sandbox.readFile('data/附件1.csv')`
 * 指的是「跟我同级的 data 目录」，而产物里的真实路径是
 *   `artifacts/data/附件1.csv`
 * 中间差着层级。真实文件系统靠「相对当前文件解析」来解决，
 * 这里照做 —— 否则用户只能写全路径，稍微挪个文件就全断。
 *
 * 支持 `..` 上跳；超出根就当作从根算（不报错，尽力解析）。
 */
function resolvePath(spec: string, entryPath: string): string {
  const raw = String(spec).replace(/\\/g, '/')
  // 以 / 开头视为「沙箱根」，不做相对解析
  const parts = raw.startsWith('/')
    ? raw.slice(1).split('/')
    : [...dirOf(entryPath).split('/').filter(Boolean), ...raw.split('/')]

  const out: string[] = []
  for (const seg of parts) {
    if (!seg || seg === '.') continue
    if (seg === '..') {
      out.pop()
      continue
    }
    out.push(seg)
  }
  return out.join('/')
}

/** 取路径的目录部分（`a/b/c.js` → `a/b`） */
function dirOf(p: string): string {
  const parts = normPath(p).split('/')
  parts.pop()
  return parts.join('/')
}

/** 把任意值渲染成一行可读文本（与浏览器 console 观感接近） */
function fmt(v: unknown): string {
  if (v === null) return 'null'
  if (v === undefined) return 'undefined'
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  if (v instanceof Error) return `${v.name}: ${v.message}`
  if (Array.isArray(v)) {
    // 长数组截断，避免把面板刷爆
    const head = v.slice(0, 20).map(fmt).join(', ')
    return v.length > 20 ? `[${head}, … +${v.length - 20}]` : `[${head}]`
  }
  try {
    const s = JSON.stringify(v)
    return s.length > 800 ? s.slice(0, 800) + '…' : s
  } catch {
    return String(v)
  }
}

/**
 * 跑一段代码。
 *
 * 设计取舍：
 *   - 用 `new Function` 而非 `eval`：作用域干净，且能显式传参
 *   - **只注入 `console` / `checkTimeout` / `sandbox`**，不注入
 *     `window`/`document`：解题代码是纯计算，不该碰 DOM；碰到就抛错，
 *     错误信息会进 stderr —— 这比默默改坏页面安全。
 *   - 同步执行 + 事后取日志：内置代码都是同步小算例；
 *     真要异步（fetch/定时器）也支持，靠返回值是 Promise 来判断
 *   - 超时用「协作式检查 + Promise.race」双保险：死循环不会锁死页面
 */
export async function runLocalCode(
  code: string,
  options: RunLocalOptions = {},
): Promise<LocalRunOutput> {
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT
  const start = (options.now ?? Date.now)()

  const logs: LocalRunOutput['logs'] = []
  const stdoutParts: string[] = []

  // 收集 console 输出；同时把纯文本级别（log/info）汇入 stdout，
  // warn/error 汇入 stderr —— 与后端 execute 的观感保持一致
  const stderrParts: string[] = []
  const pushLog = (level: string, args: unknown[]) => {
    const line = args.map(fmt).join(' ')
    logs.push({ level, args: line })
    if (level === 'warn' || level === 'error') stderrParts.push(line)
    else stdoutParts.push(line)
  }

  const fakeConsole = {
    log: (...a: unknown[]) => pushLog('log', a),
    info: (...a: unknown[]) => pushLog('info', a),
    warn: (...a: unknown[]) => pushLog('warn', a),
    error: (...a: unknown[]) => pushLog('error', a),
    debug: (...a: unknown[]) => pushLog('debug', a),
    table: (...a: unknown[]) => pushLog('log', a),
  }

  // 只读文件视图。
  //
  // 索引建三套键，按「从具体到宽松」的顺序查：
  //   1. 完整路径           artifacts/data/x.csv
  //   2. 去掉 artifacts/ 前缀  data/x.csv       ← 用户最常写的形式
  //   3. 尾部文件名           x.csv             ← 同目录引用时最省事
  // 这样一个文件无论用哪种写法都能命中，不必逼用户记住后端目录前缀。
  const fsIndex = new Map<string, string>()
  const addKey = (k: string, v: string) => {
    const key = normPath(k)
    if (key && !fsIndex.has(key)) fsIndex.set(key, v)
  }
  for (const f of options.files ?? []) {
    const full = normPath(f.path)
    addKey(full, f.content)
    // 后端产物目录前缀（artifacts）不是用户概念里的「根」，剥掉再建一份键
    addKey(full.replace(/^artifacts\//, ''), f.content)
    addKey(full.split('/').pop() ?? '', f.content)
  }

  // 执行入口文件路径 —— 相对路径解析的基准
  const entryPath = normPath(options.entryPath ?? '')

  const lookup = (spec: string): string | undefined => {
    const direct = fsIndex.get(normPath(spec))
    if (direct !== undefined) return direct
    // 相对入口文件解析（真实文件系统的行为）
    if (entryPath) {
      const rel = fsIndex.get(resolvePath(spec, entryPath))
      if (rel !== undefined) return rel
    }
    return undefined
  }

  const sandboxFS: SandboxFS = {
    exists: (p: string) => lookup(p) !== undefined,
    readFile: (p: string) => {
      const hit = lookup(p)
      if (hit === undefined) {
        // 报一个「像文件系统」的错，方便用户看出是路径写错了
        const known = [...fsIndex.keys()].slice(0, 12).join(', ')
        throw new Error(`ENOENT: 沙箱内没有文件 '${p}'。可读文件：${known || '（无）'}`)
      }
      return hit
    },
    list: () => [...fsIndex.keys()],
  }

  let exitCode = 0
  let isError = false
  let timedOut = false

  // 超时保护：内置代码若死循环会锁死页面，所以用一个「协作式」检查 +
  // 外层 Promise.race 双保险。协作式检查注入到代码可用的 `checkTimeout()`。
  const deadline = start + timeoutMs
  const checkTimeout = () => {
    if (Date.now() > deadline) {
      throw new Error(`执行超时（${timeoutMs}ms）—— 内置代码不应有耗时循环，请检查`)
    }
  }

  try {
    // eslint-disable-next-line no-new-func
    const fn = new Function(
      'console',
      'checkTimeout',
      'sandbox',
      `"use strict";\n${code}\n`,
    )
    const ret = fn(fakeConsole, checkTimeout, sandboxFS)
    // 支持返回 Promise 的代码（内置代码基本都是同步，这里只是留出口）
    if (ret && typeof ret.then === 'function') {
      await Promise.race([
        ret,
        new Promise((_, rej) =>
          setTimeout(() => rej(new Error(`执行超时（${timeoutMs}ms）`)), timeoutMs),
        ),
      ])
    }
  } catch (e) {
    const msg = e instanceof Error ? `${e.name}: ${e.message}` : String(e)
    stderrParts.push(msg)
    // 超时归类为 124（与 shell 的 timeout 约定一致），其它算 1
    const isTimeout = /超时/.test(msg)
    timedOut = isTimeout
    exitCode = isTimeout ? 124 : 1
    isError = true
  }

  const elapsedMs = (options.now ?? Date.now)() - start

  return {
    timedOut,
    logs,
    result: {
      toolCallId: `local-${Math.random().toString(36).slice(2, 10)}`,
      toolName: 'local',
      command: '',
      stdout: stdoutParts.join('\n'),
      stderr: stderrParts.join('\n'),
      exitCode,
      elapsedMs,
      isError,
      // 原始文本按后端 execute 的格式产出，让「原始输出」视图两种通道观感一致
      raw: `Exit code: ${exitCode}\n\n${stdoutParts.join('\n') || '<no output>'}`,
    },
  }
}
