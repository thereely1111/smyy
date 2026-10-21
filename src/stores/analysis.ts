﻿/**
 * 「题目分析」通道的状态机
 *
 * 职责边界：
 *   - 决定**哪道题有真实通道**（内置题面 / 用户输入的题目）
 *   - 发起一次分析、缓存结果、给出进度与失败原因
 *   - 把结果交给 render.ts 变成对话能吃的形状
 *   - 其余一概不管：不碰 DOM、不认识路由、不认识原型那套函数
 *
 * 为什么用模块级单例而不是 Pinia store：这条通路的消费者是 core.ts 里的
 * 同步渲染流程，它不参与 Vue 的响应式；用 store 只会多一层 setup 依赖，
 * 换不来任何东西。
 *
 * 生命周期：一次分析 = 一次 AG-UI run。产物落在会话工作区里，所以刷新页面后
 * 可以**先试着把已存在的产物读回来**，读到了就不重跑（见 reuse()）——
 * 否则每次刷新都烧一次模型调用。
 */

import { ApiError, USER_ID_KEY, getAccessToken } from '@/api/http'
import { AGENT_ID } from '@/config'
import { fetchArtifactContent, fetchArtifactTree } from '@/api/artifact'
import { generatePreviewImage } from '@/api/previewImage'
import { deleteAttachment, precheckFiles, uploadAttachment } from '@/api/attachment'
import { fetchSessionProgress, fetchSessionRunStatus, listSessions } from '@/api/session'
import { isSessionBusyError } from '@/api/sessionBusy'
import { extractInterrupts, parseAguiStream, runAgui, userMessage, runEventError } from '@/api/agui'
import type { AguiEvent, AguiInterrupt, AguiMessage } from '@/types/agui'
import { fetchAguiEventWatermark, followAguiEvents } from '@/api/aguiEvents'
import { registerInterrupts } from '@/api/formInterrupt'
import {
  humanizeFailure,
  isUnresolvedInterruptsError,
  pendingInterruptIds,
  releaseInterrupts,
  releasePendingInterrupts,
  submitResumeCoveringAll,
} from '@/api/interruptRelease'
import { subscribeSessionActivity } from '@/stores/notifyStream'
import { rebuildPendingGate } from '@/utils/analysis/gateRebuild'
import {
  flattenArtifacts,
  findArtifactByTail,
  harvestAnalysis,
  parseJsonLoose,
  pickReport,
  runProblemAnalysis,
} from '@/api/analysisRun'
import { normalizeContract, normalizeResult, normalizeSolutionMap } from '@/utils/analysis/normalize'
import {
  followStopReason,
  isSettledAction,
  type FollowStopReason,
} from '@/utils/analysis/followPolicy'
import { forgetThreads, judgeSessionUsable, markFreshThread, settleThread } from '@/utils/analysis/sessionProbe'
import { createUuid } from '@/utils/uuid'
import { builtinProblem, type BuiltinAttachment } from '@/data/problemStatement'
/**
 * 实时对话通道。
 *
 * 过去这条通路**只把产物收回来**、事件流当场丢掉，所以站点对话区里看不到
 * 智能体「思考了多久、跑了哪些工具、逐段说了什么」—— 而这些在平台那边都看得见。
 * 现在每个 AG-UI 事件都喂给 live 模块，由它渲染成对话块。
 */
import { liveBegin, liveBeginResume, liveClose, liveFeed, liveFinishResume, liveNoteResume, liveReset } from '@/utils/analysis/live'
export {
  liveActive,
  liveBlocks,
  liveClosed,
  liveCounters,
  liveMount,
  liveRender,
  liveReset,
} from '@/utils/analysis/live'
import { renderLiveDoc } from '@/utils/analysis/render'
import type {
  AnalysisOutcome,
  AnalysisResult,
  ProblemAnalysisRequest,
  ProblemContract,
  SolutionMap,
} from '@/types/analysis'
import { gateFromInterrupts, gatePayload, type GateSpec, type GateSubmitResult, type GateValues } from '@/utils/analysis/gate'
import type { ArtifactNode } from '@/types/sandbox'

export type AnalysisStatus = 'idle' | 'running' | 'done' | 'failed'

/**
 * 通道开关。
 *
 * 与沙箱的 `VITE_SANDBOX_MODE` 分开：那个开关管的是沙箱模块的假会话，
 * 这里管的是「要不要真的调智能体做分析」。两者混在一个开关上，
 * 会出现「沙箱用 mock 演示、分析也跟着永不生效」这种莫名其妙的结果。
 * 默认 real —— 后端不可用时链路自己会回落，不会把页面卡住。
 */
const rawMode = import.meta.env.VITE_ANALYSIS_MODE as string | undefined
export const ANALYSIS_MODE: 'mock' | 'real' = rawMode === 'mock' ? 'mock' : 'real'

/**
 * 最长等待（毫秒）。超时按失败回落，不无限期挂着。
 *
 */
const MAX_WAIT_MS = 40 * 60 * 1000

/**
 * 单问深挖的最长等待 —— 比全局那次宽得多。
 *
 * 实测（2026-09-29 真机）：全局一次跑四问约 5 分钟就交回；但**只做一问**时
 * 子智能体会把这一问写到 700+ 行，全程 **21 分钟**。这不是慢，是深。
 * 所以这里不按全局的 8 分钟卡，免得把正常在跑的深挖当成失败回落到剧本。
 * 用户随时可以点「跳过等待」——等待气泡里也写明了大概要多久。
 */
const QUESTION_WAIT_MS = 40 * 60 * 1000

/**
 * 流关掉之后，再给产物多少时间才认输（毫秒）。
 *
 * 为什么必须有这一段：smyy 挂的「数学建模解答助手」是**异步编排型**智能体 ——
 * 它把 S1 派给「题目解析智能体」（`agent_spawn` 同步等 120s 超时后转后台），
 * 本轮就先收尾**把 SSE 流关掉**，产物要再等十几到二十几分钟才落盘。
 * 流关掉 ≠ 活干完。旧逻辑把两者当成一回事，于是：
 *   14:47:22 发 run → 14:48:16 流结束（只回了一句「S1 仍在跑」）→ 立刻收产物 →
 *   什么都没有 → 报「智能体没有写出约定的产物文件」并回落内置剧本。
 *   而同一时刻后台的 S1 还在跑（14:56 仍在调工具），约 15 分钟后才写出 truth/*。
 * 也就是说那句「没有写出产物」是**误判**，不是失败。
 *
 * 所以这里补一段「跟随」：流一关就开始每隔 {@link FOLLOW_POLL_MS} 问一次
 * 「后端还在跑吗 / 产物变了吗 / 台账说什么」（见 `followSession`），直到它真的收尾，
 * 或等到这个上限。期间**只读，不发新 run** —— 后端靠 inbox 唤醒自己接着跑，
 * 我们插进去反而会撞上「同会话已有活跃 run」。
 *
 * 上限取 40 分钟（与单问深挖同一量级，比全局那 8 分钟宽得多）：实测这类题的 S1
 * 从派发到写完全部四份产物要 **26 分钟以上** —— 14:11 派发的那次 14:37 才写完
 * `analysis_result.json`；用户当前这次 14:37 派发、15:02 仍在写。按 8 分钟卡必然误判。
 * 用户不想等，等待气泡里有「跳过等待，先用内置演示」；跳过之后后台**不会停**，
 * 回头刷新页面时 `reuse()` 会把已经落盘的产物接回来，不用重跑模型。
 */
const HARVEST_WAIT_MS = 40 * 60 * 1000

/**
 * 流关掉之后的**跟随轮询**间隔（2026-10-14 新增）。
 *
 * 旧的 `harvestUntil` 每 10 秒只看一眼产物，**第一份产物落盘就收工** —— 而那时
 * 后端往往才刚把 S1 派出去（实测 14:39 开题、15:17 仍在逐件填内容）。收工之后
 * 气泡定格、事件不再上屏、进度条不动，用户看到的就是「对话不再同步」。
 * 现在改成持续跟随：每 5 秒并问一次「后端还在跑吗 / 产物变了吗 / 台账说什么」。
 */
const FOLLOW_POLL_MS = 5 * 1000

/**
 * 跟随期间每几轮真正去读一次产物**内容**与台账。
 *
 * 产物树的「有没有」每轮都看（便宜），但把 `analysis_result.json` /
 * `analysis_v*.md` 全文读回来只是为了收尾时能渲染 —— 没必要 5 秒读一次。
 */
const FOLLOW_DEEP_EVERY = 3

/**
 * 「后端没在跑算不算收工」这套判据已经抽到 `utils/analysis/followPolicy.ts`
 * （纯函数 + 回归测试）—— 2026-10-14 的误判就出在这条规则上。
 *
 * 一句话：旧规则「空闲 3 轮（15 秒）即收工」在**异步交接空窗**里会误判 ——
 * 父会话把活派给子智能体后自己收流空闲，子智能体跑在自己的会话里，
 * `run-status.running`（`SessionActivityTracker`，按 sessionId 计）读出来恒 false。
 * 实测空窗 303 秒（16:34:04 收流 → 16:39:07 被 wakeup 唤醒）。
 *
 * 现在喂给判据的是 `running || pendingWork`（P0-2：后端 `run-status` 已补
 * `pendingWork`，含「子任务还在 RUNNING/PENDING」与「结果待投递」两路），
 * 再加两道保险：台账说 CONTINUE 就不据空闲收尾；拿不到台账才退回「空闲满 3 分钟」。
 */

/** 顶部六步进度条的终点（论文） */
const LAST_STEP = 5

/**
 * 发言排队的最长等待（毫秒）。
 *
 * 后端同一会话只允许一个活跃 run，而平台会自己把智能体唤醒着往下做（20–40 分钟）。
 * 这段时间用户发言必然撞 409，所以只能排队 —— 但不能无限排：等满这个上限就如实
 * 告诉用户「没发出去」，并把出口指给他（工作台可以暂停这个任务）。
 */
const ASK_WAIT_MS = 10 * 60 * 1000

/** 排队期间的探针间隔 —— 后端的「空闲窗口」很短（看门狗 force-release 那一刻），探密一点 */
const ASK_POLL_MS = 4 * 1000

/** 撞 409 之后重试几次（探到空闲与真正发出之间有竞态） */
const ASK_RETRY_MAX = 4
const ASK_RETRY_MS = 4 * 1000

/**
 * 「读不到线程全量未决中断」时补在失败文案后面的一句话。
 *
 * 触发场景（2026-10-14 实测）：会话级接口带归属校验，**换账号操作别人的会话**时
 * `/interrupts` 直接 404、`/form-interrupts` 静默返回空数组，前端据此拼出的
 * `resume[]` 凑不齐，被框架整体拒掉。不给这句话，用户只会看到一句英文报错，
 * 无从知道「这个会话根本不属于当前账号」。
 */
const DEGRADED_HINT = '（另外：当前账号读不到这条会话的「未决确认清单」——它可能不属于当前账号）'

/** 台账 nextAction → 人话 */
const NEXT_ACTION_CN: Record<string, string> = {
  CONTINUE: '推进中',
  WAIT_HITL: '在等你确认',
  DONE: '已完成',
  IDLE: '已停住',
  UNKNOWN: '未知',
}

/**
 * 一个附件条目。
 *
 * 两种来源：① 内置题面里点名、但只有名字的（`uploaded` 为空，提示词照旧写「内容未提供」）；
 * ② 用户真上传到本会话工作区的（`uploaded: true`，提示词要如实写「已提供」）。
 * 这个标记决定提示词与页内文案怎么措辞，不能靠猜 —— 见 analysisRun.buildAnalysisPrompt。
 */
export interface CaseAttachment extends BuiltinAttachment {
  uploaded?: boolean
  /**
   * 这份文件在本题里扮什么角色。
   *
   *   'problem' —— 题目文件：题面本身。用户没在输入框写题面时，题目要求全在这里，
   *                提示词要请智能体先读它。
   *   'data'    —— 数据附件：补充材料，题面另有出处。
   * 缺省（内置题面里点名的那几个）按 'data' 对待。
   */
  role?: AttachKind
}

/** 上传槽的两类文件：题目文件与数据附件 */
export type AttachKind = 'problem' | 'data'

/** 一次分析的题面与元信息 */
export interface CaseRef {
  protoId: string
  /**
   * **站点这一侧的会话键**，不是智能体的 case_id。
   *
   * 只有两个用途：分配 threadId、在产物树里优先精确匹配。
   * 智能体那边按自己的口径另起 case_id（`YYYYMMDD_题目标识_短随机码`），
   * 所以产物**不能只靠这个值去找** —— 见 analysisRun.findArtifactByTail 的回退匹配。
   * 形如 `2026-C` / `user-xxxx`，需匹配 ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$。
   */
  caseId: string
  title: string
  text: string
  source: 'builtin' | 'user'
  attachments: CaseAttachment[]
}

/* ── 会话映射（独立于沙箱那条，互不干扰） ─────────────────────────────── */

const THREAD_KEY = 'smyy.analysis_thread_map'

/**
 * 会话映射必须**按登录用户隔离**，否则会拿到别人的会话。
 *
 * 后端把「会话归属」当权限：owner 只在一次 run 被处理时登记，附件 / 产物接口
 * 一律按 owner 校验，不属于你的会话回 404「会话不存在」（SessionAccessGuard）。
 * 而这份映射落在 localStorage 里、跨登录态保留 —— 换账号后如果 caseId 相同
 * （例如两次都传同一组文件名 → 同一个 `user-<hash>`），就会命中**上一个账号**
 * 分配的 threadId。此时后端对这次 run 只告警不报错（SessionOwnerService 的
 * keep-existing），run 照常 200，附件却在下一步必然 404。
 *
 * key 带上 userId 之后，换账号即换映射空间，各账号只看得见自己的会话。
 */
function threadKey(): string {
  try {
    const uid = localStorage.getItem('user_id')
    return uid ? `${THREAD_KEY}.${uid}` : THREAD_KEY
  } catch {
    return THREAD_KEY
  }
}

function readThreadMap(): Record<string, string> {
  try {
    const raw = localStorage.getItem(threadKey())
    if (!raw) return {}
    const parsed = JSON.parse(raw) as unknown
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed as Record<string, string>
  } catch {
    // 坏掉就重建，不影响本次会话
  }
  return {}
}

function writeThreadMap(map: Record<string, string>): void {
  try {
    localStorage.setItem(threadKey(), JSON.stringify(map))
  } catch {
    // 隐私模式：本次会话仍能用，只是刷新后换会话
  }
}

/** 前缀必须是 main-：后端按它识别「前端新建的会话」 */
function newThreadId(): string {
  const id = `main-${createUuid()}`
  /* 记进白名单：这会话后端还没登记（登记发生在第一次 run 被处理时），
     会话探活要跳过列表判定，免得把它误判成「不是我的会话」而白换一条 */
  markFreshThread(id)
  return id
}

function threadOf(caseId: string): string {
  const map = readThreadMap()
  if (map[caseId]) return map[caseId]
  const tid = newThreadId()
  writeThreadMap({ ...map, [caseId]: tid })
  return tid
}

