/**
 * 产物适配层：智能体的真实产物 → 站点建模
 *
 * 为什么需要这一层：站点渲染层（render.ts）是照**站点自己那套字段名**写的
 * （`tasks` / `sources` / `facts` / `routes` / `candidate_models`…），而
 * smyy 自带的「数学建模解答助手」按它自己的 Skill 规范产出另一套名字。
 * 两边不是「差几个字段」，是**两套命名体系**。不转换的话，页面上会看到
 * 「run 跑了、产物也读到了，但一块内容都渲染不出来」这种静默失败。
 *
 * ⚠️ 实测下来智能体**不止一套命名**（同一 Agent 不同轮次给的不同）：
 *   A. 站点形状           `tasks[]` / `routes[]`
 *   B. 一轮的产物         `questions[].question_id` / `given` / `acceptance_criteria`
 *   C. 另一轮的产物       `questions[].qid` / `facts_from_statement` / `source_files`
 *                        / `unknowns_blocking_S3` / `acceptance_checks` / `verbs`
 * 所以这里的读取一律走**候选键**（`pick(o, 'question_id', 'qid')`），而不是认死一个。
 * 这不是过度设计 —— 少认一套，那一轮的产物在页面上就是空白。
 *
 * 三条纪律：
 *   1. **不补数字、不编结论** —— 这里只做搬运与拼装。缺的字段就是缺，
 *      渲染层会就地省略。绝不用示意值填空。
 *   2. **对象数组压成字符串** —— 真实产物的数组元素多是 `{id,name,unit}` 这种
 *      结构化对象，而渲染层只对少数键名有约定。与其让渲染层去猜每种形状，
 *      不如在这里一次压成人能读的一行。
 *   3. **单问产物要做位置补齐** —— 智能体可以只分析一问（`questions` 里只有 q02）。
 *      站点那条对话是按**位置**取内容的（第 0 问 → 问题一），若只补一条，
 *      q02 的内容会被贴到问题上 —— 串问比缺内容严重得多。所以按 `q(\d+)`
 *      补齐占位，让「这一问本轮没做」如实显示出来。
 */

import type {
  AnalysisResult,
  CandidateModel,
  ContractAssumption,
  ContractDependency,
  ContractSymbol,
  ContractTask,
  ContractUnknown,
  EvidencedItem,
  ProblemContract,
  ProblemSource,
  SolutionMap,
  SolutionRoute,
  SourceStatus,
} from '@/types/analysis'

/* ── 基础工具 ───────────────────────────────────────────────────────────── */

function isRecord(v: unknown): v is Record<string, unknown> {
  return !!v && typeof v === 'object' && !Array.isArray(v)
}

function rec(v: unknown): Record<string, unknown> {
  return isRecord(v) ? v : {}
}

function arr(v: unknown): unknown[] {
  return Array.isArray(v) ? v : []
}

/** 候选键取值：智能体不同轮次给的键名不同，按优先级取第一个非空 */
function pick(o: Record<string, unknown>, ...keys: string[]): unknown {
  for (const k of keys) {
    const v = o[k]
    if (v !== undefined && v !== null && v !== '') return v
  }
  return undefined
}

function s(v: unknown): string {
  return typeof v === 'string' ? v : typeof v === 'number' ? String(v) : ''
}

function pickStr(o: Record<string, unknown>, ...keys: string[]): string {
  return s(pick(o, ...keys))
}

/** 字符串数组：非数组时把单个值也接住；数组元素是对象时用 line() 压平 */
function strList(v: unknown): string[] {
  if (Array.isArray(v)) return v.map(line).filter((x) => !!x)
  const one = line(v)
  return one ? [one] : []
}

function pickList(o: Record<string, unknown>, ...keys: string[]): string[] {
  return strList(pick(o, ...keys))
}

const normPath = (p: string): string => p.replace(/\\/g, '/')

/**
 * 任意条目 → 一行文字。
 *
 * 三种产物里同一个语义的键名换过好几轮（`name` / `symbol` / `text` / `item` /
 * `format` / `core_relation`…），所以这里按用途排优先级：符号与名称在前，
 * 说明在后，最后兜底 `unit` 与状态。取不到就返回空串（渲染层会跳过）。
 */
