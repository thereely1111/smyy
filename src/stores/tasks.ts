/**
 * 工作台任务仓
 *
 * 解决两件事：
 *   ① 「聊着聊着跳回首页」—— 工作台的状态（`state.workTitle` / `state.detailId` /
 *      对话区 DOM）以前**只在内存里**，一次刷新或一次 Vite 热更新重载就全没了。
 *      重载后 `applyRoute('#/work')` 调 `go('work')`，而 `go()` 第一行就是
 *      `if(!state.workTitle) { toast(...); return }` —— 于是视图停在首页，
 *      地址栏却还挂着 `#/work`。这里把会话落盘，重载后能原位贴回去。
 *   ② 「首页常驻工作台入口 + 多任务」—— 每条任务各自记题面、题号、阶段、对话快照，
 *      首页那条常驻入口据此列出任务、一键返回。
 *
 * 为什么是 localStorage 而不是 Pinia：
 *   跨刷新要活着，只有落盘一条路；而这条数据同时被「页面外壳（Vue）」与
 *   「core.ts 的桥接层（非 Vue 上下文）」读，放在一个纯模块里最省事 ——
 *   core 侧通过 `legacy/prototype/hooks.ts` 的注册表拿到它，不直接 import。
 *
 * 2026-10-12 起：**任务的正文（对话 / 右栏 / 步骤 / 标题 / 暂停标记）存到 smyy 库**
 * （后端会话扩展，见 `api/workbenchStore.ts`），localStorage 降级为
 * 「任务索引 + 离线缓存」。理由：只存本地的话，换浏览器 / 清站点数据 / 换机器，
 * 聊了半天的任务就没了；而后端本来就按会话存着这条对话的产物与事件流。
 *   写入节拍：改动先攒进 `dirty` 集合，由页面外壳按较慢的节拍（10 秒）
 *   调 `flushRemote()` 推一次 —— 对话快照有几十 KB，跟着 1.5 秒的对账节拍推
 *   会把后端当成写盘缓存用，没必要。
 *   读取时机：外壳挂载时 `hydrateTasks()` 拉一次，**后端更新才覆盖本地**
 *   （本地刚写、还没推上去的改动不能被旧数据盖掉）。
 *   注意：**任务索引本身仍在 localStorage**（后端只有会话级键值，没有用户级），
 *   所以「另一台机器上要看见任务列表」还需要后端加一个用户级接口 —— 见 docs。
 *
 * 容量口径：单条任务的对话快照截到 `MAX_STREAM`，任务数上限 `MAX_TASKS`，
 * 超了按「最久没动」淘汰。localStorage 写失败（配额/隐私模式）不影响页面，
 * 只是退化成「内存内有效」。
 */

import { ref } from 'vue'
import { readWorkbench, writeWorkbench, MAX_REMOTE_RAIL, MAX_REMOTE_STREAM, WORKBENCH_VERSION, type WorkbenchSnapshot } from '@/api/workbenchStore'