/**
 * 丢掉 caseId 现有的 threadId，换一个全新的（并写回映射）。
 *
 * 用在拿到 404「会话不存在」之后：那说明这个 threadId 已被别的账号登记过
 * （换账号后残留的旧映射），本账号永远写不进去。换新 id 后本账号就是它的
 * 第一个 owner —— 上一轮想上传的附件也才有着落。
 *
 * 代价：旧会话里的产物不再被本账号读到。但那些产物本来就读不到（同样 404），
 * 所以这是纯改善。
 */
export function rotateThread(caseId: string): string {
  const tid = newThreadId()
  writeThreadMap({ ...readThreadMap(), [caseId]: tid })
  return tid
}

/**
 * 会话被硬删（后端 `POST …/clear` 置墓碑）后，清掉本地对它的全部记忆。
 *
 * 两处都得清：① `sessionProbe` 的「刚新建」白名单会永久放行墓碑会话（同页面删完再输同一题
 * 就是这条路）；② `caseId → threadId` 映射留着会让下次输入同一道题又指回同一座墓碑。
 * 清掉映射后，同一题面会由 `threadOf` 派生一条全新会话。
 */
export function forgetSessions(ids: readonly string[]): void {
  const drop = new Set(ids.filter(Boolean))
  if (!drop.size) return
  for (const id of drop) sessionReady.delete(id)
  const map = readThreadMap()
  let dirty = false
  for (const [caseId, tid] of Object.entries(map)) {
    if (drop.has(tid)) {
      delete map[caseId]
      dirty = true
    }
  }
  if (dirty) writeThreadMap(map)
  forgetThreads([...drop])
}

/**
 * 会话键 → threadId 的对外只读口。
 *
 * S2（动画）/ S3（代码）与 S1 跑在**同一个会话**里：智能体只有在本会话空间内
 * 才能按相对路径读到 S1 落盘的分析产物。两个后续通道一律经这里取同一个 thread，
 * 不得自己再开映射（会分叉会话，产物互不可见）。
 */
export function threadIdOf(caseId: string): string {
  return threadOf(caseId)
}

/**
 * 用户自己输入的那道题也要一个合规的 case_id。
 *
 * 这里用的是一个**纯前端、只需稳定**的短哈希 —— 它不参与任何跨语言一致性校验，
 * 只要求「同一段题面得到同一个目录名」。所以可以就地算，不必像 agentId 那样
 * 写成常量 + 校验脚本（那类值的纪律见 config/index.ts 的注释）。
 */
function shortHash(text: string): string {
  let h = 5381
  for (let i = 0; i < text.length; i += 1) h = ((h << 5) + h + text.charCodeAt(i)) | 0
  return (h >>> 0).toString(36)
}

/** 题面短到一定程度就不值得送模型（多半是标题而不是题目） */
const MIN_USER_TEXT = 20

/**
 * 判断当前这道题该用哪份题面。
 *
 * 四种情况：
 *   1. 题库里登记了内置题面的真题 → 用内置题面（真实公开材料）
 *   2. 用户从首页输入框自己写的题 → 用用户那段文本
 *   3. **只传了题目文件**（`paperAsProblem` 为真且首页有待上传/已上传的题目文件）
 *      → 题面正文由文件承担：这里给一个稳定会话键与标题，`text` 留空，
 *        提示词那边会写明「题面在文件里，先读它」
 *   4. 其余（题库真题但没有内置题面 / 输入太短 / 两者都没有）→ 返回 null，走内置剧本
 *
 * 第 4 个参数由桥接层给出（`attachHasProblem('home')`）—— 只有首页入口可能为真；
 * 从详情页进来用的始终是题库题面。之所以不让本函数自己去读暂存区：暂存区是
 * 模块私有状态，摆到参数上，调用点与判定条件在同一处看得见。
 */
export function resolveCase(
  protoId: string | null,
  title: string,
  fromDetail: boolean,
  paperAsProblem = false,
  /** 刷新后的兜底：开题那一刻记下的题目文件名列表（附件暂存区已空时用它） */
  paperNamesFallback: string[] = [],
): CaseRef | null {
  const builtin = builtinProblem(protoId)
  if (builtin) {
    return {
      protoId: builtin.protoId,
      // caseId 原由 problemMap 提供（p3 → 2026-C）；该映射表已随沙箱侧重构删除，
      // 改由题目自带。值不变，已有产物照旧读得回来。
      caseId: builtin.caseId || 'builtin-case',
      title: builtin.title,
      text: builtin.text,
      source: 'builtin',
      attachments: builtin.attachments,
    }
  }

  const typed = (title || '').trim()
  if (fromDetail) return null

  /* 题面来自题目文件：首页暂存区还有就用实时的；刷新后暂存区没了，
     就用开题那一刻持久化下来的文件名列表（同一份文件 ⇒ 同一个 caseId）。 */
  const papers = paperAsProblem ? problemFiles('home') : []
  const names = papers.map((f) => f.name)
  const paperNames = names.length ? names : paperNamesFallback.filter((n) => !!n)
  const hasTyped = typed.length >= MIN_USER_TEXT
  if (!hasTyped && !paperNames.length) return null

  if (hasTyped) {
    return {
      protoId: protoId ?? '',
      caseId: `user-${shortHash(typed)}`,
      title: typed.slice(0, 40),
      text: typed,
      source: 'user',
      attachments: [],
    }
  }

  /* 题面来自题目文件。会话键用文件名列表算 —— 与「同一段题面得到同一个目录名」
     同一个性质：文件不变，会话就稳定，刷新后还能接回产物。 */
  return {
    protoId: protoId ?? '',
    caseId: `user-${shortHash(`paper:${paperNames.join('|')}`)}`,
    title: (problemTitle('home') || paperNames[0] || '').slice(0, 40),
    text: '',
    source: 'user',
    attachments: [],
  }
}

/* ── 待上传附件（用户在首页/工作台真选的文件）───────────────────────────── */

/**
 * 选文件与上传是两件事，中间要有个暂存区。
 *
 * 原因：上传必须落在「本次分析那个会话」里，而会话是由 caseId 决定的、caseId
 * 又是由题面算出来的 —— 用户在首页选文件那一刻，caseId 还不存在。所以：
 *   - home 入口：先留着，点「开始演易」时随 start() 一起传
 *   - work 入口：题目已经定了，当场传到本题会话
 * 两个入口各存一份，不共用一个槽（否则在工作台选的文件会跟着下一道题走）。
 */
export type AttachScope = 'home' | 'work'

export interface HeldFile {
  /** 前端稳定键：待上传用序号+文件名，已上传用后端给的附件 ref */
  key: string
  name: string
  uploaded: boolean
  /** 正在传 —— 首次开题要先登记会话再传，这段时间界面得看得见 */
  uploading?: boolean
  /** 题目文件还是数据附件 —— 决定附件条上打不打「题目」标、提示词怎么写 */
  kind: AttachKind
}

/** 暂存项：文件本身 + 它被选进来的那个入口类型 */
interface HeldEntry {
  file: File
  kind: AttachKind
}

const heldFiles: Record<AttachScope, HeldEntry[]> = { home: [], work: [] }
const heldUploaded: Record<AttachScope, CaseAttachment[]> = { home: [], work: [] }
const uploading: Record<AttachScope, boolean> = { home: false, work: false }

/**
 * 没过校验的文件（白名单 / 体积）。
 *
 * 这一条得留着，不能只靠一句一闪而过的 toast：用户选完看到"没反应"，
 * 会以为文件已经交出去了，转头就来问"智能体怎么读不到"。
 */
export interface RejectedFile {
  key: string
  name: string
  reason: string
}
const heldRejected: Record<AttachScope, RejectedFile[]> = { home: [], work: [] }

/**
 * 已上传附件的原始 `File`（按 attachment_id 存）。
 *
 * 为什么非留不可：`uploadHeld` 成功后文件就被移出待上传区、`File` 随之失去引用；
 * 而**单问深挖跑在独立会话（`-q1`..`-q4`）**，智能体的文件工具锚定在本会话目录 ——
 * 全局那次传上去的文件在那一问的会话里根本不存在，只能重传一份过去。
 * 没有原始 File 就没法重传，于是提示词里只能写「未提供」，用户看到的就是
 * 「我明明传了，它却读不到」。
 */
const uploadedBlobs: Record<AttachScope, Map<string, { file: File; kind: AttachKind }>> = {
  home: new Map(),
  work: new Map(),
}

/** 选文件：先过白名单与体积，通过的留下来（kind 记录它算题目还是附件） */
export function holdFiles(
  scope: AttachScope,
  files: File[],
  kind: AttachKind = 'data',
): { accepted: number; rejected: string[] } {
  const { accepted, rejected } = precheckFiles(files)
  if (accepted.length) heldFiles[scope] = [...heldFiles[scope], ...accepted.map((file) => ({ file, kind }))]
  if (rejected.length) {
    const stamp = Date.now()
    heldRejected[scope] = [
      ...heldRejected[scope],
      ...rejected.map((r, i) => ({ key: `bad-${stamp}-${i}-${r.file.name}`, name: r.file.name, reason: r.message })),
    ]
  }
  return { accepted: accepted.length, rejected: rejected.map((r) => r.message) }
}

/** 没过校验的那几个（供附件条如实标出来） */
export function rejectedList(scope: AttachScope): RejectedFile[] {
  return heldRejected[scope]
}

/** 用户点掉一条没通过的提示 */
export function dropRejected(scope: AttachScope, key: string): void {
  heldRejected[scope] = heldRejected[scope].filter((x) => x.key !== key)
}

/** 当前挂着的附件（待上传在前、已上传在后），给 UI 画附件条用 */
export function heldList(scope: AttachScope): HeldFile[] {
  return [
    ...heldUploaded[scope].map((a) => ({
      key: a.attachment_id,
      name: a.name,
      uploaded: true,
      kind: a.role ?? 'data',
    })),
    ...heldFiles[scope].map((e, i) => ({
      key: `pending-${i}-${e.file.name}`,
      name: e.file.name,
      uploaded: false,
      uploading: uploading[scope],
      kind: e.kind,
    })),
  ]
}

/** 题目文件（含已上传的）—— 只有它们算题面来源 */
export function problemFiles(scope: AttachScope): HeldFile[] {
  return heldList(scope).filter((f) => f.kind === 'problem')
}

/**
 * 这个入口有没有题目文件。
 *
 * 用来回答「只传了题目文件、一个字没写，能不能开始演易」—— 能。
 * 题面的正文由文件承担（见 resolveCase 的 paperAsProblem）。
 */
export function hasProblemFile(scope: AttachScope): boolean {
  return heldList(scope).some((f) => f.kind === 'problem')
}

/** 题目文件的显示名（去扩展名）—— 只传了题目文件时，用它当工作台标题 */
export function problemTitle(scope: AttachScope): string {
  const hit = heldList(scope).find((f) => f.kind === 'problem')
  if (!hit) return ''
  const stem = hit.name.replace(/\.[^.]+$/, '').trim()
  return (stem || hit.name).slice(0, 60)
}

/** 撤掉一个：待上传的直接丢；已上传的连会话里的文件一起删 */
export async function removeHeld(scope: AttachScope, key: string): Promise<void> {
  const idx = heldFiles[scope].findIndex((e, i) => `pending-${i}-${e.file.name}` === key)
  if (idx >= 0) {
    heldFiles[scope].splice(idx, 1)
    return
  }
  const hit = heldUploaded[scope].find((a) => a.attachment_id === key)
  if (!hit) return
  heldUploaded[scope] = heldUploaded[scope].filter((a) => a.attachment_id !== key)
  /* 原始 File 一并丢掉 —— 单问深挖重传时不该再带上已经撤掉的这份 */
  uploadedBlobs[scope].delete(key)
  if (!state.current) return
  try {
    /* key 是附件 ref（`@uploads/<存储名>`），删除端点要的是 uploads 目录下的真实文件名 */
    const stored = key.startsWith('@uploads/') ? key.slice('@uploads/'.length) : key
    await deleteAttachment(AGENT_ID, threadOf(state.current.caseId), stored)
  } catch {
    // 删不掉不影响本次流程：文件留在会话里本来也不会有害
  }
}

/** 已上传的那几个（拼请求时用） */
export function uploadedOf(scope: AttachScope): CaseAttachment[] {
  return heldUploaded[scope]
}

/**
 * 工作台里选完就传：那时题目已经定了，当场送到本题会话。
 *
 * 还没跑过分析（`state.current` 为空）就返回空 —— 待上传的先留着，
 * 等开题那次 start() 一起传。
 */
export async function uploadHeldNow(scope: AttachScope): Promise<string[]> {
  const cur = state.current
  if (!cur || !heldFiles[scope].length) return []
  const before = new Set(heldUploaded[scope].map((a) => a.attachment_id))
  let failed = await uploadHeld(scope, threadOf(cur.caseId))
  if (failed.some(sessionMissing)) {
    /* 会话已被别的账号登记（换账号后残留的旧 threadId）→ 换一个会话再传一次。
       不换的话这份文件永远传不上去，用户只看到一句没头没尾的「会话不存在」。
       换会话同时意味着本任务早先传上去的附件也在旧会话里 —— 一并搬过来。 */
    const fresh = rotateThread(cur.caseId)
    failed = [...(await carryUploaded(fresh)), ...(await uploadHeld(scope, fresh))]
  }
  /* 这一轮 run 已经发过请求了吗？发过了，刚传上来的这份就不在那一轮里 —— 智能体读不到。
     记下来，让界面如实说清楚（否则用户只看到一个绿色的「已上传」，以为智能体已经看到） */
  if (state.sentIds.length || state.status === 'running' || state.status === 'done') {
    for (const a of heldUploaded[scope]) {
      if (!before.has(a.attachment_id) && !state.sentIds.includes(a.attachment_id)) {
        if (!state.lateIds.includes(a.attachment_id)) state.lateIds.push(a.attachment_id)
      }
    }
  }
  return failed
}

/** 传上去了、但这一轮 run 已经发过请求、智能体读不到的附件 */
export function lateAttachments(): CaseAttachment[] {
  const all = [...heldUploaded.home, ...heldUploaded.work]
  return all.filter((a) => state.lateIds.includes(a.attachment_id))
}

/**
 * 「带上刚传的附件重新分析」。
 *
 * 只丢结果，不动附件：它们已经在会话工作区里，重跑时照常随请求带上。
 * 正在跑的那一轮不打断（同会话并发 run 会被平台拒），交给调用方先拦。
 */
export function rerun(): void {
  if (state.status === 'running') throw new Error('这一轮分析还在跑，等它结束再重跑')
  state.status = 'idle'
  state.outcome = null
  state.failure = ''
  state.attachFailure = ''
  state.skipped = false
  state.progress = { textLength: 0, toolCalls: 0, lastTool: '' }
  state.inflight = null
  state.lateIds = []
  /* 旧产物还在会话里，reuse 会把它们当成本轮结果直接接回来 —— 重跑必须绕开 */
  state.reuseBlocked = true
}