function line(v: unknown): string {
  if (v == null) return ''
  if (typeof v === 'string') return v.trim()
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  if (Array.isArray(v)) return v.map(line).filter((x) => !!x).join('、')
  const o = rec(v)
  const unit = s(pick(o, 'unit'))
  const head = s(pick(o, 'symbol')) || s(pick(o, 'name')) || s(pick(o, 'item')) || s(pick(o, 'id'))
  const body =
    s(pick(o, 'text', 'statement', 'description', 'objective', 'rationale', 'format', 'value', 'expr')) ||
    s(pick(o, 'criterion', 'core_relation', 'name'))
  const tail = s(pick(o, 'status', 'data_status', 'class', 'role', 'kind')) || s(pick(o, 'source', 'attachment'))
  const parts: string[] = []
  if (head) parts.push(head + (unit && unit !== '—' ? '（' + unit + '）' : ''))
  if (body && body !== head) parts.push(body)
  if (tail) parts.push('[' + tail + ']')
  const joined = parts.join('　')
  if (joined) return joined
  try {
    return JSON.stringify(v)
  } catch {
    return ''
  }
}

/* ── 站点形状判定（兼容透传） ───────────────────────────────────────────── */

const isSiteContract = (raw: unknown): boolean => Array.isArray(rec(raw)['tasks'])
const isSiteMap = (raw: unknown): boolean => Array.isArray(rec(raw)['routes'])

/* ── 报告 Markdown 里的表格（尽力而为，抽不到就留空） ────────────────────── */

interface MdTable {
  /** 最近一个二级标题（`## 5 符号表`）—— 小节归类主要看它 */
  top: string
  /** 最近一个三级标题（`### 5.1 集合与索引`），没有则为空 */
  sub: string
  headers: string[]
  rows: string[][]
}

