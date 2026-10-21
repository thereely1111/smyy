/**
 * 调智能体做「题目分析」，并把四个产物读回来
 *
 * 复用沙箱那一层（不重复造轮子）：
 *   api/agui.ts    —— AG-UI 的 SSE 客户端与纯函数解析器
 *   api/artifact.ts—— 产物树 / 内容读取
 *   config         —— agentId（数学建模解答助手）与模式开关
 *
 * 这条链只做一件事：把请求发出去、把产物收回来。**不做渲染、不碰 DOM**，
 * 这样它可以被单独跑（脚本里直接调），也不必担心样式问题。
 *
 * ⚠️ 产物路径不做 `artifacts/` 前缀硬编码：后端 artifacts-dir 是可配的，
 *    一律按「以 case_id 结尾的分段」去匹配树里的真实 path。
 */

import { extractInterrupts, parseAguiStream, runAgui, userMessage, runEventError } from '@/api/agui'
import { fetchArtifactContent, fetchArtifactTree } from '@/api/artifact'
import { registerInterrupts } from '@/api/formInterrupt'
import {
  UNRESOLVED_HINT,
  humanizeFailure,
  isUnresolvedInterruptsError,
  releaseInterrupts,
  releasePendingInterrupts,
  submitResumeCoveringAll,
} from '@/api/interruptRelease'
import type { AguiInterrupt } from '@/types/agui'
import { AGENT_ID } from '@/config'
import { gateFromInterrupts, gatePayload, type GateSpec, type GateValues } from '@/utils/analysis/gate'
import { normalizeContract, normalizeResult, normalizeSolutionMap } from '@/utils/analysis/normalize'
import type { AguiEvent } from '@/types/agui'
import type { ArtifactNode } from '@/types/sandbox'
import type {
  AnalysisOutcome,
  AnalysisResult,
  ProblemAnalysisRequest,
} from '@/types/analysis'

/** 产物文件名（相对 case_id 目录） */
const FILE_RESULT = 'analysis_result.json'
const FILE_CONTRACT = 'truth/problem_contract.json'
const FILE_SOLUTION = 'truth/solution_map.json'
/**
 * 一次 run 里最多接受几轮人工确认。
 *
 * 闸门是模型可以反复发起的 —— 万一它陷进「问一次、再问一次」的循环，
 * 这里必须有个止损点，不能让对话区永远挂着等一张永远填不完的表。
 */
const MAX_GATES = 6
/**
 * 分析报告：**不写死版本号**。智能体按自己的修订节奏给 v1.0 / v1.1 …
 * （其 Skill 规定「当前版以 analysis_result.json.primary_artifacts 为准」，
 * 曾出现 v1.0 是首轮失败产物、v1.1 才是正确内容的情况）。
 * 这里只给目录与名字前缀，具体取哪一份见 pickReport()。
 */
const REPORT_DIR = 'analysis/'
const REPORT_PREFIX = 'analysis_v'

function isRecord(v: unknown): v is Record<string, unknown> {
  return !!v && typeof v === 'object' && !Array.isArray(v)
}

/** 把产物树拍平成一维路径表（树形结构深浅不定，递归取叶子） */
export function flattenArtifacts(nodes: ArtifactNode[] | null | undefined): ArtifactNode[] {
  if (!Array.isArray(nodes)) return []
  const out: ArtifactNode[] = []
  const walk = (list: ArtifactNode[]): void => {
    for (const n of list) {
      if (!n) continue
      if (n.type === 'dir') {
        if (n.children) walk(n.children)
        continue
      }
      if (typeof n.path === 'string') out.push(n)
    }
  }
  walk(nodes)
  return out
}

const normPath = (p: string): string => p.replace(/\\/g, '/')

/**
 * 在产物树里找产物文件。两级匹配，宽严有序：
 *
 *   1. `.../{caseId}/<tail>` —— 精确，路径里真的带 case_id 分段
 *   2. `.../<tail>`         —— 回退，只认结尾
 *
 * 为什么必须有回退：case_id 是**调用方建议、智能体可以自行改写**的。
 * 「数学建模解答助手」按自己的口径生成 `YYYYMMDD_题目标识_短随机码`
 * （实测 `20260928_C_a7k3`，与站点注入的值并不相同），只认第一级会全盘落空。
 * 会话是一题一会话（threadId 按 caseId 分配），所以退到「按结尾认文件」不会串题。
 *
 * 同样不拼 `artifacts/` 前缀：后端 `artifacts-dir` 可配，且子会话产物在
 * 树里的前缀是 `<subSessionId>/artifacts/`，写死前缀必失配。
 */