/**
 * 换了一道题：把上一题已上传的附件摘下来。
 *
 * 只清「已上传」，**不动待上传的那些** —— 首页是「先选文件、再点开始演易」，
 * 这个动作发生在开题时，那时用户的文件还没上传，清掉就白选了。
 */
export function beginProblem(): void {
  heldUploaded.home = []
  heldUploaded.work = []
  uploadedBlobs.home.clear()
  uploadedBlobs.work.clear()
  state.attachFailure = ''
}

/** 未传上去的附件原因（给等待气泡如实显示） */
export function attachFailureText(): string {
  return state.attachFailure
}

/** 清空。不传 scope 就清两个入口 */
export function resetHeld(scope?: AttachScope): void {
  const targets: AttachScope[] = scope ? [scope] : ['home', 'work']
  for (const s of targets) {
    heldFiles[s] = []
    heldUploaded[s] = []
    heldRejected[s] = []
    uploadedBlobs[s].clear()
  }
}

/**
 * 真上传：把暂存区里那几个文件送进 threadId 对应的会话工作区。
 *
 * 不抛异常 —— 返回没传上去的原因列表，由调用方如实告诉用户。
 * 少一个附件不该把整条分析通道拖垮：传上去几个就用几个。
 */
export async function uploadHeld(scope: AttachScope, threadId: string): Promise<string[]> {
  const jobs = heldFiles[scope]
  if (!jobs.length) return []
  uploading[scope] = true
  const failed: string[] = []
  try {
    for (const job of jobs) {
      const f = job.file
      try {
        const item = await uploadAttachment(AGENT_ID, threadId, f)
        const id = item.ref || item.fileName
        heldUploaded[scope].push({
          /* 后端上传响应只有 fileName / ref（没有 fileId —— 类型里的 fileId 是理想化字段，
             实测恒为 undefined）。ref 形如 `@uploads/<uuid>-<ascii 名>`，后端自己说它就是
             「用户消息里应携带的引用文本」，所以拿它当附件 id：提示词里智能体可以照抄引用。
             删的时候再去掉 `@uploads/` 前缀（删除端点要的是真实文件名）。 */
          attachment_id: id,
          name: item.fileName,
          media_type: f.type || 'application/octet-stream',
          uploaded: true,
          role: job.kind,
        })
        /* 原始 File 留一份 —— 单问深挖要在自己的会话里重传（见 uploadedBlobs 的说明） */
        uploadedBlobs[scope].set(id, { file: f, kind: job.kind })
        /* 传成一个摘一个 —— 而不是开头把待上传区清空：清空的话上传过程中
           附件条会空掉，用户看不见「上传中」，只会看到东西先消失再出现 */
        heldFiles[scope] = heldFiles[scope].filter((x) => x !== job)
      } catch (e) {
        failed.push(`「${f.name}」上传失败：${e instanceof Error ? e.message : '未知原因'}`)
        /* 没传上去的留在待上传区 —— 附件条上继续显示「待上传」，下一次重试还会带上它。
           直接丢掉的话，用户会以为已经传好了 */
      }
    }
  } finally {
    uploading[scope] = false
  }
  return failed
}

/* ── 会话登记（上传附件的前置条件）───────────────────────────────────────── */

/**
 * 会话归属是**一次 run 被处理时才登记**的，附件接口按归属校验。
 *
 * 后端在模型调用**之前**就 ensureSessionDir + record（见
 * PlatformAguiRuntimeContextResolver），没登记的会话一律 404「会话不存在」。
 * 也就是说：第一次开一道新题时，会话还不存在，直接传附件必然失败。
 *
 * 办法：先用一条极短的 run 把会话登记下来，等它跑完再上传。
 * 不能与正式 run 并发 —— 平台对同一会话有 run 互斥守卫（已有 run 在跑时 409）。
 *
 * 只在真的选了附件时才会走到这里：没选附件的老路径一次调用都不多。
 */
const sessionReady = new Set<string>()

/**
 * 后端对「不属于你的会话」统一回 404，文案写死在 SessionAccessGuard
 * （`ApiException.notFound("会话不存在")`）。上传失败原因被 uploadHeld 拍平成
 * 字符串带出来，所以这里按文案判 —— 后端改文案时要同步改这一行。
 */
const sessionMissing = (s: string): boolean => s.includes('会话不存在')

/**
 * 这座会话**现在还能不能接着用**：归属是我的 + 没被删过，两件事一起问。
 *
 * 不能用「run 成功」代替：READY run 落在别人的会话上时后端只告警不报错
 * （SessionOwnerService 保留原 owner），run 会照常 200，附件却必然 404。
 *
 * **为什么不用附件接口探**（2026-10-13 修）：删除会话**不删归属行** ——
 * `AgentRuntimeService.clearSession` 第一步就是 `markDeleted`，只置墓碑
 * `deleted_at`、保留整行；而归属校验 `SessionAccessGuard.requireSessionOwned`
 * 走的是 `findByAgentIdAndSessionId` 派生查询（**不看墓碑**），owner 仍是本人
 * ⇒ 放行、目录按路径算得出来 ⇒ 附件接口对一座已删会话照回 200（列表为空）。
 * 于是探活把墓碑会话读成「可用」，紧接着的 run 被后端终态拒绝：
 * `PlatformAguiRuntimeContextResolver` 第 2.2 步 `isDeletedBySession` → 410
 * 「该会话已被删除，请新建会话」。用户看到的正是「删掉旧任务后，同一道题再输
 * 一遍就报错、旧任务干扰新任务」——因为 caseId 由题面短哈希决定，同一道题的
 * 会话键永远相同，映射表里那条墓碑会话被反复复用。
 *
 * 会话列表才是如实回答这个问题的接口：它按 `deleted_at IS NULL` 过滤
 * （归档的用 includeArchived 一并取回，归档不算删）。列表里没有 = 已删或
 * 从不是我的会话，两种都该换一条新的。
 *
 * 只按「别误换会话」的三种例外放行：本模块刚新建的（`freshThreads`，后端还没登记）、
 * 列表请求本身失败（网络抖动 / 后端没起）。
 */
async function sessionUsable(threadId: string): Promise<boolean> {
  try {
    return judgeSessionUsable(await listSessions(AGENT_ID, true), threadId)
  } catch {
    // 网络抖动 / 后端没起：不算「不能用了」，免得白换一个会话
    return judgeSessionUsable(null, threadId)
  }
}

/** 预建会话的提示词：只让它回两个字，把这次调用压到最小 */
const READY_PROMPT =
  '（站点自动消息）我接下来要给你一道数学建模题和几个附件。请只回复「就绪」两个字，不要做任何分析。'

const READY_TIMEOUT_MS = 60 * 1000

async function ensureSession(threadId: string): Promise<boolean> {
  if (sessionReady.has(threadId)) return true
  const ctl = new AbortController()
  const timer = window.setTimeout(() => ctl.abort(), READY_TIMEOUT_MS)
  try {
    const res = await runAgui(
      AGENT_ID,
      {
        threadId,
        runId: `run-${createUuid()}`,
        // id 不能省：后端 RunAgentInput 反序列化缺 id 直接 400
        messages: [userMessage(`u-ready-${createUuid()}`, READY_PROMPT)],
      },
      ctl.signal,
    )
    let terminal = false
    let failure = ''
    await parseAguiStream(res, (event) => {
      failure ||= runEventError(event)
      if (event.type === 'RUN_FINISHED') {
        terminal = true
        if ((event.outcome as { type?: string } | undefined)?.type === 'interrupt') failure = '会话初始化等待人工确认'
      }
    }, ctl.signal)
    if (ctl.signal.aborted || failure || !terminal) return false
    sessionReady.add(threadId)
    // 跑通过一次 = 后端已登记，从此探活以会话列表为准（见 sessionProbe.settleThread）
    settleThread(threadId)
    return true
  } catch {
    return false
  } finally {
    window.clearTimeout(timer)
  }
}

/**
 * 复用旧会话之前先确认它**还能用**（是我的 + 没被删）；新会话不做额外探活。
 *
 * 为什么复用前要问这一句：会话键由题面短哈希决定，同一道题永远指向同一座会话；
 * 而「删除任务」会把后端那座会话置墓碑。墓碑会话的归属行还在，光问归属问不出来
 * （见 `sessionUsable` 的说明），结果就是删了旧任务再输同一道题时，run 被后端
 * 终态拒绝（410）。这里问一次，判死就换一条全新的，用户无感。
 *
 * 返回值带 `rotated`：换过会话意味着旧会话里的 `@uploads/<存储名>` 引用全部作废
 * （会话是文件可见性的边界），调用方据此把已上传的附件搬进新会话。
 */
async function ownedThreadForRun(
  caseId: string,
): Promise<{ threadId: string; rotated: boolean }> {
  const previous = readThreadMap()[caseId]
  const threadId = threadOf(caseId)
  if (previous && !(await sessionUsable(threadId))) {
    sessionReady.delete(threadId)
    return { threadId: rotateThread(caseId), rotated: true }
  }
  return { threadId, rotated: false }
}

/**
 * 换会话后把已上传的附件搬过去。
 *
 * 会话是文件可见性的边界：旧会话里的 `@uploads/<存储名>` 在新会话里根本不存在，
 * 提示词若照抄旧 ref，智能体会如实回「没有这份文件」。所以一旦换过会话，就用本地
 * 留存的原始 `File` 在新会话里重传一份，并把 `heldUploaded` / `uploadedBlobs` 的键
 * 一起换成新会话的 ref（旧 ref 从这里起作废）。
 *
 * 拿不到原始 `File` 的（刷新或换账号后本地已清空）无从搬运：那种情况下文件本就在
 * 别的账号的会话里，按归属规则本账号也读不到 —— 只能请用户重传。
 */
async function carryUploaded(threadId: string): Promise<string[]> {
  const failed: string[] = []
  for (const scope of ['home', 'work'] as AttachScope[]) {
    const entries = [...uploadedBlobs[scope].entries()]
    if (!entries.length) continue
    const next: CaseAttachment[] = []
    const blobs = new Map<string, { file: File; kind: AttachKind }>()
    for (const [, held] of entries) {
      try {
        const item = await uploadAttachment(AGENT_ID, threadId, held.file)
        const id = item.ref || item.fileName
        next.push({
          attachment_id: id,
          name: item.fileName,
          media_type: held.file.type || 'application/octet-stream',
          uploaded: true,
          role: held.kind,
        })
        blobs.set(id, held)
      } catch (e) {
        failed.push(`「${held.file.name}」没能带进新会话：${e instanceof Error ? e.message : '未知原因'}`)
      }
    }
    heldUploaded[scope] = next
    uploadedBlobs[scope] = blobs
  }
  return failed
}

/**
 * 附件上路：先确认会话能用，再登记，最后上传。
 *
 * 返回真正落地的 threadId 与没能传上去的原因（空数组表示都成了）。
 * 没有待上传附件、也没换过会话时直接返回，一次网络请求都不发 —— 老路径零额外调用。
 *
 * **为什么要换 threadId**：两种情形都只有换新会话这一条出路 ——
 * ① 会话被别的账号登记过（换账号后 localStorage 残留的旧映射），本账号既传不了
 *    附件也读不到产物；② 会话已被删除（墓碑，归属行还在，见 `sessionUsable`）。
 * 换完本轮 run 也跑在新会话里，不会再往死会话里写东西。
 *
 * **换过会话的附件必须搬过来**（`rotated`）：`ownedThreadForRun` 只换了 id，
 * 早先传上去的那几个文件还在旧会话里 —— 不搬的话智能体看到的仍是「文件没有」。
 */
async function prepareAttachments(
  caseId: string,
  threadId: string,
  rotated = false,
): Promise<{ threadId: string; failed: string[] }> {
  const pending = heldFiles.home.length + heldFiles.work.length
  const carryable = uploadedBlobs.home.size + uploadedBlobs.work.size
  /* 没待传的、也没有要搬的：老路径一次网络请求都不发 */
  if (!pending && !(rotated && carryable)) return { threadId, failed: [] }
  /* ① 先确认这座会话还能用（已删 / 别人的 → 换新会话，顺带把附件搬过去）。
        `rotated` 的那条是 `ownedThreadForRun` 刚换出来的，不必重问。 */
  let tid = threadId
  let fresh = rotated
  if (!fresh && !(await sessionUsable(tid))) {
    sessionReady.delete(tid)
    tid = rotateThread(caseId)
    fresh = true
  }
  /* ② 把会话登记出来：登记发生在第一次 run 被处理时，而上传接口按 owner 校验，
        没登记的会话必然 404。登记不了（后端没起 / 超时）就到此为止 ——
        别把「后端没起来」误判成「会话不属于我」而白换一座会话。 */
  if (!(await ensureSession(tid))) {
    return { threadId: tid, failed: ['会话没能在后端建立，附件这一轮没有上传'] }
  }
  /* ③ 换过会话的那批文件先搬进新会话，再把本轮待传的传上去 */
  const moved = fresh ? await carryUploaded(tid) : []
  return {
    threadId: tid,
    failed: [...moved, ...(await uploadHeld('home', tid)), ...(await uploadHeld('work', tid))],
  }
}

/**
 * 单问深挖要用的附件 —— 把本任务**已经传过**的文件再传一份进这一问自己的会话。
 *
 * 为什么必须重传：会话是文件可见性的边界（智能体的文件工具锚定在本会话目录），
 * 全局那轮落在 `uploads/` 里的文件在 `-qN` 会话里根本不存在。早先只把附件清单
 * 写进提示词、文件没过去，智能体只能如实回「附件清单为空」—— 用户看到的就是
 * 「我明明传了，它却读不到」。
 *
 * 会话是新的（第一次进这一问）时先按附件通道的硬规矩 `ensureSession` 把它登记出来，
 * 否则上传必然 404「会话不存在」。
 *
 * 传不上去的照样列进清单，但 `uploaded:false` —— 提示词那边会写「本次未随请求
 * 提供内容」。宁可如实说没给，也不能让智能体以为手上真有这份文件。
 */
async function attachmentsForQuestion(threadId: string): Promise<CaseAttachment[]> {
  const jobs: Array<{ file: File; kind: AttachKind }> = []
  for (const scope of ['home', 'work'] as AttachScope[]) {
    for (const a of heldUploaded[scope]) {
      const hit = uploadedBlobs[scope].get(a.attachment_id)
      if (hit) jobs.push(hit)
    }
  }
  if (!jobs.length) return []
  const missing = (j: { file: File; kind: AttachKind }): CaseAttachment => ({
    attachment_id: j.file.name,
    name: j.file.name,
    media_type: j.file.type || 'application/octet-stream',
    uploaded: false,
    role: j.kind,
  })
  if (!(await ensureSession(threadId))) return jobs.map(missing)

  const out: CaseAttachment[] = []
  for (const j of jobs) {
    try {
      const item = await uploadAttachment(AGENT_ID, threadId, j.file)
      out.push({
        attachment_id: item.ref || item.fileName,
        name: item.fileName,
        media_type: j.file.type || 'application/octet-stream',
        uploaded: true,
        role: j.kind,
      })
    } catch {
      out.push(missing(j))
    }
  }
  return out
}

/* ── 运行态 ─────────────────────────────────────────────────────────────── */