export interface WorkTask {
  /** 任务 id（本地生成，形如 t20261008-171203-abcd） */
  id: string
  /** 题面全文（恢复 state.workTitle 用；界面上一律用 shortTitle 显示） */
  title: string
  /** 内置题号（'p3' 之类）；用户自己输入的题目为空串 */
  protoId: string
  /** 是不是从题目详情页进来的（来自详情页的才算内置题） */
  fromDetail: boolean
  createdAt: number
  /** 最后一次动过的时间（对话区有变化 / 阶段变化时更新） */
  updatedAt: number
  /** 顶栏那条阶段文案（读自 #w-stage） */
  stage: string
  /**
   * 顶栏六步进度条走到第几步（0 = 全局分析，5 = 论文）。
   *
   * 为什么任务仓要管它：那条进度条只在 `startWork()` 里由 `renderSteps()` 画过一次，
   * 刷新 / 换任务时没人补 —— 用户回来看到的是空的一块（2026-10-12 取证）。
   * 存下来，恢复时才画得出「他当时停在哪一步」，而不是一律高亮第一步。
   */
  step: number
  /** 对话区 HTML 快照 */
  stream: string
  /**
   * 右栏「闸门」区的 HTML 快照。
   *
   * 为什么单独存：2026-10-12 起人工决策闸门**不再进对话流**，只在右栏渲染
   * （用户定的布局：左对话 / 右闸门+产物）。它也就不在 `stream` 里了 ——
   * 不单独存的话，刷新或切任务回来右栏会空掉，用户以为闸门丢了。
   */
  rail: string
  /**
   * 这条任务的**会话键**（形如 `user-3z12ft` / `2026-C`）。
   *
   * 开题那一刻由页面外壳算一次并记下（那时附件暂存区一定是对的），此后一律按它
   * 查会话，不再重新推导 —— 暂存区是内存态，刷新或切视图就没
   * （2026-10-09 修：正是它把暂停/删除逼到回落一条 404 的死会话）。
   */
  caseId: string
  /**
   * 这条任务在平台后端那边的**会话 id**（形如 main-xxxxxxxx-…）。
   *
   * 由页面外壳按「题号/题面」向分析通道问出来（`threadIdForTask`），并由分析通道
   * 在真正落地时回写（`setThreadLandedSink`）—— 会话映射的 owner 是 analysis/flow，
   * 两边各写一份必然分叉。
   * 空串 = 这条任务还没跑过真实 run（后端没有它的会话），暂停/删除就只能改本地。
   */
  threadId: string
  /** 用户主动暂停过（后端那份 run 已被 interrupt 端点叫停，产物目录保留） */
  paused: boolean
  /**
   * 开题那一刻的**题目文件名列表**（题面由文件承担的任务才有）。
   *
   * 附件暂存区是内存态，刷新即丢 —— 不把文件名持久化下来，「用题目文件开的题」
   * 刷新后就永远解不出题面（resolveCase 返回 null），对话区一句话都发不出去。
   * 文件名列表不变 ⇒ 算出的 caseId 不变 ⇒ 还能接回原会话。
   */
  paperNames: string[]
}

const KEY = 'smyy.work.tasks'
const MAX_TASKS = 12
const MAX_STREAM = 180_000

/** 任务列表（按 updatedAt 倒序，最新的在前） */
export const tasks = ref<WorkTask[]>([])
/** 前台任务 id；空串 = 还没有任何任务 */
export const currentId = ref('')

let loaded = false

/* ── id / 时间 ───────────────────────────────────────────────────────────── */

const pad = (n: number): string => String(n).padStart(2, '0')

function newId(): string {
  const d = new Date()
  const stamp =
    `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}` +
    `-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`
  return `t${stamp}-${Math.random().toString(36).slice(2, 6)}`
}

/** 「刚刚 / 12 分钟前 / 昨天 18:20」—— 任务列表用 */
export function relTime(ts: number): string {
  if (!ts) return '—'
  const diff = Date.now() - ts
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  const d = new Date(ts)
  if (diff < 86_400_000) return `${pad(d.getHours())}:${pad(d.getMinutes())}`
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 「进行中」的判据：最近两分钟还有人往里写东西 */
export const LIVE_WINDOW_MS = 2 * 60 * 1000
export function isLive(t: WorkTask, now = Date.now()): boolean {
  return now - t.updatedAt < LIVE_WINDOW_MS
}

/** 列表里显示的短题面（原型的 state.workTitle 用的是 46 字截断，这里跟它一致） */
export function shortTitle(title: string): string {
  const s = (title || '').replace(/\s+/g, ' ').trim()
  if (!s) return '未命名题目'
  return s.length > 26 ? `${s.slice(0, 26)}…` : s
}

/* ── 落盘 ────────────────────────────────────────────────────────────────── */

function safeStorage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage
  } catch {
    return null
  }
}