export function findArtifactByTail(files: ArtifactNode[], caseId: string, tail: string): ArtifactNode | null {
  const want = `/${tail}`
  const exact = files.filter((f) => normPath(f.path).endsWith(`/${caseId}${want}`))
  if (exact.length) return latestOf(exact)
  return latestOf(files.filter((f) => normPath(f.path).endsWith(want)))
}

/**
 * 同名多份时取**最近修改**的那一份。
 *
 * 产物树是「本会话 + 全部子会话」合并出来的，同名文件并存是常态（例如
 * `artifacts/<case>/truth/…` 与 `sub-xxxx/artifacts/<case>/truth/…`）。
 * 早先取的是 `find()` 的第一个 —— 那取决于目录遍历顺序，可能挑到**旧的那份**，
 * 于是页面展示的是上一版内容却装作是本轮的。按 mtime 取最新才符合「当前版」的语义。
 * `updatedAt` 取不到（null）时按 0 参与比较，全取不到就退化成「取最后一个」。
 */
function latestOf(list: ArtifactNode[]): ArtifactNode | null {
  let best: ArtifactNode | null = null
  for (const n of list) {
    if (!best || (n.updatedAt ?? 0) >= (best.updatedAt ?? 0)) best = n
  }
  return best
}

/** 从 analysis_result.json 的 primary_artifacts 里取产物名（结构宽松，字符串或对象都吃） */
function primaryNames(result: AnalysisResult | null): string[] {
  const raw = isRecord(result) ? result['primary_artifacts'] : undefined
  if (!Array.isArray(raw)) return []
  return raw
    .map((x) => {
      if (typeof x === 'string') return x
      if (isRecord(x)) {
        const v = x['path'] ?? x['name'] ?? x['file']
        return typeof v === 'string' ? v : ''
      }
      return ''
    })
    .filter((s) => s.length > 0)
}

/**
 * 找分析报告那一份 Markdown。
 *
 * 优先级：
 *   1. `analysis_result.json.primary_artifacts` 点名的那个 —— 智能体自己的权威版本指针
 *   2. 会话里任意 `analysis/analysis_v*.md` —— 多个时取路径排序最后一个（版本号最大）
 *   3. 都没有 → null，上层按「只有 JSON、没有正文」降级展示
 */
export function pickReport(
  files: ArtifactNode[],
  caseId: string,
  result: AnalysisResult | null,
): ArtifactNode | null {
  for (const name of primaryNames(result)) {
    const hit = findArtifactByTail(files, caseId, name)
    if (hit) return hit
  }
  const candidates = files
    .filter((f) => {
      const t = normPath(f.path)
      const seg = t.slice(t.lastIndexOf('/') + 1)
      return t.includes(`/${REPORT_DIR}`) && seg.startsWith(REPORT_PREFIX) && seg.endsWith('.md')
    })
    .sort((a, b) => normPath(a.path).localeCompare(normPath(b.path)))
  return candidates.length ? candidates[candidates.length - 1] : null
}

/**
 * 只读会话产物、组装成一次结果。**不发 run、不碰模型**。
 *
 * 与 `runProblemAnalysis` 收产物那段是同一套判据，抽出来是为了让上层能**反复**来收：
 * 「数学建模解答助手」是**异步编排型**智能体 —— 它把 S1 派给「题目解析智能体」
 * （`agent_spawn` 同步等 120s 超时后转后台），本轮就先收尾把 SSE 流关掉，
 * 产物要再等十几到二十几分钟才落盘。**流关掉 ≠ 活干完**。
 * 所以流关掉之后，上层要能隔一会儿再来收一次，直到约定的产物出现。
 *
 * 约定的四份产物一份都没读到 → 返回 `null`（上层据此知道「还没写完」，继续等）。
 * 产物层（后端）暂时不可用也按 `null` 处理 —— 宁可再试一次，不要误判成失败。
 *
 * @param since 只认「这个时刻之后落盘」的产物（毫秒）。传 0/undefined 表示不设限。
 *              用来防止把上一次运行留下的同名产物当成本轮结果 —— 本次是**等这一轮**的产物。
 */