const state = {
  current: null as CaseRef | null,
  status: 'idle' as AnalysisStatus,
  outcome: null as AnalysisOutcome | null,
  failure: '',
  /** 附件没传上去的原因（后端不可用等）；空表示本次没有附件问题 */
  attachFailure: '',
  progress: { textLength: 0, toolCalls: 0, lastTool: '' },
  startedAt: 0,
  skipped: false,
  inflight: null as Promise<void> | null,
  /** 这一轮 run 已经带出去的附件 id —— 用来判断「刚传的这份本轮读不到」 */
  sentIds: [] as string[],
  /** 传上去了、但这一轮 run 已经发过请求、智能体读不到的附件 */
  lateIds: [] as string[],
  /** 用户点了「带上附件重新分析」：这一轮不许拿旧产物直接接回来 */
  reuseBlocked: false,
  /**
   * 流已经关了、正在等产物落盘。
   *
   * 与「正在跑」区分开只为了一件事：等待气泡上那句进度得说实话 ——
   * 「智能体还在写产物」和「模型正在回话」对用户是两种不同的等待。
   */
  harvesting: false,
  /**
   * 顶部六步进度条走到第几步（0=全局分析 … 5=论文）。
   *
   * 由**产物树 + 平台台账**推出来，只前进不后退（见 `syncStep`）。
   * 2026-10-14 之前这个值是 core.ts 扫对话文本猜的 —— 智能体一句进度话术里
   * 写了 `q02-q04`，进度条就跳到「问题四」，而那一问一个字都没写。
   */
  step: 0,
  /**
   * 产物签名变更计数 —— 对话区据此决定要不要重拉产物卡。
   *
   * 以前产物卡只在「开题那次 run 跑完」刷一次，跟随期间新落盘的产物在清单里看不见。
   */
  artifactStamp: 0,
  /** 全局那次 run 挂起的闸门（等用户填表），没有就是 null */
  gate: null as GateSlot | null,
}

/**
 * 排队等待期间的说明（空 = 没在排队）。
 *
 * 后端忙时发言要排队，而队列可能长达十分钟 —— 用户必须看得见「它在等什么」，
 * 否则「输入框吞了消息」和「发不出去」在体感上没区别。core 把它写进气泡头部。
 */
let askWaitNote = ''

/** 最近一次台账快照（阶段 / 下一步 / 那句话）—— 进度条与进度文案共用 */
let ledger: { phase: string; nextAction: string; progressLine: string } = {
  phase: '',
  nextAction: '',
  progressLine: '',
}

/** 产物清单签名（变了才抬 `artifactStamp`，避免每轮都重拉产物卡） */
let lastArtSig = ''

/* ── 闸门（HITL）：把「智能体在等你填表」这件事交到页面上 ────────────────────
   平台侧闸门（ui_form_advanced / ui_gate）是 external 工具：模型一调，run 就挂起，
   等人提交后按同一 (threadId, runId) 续跑。站点这边要做两件事：
     ① 把挂起的表单交给对话区渲染成卡片（`gateOf` → 桥接层画卡）；
     ② 用户填完把值送回 await 中的那条 run（`gateSubmit` → resolve）。
   所以这里是「一个 promise 的两个头」：flow 持有 resolver，页面持有规范化的表单描述。
   一个任务同时最多只有一张挂起的闸门（run 是串行发起的），故全局/单问各存一个槽。
   注意：run 一侧的 `onGate` 触发点目前未接（2026-10-10 重构移除），槽位不会被创建，
   gateOf 恒为 null、闸门按取消收尾 —— 这组 API 仅为桥接层/原型 UI 的既有调用保留。 */

interface GateSlot {
  spec: GateSpec
  resolve: (values: GateValues | null) => void
  /**
   * 转录重建出来的闸门（见 `gateRebuild.ts`）。
   *
   * 与 SSE 路径的根本差别：那条背后有一条本地 run 在 `await onGate`，`resolve`
   * 一下它就顺着跑；**这条背后什么都没有** —— run 是后端自己唤醒的，前端连
   * SSE 都没见过。所以提交时不能只 resolve，必须自己发一次 resume。
   * 为 null / 缺省 = 正常的 SSE 闸门。
   */
  adopt?: AdoptedGate
}

/** 一张「从 transcript 重建来」的闸门：够 resume 一次所需的全部信息 */
interface AdoptedGate {
  threadId: string
  /** 待续跑的那条 run；登记表里没有就是空串（此时没法替用户提交，要如实报错） */
  runId: string
  /** resume 用的中断 id（复合 id 优先，见 gateRebuild） */
  interruptId: string
  /** 收产物用的 caseId */
  caseId: string
  /** 去重键：同一个 toolCallId 只呈现一次 */
  toolCallId: string
}

/**
 * 取当前挂起的闸门（没有返回 null）。
 *
 * 只有**一个槽**：分析只有一条 run（进工作台那次开题），闸门不会同时挂两张。
 */
export function gateOf(): GateSpec | null {
  return state.gate ? state.gate.spec : null
}

/** 用户提交了表单 —— 把值送回那条挂起的 run，它按同一 runId 续跑 */
export function gateSubmit(values: GateValues): Promise<GateSubmitResult> {
  return closeGate(values)
}

/** 用户点了「取消」—— 按取消收尾，不让那条 run 永远挂着 */
export function gateCancel(): Promise<GateSubmitResult> {
  return closeGate(null)
}

/**
 * 给「转录重建」出来的闸门自造一个 runId。
 *
 * 为什么非自造不可（2026-10-16 实测故障的根因）：重建闸门的 runId 只能从平台
 * form-interrupts 登记表里取，而那个 record **根本没有 runId 字段**（后端
 * `PendingFormInterruptStore.PendingFormInterrupt` 的六个字段是 interruptId /
 * replyId / toolCallId / toolName / toolInput / createdAt）⇒ 恒为空串。早先这里
 * 写的是「没 runId 就续不了」直接 bail —— 后果是**一个请求都没发出去**，而卡片
 * 已经被定格成「已提交」，用户看到的就是「确定闸门之后后端接收不到」。
 *
 * 而 runId 在框架侧只作本次 run 的标识：resume 的契约（`AguiResumeCoordinator`）
 * 只比对 **interruptId 集合**与 `pendingInterruptsByThread` 是否精确相等，不校验
 * 这个 runId 是不是那条挂起 run 的名字。取消路径（`releaseInterrupts`）一直用的
 * 就是这个手法（`release-<时间戳>`），后端照常处理。故同款自造。
 */