function coerce(v: unknown): WorkTask | null {
  if (!v || typeof v !== 'object') return null
  const o = v as Record<string, unknown>
  const id = typeof o.id === 'string' ? o.id : ''
  const title = typeof o.title === 'string' ? o.title : ''
  if (!id || !title) return null
  const at = typeof o.updatedAt === 'number' ? o.updatedAt : Date.now()
  return {
    id,
    title,
    protoId: typeof o.protoId === 'string' ? o.protoId : '',
    fromDetail: o.fromDetail === true,
    createdAt: typeof o.createdAt === 'number' ? o.createdAt : at,
    updatedAt: at,
    stage: typeof o.stage === 'string' ? o.stage : '',
    /* 升级前的老任务没有这个字段 —— 当第 0 步（与 startWork 的起点一致） */
    step: typeof o.step === 'number' ? o.step : 0,
    stream: typeof o.stream === 'string' ? o.stream : '',
    /* 升级前的老任务没有这个字段 —— 当右栏空着（闸门本来也只在等待时才出现） */
    rail: typeof o.rail === 'string' ? o.rail : '',
    /* 升级前的老任务没有这个字段 —— 留空串，暂停/删除会回落到按题面现算那条路 */
    caseId: typeof o.caseId === 'string' ? o.caseId : '',
    threadId: typeof o.threadId === 'string' ? o.threadId : '',
    paused: o.paused === true,
    /* 升级前的老任务没有这个字段 —— 当空列表（那些题刷新后仍需重传一次题目文件） */
    paperNames: Array.isArray(o.paperNames) ? o.paperNames.filter((x) => typeof x === 'string') : [],
  }
}

/** 第一次用到时读盘。重复调用只生效一次 */
export function loadTasks(): void {
  if (loaded) return
  loaded = true
  const ls = safeStorage()
  if (!ls) return
  try {
    const raw = ls.getItem(KEY)
    if (!raw) return
    const parsed = JSON.parse(raw) as { currentId?: unknown; items?: unknown }
    const items = Array.isArray(parsed.items) ? parsed.items : []
    const list = items.map(coerce).filter((t): t is WorkTask => !!t)
    list.sort((a, b) => b.updatedAt - a.updatedAt)
    tasks.value = list.slice(0, MAX_TASKS)
    currentId.value = typeof parsed.currentId === 'string' ? parsed.currentId : ''
    if (!list.some((t) => t.id === currentId.value)) currentId.value = list[0]?.id ?? ''
  } catch {
    /* 盘上的数据坏了就当没有 —— 不要把整页拖垮 */
  }
}

let writeWarned = false

function persist(): void {
  const ls = safeStorage()
  if (!ls) return
  try {
    ls.setItem(KEY, JSON.stringify({ currentId: currentId.value, items: tasks.value }))
  } catch {
    /* 配额满 / 隐私模式：退化成内存内有效，只提醒一次 */
    if (!writeWarned) {
      writeWarned = true
      console.warn('[workbench] 任务仓落盘失败（localStorage 配额或隐私模式），本次会话只在内存里有效')
    }
  }
}

function resort(): void {
  tasks.value = [...tasks.value].sort((a, b) => b.updatedAt - a.updatedAt)
}

/* ── 后端落盘（2026-10-12：任务与对话存到 smyy 库）────────────────────────────
   口径见文件头。这里只做三件事：攒脏、推、拉。两者都不抛错给调用方 ——
   后端抖一下就退回本地那一份，页面不该因为一次 500 就崩。 */

/** 有改动待推的任务 id */
const dirty = new Set<string>()

/** 记一笔「这条任务变了」。没有会话 id 的直接跳过 —— 那时后端也没这条会话 */
function touch(t: WorkTask | null | undefined): void {
  if (t && t.threadId) dirty.add(t.id)
}

function snapshotOf(t: WorkTask): WorkbenchSnapshot {
  const cut = (s: string, max: number): string => (s.length > max ? `${s.slice(0, max)}<!--truncated-->` : s)
  return {
    v: WORKBENCH_VERSION,
    updatedAt: t.updatedAt,
    title: t.title,
    protoId: t.protoId,
    fromDetail: t.fromDetail,
    createdAt: t.createdAt,
    step: t.step,
    stream: cut(t.stream, MAX_REMOTE_STREAM),
    rail: cut(t.rail, MAX_REMOTE_RAIL),
    caseId: t.caseId,
    paused: t.paused,
    paperNames: t.paperNames,
  }
}

let flushing = false
let flushWarned = false

/**
 * 把攒下的改动推回后端。
 *
 * 由页面外壳按 10 秒的慢节拍调用（离开页面时也调一次）。串行推、失败不重试轰炸：
 * 推失败的那条留在 dirty 里，下一轮自然再试。
 */