export async function harvestAnalysis(
  caseId: string,
  threadId: string,
  since = 0,
): Promise<AnalysisOutcome | null> {
  let files: ArtifactNode[]
  try {
    files = flattenArtifacts(await fetchArtifactTree(AGENT_ID, threadId))
  } catch {
    return null
  }
  if (!files.length) return null

  const fresh = (node: ArtifactNode): boolean =>
    !since || node.updatedAt == null || node.updatedAt >= since

  const artifacts: string[] = []
  const read = async (tail: string): Promise<string> => {
    const node = findArtifactByTail(files, caseId, tail)
    if (!node || !fresh(node)) return ''
    try {
      const got = await fetchArtifactContent(AGENT_ID, threadId, node.path)
      artifacts.push(node.path)
      return got?.content ?? ''
    } catch {
      // 单个文件读不到不算整次失败 —— 其余产物照用
      return ''
    }
  }

  const rawContract = await read(FILE_CONTRACT)
  const rawSolution = await read(FILE_SOLUTION)
  const rawResult = await read(FILE_RESULT)

  const jsonContract = parseJsonLoose<unknown>(rawContract)
  const jsonSolution = parseJsonLoose<unknown>(rawSolution)
  const jsonResult = parseJsonLoose<unknown>(rawResult)

  /* 报告文件名带版本号，得先拿到 analysis_result.json 才知道取哪一份 */
  const reportNode = pickReport(files, caseId, jsonResult as AnalysisResult | null)
  let report = ''
  if (reportNode && fresh(reportNode)) {
    try {
      report = (await fetchArtifactContent(AGENT_ID, threadId, reportNode.path))?.content ?? ''
      if (report) artifacts.push(reportNode.path)
    } catch {
      report = ''
    }
  }

  /* 智能体自报「还在跑」时不能算收到 —— 它可能先落一份占位 result 再补内容 */
  const rawStatus = isRecord(jsonResult) ? jsonResult['status'] : undefined
  if (typeof rawStatus === 'string' && /^(EXECUTING|RUNNING|IN_PROGRESS|PENDING)$/i.test(rawStatus.trim())) {
    return null
  }

  /* 适配放在报告之后：contract 的材料清单 / 假设 / 符号表要从报告表格里抽 */
  const contract = normalizeContract(jsonContract, report)
  const solutionMap = normalizeSolutionMap(jsonSolution)
  const result = normalizeResult(jsonResult)
  if (!contract && !solutionMap && !result && !report) return null

  return {
    status: statusOf(result, !!(contract || solutionMap || report)),
    caseId,
    contract,
    solutionMap,
    result,
    report,
    replyText: '',
    artifacts,
  }
}

/**
 * 由 `analysis_result.json` 的自报状态定档；它没说话（或说了个没见过的词）时，
 * 按「有产物就算 PARTIAL」兜底。
 *
 * FAILED 与 BLOCKED 一律折成 BLOCKED —— 上层靠这个决定要不要回落内置剧本，
 * 「捡到零散产物」不能宣称完成。
 */
function statusOf(result: AnalysisResult | null, hasSide: boolean): AnalysisOutcome['status'] {
  if (result?.status === 'COMPLETED') return 'COMPLETED'
  if (result?.status === 'BLOCKED' || result?.status === 'FAILED') return 'BLOCKED'
  if (result?.status === 'PARTIAL') return 'PARTIAL'
  return hasSide ? 'PARTIAL' : 'BLOCKED'
}

/** 宽松解析 JSON —— 智能体可能给出带 ``` 围栏的文本 */
export function parseJsonLoose<T>(raw: string): T | null {
  if (!raw) return null
  let text = raw.trim()
  const fence = text.match(/```(?:json)?\s*([\s\S]*?)```/i)
  if (fence) text = fence[1].trim()
  try {
    return JSON.parse(text) as T
  } catch {
    // 没解析出来就当缺失 —— 上层会降级展示，不抛错
    return null
  }
}

