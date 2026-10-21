/**
 * 调「数学建模解答助手」做 S3 解题代码，并把代码包读回来
 *
 * 范式与 `@/api/analysisRun`、`@/api/animRun` 一致：
 * runAgui(SSE) + 会话产物树。三条关键差异：
 *   1. **同一会话**：S3 的输入是 S1/S2 的产物，threadId 必须与它们相同
 *      （经 `analysis/flow.threadIdOf` 取），智能体才能在本会话空间里读到上游。
 *   2. **按问取包**：一个会话里四问的代码都在 `code/` 下，靠 `qXX` 段区分
 *      （智能体的命名规范是 `qXX_mYY_aZZ`）。取不到本问段时退回全部 —— 兼容
 *      只生成一问的包。
 *   3. **不运行**：智能体只产源码与运行说明（`executed_problem_code: false`）。
 *      真跑走站点那套沙箱交接，本模块只负责把源码读回来。
 *
 * 这条链只做「发请求、收产物」，不渲染、不碰 DOM。
 */

import { extractInterrupts, parseAguiStream, runAgui, userMessage, runEventError } from '@/api/agui'
import { fetchArtifactContent, fetchArtifactTree } from '@/api/artifact'
import { waitForArtifact } from '@/api/waitForArtifact'
import { registerInterrupts } from '@/api/formInterrupt'
import type { AguiInterrupt } from '@/types/agui'
import { AGENT_ID } from '@/config'
import { flattenArtifacts } from '@/api/analysisRun'
import type { AguiEvent } from '@/types/agui'
import type { ArtifactNode } from '@/types/sandbox'

/** 一个代码文件（相对 `code/` 的路径 + 全文） */
export interface CodeFile {
  path: string
  name: string
  content: string
}

/** 一问的代码包 */
export interface CodePackage {
  /** 主入口文件名（如 main.py） */
  mainName: string
  mainText: string
  /** 除主入口外的其余文件（含 README 与 expected_outputs.json） */
  files: CodeFile[]
  readme: string
  /** 生成本包所用的模型名 —— 换模型重选时要能识别并重新生成 */
  model: string
}

/** 进度回调：让等待气泡能显示「智能体在第几步」 */
export interface CodeProgress {
  textLength: number
  toolCalls: number
  lastTool: string
}

export interface RunCodeOptions {
  threadId: string
  runId: string
  /** 上游（S1/S2）登记的产物相对路径（原样转发进提示词，便于智能体定位上游） */
  upstreamPaths?: string[]
  /** 最长等待（毫秒）。按问写一份完整源码包 + 静态检查，比全局分析宽 */
  timeoutMs?: number
  signal?: AbortSignal
  onProgress?: (p: CodeProgress) => void
  /**
   * 每个原始 AG-UI 事件（透给等待气泡的实时区）。
   * 与 `onProgress` 的分工同 `animRun`：那个给摘要数字，这个给事件流本身。
   */
  onEvent?: (ev: AguiEvent) => void
}

/** S3 阶段请求 */
export interface CodeStageRequest {
  case_id: string
  title: string
  /** 问题序号（1 基：1 = 问题一），与智能体 `qXX` 口径一致 */
  question_id: number
  /** 用户点选的那个模型 */
  model: { name: string; desc?: string }
}

export interface CodeOutcome {
  status: 'COMPLETED' | 'PARTIAL' | 'BLOCKED'
  pkg: CodePackage | null
  replyText: string
  artifacts: string[]
  failureNote?: string
}

const CODE_SEG = '/code/'
const README_NAME = 'README_RUN.md'
const MAX_FILES = 12
const DEFAULT_TIMEOUT_MS = 30 * 60 * 1000

function isRecord(v: unknown): v is Record<string, unknown> {
  return !!v && typeof v === 'object' && !Array.isArray(v)
}

const normPath = (p: string): string => p.replace(/\\/g, '/')
const fileNameOf = (p: string): string => normPath(p).slice(normPath(p).lastIndexOf('/') + 1)

/** 问题段名：问题一 → `q01` */
export function questionSeg(qi: number): string {
  return `q${String(qi + 1).padStart(2, '0')}`
}

/** 相对 `code/` 的展示路径 */
function relToCode(p: string): string {
  const t = normPath(p)
  const i = t.indexOf(CODE_SEG)
  return i < 0 ? fileNameOf(t) : t.slice(i + CODE_SEG.length)
}

/**
 * S3 阶段请求 → 给总控的用户消息。
 *
 * 对接的仍是总控型智能体：它自己会派发「解题代码智能体」并按 `qXX_mYY_aZZ`
 * 落盘。消息只给四样：上游在哪、本问是第几问、用户点了哪个模型、附件没提供。
 */