export async function flushRemote(): Promise<void> {
  if (flushing || !dirty.size) return
  flushing = true
  try {
    for (const id of [...dirty]) {
      const t = getTask(id)
      if (!t || !t.threadId) {
        dirty.delete(id)
        continue
      }
      dirty.delete(id)
      try {
        await writeWorkbench(t.threadId, snapshotOf(t))
      } catch {
        if (!flushWarned) {
          flushWarned = true
          console.warn('[workbench] 任务没能推到后端（网络或权限），本地那份照常用')
        }
      }
    }
  } finally {
    flushing = false
  }
}

/** 后端那份覆盖本地那份 —— **不走 touch / persist 的那套语义**，避免自己推自己 */
function applyRemote(t: WorkTask, s: WorkbenchSnapshot): void {
  t.title = s.title || t.title
  t.protoId = s.protoId
  t.fromDetail = s.fromDetail
  t.createdAt = s.createdAt || t.createdAt
  t.updatedAt = s.updatedAt
  t.step = s.step
  t.stream = s.stream
  t.rail = s.rail
  if (s.caseId) t.caseId = s.caseId
  t.paused = s.paused
  /* 老快照没有 paperNames（v 不升，缺字段回落空数组）—— 别拿空数组盖掉本地已有的 */
  if (s.paperNames.length) t.paperNames = s.paperNames
}

/**
 * 挂载时从后端把任务记录拉回来（页面外壳调一次）。
 *
 * 只有**后端那份更新**才覆盖本地：本地刚写完还没推上去的改动，不能被一份
 * 稍旧的后端数据盖掉。返回「有没有任何一条被覆盖」——调用方据此决定要不要重画。
 */
export async function hydrateTasks(): Promise<boolean> {
  loadTasks()
  const targets = tasks.value.filter((t) => !!t.threadId)
  if (!targets.length) return false
  let changed = false
  await Promise.all(
    targets.map(async (t) => {
      const snap = await readWorkbench(t.threadId)
      if (!snap || snap.updatedAt <= t.updatedAt) return
      applyRemote(t, snap)
      changed = true
    }),
  )
  if (changed) {
    resort()
    persist()
  }
  return changed
}

/* ── 增删改查 ────────────────────────────────────────────────────────────── */

export function getTask(id: string): WorkTask | null {
  return tasks.value.find((t) => t.id === id) ?? null
}

export function currentTask(): WorkTask | null {
  return getTask(currentId.value) ?? tasks.value[0] ?? null
}

/** 开一道新题：登记一条任务并置为前台。返回这条任务 */
export function beginTask(init: {
  title: string
  protoId: string
  fromDetail: boolean
  paperNames?: string[]
}): WorkTask {
  loadTasks()
  const now = Date.now()
  const t: WorkTask = {
    id: newId(),
    title: (init.title || '新题目').slice(0, 4000),
    protoId: init.protoId || '',
    fromDetail: !!init.fromDetail,
    createdAt: now,
    updatedAt: now,
    stage: '',
    step: 0,
    stream: '',
    rail: '',
    caseId: '',
    threadId: '',
    paused: false,
    paperNames: Array.isArray(init.paperNames) ? init.paperNames.filter((x) => !!x) : [],
  }
  tasks.value = [t, ...tasks.value]
  currentId.value = t.id
  /* 超上限就淘汰最久没动的（当前这条永远留着） */
  if (tasks.value.length > MAX_TASKS) {
    const keep = tasks.value.slice(0, MAX_TASKS)
    if (!keep.some((x) => x.id === currentId.value)) {
      const cur = tasks.value.find((x) => x.id === currentId.value)
      if (cur) keep[MAX_TASKS - 1] = cur
    }
    tasks.value = keep
  }
  persist()
  return t
}

/** 改了某条任务（阶段 / 快照 / 标题）；只有真变了才写盘 */
export function patchTask(id: string, patch: Partial<Omit<WorkTask, 'id'>>): void {
  const t = getTask(id)
  if (!t) return
  let changed = false
  for (const [k, v] of Object.entries(patch) as Array<[keyof WorkTask, never]>) {
    if (t[k] !== v) {
      ;(t as unknown as Record<string, unknown>)[k] = v
      changed = true
    }
  }
  if (!changed) return
  t.updatedAt = Date.now()
  resort()
  persist()
  touch(t)
}