/** 进度回调：让页面能显示「智能体在第几步」 */
export interface AnalysisProgress {
  /** 已收到的文本长度（近似进度感） */
  textLength: number
  /** 已发出的工具调用次数 */
  toolCalls: number
  /** 最近一次工具名 */
  lastTool: string
}

export interface RunAnalysisOptions {
  threadId: string
  runId: string
  /** 最长等待（毫秒）。超时按失败处理，由上层回落内置剧本 */
  timeoutMs?: number
  /** 本次开始时间（毫秒），用于排除会话内旧产物 */
  since?: number
  signal?: AbortSignal
  onProgress?: (p: AnalysisProgress) => void
  /**
   * 原始 AG-UI 事件回调（每个事件一次）。
   *
   * 为什么要单开这个：`onProgress` 只给「长度 + 次数」这类统计量，够画进度条，
   * 但画不出对话 —— 而在平台那边看得见的「思考了多久、跑了什么工具、逐段说了
   * 什么」，全部只存在于事件本身里。把流**原样**交出去，渲染那一层（live 模块）
   * 自己决定做成什么样。
   *
   * ⚠️ 回调里不要做重活：它跑在 SSE 解析的同步路径上。
   */
  onEvent?: (ev: AguiEvent) => void
  /**
   * 智能体把 run 挂起、要用户填表时回调（闸门）。
   *
   * 返回用户填的值 → 本函数按同一 (threadId, runId) 续跑，事件继续走 onEvent；
   * 返回 null → 视为用户取消，本次分析就此收尾（不会永远挂着）。
   *
   * 注意：等用户思考的这段时间**不计入超时** —— 否则他多想几分钟就被判「等待超时」。
   */
  onGate?: (gate: GateSpec) => Promise<GateValues | null>
}

/**
 * 把请求拼成给智能体的用户消息。
 *
 * 对接对象是 **smyy 自带的「数学建模解答助手」**——一个**总控型**智能体：
 * 它自己会派发「题目解析智能体」去做 S1，产物按它自己的
 * `mm-0-路径与会话隔离` / `mm-0-产物交接协议` 落盘。所以这条消息只给三样：
 *   1. 题面与附件清单（它的 S0 输入）
 *   2. 期望的产物名与目录（它的 S1 出口）
 *   3. **附件到没到位**这件事实：真上传了的写「已上传、可直接读取」，只有名字的
 *      写「未随请求提供内容」—— 它那条「缺件即停」的硬约束明令不得用示例数据顶替，
 *      所以这个口径含糊不得，两侧必须一致（见 flow.buildRequest 与 CaseAttachment.uploaded）
 *
 * **不**替它定 case_id（它会按 `YYYYMMDD_题目标识_短随机码` 自编，站点靠文件名取产物）。
 *
 * 闸门**是流程的一部分**，不是异常：S1a 收尾的「整题确认」、S1b 每问的「模型选择」
 * 与「满意度」，都由它在 run 内用 `ui_form_advanced` / `ui_gate` 发起；站点把表单画在
 * 对话区（或右栏），用户填完按同一条 (threadId, runId) 续跑（闸门循环见 `attemptAnalysis`）。
 * 单 run 闸门上限 `MAX_GATES`，所以提示词里明令**按需发、别把预算用满**。
 *
 * ⚠ 2026-10-14 口径修正：**站点只为开题起这一次 run**（`stores/analysis.ts` 的
 *   `start()`），之后逐问／重做全靠用户在对话区自由输入驱动（`askAgent`）。
 *   早先这段写着「S1a／S1b／S2／S3 由站点逐阶段各起一次 run」——与站点实际形态
 *   不符，会误导智能体空等；前端的逐问 run 驱动（`startQuestion` 等）已随之删除。
 */