function resumeRunId(): string {
  return `resume-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

/**
 * 关掉当前闸门：提交（values）或取消（null）；返回回执。
 *
 * 回执存在的唯一理由是**失败必须看得见** —— 早先这三个函数都是 void，core 只能
 * 盲着定格「已提交」，于是「后端没收到」在界面上长得跟「提交成功」一模一样。
 */
function closeGate(values: GateValues | null): Promise<GateSubmitResult> {
  const slot = state.gate
  /* 没有槽位不是「成功」：这张卡背后已经没有可提交的东西了（平台清理过 / 已经提交过
     一次）。早先 `if (!slot) return` 静默什么都不做，卡片照样定格成「已提交」——
     用户等一个永远不会来的答复。如实报错，让卡片自己说清楚。 */
  if (!slot) {
    return Promise.resolve({
      ok: false,
      reason: '这张闸门已经不在挂起状态了（平台侧可能已清理或已被提交过）。刷新页面再看一眼右栏。',
    })
  }
  state.gate = null
  /* 转录重建的闸门：本地没有 promise 在等 —— 提交即由本模块**自己发一次 resume**。
     取消也必须发一次（`status:'cancelled'`）：中断登记在后端框架的进程内存里，
     不消费掉，这条会话之后**每一次** run 都会被平台拒（`Thread has unresolved interrupts…`）。
     早先这里写的是「取消只把卡片撤掉，下一轮信号/补拉再扫回来」—— 那正是 2026-10-13
     用户报的「一追问就报错、右栏却什么都没有」的来源：卡片撤掉了，中断还在。 */
  if (slot.adopt) {
    /* 取消是幂等的（线程上本来就没有未决中断也算把线程理干净了），故一律报 ok */
    if (!values) {
      return releasePendingInterrupts(AGENT_ID, slot.adopt.threadId, slot.adopt.runId).then(() => ({
        ok: true,
        reason: '',
      }))
    }
    return resumeAdopted(slot.adopt, values).then((r) => {
      /* 失败要把槽位还回去：卡片没定格，用户能当场重试 —— 重试时 gateSubmit 得找得到它 */
      if (!r.ok && !state.gate) state.gate = slot
      return r
    })
  }
  /* 本地 promise 那条老路（run 在 `await onGate`）：resolve 一下它就顺着跑，没有失败可言 */
  slot.resolve(values)
  return Promise.resolve({ ok: true, reason: '' })
}

/** 把所有挂起的闸门都放掉（暂停任务 / 重新开题时用），否则 promise 永远挂着 */
function cancelGates(): void {
  const slot = state.gate
  state.gate = null
  if (slot) slot.resolve(null)
}

/* ── 会话守望：把「后端自己唤醒出来的闸门」扫回对话区 ──────────────────────
   移植自 kuaike 的 `notifyStream 订阅 + pollTranscript + detectPendingGate` 三件套
   （见 `@/utils/analysis/gateRebuild` 的说明）。

   为什么这条通道非有不可：闸门是 run 挂起时的瞬时态，而前端只订阅**自己发起**的
   那条 SSE。本站点的分析 run 是站点点名发起的**一次性** run —— 流一收尾，后端后续
   唤醒（子智能体完成 → wakeup 续跑 / 看门狗补投递）产生的闸门就**没有任何路径**
   能到前端：用户看到的就是「对话区一片安静，怎么点都没反应」。
   沙箱通道没这个问题（run 是用户自己发的，闸门走 SSE 就到），所以现状只有分析通道
   （含单问深挖、自由问答）需要这一层。
   ────────────────────────────────────────────────────────────────────────── */

/** 正在盯的会话：threadId → 它对应哪道题的哪座会话 */
interface WatchedSession {
  threadId: string
  caseId: string
}

const watched = new Map<string, WatchedSession>()
/** 通知流订阅句柄（全应用单例连接，引用计数在 notifyStream 里管） */
let notifyUnsub: (() => void) | null = null
let visibilityBound = false
/** 已呈现过的闸门：threadId → toolCallId（避免每轮信号重复弹同一张卡） */
const shownGates = new Map<string, string>()
/**
 * 「死条目」计数：threadId → 连续几轮「登记表上有未决中断，但转录里找不出可呈现的挂起表单」。
 *
 * 这种中断是后端框架内存里的孤魂：这条会话之后**每一次** run 都会被平台拒
 * （`Thread has unresolved interrupts…`），而前端根本拿不出卡片让用户答。
 * 连数两轮才动手，是防「转录刚落盘、登记已写入」的时序抖动误伤真卡片。
 */
const staleRounds = new Map<string, number>()
/** 重建去重：上一次还在飞时跳过本轮，别把 transcript / 登记表打爆 */
let rebuildBusy = false
/**
 * 「提交重建闸门 → 跟随后台」的在飞计数（2026-10-19）。
 *
 * 这段窗口里本地**没有** SSE 会把闸门直接画进槽位（那条 resume 流只管回执），
 * 续跑又挂出的新闸门全靠守望层扫回来。而守望层原先的让路判据是
 * `status === 'running' && !harvesting` —— 而重建闸门提交时这两条正好都成立
 * （`resumeAdopted` 会把 status 置成 running），于是**新闸门永远画不出来**：
 * 用户点完一张、智能体接着问第二件事，右栏还是那张定格的老卡。
 */
let adoptedBusy = 0

function enterAdopted(): void {
  adoptedBusy += 1
}

function leaveAdopted(): void {
  if (adoptedBusy > 0) adoptedBusy -= 1
}

function ensureWatchPlumbing(): void {
  if (!notifyUnsub) {
    notifyUnsub = subscribeSessionActivity((a) => {
      if (!watched.has(a.sessionId)) return
      void rebuildGateFor(a.sessionId)
    })
  }
  if (!visibilityBound && typeof document !== 'undefined') {
    visibilityBound = true
    /* kuaike 的 visibilitychange 补拉：轮询在 `document.hidden` 时一律让路，
       切回可见却不补一次的话，后台唤醒的闸门会一直看不到（用户切回来只看到
       一片「还在等」）。这里对所有在盯的会话各补扫一次。 */
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) return
      for (const threadId of [...watched.keys()]) void rebuildGateFor(threadId)
    })
  }
}

/**
 * 开始盯一个会话。各条发起通路在**拿到真正落地的 threadId 之后**调它。
 *
 * 幂等：同一个 threadId 重复登记只覆盖归属信息。
 */
export function watchSession(threadId: string, caseId: string): void {
  if (!threadId || !caseId) return
  watched.set(threadId, { threadId, caseId })
  ensureWatchPlumbing()
}

/** 停止盯（缺省=全部停止）。任务被清掉时必须调，否则会一直拉着一条 SSE 连接 */
export function unwatchSession(threadId?: string): void {
  if (threadId) watched.delete(threadId)
  else watched.clear()
  if (!watched.size && notifyUnsub) {
    notifyUnsub()
    notifyUnsub = null
  }
}

/**
 * 重扫一个会话，把挂起的闸门呈现到对话区。
 *
 * 三条护栏：
 *   ① 本地正有一条 run 在等闸门（SSE 路径已经画过了）→ 让路，别抢；
 *   ② 同一个 toolCallId 已经呈现过 → 跳过，否则每轮信号都会重弹同一张卡；
 *   ③ 扫不到挂起闸门 → 把去重记录清掉（那张卡已被后端消解，允许下次重新呈现）。
 *
 * 自愈路径失败一律静默：下一轮信号 / 补拉会再来，不该为此打扰用户。
 */
async function rebuildGateFor(threadId: string): Promise<void> {
  const target = watched.get(threadId)
  if (!target || rebuildBusy) return
  /* 本地正开着一条流（还没进入等产物阶段）→ 它自己会把闸门走 SSE 画出来，让路。
     注意「等产物」不算忙：那段可能长达十几分钟，而后端这时是空闲的，闸门正可能在这时冒出来。
     重建闸门的「提交 + 跟随」也不算忙（`adoptedBusy`）：那段窗口里没有本地 waiter 会
     把闸门画出来，让路就等于把新闸门饿死（见 `adoptedBusy` 的说明）。 */
  if (state.status === 'running' && !state.harvesting && !adoptedBusy) return
  /* 槽位里是本地那条 run 在等用户答（没有 adopt）→ 让路。重建出来的槽位（有 adopt）
     可以被新扫到的闸门顶掉：那张卡背后的中断可能早已被取消/超时，霸着槽位会让
     新闸门永远进不来（右栏只剩一张点不动的旧卡）。 */
  if (state.gate && !state.gate.adopt) return
  rebuildBusy = true
  try {
    const pending = await rebuildPendingGate(AGENT_ID, threadId)
    if (!pending) {
      shownGates.delete(threadId)
      /* 转录里没有可呈现的挂起表单，而登记表上还压着未决中断 ⇒ 后端框架内存里的
         **死条目**：前端画不出卡片（用户没得答），它却让这条会话之后每一次 run 都被
         平台拒掉 —— 用户看到的就是「右栏什么都没有，一追问就报 unresolved」。
         连数两轮再动手（见 staleRounds 的说明），确认不是转录时序抖动。 */
      const stale = await pendingInterruptIds(AGENT_ID, threadId)
      if (!stale.length) {
        staleRounds.delete(threadId)
        return
      }
      const rounds = (staleRounds.get(threadId) ?? 0) + 1
      if (rounds < 2) {
        staleRounds.set(threadId, rounds)
        return
      }
      staleRounds.delete(threadId)
      await releaseInterrupts(AGENT_ID, threadId, '', stale)
      return
    }
    staleRounds.delete(threadId)
    if (shownGates.get(threadId) === pending.toolCallId) return
    shownGates.set(threadId, pending.toolCallId)
    const slot: GateSlot = {
      spec: pending.spec,
      /* 本地没有 waiter —— resolve 只是占位，真正的续跑走 adopt → resumeAdopted */
      resolve: () => {},
      adopt: {
        threadId,
        runId: pending.runId,
        interruptId: pending.interruptId,
        caseId: target.caseId,
        toolCallId: pending.toolCallId,
      },
    }
    state.gate = slot
  } catch {
    /* 自愈失败不打扰用户 */
  } finally {
    rebuildBusy = false
  }
}

/** 回执等多久算「后端没收下」：受理后 RUN_STARTED 一般 1 秒内就到，给 20 秒余量 */
const RECEIPT_TIMEOUT_MS = 20_000

/**
 * 提交一张**转录重建**的闸门 —— 自己发一次 resume 把那条 run 接着跑完。
 *
 * 与 SSE 路径的差别：那条有本地 run 在 `await onGate`，resolve 一下就顺着跑；
 * 这条背后什么都没有，必须按 (threadId, interruptId) 重新 POST —— runId 自造，
 * 见 `resumeRunId()`。
 *
 * 回执边界（2026-10-19 修正）：**回执 = run 起来了**，不是流跑完了。早先这里
 * `await submitResumeCoveringAll(...)`，而那个函数会一直读到 SSE 收尾 —— 受理后的
 * run 可能连跑几分钟，卡片就被「提交中…」钉在那儿（实测 11:46:50 已受理、11:49 还在转）。
 * 现在只等第一个非错误事件（RUN_STARTED），其余交给 tail 在后台收尾。
 */
async function resumeAdopted(a: AdoptedGate, values: GateValues): Promise<GateSubmitResult> {
  const target = state
  if (!target) return { ok: false, reason: '分析模块还没准备好，稍后再试' }

  /* 空串 = 后端登记表没记（它压根没这个字段），不是「不能提交」——自造一个 */
  const runId = a.runId || resumeRunId()

  target.status = 'running'
  target.failure = ''
  target.skipped = false
  target.startedAt = Date.now()
  const startedAt = target.startedAt
  /* 2026-10-20：这一轮**新开一条气泡**，不再复用 liveBegin()。
     liveBegin() 会把上一条气泡的实时区清空（beginOn 里 el.innerHTML=''），
     用户看到的就是「点完提交，刚跑完那轮内容被洗掉、只剩我自己说过的上一句话」。 */
  const liveBox = liveBeginResume('闸门已提交 —— 智能体接着往下做，这条气泡会实时显示它的输出。')
  const ctl = new AbortController()
  let pending: AguiInterrupt[] = []

  try {
    let accepted = false
    let tellAccepted: () => void = () => {}
    const acceptedOnce = new Promise<void>((res) => {
      tellAccepted = res
    })
    const markAccepted = (): void => {
      if (accepted) return
      accepted = true
      tellAccepted()
    }

    /* 线程上未必只有这一条未决中断（登记表回灌 / 超时重发 / 后端唤醒时的多次挂起都会
       留下更多条），而框架要求 `resume[]` 与未决集合**精确相等** —— 只答一条会被整体
       拒掉。统一交给提交器：先查回全量（要答的这条 resolved、其余没有卡片可填的一律
       cancelled），查不到就退回只答这一条；仍被拒时再从后端报错里的 missing 补一次。 */
    /* 从这里到流收尾/跟随结束，本地没有 waiter 会画闸门 ⇒ 守望层必须能扫（见 adoptedBusy） */
    enterAdopted()
    const work = submitResumeCoveringAll({
      agentId: AGENT_ID,
      threadId: a.threadId,
      runId,
      answerId: a.interruptId,
      payload: gatePayload(values),
      signal: ctl.signal,
      onAccepted: markAccepted,
      onEvent: (ev) => {
        liveFeed(ev, liveBox)
        const forms = extractInterrupts(ev)
        if (forms.length) pending = forms
      },
    }).catch((e: unknown) => ({
      error: humanizeFailure(e instanceof Error ? e.message : '闸门续跑失败'),
      repaired: false,
      degraded: false,
    }))

    /* 后台收尾：等流真的结束（或被拒 / 崩了）再谈「登记新闸门」「跟着收产物」——
       这些都不该压住回执。平台把「拒绝」写在流里（HTTP 依旧 200），只有走到这里
       才拿得到原文。 */
    void work.then((r) => {
      if (r.error) {
        liveNoteResume(liveBox, '续跑没成功：' + r.error + (r.degraded ? DEGRADED_HINT : ''))
        liveClose(liveBox)
        leaveAdopted()
        target.status = 'failed'
        target.failure = r.error + (r.degraded ? DEGRADED_HINT : '')
        /* 被「未决中断」拒掉 ⇒ 顺手把这条会话上的挂起中断清干净，用户重发一次就能过 */
        if (isUnresolvedInterruptsError(r.error)) {
          void releasePendingInterrupts(AGENT_ID, a.threadId, runId)
        }
        return
      }
      /* 续跑又挂出一张新闸门（智能体常这么连着问）：登记好，交给守望层下一轮扫回来。
         先退出「跟随窗口」再 rebuild —— 顺序反了会被让路判据挡在门外。 */
      if (pending.length) {
        liveFinishResume(liveBox, '智能体又要你拍一件事 —— 到右栏「闸门」里填一下。')
        void registerInterrupts(AGENT_ID, a.threadId, pending, runId)
          .catch(() => {})
          .then(() => {
            leaveAdopted()
            void rebuildGateFor(a.threadId)
          })
        return
      }
      /* 没挂新闸门，线程上就不该还有未决中断，补一次核对（见 dropStaleInterrupt） */
      void dropStaleInterrupt(a.threadId, runId, a.interruptId)
      void followAdopted(a, startedAt, liveBox).finally(leaveAdopted)
    })

    /* 回执：受理信号 / 流整体落定 / 超时，谁先到算谁 */
    const which = await Promise.race([
      acceptedOnce.then(() => 'accepted' as const),
      work.then((r) => (r.error ? ('failed' as const) : ('done' as const))),
      sleep(RECEIPT_TIMEOUT_MS).then(() => 'timeout' as const),
    ])
    if (which === 'failed') {
      const r = await work
      return { ok: false, reason: r.error + (r.degraded ? DEGRADED_HINT : '') }
    }
    if (which === 'timeout') {
      /* 不假装成功：包已经发出去了，但后端连 run 都没起 —— 让用户自己看一眼再决策 */
      return {
        ok: false,
        reason: `提交已经发出，但后端 ${RECEIPT_TIMEOUT_MS / 1000} 秒内没有确认受理。刷新页面看看右栏和对话区是不是已经在跑。`,
      }
    }
    return { ok: true, reason: '' }
  } catch (e) {
    const reason = humanizeFailure(e instanceof Error ? e.message : '闸门续跑失败')
    liveNoteResume(liveBox, '续跑没成功：' + reason)
    liveClose(liveBox)
    leaveAdopted()
    target.status = 'failed'
    target.failure = reason
    return { ok: false, reason }
  }
}

/**
 * 提交受理后核对：我答的那条是否真被后端消费掉了。
 *
 * 平台偶尔会出现「受理了 resume，中断却还留在内存里」，下一句 run 就被整份拒掉。
 * 这里只补作废自己这一条；调用点已确认这轮没有再挂新闸门，不会误杀新卡。
 */
async function dropStaleInterrupt(threadId: string, runId: string, interruptId: string): Promise<void> {
  const id = (interruptId || '').trim()
  if (!id) return
  const live = await pendingInterruptIds(AGENT_ID, threadId).catch(() => [] as string[])
  if (!live.includes(id)) return
  await releaseInterrupts(AGENT_ID, threadId, runId, [id])
}

/**
 * 提交受理之后的「跟着跑」那一段（原 `resumeAdopted` 的后半截）。
 *
 * 单独拆出来的理由只有一个：**回执与跟随的时限差两个数量级** —— 提交几百毫秒就
 * 该有回执，而收产物可以十几分钟。回执交给卡片，跟随留在后台。
 */
async function followAdopted(
  a: AdoptedGate,
  startedAt: number,
  liveBox: ReturnType<typeof liveBegin>,
): Promise<void> {
  const target = state
  if (!target) return
  /* 与 `askAgent` 那条路对齐：流已关、正在跟后台推进 ⇒ harvesting。它同时是
     守望层让路判据里的「这段不算忙」（见 `rebuildGateFor`），也让进度文案说得准。 */
  target.harvesting = true
  try {
    const follow = await followSession({
      caseId: a.caseId,
      threadId: a.threadId,
      since: startedAt - 5000,
      isStopped: () => target.skipped,
      onEvent: (ev) => liveFeed(ev, liveBox),
    })
    if (follow.outcome) {
      target.outcome = follow.outcome
      settle(target, follow.outcome)
      /* 2026-10-20：这一轮在对话里收个尾 —— 早先续跑的结果只落到状态机里，
         页面上那条气泡永远停在「思考中」，用户以为没连上后端（用户原话）。 */
      liveFinishResume(liveBox, '这一轮做完了。接着说一句 —— 比如「开始下一题的分析」—— 我就接着往下走。')
      return
    }
    target.status = 'failed'
    target.failure = followNote(follow)
    liveFinishResume(liveBox, '这一轮没等到结果：' + followNote(follow))
  } catch (e) {
    target.status = 'failed'
    target.failure = humanizeFailure(e instanceof Error ? e.message : '闸门续跑失败')
    liveFinishResume(liveBox, '这一轮没跑完：' + target.failure)
  } finally {
    target.harvesting = false
    /* 兜底：上面任何一条提前 return 的路径都不该把气泡留在「进行中」的样子 */
    liveFinishResume(liveBox)
  }
}
/** 把请求体拼好（拼成给智能体的那条用户消息的入参） */
function buildRequest(ref: CaseRef): ProblemAnalysisRequest {
  const uploaded = ref.attachments.filter((a) => a.uploaded)
  const missing = ref.attachments.filter((a) => !a.uploaded)
  const paper = uploaded.filter((a) => a.role === 'problem')
  const data = uploaded.filter((a) => a.role !== 'problem')
  const constraints: string[] = []
  if (paper.length) {
    constraints.push(
      `${paper.length} 份题目文件已上传到本会话工作区（${paper.map((a) => a.name).join('、')}）：题面以这些文件为准，请先读它们`,
    )
    if (!ref.text) constraints.push('本次没有另附题面文字，题目要求全部在这些题目文件里')
  }
  if (data.length) {
    constraints.push(
      `${data.length} 个数据附件已上传到本会话工作区，可直接读取；里面的数值以文件为准`,
    )
  }
  if (missing.length) {
    constraints.push(
      `${missing.length} 个附件只有文件名、内容未随请求提供，请按未读取处理，不要根据文件名推测其中的数据`,
    )
  }
  constraints.push('只做题意分析与建模路线，不求解、不写代码、不生成动画')
  return {
    schema_version: '1.0',
    case_id: ref.caseId,
    problem: {
      title: ref.title,
      text: ref.text,
      source: ref.source === 'builtin' ? '竞赛题面原文（站点内置）' : '用户在站点输入的题目',
    },
    attachments: ref.attachments.map((a) => ({
      attachment_id: a.attachment_id,
      name: a.name,
      media_type: a.media_type,
      uploaded: !!a.uploaded,
      role: a.role ?? 'data',
    })),
    user_constraints: constraints,
    output_preferences: { language: 'zh-CN' },
  }
}

/** 从结果里判定「拿到了东西没有」—— 全局与单问同一套判据 */
function settle(
  target: { status: AnalysisStatus; failure: string; skipped: boolean },
  outcome: AnalysisOutcome,
): void {
  if (outcome.result || outcome.contract || outcome.solutionMap || outcome.report) {
    target.status = 'done'
    return
  }
  target.status = 'failed'
  target.failure =
    target.failure ||
    outcome.failureNote ||
    (outcome.replyText ? '智能体没有写出约定的产物文件' : '智能体没有返回可用的分析结果')
}

const sleep = (ms: number): Promise<void> => new Promise((r) => window.setTimeout(r, ms))

/* ── 进度条：产物 + 台账（2026-10-14 新增）──────────────────────────────────

   六步进度条（全局分析 / 问题一~四 / 论文）以前由 core.ts 扫**对话文本**推：
   `stepOfText` 认 `questions/qNN` 与正文里的「问题三」—— 于是智能体在进度话术里
   写一句「q02-q04 是否跨日 SOC 链」，进度条就跳到「问题四」，而逐问（S1b）
   一个字都还没写。这不是显示瑕疵：那个数字还被动画栏当「跟随解答步骤」的档位用。

   现在改成两条真证据，取较大者、只前进：
     · **产物**（硬）：`truth/problem_contract.json` + `truth/solution_map.json`
       + 报告或 `analysis_result.json` ⇒ 全局分析走完；`questions/q0N/**` ⇒ 第 N 问；
       论文稿 ⇒ 论文；
     · **台账**（软）：平台从智能体尾文本抽的「阶段=S5」⇒ 论文。
   台账拿不到（老后端 / 会话还没被看护扫过）时只用产物，不编。 */

function normPath(p: string): string {
  return String(p || '').replace(/\\/g, '/')
}

/** 产物硬证据 → 第几步 */
function stepFromArtifacts(files: ArtifactNode[]): number {
  let n = 0
  let contract = false
  let solution = false
  let result = false
  let report = false
  let paper = false
  for (const f of files) {
    const p = normPath(f.path)
    if (/\/truth\/problem_contract\.json$/.test(p)) contract = true
    else if (/\/truth\/solution_map\.json$/.test(p)) solution = true
    else if (/\/analysis_result\.json$/.test(p)) result = true
    else if (/\/analysis\/analysis_v[\d.]+\.md$/i.test(p)) report = true
    if (/\/paper\//i.test(p) || /\/(论文|paper|thesis)/i.test(p)) paper = true
    /* 逐问产物：artifacts/<case_id>/questions/q01/…（早先 -qN 独立会话的形态已下线） */
    const m = p.match(/\/questions\/q0?([1-4])(?:\/|$)/)
    if (m) n = Math.max(n, 1 + parseInt(m[1], 10))
  }
  if (contract && solution && (result || report)) n = Math.max(n, 1)
  if (paper) n = Math.max(n, LAST_STEP)
  return n
}

/** 台账软证据 → 第几步；拿不到返回 null（不编） */
function stepFromLedger(): number | null {
  const phase = String(ledger.phase || '').toUpperCase()
  if (!phase) return null
  if (/S5/.test(phase)) return LAST_STEP
  /* S0 / S1a / S1b / S2 / S3 都不足以单独定档（S1b 的「第几问」要靠产物认），
     所以这里只兜「论文」这一格，其余交给产物。 */
  return null
}

/** 只前进：倒退会让用户以为前面干的活白干了（与设计口径一致） */
function raiseStep(n: number): void {
  const want = Math.max(0, Math.min(LAST_STEP, Math.round(n) || 0))
  if (want > state.step) state.step = want
}

/** 产物清单变了就抬一次签名 —— 对话区据此重拉产物卡 */
function noteArtifacts(files: ArtifactNode[]): void {
  const sig = files.map((f) => normPath(f.path)).sort().join('|')
  if (!sig || sig === lastArtSig) return
  lastArtSig = sig
  state.artifactStamp += 1
}

/** 用产物树对一次账（进度条 + 产物卡签名） */
function syncStep(files: ArtifactNode[] | null): void {
  if (!files || !files.length) return
  noteArtifacts(files)
  raiseStep(stepFromArtifacts(files))
  const fromLedger = stepFromLedger()
  if (fromLedger != null) raiseStep(fromLedger)
}

/** 拉一次台账（阶段 / 下一步 / 那句话）。失败静默：跟随是自愈路径，不因它打断 */
async function refreshLedger(threadId: string): Promise<boolean> {
  try {
    const p = await fetchSessionProgress(AGENT_ID, threadId)
    ledger = {
      phase: String(p?.phase ?? ''),
      nextAction: String(p?.nextAction ?? ''),
      progressLine: String(p?.progressLine ?? ''),
    }
    return true
  } catch {
    /* 老后端没有该端点 / 会话还没被看护扫过 / 跨账号读不到 —— 保持上一次的值 */
    return false
  }
}

/** 台账已经把话说死了：不必再等产物（判据在 followPolicy，与跟随循环同源） */
function ledgerSettled(): boolean {
  return isSettledAction(ledger.nextAction)
}

/* ── 跟随会话 ─────────────────────────────────────────────────────────────── */

export interface FollowReport {
  /** 最后一次收上来的可用产物（null = 全程没读到） */
  outcome: AnalysisOutcome | null
  /** 后端确实收尾了（不是超时、也不是用户停手） */
  idle: boolean
  /** 为什么停 —— 决定失败文案，别把「我们没等到」说成「它空闲了」 */
  reason: FollowStopReason
}

/**
 * 收尾文案：三个停机原因各说各的实话。
 *
 * 刻意**不写「会话已空闲」** —— 2026-10-14 实测这句话会被事实证伪：报错卡出现时
 * 后端子智能体仍在跑（父会话只是暂时收流），用户一看日志就发现前后端对不上。
 */
function followNote(r: FollowReport): string {
  const mins = Math.round(HARVEST_WAIT_MS / 60000)
  if (r.reason === 'lost') {
    return '读不到这条会话的实时状态（网络或权限原因），本次没接到产物 —— 可在产物浏览器里核对'
  }
  if (r.reason === 'timeout') {
    return `跟了 ${mins} 分钟仍未见它收尾（后台可能还在跑，可在产物浏览器里核对）`
  }
  return '智能体这一轮还没有写出约定的产物（可能仍在后台生成，可在产物浏览器里核对）'
}

/**
 * 跟着一条会话跑到它自己收尾 —— 取代旧 `harvestUntil` 的「收到第一份产物就收工」。
 *
 * 为什么必须改：smyy 挂的是**异步编排型**智能体 —— 流一关它才刚把 S1 派给子智能体，
 * 之后靠平台的 wakeup 自驱续跑（实测 20–40 分钟），而旧逻辑一看到
 * `truth/*.json`（「四件骨架落盘」）就返回：气泡定格、事件不再上屏、进度条不动。
 * 用户那边看到的就是「第一次产物出来之后，对话就不动了」。
 *
 * 收尾条件（任一）：
 *   · 闸门挂出来了（`state.gate`）—— 智能体在等人，跟随再久也不会有新动静；
 *   · 台账说 DONE / WAIT_HITL / IDLE —— 智能体自己表了态；
 *   · 探到父会话空闲、**且**空闲满阈值 —— 台账说 CONTINUE 时永不据此收尾
 *     （那正是「派了子智能体、在等它回报」的形态）。判据本体见
 *     {@link followStopReason}，纯函数、有回归测试；
 *   · 用户停手 / 到 {@link HARVEST_WAIT_MS} 上限 / 探针连续失联。
 *
 * 全程**只读**：不发 run、不打断后台那一轮（插进去只会撞并发守卫）。
 *
 * @param since 只认这个时刻之后落盘的产物 —— 别把上一轮留下的同名产物当成本轮成果。
 */
async function followSession(opts: {
  caseId: string
  threadId: string
  since: number
  isStopped: () => boolean
  onEvent: (ev: import('@/types/agui').AguiEvent) => void
}): Promise<FollowReport> {
  const deadline = Date.now() + HARVEST_WAIT_MS
  let outcome: AnalysisOutcome | null = null
  let followerStopped = false
  let eventFollower: Promise<void> | null = null
  try {
    const watermark = await fetchAguiEventWatermark(opts.threadId)
    if (watermark.latest === true) {
      eventFollower = followAguiEvents(
        opts.threadId,
        watermark.nextAfterSequence ?? 0,
        opts.onEvent,
        () => followerStopped || opts.isStopped(),
      )
    }
  } catch { /* 老版本后端或断网时仍走产物轮询 */ }

  const stop = async (reason: FollowStopReason): Promise<FollowReport> => {
    followerStopped = true
    if (eventFollower) await eventFollower.catch(() => {})
    return { outcome, idle: reason === 'settled' || reason === 'idle', reason }
  }

  /* 开工先拉一次台账：后面「CONTINUE 就不收尾」这条判据只认**本会话**刚拉到的值
     （`ledger` 是模块级的，可能是上一轮留下的），拉不到就退回时间阈值 */
  let ledgerOk = await refreshLedger(opts.threadId)

  let idleStreak = 0
  let idleSince = 0
  let probeFails = 0
  let round = 0
  while (Date.now() < deadline) {
    await sleep(FOLLOW_POLL_MS)
    if (opts.isStopped()) return stop('stopped')
    /* 闸门一挂出就收手 —— 人在环优先，而且那条 run 已经挂起，产物不会再动 */
    if (state.gate) return stop('settled')
    round += 1

    /* 每轮只问「变了吗」：产物树 + 后端还有没有在途工作（都便宜）。
       `busy` = `running || pendingWork` —— 只看父会话在不在跑会漏掉「子智能体
       还在跑、父会话在等回报」这段空窗（P0-2 后端补的 `pendingWork` 就是它）。 */
    const [busy, tree] = await Promise.all([
      fetchSessionRunStatus(AGENT_ID, opts.threadId)
        .then((r) => (r?.running || r?.pendingWork ? true : false))
        .catch(() => null),
      fetchArtifactTree(AGENT_ID, opts.threadId).then(flattenArtifacts).catch(() => null),
    ])
    syncStep(tree)

    /* 每隔几轮才做「贵」的两件事：读产物内容（收尾要渲染）、拉台账（定档） */
    if (round % FOLLOW_DEEP_EVERY === 0) {
      if (await refreshLedger(opts.threadId)) ledgerOk = true
      try {
        const got = await harvestAnalysis(opts.caseId, opts.threadId, opts.since)
        if (got) outcome = got
      } catch { /* 单轮读不到不算失败 */ }
    }

    if (busy === true) {
      idleStreak = 0
      idleSince = 0
      probeFails = 0
    } else if (busy === false) {
      probeFails = 0
      idleStreak += 1
      if (!idleSince) idleSince = Date.now()
    } else {
      probeFails += 1
    }

    const why = followStopReason({
      ledgerOk,
      nextAction: ledger.nextAction,
      busy,
      idleStreak,
      idleForMs: idleSince ? Date.now() - idleSince : 0,
      probeFails,
    })
    if (why) return stop(why)
  }
  return stop('timeout')
}

/** 「已用时 X 分 Y 秒」—— 等久了要说得出等了多久，光写秒数看不出在不在动 */
function elapsedLine(startedAt: number): string {
  const secs = startedAt ? Math.round((Date.now() - startedAt) / 1000) : 0
  if (secs < 60) return `已用时 ${secs} 秒`
  return `已用时 ${Math.floor(secs / 60)} 分 ${secs % 60} 秒`
}

/* ==================================================================
   对话区（2026-10-09 新增，2026-10-12 改为智能体全权驱动）

   用户在对话区说一句，就是**发给智能体的这一轮指令**。方向盘在智能体手里：
   它可以只是回答，可以接着往下做（派子智能体、写产物、出论文），
   也可以反过来发一张闸门（ui_form_advanced / ui_gate）问用户要一个决定。

   所以这条通路的边界只剩两条：
     · 不碰 state.outcome —— 产物由智能体自己写进工作区，
       站点不再替它规划「第几步该干什么」；
     · 闸门走与读题同一个槽位（state.gate，桥接层按 gateOf() 渲染成卡片），
       用户填完由 gateSubmit 把值送回这里 await 中的那条 run，按同一 (threadId, runId) 续跑。

   与 start 的区别：那一条是「站点点名要一次分析」，
   这一条只是「用户说了一句话」。

   分工：前端不再做意图粗筛（判「是不是跑题」的那套同写死对话一起删掉了），
   跑题与否由智能体自己按它的 AGENTS.md 意图路由处理。
   ================================================================== */

export interface AskResult {
  /** 这一问有没有成功走完（流正常收尾） */
  ok: boolean
  /** 智能体写给用户的正文（失败时可能只有半段，如实带回） */
  text: string
  /** 没成功的原因（ok=true 时为空串） */
  note: string
}

/**
 * 对话区一句话的提示词（2026-10-12 重写）。
 *
 * 旧版把这一句钉死成「随手一问、不许推进流程、不许发闸门」—— 那是「前端脚本掌方向盘」
 * 时代的口径。现在方向盘交给智能体，用户说的每一句都是**这一轮的真实指令**：
 * 它可以只是回答，也可以是「继续」，还可以反过来发一张闸门问用户。
 *
 * 仍然写死的只有两条，因为它们是站点这一侧的事实，不是流程约束：
 *   · 本题的 case_id 与产物目录（它得知道在哪落文件）；
 *   · 闸门是允许的 —— 前端会把表单画在右栏并等他填，填完他自己续跑。
 */
function buildAskPrompt(ref: CaseRef, question: string): string {
  return [
    '（站点对话区 · 用户在对话里说的一句）',
    '',
    '## 用户的话',
    question,
    '',
    '## 这一轮的边界',
    '- 按你自己的判断处理这一句：他在问就回答；他让你继续就继续做（派子智能体、写产物、出论文都行）；',
    '  需要他拍板的事，就按平台规范发闸门（`ui_form_advanced` / `ui_gate`），站点会把表单画在右栏并等他提交。',
    '  ⚠ **闸门按需发**：能用一句话问清的就别发表单；单次 run 累计别超过 6 张（站点硬限），建议 ≤ 5。',
    '- 他的话**可能就是一条流程指令**（继续 / 重做问题二 / 换成 XX 模型 / 跳过这一问 / 出代码），',
    '  也**可能是想改主意**（不满意、这个不行、再来一遍）—— 这两类都按你 `AGENTS.md` 的 §五 状态机与 §六「重做通道」走一步，',
    '  **不要**当成普通问答答一句就过去；站点侧的前端意图路由已下线，**它不会替你判意图，原话什么样就什么样**。',
    '- 只谈与本题有关的内容。与本题无关的话题一句话带过，把话题拉回本题，**不要展开**。',
    '- 回答简洁：能用短列表就别写长段落。',
    `- 本题 case_id：\`${ref.caseId}\`；产物落在 \`artifacts/${ref.caseId}/\`，需要看文件时按你的规范去读。`,
  ].join('\n')
}

/**
 * 一次对话最多允许几张闸门 —— 与读题那条链路同口径，防止模型来回问个不停。
 * 超了不静默放弃，而是把原因写在 note 里如实告诉用户。
 */
const ASK_MAX_GATES = 6

/**
 * 等这条会话的后端 run 收尾 —— 忙就**排队**，别硬发。
 *
 * 2026-10-14 之前这里只拦「本地正开着流」，于是「流关了但后端还在跑」时照发，
 * 撞上并发守卫 409，用户看到的是「这句话没能发出去 / 该会话有正在执行的任务，
 * 请等待其完成后再试」，而且完全不知道要等多久。真实原因只有一个：**没排队**。
 *
 * 判据用后端的 run-status（`SessionActivityTracker` 权威）。它有已知的滞后
 * （计数泄漏时看门狗 300s 才放），所以：
 *   · 探针**问不出来**（老后端 404 / 网络抖动）→ 退回旧的本地判据，不把用户卡死；
 *   · 等待有上限（{@link ASK_WAIT_MS}），到点如实回报「没发出去」并把出口指给用户。
 *
 * ⚠ 这里**刻意只认 `running`、不看 `pendingWork`**（2026-10-14 P0-2）。两个字段答的是
 * 两个问题：`running`＝「这条会话能不能再发一条」（并发守卫会不会 409）；`pendingWork`＝
 * 「这单活干完没有」。子智能体在后台跑时未必会拒新 run —— 把它并进来会让用户
 * 想追问的一句被排队卡到子任务结束（最长 10 分钟）。判「该不该收工」的地方
 * （`followSession`）才取两者的并集。
 *
 * @returns true = 可以发了；false = 等到上限还没空闲
 */
async function waitForIdle(threadId: string): Promise<boolean> {
  const t0 = Date.now()
  const deadline = t0 + ASK_WAIT_MS
  for (;;) {
    const running = await fetchSessionRunStatus(AGENT_ID, threadId)
      .then((r) => !!r?.running)
      .catch(() => null)
    if (running === false) {
      askWaitNote = ''
      return true
    }
    if (running === null) {
      /* 探针不可用：退回旧的本地判据。本地流开着（且不是在跟随）＝后端正为我们这条
         run 忙着；其余情况按「可以发」处理，别因为探针不可达就把用户卡死在这。 */
      if (!(state.status === 'running' && !state.harvesting)) {
        askWaitNote = ''
        return true
      }
    }
    if (Date.now() >= deadline) {
      askWaitNote = ''
      return false
    }
    const secs = Math.round((Date.now() - t0) / 1000)
    askWaitNote = `上一步还在后台跑（已等 ${secs} 秒），这条已排队，等它收尾后会自动发出；等不及可以在上方「暂停任务」`
    await sleep(ASK_POLL_MS)
  }
}

/**
 * 把用户在对话区说的一句交给智能体，它说的话、思考、工具调用实时画进这条气泡。
 *
 * 闸门也在这里收：智能体全权驱动之后，「问你一件事」就是它发闸门的方式。
 * 表单交给桥接层画（同一个 state.gate 槽位），用户在右栏填完 → gateSubmit
 * → 这里的 promise resolve → 按同一 (threadId, runId) 续跑，事件继续喂给
 * liveFeed，所以对话区里始终是同一条气泡、同一段实时输出。
 *
 * 后端忙时**先排队**（见 `waitForIdle`）：等它收尾再发，而不是把 409 甩给用户。
 *
 * 返回而不是抛错 —— 调用方（桥接层的 intentAsk）拿 `ok/note` 如实显示，
 * 不让一次网络抖动把整页弄崩。
 */
export async function askAgent(ref: CaseRef, text: string): Promise<AskResult> {
  const question = String(text ?? '').trim()
  if (!question) return { ok: false, text: '', note: '没有输入内容' }

  const threadId = threadOf(ref.caseId)
  /* 换中断残留后要重发，runId 跟着换（同一 id 重发会被平台判重） */
  let runId = `ask-${createUuid()}`
  /* 自由问答落在全局那一摊上，守望按同一摊登记 ——
     它扫回来的闸门才会画到对应的卡片槽里。 */
  watchSession(threadId, ref.caseId)
  /* 这一轮的宿主容器：答案就画在这条气泡里（与读题气泡同一套实时渲染） */
  const liveBox = liveBegin()

  /* ── 排队：同一会话只允许一个活跃 run，忙就等（不是失败）─────────────── */
  const ready = await waitForIdle(threadId)
  if (!ready) {
    return { ok: false, text: '', note: `上一步还在后台跑，等了 ${Math.round(ASK_WAIT_MS / 60000)} 分钟仍没收尾，这条没有发出去（可以等它写完再发，或先暂停这个任务）` }
  }

  let out = ''
  let streamError = ''
  let interrupted = false
  let pending: AguiInterrupt[] = []
  let finished = false
  /** 提交 resume 前读不到线程全量未决中断（跨账号 / 会话级接口被归属校验挡掉） */
  let degradedProbe = false
  /* 本函数自己挂上的那个闸门槽（收尾时要放掉，否则 promise 永远挂着）。
     用盒子装一层：TS 的控制流分析看不见闭包里的赋值，直接用变量会让收尾那段被窄化成 never */
  const slotRef: { current: GateSlot | null } = { current: null }
  const ctl = new AbortController()

  /* 一轮 run 的事件处理：首轮与每次闸门续跑共用（续跑是同一条 run 的下一段） */
  const handle = (ev: AguiEvent): void => {
    liveFeed(ev, liveBox)
    const type = String(ev['type'] ?? '')
    if (/TEXT_MESSAGE_CONTENT/i.test(type)) {
      const delta = typeof ev['delta'] === 'string' ? ev['delta'] : ''
      if (delta) out += delta
    }
    if (type === 'RUN_FINISHED') {
      finished = true
      const outcome = ev['outcome']
      if (outcome && typeof outcome === 'object' && (outcome as Record<string, unknown>)['type'] === 'interrupt') {
        interrupted = true
      }
    }
    /* 闸门也可能不靠 RUN_FINISHED 带出来（老版本事件形状不同），单独认一次 */
    const forms = extractInterrupts(ev)
    if (forms.length) {
      pending = forms
      interrupted = true
    }
    streamError ||= runEventError(ev)
  }

  try {
    /* 发一条 run；抽出来是为了自愈时能原样重发。 */
    const sendOnce = async (): Promise<void> => {
      const body = {
        threadId,
        runId,
        messages: [userMessage(`u-${runId}`, buildAskPrompt(ref, question))],
      }
      /* 竞态兜底：探到「空闲」与真正发出之间，后台可能又起了一轮（wakeup 的空闲窗口
         很短）。撞 409 不算失败 —— 退避重试，别让它变成一句「发不出去」。 */
      let res: Response | null = null
      for (let attempt = 1; ; attempt += 1) {
        try {
          res = await runAgui(AGENT_ID, body, ctl.signal)
          break
        } catch (e) {
          if (!isSessionBusyError(e) || attempt >= ASK_RETRY_MAX) throw e
          askWaitNote = `上一步刚好又起了一轮，这条正在重试（第 ${attempt} 次）`
          await sleep(ASK_RETRY_MS)
        }
      }
      askWaitNote = ''
      await parseAguiStream(res, handle, ctl.signal)
    }

    await sendOnce()

    /* 线程上还挂着没答的中断时，平台会把这次 run 整份拒掉。中断存在后端进程内存里，
       刷新清不掉，所以这里作废掉再重发一次。只在没产出正文时重发（被拒发生在起跑前），
       右栏有闸门在填就不动手，作废失败就原样报错。 */
    let healed = false
    if (streamError && !out.trim() && isUnresolvedInterruptsError(streamError) && !state.gate) {
      const cleared = await pendingInterruptIds(AGENT_ID, threadId)
      if (cleared.length) {
        healed = true
        await releaseInterrupts(AGENT_ID, threadId, runId, cleared)
        out = ''
        streamError = ''
        interrupted = false
        finished = false
        pending = []
        runId = `ask-${createUuid()}`
        liveReset(liveBox)
        await sendOnce()
      }
    }

    /* ── 闸门：这一轮里智能体反过来问用户一件事 ────────────────────────────
       平台把表单做成 external 工具：模型一调，run 就停在 RUN_FINISHED(interrupt)。
       与读题那条链路共用 state.gate 槽位（桥接层按 gateOf() 画卡），
       用户填完 → gateSubmit → resolve → 本函数按同一 (threadId, runId) 续跑。 */
    let gates = 0
    /* 不提交就离开闸门时，必须把中断消费掉 —— 详见 @/api/interruptRelease 的说明：
       留在后端框架的内存里，这条会话之后**每一次** run 都会被平台拒掉。 */
    const releasePending = async (): Promise<void> => {
      if (!pending.length) return
      const ids = pending.map((it) => (it.id || it.interruptId || '').trim()).filter(Boolean)
      pending = []
      await releaseInterrupts(AGENT_ID, threadId, runId, ids)
    }
    while (interrupted && finished && !streamError && !ctl.signal.aborted) {
      if (gates >= ASK_MAX_GATES) {
        streamError = `连续 ${ASK_MAX_GATES} 次人工确认仍未结束，已停止等待`
        await releasePending()
        break
      }
      gates += 1
      const spec = gateFromInterrupts(pending)
      if (!spec) {
        /* 认不出来的闸门不能假装已处理 —— 否则这条 run 会永远挂着、气泡一直转圈 */
        streamError = '智能体要人工确认，但没给出可填写的表单'
        await releasePending()
        break
      }
      const values = await new Promise<GateValues | null>((resolve) => {
        slotRef.current = { spec, resolve }
        state.gate = slotRef.current
      })
      if (!values) {
        streamError = '你取消了这次确认'
        await releasePending()
        break
      }
      /* 提交前按「线程上的**全量**未决中断」拼 resume：要答的这条 resolved、其余 cancelled。
         框架要求集合精确相等，只答一条必被整体拒；查不到全量时还会从后端报错里的
         `missing=[…]` 补一次再发 —— 详见 submitResumeCoveringAll。 */
      const answerId = (pending[0]?.id || pending[0]?.interruptId || '').trim()
      interrupted = false
      pending = []
      finished = false
      const attempt = await submitResumeCoveringAll({
        agentId: AGENT_ID,
        threadId,
        runId,
        answerId,
        payload: gatePayload(values),
        signal: ctl.signal,
        onEvent: handle,
      })
      degradedProbe = attempt.degraded
      if (attempt.error) {
        streamError = attempt.error
        break
      }
    }

    liveClose(liveBox)
    if (streamError) {
      const poisoned = isUnresolvedInterruptsError(streamError)
      /* 自愈没覆盖到的两种情况：右栏有闸门在填、或探测不到残留 id。这里再补一次作废 */
      if (poisoned) await releasePendingInterrupts(AGENT_ID, threadId, runId).catch(() => 0)
      const tail = state.gate
        ? '右栏「闸门」里那张卡还在，先把它提交或取消，再发这一句。'
        : healed
          ? '刚刚已经把它作废并重发过一次，仍被挡 —— 这条会话可能卡在别的地方了。'
          : ''
      return {
        ok: false,
        text: out,
        note: humanizeFailure(streamError) + (tail ? '（' + tail + '）' : '') + (degradedProbe ? DEGRADED_HINT : ''),
      }
    }
    if (interrupted) return { ok: false, text: out, note: '智能体需要人工确认，但这次确认没有完成' }
    if (!out.trim()) return { ok: false, text: '', note: '智能体没有回话，稍后再发一次' }

    /* 流关了 ≠ 活干完：它这一轮里可能刚派了子智能体（「继续」「重做问题二」都会），
       后台还要跑一阵。跟着它，直到收尾或挂出闸门 —— 事件照旧画进这条气泡。 */
    state.harvesting = true
    try {
      await followSession({
        caseId: ref.caseId,
        threadId,
        since: Date.now() - 5000,
        isStopped: () => state.skipped,
        onEvent: (ev) => handle(ev),
      })
    } finally {
      state.harvesting = false
    }
    return { ok: true, text: out, note: '' }
  } catch (e) {
    liveClose(liveBox)
    return { ok: false, text: out, note: humanizeFailure(e instanceof Error ? e.message : '调用失败') }
  } finally {
    /* 半路抛错 / 被取消时槽位可能还挂着 —— 放掉它，别让页面停留在「等你填表」 */
    const held = slotRef.current
    if (held && state.gate === held) {
      state.gate = null
      held.resolve(null)
    }
  }
}

/** 这道题现在能不能走真实通道 */
export function available(protoId: string | null, title: string, fromDetail: boolean): boolean {
  if (ANALYSIS_MODE !== 'real') return false
  if (!AGENT_ID) return false
  if (!getAccessToken()) return false
  return resolveCase(protoId, title, fromDetail) !== null
}

export function status(): AnalysisStatus {
  return state.status
}

export function currentCase(): CaseRef | null {
  return state.current
}

export function failureText(): string {
  return state.failure
}

export function progressText(): string {
  const bits: string[] = [elapsedLine(state.startedAt)]
  if (state.harvesting) {
    bits.push('模型已交回，正在跟随后台推进（实测这类题要 20–40 分钟；可先「跳过等待」，稍后刷新会自动接回已落盘的产物）')
  }
  /* 平台台账是智能体自己那句「阶段=… 状态=…」的镜像 —— 比站点猜的要准 */
  if (ledger.phase) bits.push(`阶段 ${ledger.phase}${ledger.nextAction ? `（${NEXT_ACTION_CN[ledger.nextAction] ?? ledger.nextAction}）` : ''}`)
  if (state.progress.toolCalls) bits.push(`工具调用 ${state.progress.toolCalls} 次`)
  if (state.progress.lastTool) bits.push(`最近：${state.progress.lastTool}`)
  if (state.progress.textLength) bits.push(`输出 ${state.progress.textLength} 字`)
  return bits.join(' · ')
}

/* ── 交给对话区的三个数（core.ts 只取数，不猜）───────────────────────────── */

/** 六步进度条走到第几步（0=全局分析 … 5=论文） */
export function step(): number {
  return state.step
}

/** 产物签名变更计数 —— 变了说明有新东西落盘，对话区该重拉产物卡 */
export function artifactStamp(): number {
  return state.artifactStamp
}

/** 排队等待的说明（空 = 没在排队）—— 后端忙时发言要排队，用户得看得见它在等什么 */
export function askWaitNoteText(): string {
  return askWaitNote
}

/** 用户点「跳过等待」——立刻回落内置剧本 */
export function skip(): void {
  if (state.status !== 'running') return
  state.skipped = true
  state.failure = '你选择了跳过等待'
  state.status = 'failed'
  /* 正卡在闸门上等填表时也要放掉，否则这条 run 挂着不走 */
  if (state.gate) closeGate(null)
}

/**
 * 用户在工作台点了「暂停任务」：停止本地这一轮的等待。
 *
 * 与 {@link skip} 的区别只在**话术**与**谁去停后端**：
 *   - skip 是「不等了，先跳过这一步」（后端 run 仍在跑）；
 *   - suspend 是「这个任务先停住」—— 调用方**已经**先调了后端的 interrupt 端点，
 *     那边会把在跑的 run 按中断路径收尾（状态照落，产物目录保留）。
 *
 * 本地这一侧必须跟着收手：否则流关掉之后 harvestUntil 会照旧空转最多 40 分钟
 * （它靠 state.skipped 这个出口退出）。会话与产物都不动 —— 用户「继续」时
 * 在原会话上再发一条消息就是新一轮 run，上一轮已落盘的产物照旧读得到。
 */
export function suspend(): void {
  state.skipped = true
  if (state.status === 'running') {
    state.failure = '你暂停了这个任务'
    state.status = 'failed'
  }
  /* 卡在闸门上等填表的 run 也要放掉，否则那条 promise 一直挂着、对话区永远在转圈 */
  cancelGates()
}

/**
 * 工作台的一条任务 → 它在平台后端那边的会话 id。
 *
 * 为什么让页面外壳来问、而不是把 threadId 存进任务仓：
 *   会话映射（`caseId → threadId`）的 owner 是本模块（见 threadOf / rotateThread），
 *   两边各存一份必然分叉（比如换账号触发的 rotateThread 只会更新这里那份）。
 *   本函数是**纯函数式**的：同一条任务问几次都是同一个 id（首次调用会把它写进映射，
 *   与随后 start() 用的正是同一条 —— 因为 resolveCase 对同一入参是确定性的）。
 *
 * 返回空串 = 这条任务当前不该有会话（题面太短、或只有题目文件时题面在文件里，
 * 这种任务的「暂停/删除」就只作用于本地）。
 */
export function threadIdForTask(protoId: string, title: string, fromDetail: boolean): string {
  const caseId = resolveCaseIdFor(protoId, title, fromDetail)
  if (!caseId) return ''
  /* ⚠ 只读，**不建会话**（2026-10-09 修）。
     原先是 `threadOf(caseId)`：它顺手把 caseId 写进映射表并生成一条新 threadId。于是
     `start()` 里 `ownedThreadForRun` 读到的 previous 恒为非空，对这条**刚建、后端还不认识**
     的会话探活必然 404，判成「别人的会话」而 `rotateThread` —— 每开一道新题都白建一条死会话，
     而任务仓里记的正是它（暂停/删除打上去必然 404，实测见 probe-pause）。 */
  return readThreadMap()[caseId] || ''
}

/**
 * 这条任务该用哪个 caseId —— **纯计算，不碰会话**。
 *
 * 给页面外壳在「开题那一刻」算一次、存进任务仓用：那一刻附件暂存区一定是对的，
 * 之后无论刷新还是切视图都还能按它查出会话（2026-10-09 修）。
 * 算不出（题面太短、或只有题目文件但暂存区已空）返回空串。
 */
export function resolveCaseIdFor(
  protoId: string,
  title: string,
  fromDetail: boolean,
  paperNamesFallback: string[] = [],
): string {
  const c = resolveCase(
    protoId || null,
    title || '',
    !!fromDetail,
    !fromDetail && hasProblemFile('home'),
    paperNamesFallback,
  )
  return c ? c.caseId : ''
}

/**
 * 某个 caseId 在后端**已经存在**的会话 id（0 或 1 条）。
 *
 * 一道题只有一条分析会话；这里只查映射表里已有的，绝不 `threadOf()` 新建 ——
 * 那会凭空造出一条没人认领的会话，中断接口只能拿到 404，反倒把真失败掩盖成
 * 含糊提示。入口是 **caseId 本身**（而不是题面）：不重算、不看内存暂存区，
 * 因此刷新之后暂停/删除照样查得动。
 */
export function threadIdsForCase(caseId: string): string[] {
  if (!caseId) return []
  const tid = readThreadMap()[caseId]
  return tid ? [tid] : []
}

export function threadIdsForTask(protoId: string, title: string, fromDetail: boolean): string[] {
  return threadIdsForCase(resolveCaseIdFor(protoId, title, fromDetail))
}

/**
 * 「这一轮真正落地用了哪座会话」的回写口。
 *
 * 为什么需要：会话 id 的最终决定权在 `start()` 里（`ownedThreadForRun` 可能换过），
 * 而任务仓是**页面外壳**的地盘。两边各存一份必然分叉 —— 实测任务仓那份是一条
 * 后端从未登记过的死会话，暂停/删除打上去 404（见 probe-pause）。
 * 由本模块在拿到落地会话后回调一次，外壳据此把任务仓纠正过来。
 */
let onThreadLanded: ((caseId: string, threadId: string) => void) | null = null

export function setThreadLandedSink(fn: ((caseId: string, threadId: string) => void) | null): void {
  onThreadLanded = fn
}

/**
 * 由题面拼出封面图的提示词。
 *
 * 只取标题 + 题面开头一小段：出图模型对长文不敏感，塞整篇题面只会让画面跑偏；
 * 结尾那几句风格约束是统一观感的（站点首页的案例卡都是这个调性）。
 */
function coverPrompt(ref: CaseRef): string {
  const title = (ref.title || '').trim()
  const brief = (ref.text || '').replace(/\s+/g, ' ').trim().slice(0, 300)
  return [
    '为数学建模案例生成一张横向预览封面插画。',
    title ? `案例标题：${title}` : '',
    brief ? `题面要点：${brief}` : '',
    '风格：简约、理性、有学术科技感；扁平插画；浅色背景；主体居中并留白；画面中不要出现任何文字。',
  ]
    .filter(Boolean)
    .join('\n')
}

/**
 * 让**后端直接出图**并落盘到本会话的 `artifacts/`（不经智能体，见 `PreviewImageController`）。
 *
 * 两个刻意的选择：
 *  ① **不 await、失败只 warn**：出图是几十秒的慢请求，还可能因余额/渠道失败；
 *     绝不能让它拖住解题主流程，更不能把失败算成"分析失败"。
 *  ② **落盘位置由后端决定**：这里只把返回的 `path` 记在 console，产物卡刷新时自然能看到。
 */
async function requestPreviewCover(threadId: string, ref: CaseRef): Promise<void> {
  if (!AGENT_ID || !threadId) return
  try {
    const got = await generatePreviewImage(AGENT_ID, threadId, coverPrompt(ref))
    console.info('[preview-cover] 已生成：', got.path, `（${got.size} 字节）`)
  } catch (e: unknown) {
    console.warn('[preview-cover] 生成失败（不影响解题）：', e instanceof Error ? e.message : e)
  }
}

/**
 * 发起一次分析（幂等）。
 *
 * - 同一道题已经有结果 → 什么都不做
 * - 正在跑 → 复用同一个 promise，不会并发起两次
 * - 之前失败过 → 允许重试（重新生成按钮走这里）
 */
export function start(c: CaseRef): Promise<void> {
  if (state.status === 'done' && state.current?.caseId === c.caseId) return Promise.resolve()
  if (state.inflight) return state.inflight

  state.current = c
  state.status = 'running'
  state.failure = ''
  state.attachFailure = ''
  state.skipped = false
  state.progress = { textLength: 0, toolCalls: 0, lastTool: '' }
  state.startedAt = Date.now()
  state.harvesting = false
  /* 新的一轮：进度条回到起点、产物签名作废（否则上一轮的签名会让新产物卡不刷新） */
  state.step = 0
  state.artifactStamp += 1
  lastArtSig = ''
  ledger = { phase: '', nextAction: '', progressLine: '' }
  /* 新的一轮：清空实时对话块（上一轮的内容由桥接层定格在对话区里了）。
     liveBegin 交回「这一轮的宿主容器」，后面的事件与收尾都认它 ——
     用户中途切到别的任务时，这一轮的输出不会跟着改道 */
  const liveBox = liveBegin()

  const runId = `run-${createUuid()}`
  /* 本轮的起跑时刻：等产物时用它把「上一轮留下的同名产物」挡在外面 */
  const startedAt = state.startedAt

  /* 首页选的附件：先登记会话再上传，然后才拼请求 —— 提示词里写「已提供」
     还是「未提供」，取决于这一步真的成了没有。传失败不中断流程：传上去几个
     算几个，原因如实带出去 */
  const p = ownedThreadForRun(c.caseId)
    .then(({ threadId, rotated }) => prepareAttachments(c.caseId, threadId, rotated))
    .then((prep) => {
      /* 会话 id 到这里才真正定下来（`ownedThreadForRun` / `prepareAttachments` 都可能换过）——
         回写一次，让任务仓别再抱着一条后端不认的死会话 */
      onThreadLanded?.(c.caseId, prep.threadId)
      /* 会话一旦落地就盯上：流收尾之后，后端唤醒产生的闸门只能靠守望层扫回来 */
      watchSession(prep.threadId, c.caseId)
      if (prep.failed.length) state.attachFailure = prep.failed.join('；')
      const withAtt: CaseRef = {
        ...c,
        attachments: [...c.attachments, ...uploadedOf('home'), ...uploadedOf('work')],
      }
      state.current = withAtt
      /* 记下这一轮真正带出去的附件 —— 之后在工作台里补传的就能据此认出「本轮读不到」 */
      state.sentIds = withAtt.attachments.filter((a) => a.uploaded).map((a) => a.attachment_id)
      state.lateIds = []
      /* 附件与题面都到位了 —— 顺手让后端直接出一张封面图落进本会话的 artifacts/。
         必须 fire-and-forget：它慢且可能失败，但绝不能拖住/拖垮下面的解题主流程。 */
      void requestPreviewCover(prep.threadId, withAtt)
      return runProblemAnalysis(buildRequest(withAtt), {
        /* 会话可能刚被换过（旧 threadId 属于别的账号）——用真正落地的那个，
           否则 run 又落回别人的会话，产物本账号读不到 */
        threadId: prep.threadId,
        runId,
        timeoutMs: MAX_WAIT_MS,
        since: startedAt - 2000,
        onProgress: (prog) => {
          state.progress = prog
        },
        /* 事件流原样交给 live 模块 —— 对话区靠它实时画思考/工具/正文 */
        onEvent: (ev) => liveFeed(ev, liveBox),
        /* 智能体发闸门时把表单交给对话区渲染，等用户填完再按同一 runId 续跑 */
        onGate: (spec) => new Promise((resolve) => {
          state.gate = { spec, resolve }
        }),
      }).then(async (outcome) => {
        /* 流已经读完 —— 把还开着的块收掉，别让它们一直转圈。
           注意：**这不代表活干完了**，异步编排型智能体才刚把 S1 派出去（见下） */
        liveClose(liveBox)
        /* 这一轮已经报错 / 用户点了跳过 → 不跟随，如实收尾 */
        if (state.skipped || outcome.failureNote) return outcome
        /* 流关了才是正经的「跟随」起点。
           ⚠ 2026-10-14 前这里有一句 `if (outcome.result || …) return outcome` ——
           只要流收尾时手里已经有一份产物就直接收工，比 `harvestUntil` 更早退出。
           现在一律跟着走，产物只是「顺手收」，不再当结束信号。 */
        state.harvesting = true
        try {
          /* 前后各留 5 秒余量：产物可能就落在我们发请求的那一两秒里，
             掐得太死会把刚写好的那份当成「上一轮的」而白等满 40 分钟 */
          const follow = await followSession({
            caseId: withAtt.caseId,
            threadId: prep.threadId,
            since: startedAt - 5000,
            isStopped: () => state.skipped,
            onEvent: (ev) => liveFeed(ev, liveBox),
          })
          if (follow.outcome) return { ...follow.outcome, replyText: follow.outcome.replyText || outcome.replyText }
          /* 没收到产物 —— 原因照实说：停机原因不同说法不同（见 followNote），
             再也别写「会话已空闲」—— 那会在子智能体还在跑时被事实打脸 */
          return {
            ...outcome,
            failureNote: outcome.failureNote || followNote(follow),
          }
        } finally {
          state.harvesting = false
        }
      })
    })
    .then((outcome) => {
      state.outcome = outcome
      /* PARTIAL 也算「有内容」：产物能读就直接用，页内另行标注状态 */
      settle(state, outcome)
    })
    .catch((e: unknown) => {
      state.status = 'failed'
      state.failure = e instanceof Error ? e.message : '智能体调用失败'
    })
    .finally(() => {
      state.inflight = null
      state.harvesting = false
      if (state.skipped) state.status = 'failed'
    })

  state.inflight = p
  return p
}

/**
 * 刷新后用已有产物把结果接回来，避免重跑一次模型。
 *
 * 只在「本地没有结果」时调用。产物不在（或后端不可用）就返回 false，
 * 由调用方决定是否重跑。
 */
export async function reuse(c: CaseRef): Promise<boolean> {
  /* 进工作台就先盯上这个会话（幂等）。
     为什么放在最前面而不是「有产物才盯」：刷新后本地没有任何 run 在跑，而后台可能
     正挂着一张闸门 —— 这时候**恰恰没有产物**，若等产物才登记就永远扫不到它。
     这条通路自己不发 run，守望层只是借它知道「该盯哪个会话」。 */
  const reuseThread = threadOf(c.caseId)
  watchSession(reuseThread, c.caseId)
  void rebuildGateFor(reuseThread)

  if (state.status === 'running') return false
  /* 用户点了重跑：会话里的旧产物是上一轮留下的，接回来就等于没重跑 */
  if (state.reuseBlocked) {
    state.reuseBlocked = false
    return false
  }
  try {
    const threadId = threadOf(c.caseId)
    const files = flattenArtifacts(await fetchArtifactTree(AGENT_ID, threadId))
    if (!files.length) return false
    /* 接回产物时把进度条 / 产物卡一起接回来：否则刷新之后盘上明明有产物，
       顶上的进度条还停在「全局分析」，产物卡也要等用户手点刷新 */
    syncStep(files)
    void refreshLedger(threadId)
    const hasResult = !!findArtifactByTail(files, c.caseId, 'analysis_result.json')
    const hasContract = !!findArtifactByTail(files, c.caseId, 'truth/problem_contract.json')
    if (!hasResult && !hasContract) return false

    const readJson = async (tail: string): Promise<unknown> => {
      const node = findArtifactByTail(files, c.caseId, tail)
      if (!node) return null
      try {
        const got = await fetchArtifactContent(AGENT_ID, threadId, node.path)
        return parseJsonLoose<unknown>(got?.content ?? '')
      } catch {
        return null
      }
    }
    const jsonContract = await readJson('truth/problem_contract.json')
    const jsonSolution = await readJson('truth/solution_map.json')
    const jsonResult = await readJson('analysis_result.json')
    const result = normalizeResult(jsonResult)
    /* 报告名带版本号，由 analysis_result.json 的 primary_artifacts 指路 */
    const reportNode = pickReport(files, c.caseId, result)
    let report = ''
    if (reportNode) {
      try {
        report = (await fetchArtifactContent(AGENT_ID, threadId, reportNode.path))?.content ?? ''
      } catch {
        report = ''
      }
    }
    /* 适配在报告之后：材料清单 / 假设 / 符号表要从报告表格里抽 */
    const contract = normalizeContract(jsonContract, report)
    if (!contract && !result) return false

    state.current = c
    state.outcome = {
      status: result?.status ?? 'PARTIAL',
      caseId: c.caseId,
      contract,
      solutionMap: normalizeSolutionMap(jsonSolution),
      result,
      report,
      replyText: '',
      artifacts: files.map((f) => f.path),
    }
    state.status = 'done'
    state.failure = ''
    return true
  } catch {
    return false
  }
}

/** 清掉结果，强制重新生成 */
export function reset(): void {
  state.status = 'idle'
  state.outcome = null
  state.failure = ''
  state.attachFailure = ''
  state.progress = { textLength: 0, toolCalls: 0, lastTool: '' }
  state.step = 0
  askWaitNote = ''
  ledger = { phase: '', nextAction: '', progressLine: '' }
}

/**
 * 清掉结果 —— 工作台**在多条任务之间切换**时用。
 *
 * 这个模块是**模块级单例**（消费方是 core 那套同步渲染流程），一次只有一条任务是
 * 「当前」的；切到另一条之前必须把上一条的结果摘干净，否则渲染层会把**上一条任务**
 * 的分析贴进这一条的对话里。摘掉之后，若这条任务在会话里本来就有产物，由紧随其后的
 * reuse() 接回来。
 */
export function resetRuns(): void {
  reset()
  /* 上一条任务挂着的闸门必须放掉，否则切走之后那张卡片还留在别条任务的对话里 */
  cancelGates()
  state.current = null
  state.startedAt = 0
  state.harvesting = false
  state.step = 0
  askWaitNote = ''
  ledger = { phase: '', nextAction: '', progressLine: '' }
  state.sentIds = []
  state.lateIds = []
  /* 停止守望：不进工作台就不该再拉着那条通知流连接（切回来时 reuse/start 会重新登记） */
  unwatchSession()
}

/* ── 取内容 ─────────────────────────────────────────────────────────────── */

function ready(): boolean {
  return state.status === 'done' && !!state.outcome
}

/** 当前这一摊的结果；没就绪返回 null（调用方据此如实提示） */
function outcomeFor(): AnalysisOutcome | null {
  return ready() ? state.outcome : null
}

/** 代码那一步的标注 —— 分析走真实、代码仍走内置，这件事要如实写出来 */
export function codeNote(): string {
  const o = outcomeFor()
  if (!o) return ''
  return '<p class="src-note">说明：本步代码来自平台内置示例；分析部分（本研究由智能体生成）已按题面实际产出。</p>'
}

/** 产物抽屉里的真实文档；没有真实产物返回空串（抽屉回落到原有内容） */
export function liveDoc(type: string): string {
  if (type !== 'analysis') return ''
  const o = outcomeFor()
  if (!o) return ''
  /* 整份文档，不按当前进度裁剪 —— 否则回头点早先那张卡会看到别的问题的分析 */
  return renderLiveDoc(o)
}

/** 产物路径清单，用于「重新生成」后的提示 */
export function artifactPaths(): string[] {
  return state.outcome?.artifacts ?? []
}