function cleanCell(x: string): string {
  return x.replace(/\*\*/g, '').replace(/`/g, '').trim()
}

/**
 * 扫出报告里所有管道表。
 *
 * 报告里的表格是智能体按模板写的，小节编号会变，所以按**标题关键词 +
 * 表头关键词**两级定位，不记死「§5.1」这种编号。
 * 记两级标题是必需的：符号表常见于 `## 5 符号表` 下的 `### 5.1 集合` …
 * 这类子小节，只看最后一级会漏掉大半。
 */
export function mdTables(md: string): MdTable[] {
  const out: MdTable[] = []
  if (!md) return out
  let top = ''
  let sub = ''
  let cur: MdTable | null = null
  for (const rawLine of md.split(/\r?\n/)) {
    const head = /^(#{2,4})\s+(.*)$/.exec(rawLine)
    if (head) {
      const level = head[1].length
      const text = head[2].trim()
      if (level === 2) {
        top = text
        sub = ''
      } else if (level === 3) {
        sub = text
      }
      cur = null
      continue
    }
    const t = rawLine.trim()
    if (t.startsWith('|')) {
      const cells = t.replace(/^\|/, '').replace(/\|$/, '').split('|').map((x) => x.trim())
      if (cells.every((x) => x === '' || /^:?-{2,}:?$/.test(x))) continue
      if (!cur) {
        cur = { top, sub, headers: cells.map(cleanCell), rows: [] }
        out.push(cur)
      } else {
        cur.rows.push(cells.map(cleanCell))
      }
      continue
    }
    cur = null
  }
  return out
}

function colOf(headers: string[], ...keys: string[]): number {
  for (const k of keys) {
    const i = headers.findIndex((h) => h.includes(k))
    if (i >= 0) return i
  }
  return -1
}

function cellOf(row: string[], idx: number): string {
  return idx >= 0 && idx < row.length ? row[idx] ?? '' : ''
}

/** 按标题关键词 + 表头关键词找表 */
function findTable(tables: MdTable[], sectionKeys: string[], headerKey: string): MdTable | null {
  for (const t of tables) {
    const where = t.top + ' ' + t.sub
    if (!sectionKeys.some((k) => where.includes(k))) continue
    if (colOf(t.headers, headerKey) < 0) continue
    if (!t.rows.length) continue
    return t
  }
  return null
}

/* ── 问号解析与位置补齐 ─────────────────────────────────────────────────── */

/** `q02` → 2；解析不出返回 0 */
function qidIndex(qid: string): number {
  const m = /q0*(\d+)/i.exec(qid)
  if (!m) return 0
  const n = Number(m[1])
  return Number.isFinite(n) && n > 0 ? n : 0
}

/**
 * 原样保留问号（`q02` / `q2` / `2`），解析不出就给位置序。
 * 站点渲染按位置对齐，question_id 只用于展示与跨轮次对齐。
 */
function qidOf(q: Record<string, unknown>, fallback: number): string {
  const raw = pickStr(q, 'question_id', 'qid', 'id')
  if (!raw) return `q0${fallback}`
  return /^q/i.test(raw) ? raw.toLowerCase() : `q0${raw}`
}

/** 按问号补齐到同一长度，避免串问 */
function padByQid<T>(entries: Array<{ idx: number; value: T }>, make: (n: number) => T, maxHint = 0): T[] {
  const max = Math.max(maxHint, entries.reduce((m, e) => Math.max(m, e.idx), 0))
  if (max <= 0) return entries.map((e) => e.value)
  const out: T[] = []
  for (let n = 1; n <= max; n += 1) {
    const hit = entries.find((e) => e.idx === n)
    out.push(hit ? hit.value : make(n))
  }
  return out
}

const PLACEHOLDER_OBJECTIVE = '本轮未展开'

/**
 * 这条 task 是不是「本轮未展开」的占位。
 *
 * 上层（flow）据此判断「这一问到底有没有被真正分析过」—— 没有的话，
 * 就是该为它单独发起一次 run 的时机。
 */
export function isPlaceholderTask(t: ContractTask | null | undefined): boolean {
  if (!t) return false
  return t.objective === PLACEHOLDER_OBJECTIVE && !t.inputs.length && !t.constraints.length && !t.deliverables.length
}

function placeholderTask(qid: string): ContractTask {
  return {
    question_id: qid,
    task_verb: '智能体本次只分析了其中一问，这一问留到下一轮',
    objective: PLACEHOLDER_OBJECTIVE,
    inputs: [],
    outputs: [],
    constraints: [],
    evaluation_criteria: [],
    deliverables: [],
    source_refs: [],
  }
}

function placeholderRoute(qid: string): SolutionRoute {
  return {
    question_id: qid,
    objective: PLACEHOLDER_OBJECTIVE,
    mechanism: '',
    candidate_models: [],
    recommended_route: null,
    alternatives: [],
    required_inputs: [],
    parameters: [],
    decision_variables: [],
    metrics: [],
    conditions: {},
    data_preprocessing: [],
    solving_steps: [],
    validation_plan: [],
    risks: [],
  }
}

/* ── 状态词归一 ─────────────────────────────────────────────────────────── */

function mapSourceStatus(text: string): SourceStatus {
  const t = text || ''
  if (/未提供|未读取|不存在|NOT_PROVIDED|NOT_APPLICABLE|MISSING/i.test(t)) return 'missing'
  if (/部分|PARTIAL/i.test(t)) return 'partial'
  if (/已读取|已读|READABLE|(^|[^N])READ\b/i.test(t)) return 'read'
  if (/未读|UNREAD|未使用|NOT_REQUIRED/i.test(t)) return 'unread'
  return 'missing'
}

/* ── 证据（题面事实 / 推断 / 缺口） ─────────────────────────────────────── */

/** 拆掉尾部的 `[L27 · PROVED_题面]`，内容进 statement、锚点进 source_refs */
function splitAnchor(text: string): { body: string; refs: string[] } {
  const m = /\s*\[([^\]]+)\]\s*$/.exec(text)
  if (!m) return { body: text, refs: [] }
  const refs = m[1]
    .split(/[·,，、]/)
    .map((x) => x.trim())
    .filter((x) => /^L\d+|^附件|^表/.test(x))
  return { body: text.slice(0, m.index).trim(), refs }
}

const EVIDENCE_INFER_RE = /INFERRED|PENDING_USER|MISSING|NOT_|TBD|未定义|待确认/i

/**
 * 抽「题面事实 / 推断」。
 *
 * 两个来源，都要认：
 *   - 顶层 `facts_from_statement[]` —— `{id, text, unit, source, class}`（某轮产物）
 *   - 问级 `given[]` + `evidence_status{}`  —— 逐问给（另一轮产物）
 */
function factsOf(raw: Record<string, unknown>, questions: Record<string, unknown>[]): EvidencedItem[] {
  const out: EvidencedItem[] = []
  for (const x of arr(pick(raw, 'facts_from_statement'))) {
    const o = rec(x)
    const text = pickStr(o, 'text', 'statement')
    if (!text) continue
    const unit = pickStr(o, 'unit')
    out.push({
      item_id: pickStr(o, 'id') || `F${out.length + 1}`,
      statement: unit && unit !== '—' ? `${text}（${unit}）` : text,
      evidence_type: /explicit/i.test(pickStr(o, 'class')) ? 'explicit_fact' : 'inference',
      source_refs: pickList(o, 'source'),
    })
  }

  let n = out.length
  for (const q of questions) {
    for (const x of arr(q['given'])) {
      const text = s(x)
      if (!text) continue
      const { body, refs } = splitAnchor(text)
      n += 1
      out.push({ item_id: pickStr(q, 'question_id', 'qid') + `-F${n}`, statement: body, evidence_type: 'explicit_fact', source_refs: refs })
    }
    for (const [k, v] of Object.entries(rec(q['evidence_status']))) {
      const val = s(v)
      if (!val) continue
      if (/^given_/.test(k)) continue
      if (/^PROVED/.test(val)) continue
      if (!EVIDENCE_INFER_RE.test(val)) continue
      n += 1
      out.push({
        item_id: `I${n}`,
        statement: k.replace(/^[a-z_]+_/, '').replace(/_/g, ' ') + '：' + val,
        evidence_type: 'inference',
        source_refs: [],
      })
    }
  }
  return out
}

/* ── contract：真实形状 → 站点形状 ──────────────────────────────────────── */

function fmtInputs(v: unknown): string[] {
  return arr(v)
    .map((x) => {
      if (typeof x === 'string') return x
      const o = rec(x)
      const name = pickStr(o, 'name') || pickStr(o, 'symbol')
      if (!name) return ''
      const unit = pickStr(o, 'unit')
      const sym = pickStr(o, 'symbol')
      const bits = [name]
      if (sym && sym !== name) bits.push(`符号 ${sym}`)
      if (unit && unit !== '—') bits.push(unit)
      const src = pickStr(o, 'source', 'attachment')
      if (src) bits.push('来源：' + src)
      const st = pickStr(o, 'status', 'data_status')
      if (st) bits.push(st)
      const gran = pickStr(o, 'granularity')
      if (gran) bits.push('粒度：' + gran)
      return bits.join('　')
    })
    .filter((x) => !!x)
}

function fmtConstraints(v: unknown): string[] {
  return arr(v)
    .map((x) => {
      if (typeof x === 'string') return x
      const o = rec(x)
      const head = [pickStr(o, 'id'), pickStr(o, 'name')].filter((y) => !!y).join(' ')
      const expr = pickStr(o, 'expr', 'expression')
      const body = [head, expr].filter((y) => !!y).join('：')
      const tail = [pickStr(o, 'type'), pickStr(o, 'status')].filter((y) => !!y).join('；')
      return tail ? body + '　[' + tail + ']' : body
    })
    .filter((x) => !!x)
}

function fmtDeliverables(v: unknown): string[] {
  return arr(v)
    .map((x) => {
      if (typeof x === 'string') return x
      const o = rec(x)
      const item = pickStr(o, 'item', 'name')
      const detail = pickStr(o, 'cells', 'format')
      const st = pickStr(o, 'status')
      const body = [item, detail].filter((y) => !!y).join('：')
      return st ? body + '　[' + st + ']' : body
    })
    .filter((x) => !!x)
}

function fmtCriteria(v: unknown): string[] {
  return arr(v)
    .map((x) => {
      if (typeof x === 'string') return x
      const o = rec(x)
      const head = [pickStr(o, 'id'), pickStr(o, 'name')].filter((y) => !!y).join(' ')
      const body = [head, pickStr(o, 'criterion', 'text', 'check')].filter((y) => !!y).join('：')
      const st = pickStr(o, 'status')
      return st ? body + '　[' + st + ']' : body
    })
    .filter((x) => !!x)
}

/** 去掉 label 的「问题 2：」前缀，让 objective 就是一句话 */
function stripLabel(label: string): string {
  return label.replace(/^问题\s*[一二三四五六七八九十\d]+\s*[：:]\s*/, '').trim()
}

function toTask(q: Record<string, unknown>, fallbackQid: string): ContractTask {
  const label = pickStr(q, 'label', 'title')
  const verbs = pickList(q, 'verbs')
  const verb = pickStr(q, 'verb') || verbs.join('；')
  const objectiveRaw = pick(q, 'objective')
  const objective =
    stripLabel(label) ||
    (isRecord(objectiveRaw) ? pickStr(objectiveRaw, 'words', 'symbolic') : s(objectiveRaw)) ||
    verb
  const inputs = fmtInputs(q['inputs'])
  return {
    question_id: fallbackQid,
    task_verb: verb,
    objective,
    problem_type: pickStr(q, 'question_type', 'problem_type') || undefined,
    inputs,
    outputs: pickList(q, 'outputs'),
    constraints: fmtConstraints(q['constraints']),
    evaluation_criteria: fmtCriteria(pick(q, 'acceptance_criteria', 'acceptance_checks')),
    deliverables: fmtDeliverables(q['deliverables']),
    source_refs: pickList(q, 'source_refs'),
  }
}

/** 问级 open_questions + 顶层阻断项 → contract.unknowns */
function unknownsOf(raw: Record<string, unknown>, questions: Record<string, unknown>[]): ContractUnknown[] {
  const out: ContractUnknown[] = []
  const seen = new Set<string>()
  const add = (u: ContractUnknown): void => {
    if (!u.description || seen.has(u.unknown_id)) return
    seen.add(u.unknown_id)
    out.push(u)
  }

  for (const x of arr(pick(raw, 'unknowns_blocking_S3'))) {
    const text = s(x)
    if (!text) continue
    add({ unknown_id: `B${out.length + 1}`, description: text, impact: '进入求解阶段前必须确认', blocking: true })
  }
  for (const x of arr(pick(raw, 'blocking_items_for_computation'))) {
    const text = s(x)
    if (!text) continue
    add({ unknown_id: `B${out.length + 1}`, description: text, impact: '不补齐无法进入求解阶段', blocking: true })
  }
  for (const q of questions) {
    for (const x of arr(q['open_questions'])) {
      if (typeof x === 'string') continue /* 只是 ID 引用，实体在报告里 */
      const o = rec(x)
      add({
        unknown_id: pickStr(o, 'id') || `U${out.length + 1}`,
        description: pickStr(o, 'q', 'question', 'description', 'topic'),
        impact: pickStr(o, 'impact', 'status', 'severity'),
        blocking: o['blocking'] === true,
      })
    }
  }
  return out
}

/** 材料清单 → sources：报告表格优先，其次用各轮的附件登记字段 */
function sourcesOf(raw: Record<string, unknown>, tables: MdTable[]): ProblemSource[] {
  const out: ProblemSource[] = []

  const primary = rec(pick(raw, 'primary_source'))
  const pfile = pickStr(primary, 'file')
  if (pfile) {
    out.push({
      source_id: 'S0',
      name: pfile,
      kind: 'statement',
      status: primary['read'] === true ? 'read' : mapSourceStatus(s(primary['read'])),
      notes: typeof primary['bytes'] === 'number' ? primary['bytes'] + ' 字节' : '',
    })
  }
  for (const x of arr(pick(raw, 'source_files'))) {
    const o = rec(x)
    const p = pickStr(o, 'path')
    if (!p) continue
    const role = pickStr(o, 'role')
    const base = p.split(/[\\/]/).pop() || p
    out.push({
      source_id: `SF${out.length}`,
      name: role ? role + '：' + base : base,
      kind: 'material',
      status: o['readable'] === true ? 'read' : mapSourceStatus(s(o['readable'])),
      notes: pickStr(o, 'note'),
    })
  }

  const table = findTable(tables, ['材料清单', '输入盘点'], '材料')
  if (table) {
    const cName = colOf(table.headers, '材料', '文件')
    const cRead = colOf(table.headers, '是否读取', '读取')
    const cState = colOf(table.headers, '实测状态', '状态')
    const cNote = colOf(table.headers, '影响', '处置')
    let i = 0
    for (const row of table.rows) {
      const name = cellOf(row, cName)
      if (!name) continue
      i += 1
      out.push({
        source_id: `S${i}`,
        name,
        kind: 'material',
        status: mapSourceStatus(cellOf(row, cRead) + ' ' + cellOf(row, cState)),
        notes: [cellOf(row, cState), cellOf(row, cNote)].filter((x) => !!x).join('　'),
      })
    }
    if (i) return out
  }

  for (const x of arr(pick(raw, 'attachment_status'))) {
    const o = rec(x)
    const name = pickStr(o, 'name', 'attachment_id')
    if (!name) continue
    const st = pickStr(o, 'status')
    const declared = pickStr(o, 'declared_content')
    out.push({
      source_id: pickStr(o, 'attachment_id') || `A${out.length + 1}`,
      name,
      kind: 'attachment',
      status: mapSourceStatus(st),
      notes: [st, declared].filter((y) => !!y).join('　'),
    })
  }
  for (const [k, v] of Object.entries(rec(pick(raw, 'attachments_status')))) {
    const text = s(v)
    if (!text) continue
    out.push({
      source_id: `A${out.length + 1}`,
      name: k.replace(/^att\d+_/, '').replace(/_/g, ' '),
      kind: 'attachment',
      status: mapSourceStatus(text),
      notes: text,
    })
  }
  return out
}

/** 假设表（报告里的）→ contract.assumptions */
function assumptionsOf(tables: MdTable[]): ContractAssumption[] {
  const table = findTable(tables, ['假设'], 'ID')
  if (!table) return []
  const cId = colOf(table.headers, 'ID', '编号')
  const cText = colOf(table.headers, '内容', '假设')
  const cBasis = colOf(table.headers, '依据')
  const cImpact = colOf(table.headers, '影响')
  const cStatus = colOf(table.headers, '状态')
  const out: ContractAssumption[] = []
  for (const row of table.rows) {
    const id = cellOf(row, cId)
    const statement = cellOf(row, cText)
    if (!id || !statement) continue
    const st = cellOf(row, cStatus)
    out.push({
      assumption_id: id,
      statement,
      rationale: cellOf(row, cBasis),
      scope: '',
      potential_impact: cellOf(row, cImpact),
      status: /REJECTED/i.test(st) ? 'rejected' : /CONFIRMED/i.test(st) ? 'confirmed' : 'proposed',
    })
  }
  return out
}

/** 符号表（可能分散在多个子小节）→ contract.symbols */
function symbolsOf(tables: MdTable[]): ContractSymbol[] {
  const out: ContractSymbol[] = []
  const roleOf = (where: string): ContractSymbol['role'] => {
    if (/决策/.test(where)) return 'decision'
    if (/状态/.test(where)) return 'state'
    if (/参数|集合|索引/.test(where)) return 'parameter'
    if (/输入/.test(where)) return 'input'
    if (/指标/.test(where)) return 'metric'
    return 'unknown'
  }
  for (const t of tables) {
    if (!/符号/.test(t.top + ' ' + t.sub)) continue
    const cId = colOf(t.headers, 'symbol_id', 'ID')
    const cSym = colOf(t.headers, '符号')
    const cName = colOf(t.headers, '含义', '名称')
    const cUnit = colOf(t.headers, '单位')
    const cSrc = colOf(t.headers, '来源')
    const cSt = colOf(t.headers, '状态')
    if (cSym < 0 || cName < 0) continue
    for (const row of t.rows) {
      const sym = cellOf(row, cSym)
      const name = cellOf(row, cName)
      if (!sym || !name) continue
      const unit = cellOf(row, cUnit)
      out.push({
        symbol_id: cellOf(row, cId) || sym,
        symbol: sym,
        name,
        role: roleOf(t.top + ' ' + t.sub),
        definition: name,
        unit: !unit || unit === '—' || unit === '-' ? null : unit,
        source_refs: cellOf(row, cSrc) ? [cellOf(row, cSrc)] : [],
        status: cellOf(row, cSt),
      })
    }
  }
  return out
}

function dependenciesOf(raw: Record<string, unknown>): ContractDependency[] {
  const out: ContractDependency[] = []
  for (const x of arr(pick(raw, 'dependencies'))) {
    const o = rec(x)
    const from = pickStr(o, 'from_question_id', 'from', 'qid')
    const to = pickStr(o, 'to_question_id', 'to')
    if (!from || !to) continue
    out.push({
      from_question_id: from,
      to_question_id: to,
      dependency_type: pickStr(o, 'dependency_type', 'type'),
      rationale: pickStr(o, 'basis', 'rationale', 'note'),
    })
  }
  return out
}

function convertContract(raw: Record<string, unknown>, report: string): ProblemContract {
  const tables = mdTables(report)
  const questions = arr(pick(raw, 'questions')).map(rec).filter((q) => !!pickStr(q, 'question_id', 'qid', 'id'))

  const oos = arr(pick(raw, 'out_of_scope_questions')).map(rec)
  const maxHint = oos.reduce((m, q) => Math.max(m, qidIndex(pickStr(q, 'question_id', 'qid'))), 0)
  const entries = questions.map((q, i) => {
    const qid = qidOf(q, i + 1)
    return { idx: qidIndex(qid), value: toTask(q, qid) }
  })
  const tasks = padByQid(entries, (n) => placeholderTask(`q0${n}`), maxHint)

  return {
    schema_version: '1.0',
    case_id: pickStr(raw, 'case_id'),
    revision: pickStr(raw, 'document_version', 'analysis_version') || 'v1.0',
    sources: sourcesOf(raw, tables),
    /* 真实产物没有「研究对象」这一栏，标题就是一句话概括（含赛事与题名） */
    research_object: { description: pickStr(raw, 'title', 'problem_title'), background: '' },
    facts: factsOf(raw, questions),
    tasks,
    dependencies: dependenciesOf(raw),
    assumptions: assumptionsOf(tables),
    unknowns: unknownsOf(raw, questions),
    symbols: symbolsOf(tables),
  }
}

/* ── solution_map：真实形状 → 站点形状 ──────────────────────────────────── */

function toCandidate(c: Record<string, unknown>): CandidateModel {
  const role = pickStr(c, 'role', 'kind')
  const objective = pickStr(c, 'objective')
  const form = pickStr(c, 'form')
  const core = pickStr(c, 'core_relations_text', 'core_relation')
  const solver = arr(pick(c, 'solver_suggestion'))
    .map(rec)
    .find((x) => !!pickStr(x, 'tool'))
  const solverText = solver ? [pickStr(solver, 'tool'), pickStr(solver, 'reason')].filter((x) => !!x).join('　理由：') : ''
  const minimal = c['minimal_viable'] === true ? '最小可行主路线' : ''
  const rationale = [role, minimal, objective, solverText ? '求解器：' + solverText : '', core]
    .filter((x) => !!x)
    .join('　')
  return {
    model_id: pickStr(c, 'model_id', 'id') || pickStr(c, 'name'),
    name: pickStr(c, 'name') || pickStr(c, 'model_id'),
    rationale,
    data_needs: strList(pick(c, 'required_data')),
    advantages: pickList(c, 'why_minimal_feasible'),
    limitations: pickList(c, 'applicability_limits', 'limitations'),
    applicability: role || form,
  }
}

function metricsOf(v: unknown): string[] {
  const o = rec(v)
  const out: string[] = []
  for (const key of ['primary', 'secondary', 'consistency', 'comparison']) {
    out.push(...strList(o[key]))
  }
  return out.length ? out : strList(v)
}

function risksOf(q: Record<string, unknown>, c0: Record<string, unknown>): string[] {
  const out = strList(pick(c0, 'risks'))
  out.push(...strList(pick(q, 'risks')))
  return out
}

function toRoute(q: Record<string, unknown>, fallbackQid: string): SolutionRoute {
  const cands = arr(pick(q, 'candidates', 'model_candidates')).map(rec)
  const c0 = cands[0] ?? {}
  const rec0 = rec(pick(q, 'recommendation'))
  const mechanism = [
    pickStr(q, 'model_form_judgement', 'task_type'),
    pickStr(q, 'incremental_mechanism', 'mechanism'),
    pickStr(c0, 'core_relations_text', 'core_relation'),
  ]
    .filter((x) => !!x)
    .join('　')
  const selected = pickStr(rec0, 'selected_model_id') || pickStr(q, 'recommended_primary')
  const reason = pickList(rec0, 'reasons').join('；') || pickStr(q, 'recommendation_rationale')
  const statusForm = pickStr(rec0, 'selected_form')

  return {
    question_id: fallbackQid,
    objective: pickStr(q, 'task_summary', 'title', 'objective'),
    mechanism,
    candidate_models: cands.map(toCandidate),
    recommended_route: selected ? { model_id: selected, reason, status: statusForm } : null,
    alternatives: pickList(rec0, 'secondary').concat(pickList(q, 'alternatives')),
    required_inputs: strList(pick(c0, 'required_data')),
    parameters: [],
    decision_variables: strList(pick(c0, 'decision_variables')),
    metrics: metricsOf(pick(q, 'evaluation_metrics', 'metrics_plan')),
    conditions: {},
    data_preprocessing: [],
    solving_steps: strList(pick(c0, 'solving_steps')),
    validation_plan: strList(pick(c0, 'verification_plan', 'acceptance_checks')),
    risks: risksOf(q, c0),
  }
}

function convertMap(raw: Record<string, unknown>): SolutionMap {
  const questions = arr(pick(raw, 'questions')).map(rec).filter((q) => !!pickStr(q, 'question_id', 'qid', 'id'))
  const deps = arr(pick(raw, 'inter_question_dependencies', 'dependency_graph')).map(rec)
  const maxHint = deps.reduce((m, d) => Math.max(m, qidIndex(pickStr(d, 'from', 'from_question_id')), qidIndex(pickStr(d, 'to', 'to_question_id'))), 0)
  const entries = questions.map((q, i) => {
    const qid = qidOf(q, i + 1)
    return { idx: qidIndex(qid), value: toRoute(q, qid) }
  })
  const routes = padByQid(entries, (n) => placeholderRoute(`q0${n}`), maxHint)
  return { schema_version: '1.0', case_id: pickStr(raw, 'case_id'), routes }
}

/* ── analysis_result：真实形状 → 站点形状 ───────────────────────────────── */

function findArtifactPath(raw: Record<string, unknown>, tail: string): string {
  const rels = pickList(raw, 'artifacts_relative_to_session')
  const pool = rels.length ? rels : pickList(raw, 'artifacts')
  return pool.find((p) => normPath(p).endsWith(tail)) ?? ''
}

function convertResult(raw: Record<string, unknown>): AnalysisResult {
  const scope = rec(pick(raw, 'scope'))
  const ev = rec(pick(raw, 'evidence_status'))
  const status = pickStr(raw, 'status')
  const ok = status === 'COMPLETED' || status === 'PARTIAL' || status === 'BLOCKED' || status === 'FAILED'

  const opens: string[] = []
  for (const x of arr(pick(raw, 'pending_user_decisions'))) {
    const o = rec(x)
    const topic = pickStr(o, 'topic', 'question', 'description')
    if (!topic) continue
    const sev = pickStr(o, 'severity')
    opens.push([pickStr(o, 'id'), topic + (sev ? '（' + sev + '）' : '')].filter((y) => !!y).join(' '))
  }

  const limits: string[] = []
  for (const x of pickList(scope, 'not_done')) limits.push('本轮未做：' + x)
  const oosStatus = pickStr(scope, 'out_of_scope_status')
  if (oosStatus) limits.push(oosStatus)
  const computed = pickStr(ev, 'computed_results')
  if (computed) limits.push(computed)

  return {
    schema_version: '1.0',
    case_id: pickStr(raw, 'case_id'),
    status: ok ? (status as AnalysisResult['status']) : 'PARTIAL',
    source_inventory: [],
    problem_contract: {
      path: findArtifactPath(raw, 'truth/problem_contract.json'),
      revision: pickStr(raw, 'document_version', 'analysis_version') || 'v1.0',
    },
    solution_map: { path: findArtifactPath(raw, 'truth/solution_map.json') },
    data_assessment: {
      available: pickList(ev, 'constants_proved_from_statement'),
      missing: pickList(raw, 'blocking_items_for_computation', 'unknowns_blocking_S3'),
      recommendations: [],
    },
    evaluation_plan: [],
    open_questions: opens,
    handoff: {},
    limitations: limits,
  }
}

/* ── 对外接口 ───────────────────────────────────────────────────────────── */

export function normalizeContract(raw: unknown, report = ''): ProblemContract | null {
  if (!raw) return null
  if (isSiteContract(raw)) return raw as ProblemContract
  const o = rec(raw)
  if (!pickStr(o, 'case_id') && !arr(pick(o, 'questions')).length) return null
  return convertContract(o, report)
}

export function normalizeSolutionMap(raw: unknown): SolutionMap | null {
  if (!raw) return null
  if (isSiteMap(raw)) return raw as SolutionMap
  const o = rec(raw)
  if (!pickStr(o, 'case_id') && !arr(pick(o, 'questions')).length) return null
  return convertMap(o)
}

export function normalizeResult(raw: unknown): AnalysisResult | null {
  if (!raw) return null
  const o = rec(raw)
  /* 站点形状：source_inventory 是数组、data_assessment 是对象 —— 直接透传 */
  if (Array.isArray(o['source_inventory']) || isRecord(o['data_assessment'])) {
    return raw as AnalysisResult
  }
  if (!pickStr(o, 'case_id') && !pickStr(o, 'status')) return null
  return convertResult(o)
}