export function buildAnalysisPrompt(req: ProblemAnalysisRequest): string {
  const anyUploaded = req.attachments.some((a) => a.uploaded)
  const anyMissing = req.attachments.some((a) => !a.uploaded)
  /** 题面是不是由题目文件承担（用户只传了文件、没写题面文字） */
  const anyPaper = req.attachments.some((a) => a.role === 'problem' && a.uploaded)
  const attachHead = !req.attachments.length
    ? '## 附件清单'
    : anyUploaded && anyMissing
      ? '## 附件清单（部分已上传到本会话工作区、部分只有名字）'
      : anyUploaded
        ? '## 附件清单（已上传到本会话工作区）'
        : '## 附件清单（只有名字，内容未随本次请求提供）'
  const attachLines = req.attachments.length
    ? req.attachments
        .map((a, i) => {
          /* 题目文件要标出来：它是题面，不是补充材料 —— 智能体先读哪一份，
             取决于这里写没写清 */
          const tag = a.role === 'problem' ? '题目文件，' : ''
          return a.uploaded
            ? `${i + 1}. ${a.name}（${tag}attachment_id: ${a.attachment_id}，已上传到本会话工作区，可直接读取）`
            : `${i + 1}. ${a.name}（${tag}attachment_id: ${a.attachment_id}，本次未随请求提供内容）`
        })
        .join('\n')
    : '（本题没有附件）'

  /* 站点只为开题起这一次 run，所以开场白恒是 S1a 整题解析 */
  const stageLine =
    '这是一道数学建模赛题，请做**整题解析**（S1a 阶段）：题目契约、子问题依赖与模型路线。'

  /**
   * 期望产物（S1a 整题级）。
   *
   * 逐问产物（`questions/qNN/*`）由你在**同一条会话里**按用户输入推进时自己写
   * （口径见 AGENTS.md §五「逐问闭环」）；本条消息只交代整题这几份。
   */
  const productLines = [
    '- `analysis/analysis_v1.0.md`（版本号按你的修订节奏）',
    '- `truth/problem_contract.json`',
    '- `truth/solution_map.json`',
    '- `analysis_result.json`（含 `primary_artifacts` 权威版本指针）',
  ]

  return [
    stageLine,
    '',
    '## 你在整条流水线里的位置（顺序固定，不得越位）',
    "S0 收题 → **S1a 整题解析** → 闸门 1'A（整题确认）→ **S1b 逐问闭环**（逐问按用户输入推进：① 初步分析 → ② 模型选择闸门 → ③ 按选定模型完整分析 → ④ 满意度由用户自己开口）→ 闸门 2'（逐问汇总）→ **S2 分析动画** → **S3 解题代码**（逐问沿用已选模型）→ S4 回传核验 → S5 论文。",
    "- 本轮＝**S1a**：只做整题解析，做完停在闸门 1'A 等用户确认；**不要**自行推进 S1b/S2/S3。",
    '',
    '## 题面',
    req.problem.text?.trim()
      ? req.problem.text
      : anyPaper
        ? '（本题没有另附题面文字 —— 题目要求全部在下面标为「题目文件」的附件里，请先读它们）'
        : '（未提供题面）',
    '',
    attachHead,
    attachLines,
    '',
    '## 本次的边界',
    '- 本轮只做**题意分析与建模路线**，**不求解、不写代码、不生成动画**。',
    '- **没有第二个智能体、也没有站点另起的并行通道**：S2 分析动画与 S3 解题代码都归你（总控）承担，在**同一条会话**里派发子智能体完成 —— S2 → `分析动画智能体`（能力在你的 `skills/mm-2-*`），S3 → `解题代码智能体`。',
    '- 但**本轮的边界只到 S1**：本轮**不要**派发「分析动画智能体」或「解题代码智能体」，也**不要**把任何阶段记为「由站点承担」而跳过。',
    '- ⚠ **站点只为开题起这一次 run**：此后的逐问、跳问、重做、出代码**都由用户在对话区打字驱动**，你按他的原话判定（判定口径见 AGENTS.md §九）。**不要等站点再发一次 run，也不要因为「没收到结构化指令」就停住**；反过来说，**用户没开口也不要自己把四问一路跑完**。',
    anyUploaded && !anyMissing
      ? '- 附件已上传到本会话工作区：先读文件再用里面的数据，**不要**用示意数据顶替；读不到的按缺件如实登记。'
      : anyUploaded
        ? '- 已上传的附件请直接读取；只有名字没给内容的那些，按你的规范如实记为未读取／缺件，**不要**用示意数据顶替。'
        : '- 附件内容没有提供：按你的规范如实记为未读取／缺件，**不要**用示意数据顶替。',
    '- 遇到**确实需要用户拍板**的关键决策（题面理解有歧义、模型路线二选一、数据口径要定），用 `ui_form_advanced` 发一张闸门表单当场问清楚 —— 站点会把表单画在对话区／右栏让用户填，填完你接着往下做。**闸门按需发，不是每轮必发**：能用一句话问清的就别发表单，也**别把闸门预算用满**（单 run 累计 ≤ 6 张，建议 ≤ 5）。',
    '',
    '## 期望产物（放在你会话空间的 `artifacts/<case_id>/` 下）',
    ...productLines,
    '',
    '## 关于 case_id',
    `建议用 \`${req.case_id}\`；你按自己的命名规范另起也可以 —— 站点按产物文件名取，不依赖这个值。`,
    '',
    '## 结构化请求',
    '```json',
    JSON.stringify(req, null, 2),
    '```',
  ].join('\n')
}