/**
 * 存一次对话区快照。
 *
 * 调用方（页面外壳）每 1.5 秒读一次 `#stream-in` —— 所以这里必须先比一遍，
 * 内容没变就直接返回：不然每 1.5 秒往 localStorage 写一遍几十 KB 的字符串。
 */
export function saveStream(id: string, html: string): void {
  const t = getTask(id)
  if (!t) return
  const next = html.length > MAX_STREAM ? `${html.slice(0, MAX_STREAM)}<!--truncated-->` : html
  if (t.stream === next) return
  t.stream = next
  t.updatedAt = Date.now()
  resort()
  persist()
  touch(t)
}

export function saveStage(id: string, stage: string): void {
  patchTask(id, { stage })
}

/**
 * 存一次「步骤条走到第几步」。
 *
 * 与 saveStage 同一口径：每 1.5 秒被外壳读一次，只有真变了才写盘
 * （patchTask 会先比对，没变就直接返回，不会把 updatedAt 顶起来）。
 */
export function saveStep(id: string, step: number): void {
  patchTask(id, { step: Number.isFinite(step) ? step : 0 })
}

/**
 * 存一次右栏闸门区快照（与 saveStream 同一口径：没变就不写盘）。
 *
 * 闸门区比对话区小得多，不需要 MAX_STREAM 那个上限，但还是设一个 —— 万一哪次
 * 卡片里带进一大段文本，别把 localStorage 顶爆。
 */
const MAX_RAIL = 60_000
export function saveRail(id: string, html: string): void {
  const t = getTask(id)
  if (!t) return
  const next = html.length > MAX_RAIL ? `${html.slice(0, MAX_RAIL)}<!--truncated-->` : html
  if (t.rail === next) return
  t.rail = next
  t.updatedAt = Date.now()
  resort()
  persist()
  touch(t)
}

/**
 * 记下这条任务在后端的会话 id。
 *
 * 只在拿到非空值时写，且**不刷新 updatedAt 以外的东西**：会话 id 是审计信息，
 * 不该把任务顶到列表最前面（所以这里不走 patchTask 的 resort 语义差异也不重要，
 * 直接复用 patchTask 即可 —— 它只在真有变化时才写盘）。
 *
 * 2026-10-12 起它还有第二个作用：**拿到会话 id 之后，这条任务的正文就能落后端了**
 * （patchTask → touch → dirty → flushRemote）。在此之前后端还没有这条会话，
 * 推过去也没有落点。
 */
export function setTaskThread(id: string, threadId: string): void {
  if (!threadId) return
  patchTask(id, { threadId })
}

/**
 * 记下这条任务的会话键。
 *
 * 只在开题那一刻写一次（那时题面/附件都在手上，算得准），此后暂停/删除一律
 * 按它查会话 —— 不再重新推导。空值直接忽略，不覆盖已有的。
 */
export function setTaskCase(id: string, caseId: string): void {
  if (!caseId) return
  patchTask(id, { caseId })
}

/** 暂停 / 继续：只改标记，真正的后端中断由调用方先做完再来打标 */
export function setPaused(id: string, paused: boolean): void {
  patchTask(id, { paused })
}

export function removeTask(id: string): void {
  const before = tasks.value.length
  tasks.value = tasks.value.filter((t) => t.id !== id)
  if (tasks.value.length === before) return
  if (currentId.value === id) currentId.value = tasks.value[0]?.id ?? ''
  persist()
}

export function selectTask(id: string): void {
  if (!getTask(id)) return
  if (currentId.value === id) return
  currentId.value = id
  persist()
}

/** 任务列表为空时也要能用 —— 页面外壳在挂载时调用 */
export function ensureLoaded(): void {
  loadTasks()
}

export function taskCount(): number {
  return tasks.value.length
}

/* 模块被引入即读盘：首页那条常驻入口与「刷新落到 #/work」的兜底，
   都要在组件渲染之前就拿到数据。 */
loadTasks()