export function buildCodePrompt(req: CodeStageRequest, upstream: string[]): string {
  const seg = `q${String(req.question_id).padStart(2, '0')}`
  return [
    `这是同一道数学建模赛题的下一阶段：**S3 解题代码**（本会话的**问题 ${req.question_id}**，沿用同一份 case 产物）。`,
    '',
    '## 上游（S1 / S2）产物 —— 已在本会话空间落盘，用文件工具读取',
    '- `analysis_result.json`：其 `primary_artifacts` 才是当前分析文件的权威指针，**不要按文件名猜版本**；',
    '- `truth/problem_contract.json`、`truth/solution_map.json`；',
    ...(upstream.length ? ['上游登记的产物相对路径（原样转发）：', ...upstream.map((p) => `- ${p}`)] : []),
    '',
    '## 本阶段任务',
    `把**已审核**的分析与模型路线实现为可审计的 Python 源码包，只做**问题 ${req.question_id}**（目录按 \`${seg}_mYY_aZZ\` 粒度隔离）。`,
    '完成后做一次静态检查（AST / JSON / 路径 / 输出契约），**不要运行题目程序**、不要制造正式数值结果。',
    '',
    '## 用户在本轮拍板的模型',
    `- 名称：${req.model.name}`,
    ...(req.model.desc ? [`- 说明：${req.model.desc}`] : []),
    '- 这是用户自己选的方案，请按它实现；若它与已审核路线冲突，写清冲突点并在产物里说明。',
    '',
    '## 期望产物（放在你会话空间的 `artifacts/<case_id>/code/` 下）',
    `- \`${seg}_m01_a01/*.py\`（源码，主入口以 \`main.py\` 为佳）`,
    '- `README_RUN.md`（环境安装、输入放置、执行命令）',
    '- `expected_outputs.json`（输出契约）',
    '- `requirements.txt`（直接依赖）',
    '',
    '## 边界',
    '- 本题附件内容未随请求提供：读取不到真实附件时按你的规范如实记为缺件，**不要编造数据**；',
    '- 本阶段**没有**用户回传的运行结果，任何数值只能标为待运行；',
    '- 本次由站点自动发起，**不需要发闸门表单**（沿用你上一阶段的确认即可）；确有需要用户拍板的事，写进产物里说明。',
    '',
    '## 结构化请求',
    '```json',
    JSON.stringify(req, null, 2),
    '```',
  ].join('\n')
}

/** 从产物树里挑出 `code/` 下、属于本问的文件；取不到本问段就退回全部 code 文件 */
export function collectCodePaths(files: ArtifactNode[], qi: number): ArtifactNode[] {
  const inCode = files.filter((f) => normPath(f.path).includes(CODE_SEG))
  if (!inCode.length) return []
  const seg = questionSeg(qi)
  const re = new RegExp(`/${seg}[_/]`)
  const mine = inCode.filter((f) => re.test(normPath(f.path)))
  // 一旦出现 qXX 分区，就不能拿其它问题的代码冒充本问。
  return mine.length ? mine : inCode.some((f) => /\/q\d{2}[_\/]/i.test(normPath(f.path))) ? [] : inCode
}

/** 主入口：优先 main.py；没有就取路径最短的那个 .py（多半就是入口） */
function pickMain(nodes: ArtifactNode[]): ArtifactNode | null {
  const pys = nodes.filter((f) => /\.py$/i.test(f.path))
  if (!pys.length) return null
  const named = pys.slice().sort((a, b) => (b.updatedAt ?? 0) - (a.updatedAt ?? 0))
    .find((f) => /(^|\/)main\.py$/i.test(normPath(f.path)))
  if (named) return named
  return pys.slice().sort((a, b) => normPath(a.path).length - normPath(b.path).length)[0]
}

/**
 * 收集并读取本问的代码包（run 结束与 reuse 共用）。
 *
 * 读取失败的文件记为空内容但**保留在清单里** —— 用户至少知道有这个文件。
 */
export async function collectCodePackage(
  threadId: string,
  qi: number,
  model: string,
  since = 0,
): Promise<{ pkg: CodePackage | null; paths: string[] }> {
  const tree = await fetchArtifactTree(AGENT_ID, threadId)
  const files = flattenArtifacts(tree).filter((f) => !since || (f.updatedAt != null && f.updatedAt >= since))
  const nodes = collectCodePaths(files, qi)
  if (!nodes.length) return { pkg: null, paths: [] }

  const main = pickMain(nodes)
  if (!main) return { pkg: null, paths: nodes.map((n) => n.path) }

  const read = async (path: string): Promise<string> => {
    try {
      return (await fetchArtifactContent(AGENT_ID, threadId, path))?.content ?? ''
    } catch {
      return ''
    }
  }

  const mainText = await read(main.path)
  const others = nodes
    .filter((n) => n.path !== main.path)
    /* README 与 expected_outputs 优先，其余按路径排序，最多 MAX_FILES 份 */
    .sort((a, b) => {
      const score = (p: string): number => {
        const n = fileNameOf(p)
        if (n === README_NAME) return 0
        if (n === 'expected_outputs.json') return 1
        if (n === 'requirements.txt') return 2
        return 3
      }
      const d = score(a.path) - score(b.path)
      return d !== 0 ? d : normPath(a.path).localeCompare(normPath(b.path))
    })
    .slice(0, MAX_FILES)

  const out: CodeFile[] = []
  let readme = ''
  for (const n of others) {
    const content = await read(n.path)
    const path = relToCode(n.path)
    if (fileNameOf(n.path) === README_NAME) readme = content
    out.push({ path, name: fileNameOf(n.path), content })
  }

  return {
    pkg: {
      mainName: fileNameOf(main.path),
      mainText,
      files: out,
      readme,
      model,
    },
    paths: [main.path, ...others.map((n) => n.path)],
  }
}