/** 从 SSE 事件里取工具名（不同版本字段名有差异，挨个试） */
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
 * 从流事件里挖错误文案。
 *
 * 平台把「拒绝」写成一个自定义事件：`{type:'RAW', event:{error:'该会话有正在执行的任务…'}}`，
 * 也可能直接给 `message`。字段名不统一，挨个试。
 */
function errorTextOf(ev: AguiEvent): string {
  for (const k of ['message', 'error']) {
    const v = ev[k]
    if (typeof v === 'string' && v) return v
  }
  const inner = ev['event']
  if (isRecord(inner)) {
    for (const k of ['error', 'message']) {
      const v = inner[k]
      if (typeof v === 'string' && v) return v
    }
  }
  return ''
}

/** 没抛流内错误时，用超时 / 异常本身给个说法 */
function reasonOf(e: unknown, timedOut: boolean, timeoutMs: number): string {
  if (timedOut) return `等待超过 ${Math.round(timeoutMs / 60000)} 分钟仍没有完整结果，已放弃等待`
  if (e instanceof Error && e.message) return e.message
  return '调用失败（原因未明）'
}

/**
 * 跑一次分析：发请求 → 读流 → 收产物。
 *
 * 失败（网络、401、超时、产物全缺）一律返回 `status: 'BLOCKED'` 的结果，
 * **不抛错** —— 调用方（站点那条对话）拿到 BLOCKED 就回落内置剧本，
 * 不需要再写 try/catch，也就不会因为一个异常把整页弄崩。
 *
 * 外面还套了一层「会话中毒」自愈（见 `runProblemAnalysis`）：线程上挂着没人答的
 * 中断时平台会先把这次请求拒掉，那种失败可以先清干净再原样重跑一次。
 */
async function attemptAnalysis(
  req: ProblemAnalysisRequest,
  opts: RunAnalysisOptions,
): Promise<AnalysisOutcome> {
  const caseId = req.case_id
  const timeoutMs = opts.timeoutMs ?? 8 * 60 * 1000

  const empty: AnalysisOutcome = {
    status: 'BLOCKED',
    caseId,
    contract: null,
    solutionMap: null,
    result: null,
    report: '',
    replyText: '',
    artifacts: [],
  }

  const ctl = new AbortController()
  const onOuterAbort = (): void => ctl.abort()
  opts.signal?.addEventListener('abort', onOuterAbort, { once: true })
  if (opts.signal?.aborted) ctl.abort()
  let timedOut = false
  let timer = 0
  /* 等用户填闸门的那段时间要把计时器停掉 —— 他多想几分钟不该被判超时 */
  const arm = (): void => {
    window.clearTimeout(timer)
    timer = window.setTimeout(() => {
      timedOut = true
      ctl.abort()
    }, timeoutMs)
  }
  arm()

  let text = ''
  let toolCalls = 0
  let lastTool = ''
  let interrupted = false
  let pending: AguiInterrupt[] = []
  /* 流内自报的错误原因。平台的拒绝（同会话已有活跃 run 等）是以事件形式写在流里的，
     HTTP 依然是 200、流也会正常收尾 —— 不认它，页面就只剩一句「没有返回结果」 */
  let streamError = ''
  let finished = false

  /**
   * 不提交就离开闸门 = 必须把中断消费掉。
   *
   * 平台框架的契约：线程上只要还挂着未决 HITL 中断，之后**任何不带 resume 的 run
   * 都会被拒**（`Thread has unresolved interrupts…`）—— 而中断是进程内存里的登记，
   * 不主动回答就永远挂着、整条会话废掉。取消是合法答案（`status:'cancelled'`）。
   * 详见 `@/api/interruptRelease`。
   */
  const releaseNow = async (): Promise<boolean> => {
    if (!pending.length) return false
    const ids = pending.map((it) => (it.id || it.interruptId || '').trim()).filter(Boolean)
    pending = []
    return releaseInterrupts(AGENT_ID, opts.threadId, opts.runId, ids)
  }

  /* 一轮 run 的事件处理：首轮与每次闸门续跑共用（续跑是同一条 run 的下一段） */
  const handleEvent = (ev: AguiEvent): void => {
    const type = String(ev['type'] ?? '')
    /* 先把原始事件交出去（对话区实时渲染用），再走下面的统计 */
    opts.onEvent?.(ev)
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
    if (type === 'RUN_ERROR' || (type === 'RAW' && isRecord(ev['event']) && typeof ev['event']['error'] === 'string')) {
      const msg = errorTextOf(ev)
      if (msg) streamError = msg
    }
    const forms = extractInterrupts(ev)
    if (forms.length) { pending = forms; interrupted = true }
    streamError ||= runEventError(ev)
    opts.onProgress?.({ textLength: text.length, toolCalls, lastTool })
  }

  try {
    const res = await runAgui(
      AGENT_ID,
      {
        threadId: opts.threadId,
        runId: opts.runId,
        // id 不是可选项：后端 RunAgentInput 反序列化缺 id 直接 400
        messages: [userMessage(`u-${opts.runId}`, buildAnalysisPrompt(req))],
      },
      ctl.signal,
    )

    await parseAguiStream(res, handleEvent, ctl.signal)

    /* ── 闸门：run 被挂起等用户填表 ─────────────────────────────────────────
       平台把闸门做成 external 工具：模型一调，run 就停在 RUN_FINISHED(interrupt)。
       页面画好表单、用户填完，本函数按**同一 (threadId, runId)** 续跑 —— 事件
       继续走 onEvent，所以对话区里还是同一条气泡、同一段实时输出。
       早先这里只把表单登记进沙箱的待确认存储，然后返回一句「请到沙箱对话处理」，
       工作台对话区什么都没有 —— 用户看到的就是「闸门回不到前端」。 */
    let gateCount = 0
    while (interrupted && finished && !streamError && !ctl.signal.aborted) {
      if (gateCount >= MAX_GATES) {
        streamError = `连续 ${MAX_GATES} 次人工确认仍未结束，已停止等待`
        await releaseNow()
        break
      }
      gateCount += 1
      /* 仍然登记一份到沙箱的待确认存储：刷新页面 / 从沙箱页面回来时能查回（沿用既有行为）。
         登记失败不影响本次提交（这一步只是副本） */
      try {
        await registerInterrupts(AGENT_ID, opts.threadId, pending, opts.runId)
      } catch {
        /* 忽略：真正的提交走下面的 resume */
      }
      const gate = gateFromInterrupts(pending)
      if (!gate) {
        /* 认不出来的中断同样要消费掉：留在后端，这条会话之后连普通提问都会被拒 */
        streamError = '智能体要人工确认，但没给出可填写的表单'
        await releaseNow()
        break
      }
      window.clearTimeout(timer)
      const values = opts.onGate ? await opts.onGate(gate) : null
      if (!values) {
        await releaseNow()
        return {
          ...empty,
          replyText: text,
          failureNote: '你取消了这次确认（这条确认已从会话上撤掉，可以直接重发）',
        }
      }
      arm()
      /* 提交前按「线程上的**全量**未决中断」拼 resume：要答的这条 resolved、其余 cancelled。
         框架要求 resume 集合与未决集合精确相等，只答一条必被整体拒掉；查不到全量时
         还会从后端报错里的 `missing=[…]` 补一次再发 —— 详见 submitResumeCoveringAll。 */
      const answerId = (pending[0]?.id || pending[0]?.interruptId || '').trim()
      interrupted = false
      pending = []
      finished = false
      const attempt = await submitResumeCoveringAll({
        agentId: AGENT_ID,
        threadId: opts.threadId,
        runId: opts.runId,
        answerId,
        payload: gatePayload(values),
        signal: ctl.signal,
        onEvent: handleEvent,
      })
      if (attempt.error) {
        streamError = attempt.error
        break
      }
    }
  } catch (e) {
    // 网络 / 401 / 超时 / 流解析异常：按失败回落，不把异常抛给页面 ——
    // 但原因得留着，页面上必须说得出「为什么」
    window.clearTimeout(timer)
    opts.signal?.removeEventListener('abort', onOuterAbort)
    return { ...empty, replyText: text, failureNote: humanizeFailure(streamError || reasonOf(e, timedOut, timeoutMs)) }
  } finally {
    window.clearTimeout(timer)
    opts.signal?.removeEventListener('abort', onOuterAbort)
    /* 兜底：超时 / 异常 / 被暂停打断在半路时，后端登记的中断不能留在那儿 ——
       留着就会让这条会话之后所有 run 被平台拒掉。这里不 await，清理是后台动作。 */
    if (pending.length) void releaseNow()
  }

  if (streamError || interrupted || !finished || ctl.signal.aborted) {
    return { ...empty, replyText: text, failureNote: humanizeFailure(streamError || (interrupted
      ? '智能体需要人工确认，但这次确认没有完成'
      : !finished ? '分析流意外断开，未收到 RUN_FINISHED' : '分析请求已取消')) }
  }

  /* ── 收产物 ───────────────────────────────────────────────── */
  /* 注意：这一收**不代表活干完了**。异步编排型智能体（总控 → 子智能体）在这条流
     关闭时往往才刚把 S1 派出去。上层（flow.start）拿到 BLOCKED 后要继续等，
     见 harvestAnalysis() 的说明。这里只负责「本轮流关掉时顺手看一眼」。 */
  const harvested = await harvestAnalysis(caseId, opts.threadId, opts.since ?? 0)

  const failureNote = streamError ? humanizeFailure(streamError) : undefined
  if (!harvested) {
    /* 流里报过错（例如被并发守卫拒绝）却没拿到东西 —— 把原因带给页面 */
    return { ...empty, replyText: text, failureNote }
  }

  // 中断没被处理掉时不能宣称完成 —— 那是「等用户确认」，不是「分析完了」
  const status: AnalysisOutcome['status'] =
    interrupted && harvested.status === 'COMPLETED' ? 'PARTIAL' : harvested.status

  return { ...harvested, status, replyText: text, failureNote }
}