/** 从 SSE 事件里取工具名（字段名有版本差异，挨个试） */
function toolNameOf(ev: AguiEvent): string {
  const candidates = ['toolCallName', 'toolName', 'name']
  for (const k of candidates) {
    const v = ev[k]
    if (typeof v === 'string' && v) return v
  }
  const meta = ev['metadata']
  if (isRecord(meta) && typeof meta['toolName'] === 'string') return meta['toolName'] as string
  return ''
}

/**
 * 跑一次 S3：发请求 → 读流 → 收代码包。
 *
 * 失败（网络、401、超时、产物全缺）返回 `BLOCKED`，**不抛错** ——
 * 调用方拿到 BLOCKED 就回落内置剧本代码，并如实标注来源。
 */
export async function runCodeStage(req: CodeStageRequest, opts: RunCodeOptions): Promise<CodeOutcome> {
  const timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const startedAt = Date.now()
  const blocked: CodeOutcome = { status: 'BLOCKED', pkg: null, replyText: '', artifacts: [] }

  const ctl = new AbortController()
  const onOuterAbort = (): void => ctl.abort()
  opts.signal?.addEventListener('abort', onOuterAbort, { once: true })
  if (opts.signal?.aborted) ctl.abort()
  const timer = window.setTimeout(() => ctl.abort(), timeoutMs)

  let text = ''
  let toolCalls = 0
  let lastTool = ''
  let interrupted = false
  let pending: AguiInterrupt[] = []
  let streamError = ''
  let finished = false

  try {
    const res = await runAgui(
      AGENT_ID,
      {
        threadId: opts.threadId,
        runId: opts.runId,
        // id 不是可选项：后端 RunAgentInput 反序列化缺 id 直接 400
        messages: [userMessage(`u-${opts.runId}`, buildCodePrompt(req, opts.upstreamPaths ?? []))],
      },
      ctl.signal,
    )
    await parseAguiStream(
      res,
      (ev) => {
        /* 先原样透给等待气泡的实时区，再做本模块的摘要统计 */
        opts.onEvent?.(ev)
        const type = String(ev['type'] ?? '')
        if (/TEXT_MESSAGE_CONTENT/i.test(type)) {
          const delta = typeof ev['delta'] === 'string' ? ev['delta'] : ''
          if (delta) text += delta
        }
        if (/TOOL_CALL_START|TOOL_CALL_ARGS/i.test(type)) {
          if (/TOOL_CALL_START/i.test(type)) toolCalls += 1
          const n = toolNameOf(ev)
          if (n) lastTool = n
        }
        if (type === 'RUN_FINISHED') {
          finished = true
          const outcome = ev['outcome']
          if (isRecord(outcome) && outcome['type'] === 'interrupt') interrupted = true
        }
        const forms = extractInterrupts(ev)
        if (forms.length) { pending = forms; interrupted = true }
        streamError ||= runEventError(ev)
        opts.onProgress?.({ textLength: text.length, toolCalls, lastTool })
      },
      ctl.signal,
    )
  } catch (e) {
    return { ...blocked, replyText: text, failureNote: streamError ||
      (ctl.signal.aborted ? '代码流等待超时或已取消' : e instanceof Error ? e.message : '代码通道调用失败') }
  } finally {
    window.clearTimeout(timer)
    opts.signal?.removeEventListener('abort', onOuterAbort)
  }

  if (interrupted && !streamError && !ctl.signal.aborted) {
    try {
      if (!pending.length) throw new Error('未返回可用的确认表单')
      await registerInterrupts(AGENT_ID, opts.threadId, pending, opts.runId)
    } catch (e) {
      streamError = '待确认表单登记失败：' + (e instanceof Error ? e.message : String(e))
    }
  }
  if (streamError || interrupted || !finished || ctl.signal.aborted) {
    return { ...blocked, replyText: text, failureNote: streamError ||
      (interrupted ? '智能体需要人工确认，请到沙箱对话处理' :
        !finished ? '代码流意外断开，未收到 RUN_FINISHED' : '代码请求已取消') }
  }

  opts.onProgress?.({ textLength: text.length, toolCalls, lastTool: '等待后台代码产物' })
  const got = await waitForArtifact(async () => {
    const result = await collectCodePackage(opts.threadId, req.question_id - 1, req.model.name, startedAt - 2000)
    return result.pkg?.mainText ? result : null
  }, startedAt + timeoutMs, opts.signal)
  if (!got) return { ...blocked, replyText: text, failureNote: opts.signal?.aborted
    ? '代码产物等待已取消' : '等待代码产物超时，后台可能仍在运行' }
  return { status: 'COMPLETED', pkg: got.pkg, replyText: text, artifacts: got.paths }
}