/**
 * 会话中毒自愈：线程上还挂着没人答的中断时，平台会**先把请求拒掉**
 * （`Thread has unresolved interrupts…`），而中断只活在后端框架的进程内存里 ——
 * 用户不管怎么重发都过不去。这种情况自己把残留清干净，再原样重跑一次。
 *
 * 什么时候会发生：上一次闸门被「取消 / 认不出表单 / 追问打断」后前端没消费掉它
 * （见 `interruptRelease.ts`）。修了那些出口之后新会话不会再中毒，但**已经中毒的
 * 会话**还在后端内存里挂着 —— 这一层就是给它兜底。
 *
 * 两条护栏：
 *   · 只在首轮**一个字都没产出**时重试（被拒发生在 run 起跑之前，真出了正文就说明
 *     这次不是被拒，重跑只会把同一段话在气泡里再念一遍）；
 *   · 清理本身失败就原样返回第一次的结果，不假装成功（`releasePendingInterrupts`
 *     查不到条目会返回 0，这正是「后端登记表也是空的」那种没法从这一侧救的情况）。
 */
export async function runProblemAnalysis(
  req: ProblemAnalysisRequest,
  opts: RunAnalysisOptions,
): Promise<AnalysisOutcome> {
  const first = await attemptAnalysis(req, opts)
  const note = first.failureNote ?? ''
  const poisoned = note === UNRESOLVED_HINT || isUnresolvedInterruptsError(note)
  if (!poisoned || (first.replyText ?? '').trim()) return first
  const cleared = await releasePendingInterrupts(AGENT_ID, opts.threadId, opts.runId)
  if (!cleared) return first
  return attemptAnalysis(req, opts)
}
