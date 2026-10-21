/**
 * 智能体产物 → 产物抽屉里可直接读的 HTML
 *
 * 对话区那条通路已改走**实时事件流**（`utils/analysis/live/*`）与**产物卡**
 * （`utils/analysis/artifact/*`），所以这里只剩一件事：把 `AnalysisOutcome`
 * 渲染成抽屉里那份可留档的完整文档（`renderLiveDoc`），外加它要的几个小工具。
 *
 * 两条纪律：
 *   1. 智能体给的每一个字符串都要 esc() —— 里面可能带 `<` 或 `&`。
 *   2. 缺字段时不编，就地省略该小节；不新增任何结论、不补数字、
 *      不把「候选路线」写成「已求解」。
 */

import type {
  AnalysisOutcome,
  ContractTask,
  SolutionRoute,
  ProblemContract,
} from '@/types/analysis'

export function esc(s: unknown): string {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function li(label: string, body: string): string {
  const l = label ? '<b>' + esc(label) + '</b>' : ''
  const sep = label && body ? '：' : ''
  return '<li>' + l + sep + body + '</li>'
}

/**
 * 拼「题目动词 + 目标」。
 *
 * 契约里 task_verb 是「建立／提出」这类动词，objective 是一句话；
 * 但示例里 objective 已经自带动词（「提出总运输成本较低的配置方案」），
 * 直接拼会得到「提出提出…」。所以已经带了就不再加。
 */
function ul(items: string[]): string {
  return items.length ? '<ul>' + items.join('') + '</ul>' : ''
}

function para(text: unknown, cls = ''): string {
  const t = String(text ?? '').trim()
  if (!t) return ''
  return '<p' + (cls ? ' class="' + cls + '"' : '') + '>' + esc(t) + '</p>'
}

/** 产物里的数组元素形态很杂（字符串 / {name,unit} / {statement} / {symbol,name}），统一成一行文字 */
export function fmtItem(v: unknown): string {
  if (v == null) return ''
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  if (typeof v !== 'object') return String(v)
  const o = v as Record<string, unknown>
  const pick = (k: string): string => (typeof o[k] === 'string' ? (o[k] as string) : '')
  const unit = pick('unit') ? '（' + pick('unit') + '）' : ''
  if (pick('statement')) return pick('statement') + unit
  if (pick('name')) return pick('name') + unit
  if (pick('symbol')) return pick('symbol') + '：' + pick('name') + unit
  if (pick('description')) return pick('description') + unit
  if (pick('objective')) return pick('objective')
  if (pick('rationale')) return pick('rationale')
  try {
    return JSON.stringify(v)
  } catch {
    return ''
  }
}

export function fmtList(v: unknown): string[] {
  if (!Array.isArray(v)) return v == null ? [] : [fmtItem(v)]
  return v.map(fmtItem).filter((s) => !!s)
}

export function orderedTasks(contract: ProblemContract | null): ContractTask[] {
  const tasks = contract?.tasks ?? []
  return tasks.slice().sort((a, b) => String(a.question_id).localeCompare(String(b.question_id)))
}

export function orderedRoutes(map: { routes?: SolutionRoute[] } | null): SolutionRoute[] {
  const routes = map?.routes ?? []
  return routes.slice().sort((a, b) => String(a.question_id).localeCompare(String(b.question_id)))
}

/**
 * 第 qi 问（0 基）对应的任务与路线。
 *
 * 靠**位置**对齐（q01 对第 0 问），因为原型的四问没有 question_id 这套 id；
 * 数量不一致时宁可返回 null（回落内置剧本），也不硬把一个问题的内容贴到另一问上。
 */
export function locate(
  contract: ProblemContract | null,
  map: { routes?: SolutionRoute[] } | null,
  qi: number,
): { task: ContractTask | null; route: SolutionRoute | null; size: number } {
  const tasks = orderedTasks(contract)
  const routes = orderedRoutes(map)
  const size = Math.max(tasks.length, routes.length)
  if (size === 0 || qi >= size) return { task: null, route: null, size }
  return { task: tasks[qi] ?? null, route: routes[qi] ?? null, size }
}

/* ── 全局分析 ───────────────────────────────────────────────────────────── */

function refs(list: unknown): string {
  const arr = fmtList(list)
  return arr.length ? '　<span class="mono">来源 ' + esc(arr.join('、')) + '</span>' : ''
}

/* ── 单问分析 ───────────────────────────────────────────────────────────── */

export function renderLiveDoc(o: AnalysisOutcome): string {
  const head =
    '<div class="card"><div class="card-h">' +
    '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#12A594" stroke-width="2" stroke-linecap="round"><path d="M9 18h6M10 22h4"/><path d="M12 2a7 7 0 0 0-4 12.7V17h8v-2.3A7 7 0 0 0 12 2z"/></svg>' +
    '<h3>题目分析 · 智能体生成</h3><div class="grow"></div>' +
    '<span class="tag-c" style="font-size:11px">' + esc(o.status) + '</span></div><div class="card-b">'

  const files = o.artifacts.length
    ? '<div class="doc" style="padding:0"><h3>产物文件</h3><ul>' +
      o.artifacts.map((p) => '<li><span class="mono">' + esc(p) + '</span></li>').join('') +
      '</ul></div>'
    : '<div class="doc" style="padding:0"><p>本次没有读到产物文件，只有对话输出。</p></div>'

  return head + liveGlobalBody(o) + liveAllQuestions(o) + files + '</div></div>'
}

/** 四个问题逐节展开；有几问渲染几问（取契约与路线图的较大值） */
function liveAllQuestions(o: AnalysisOutcome): string {
  const n = Math.max(orderedTasks(o.contract).length, o.solutionMap?.routes?.length ?? 0)
  const parts: string[] = []
  for (let qi = 0; qi < n; qi += 1) {
    const { task } = locate(o.contract, o.solutionMap, qi)
    const body = liveQuestionBody(o, qi)
    /* liveQuestionBody 自带「q01 目标」标题；缺目标时补一个，免得两问粘在一起 */
    parts.push(task ? body : '<div class="doc" style="padding:0"><h3>问题' + cnNum(qi) + '</h3></div>' + body)
  }
  return parts.join('')
}

function cnNum(i: number): string {
  return ['一', '二', '三', '四', '五'][i] ?? String(i + 1)
}

function liveGlobalBody(o: AnalysisOutcome): string {
  const c = o.contract
  const parts: string[] = ['<div class="doc" style="padding:0">']
  if (c?.research_object?.description) parts.push('<h3>研究对象</h3>' + para(c.research_object.description))
  const facts = (c?.facts ?? []).filter((f) => f.evidence_type === 'explicit_fact')
  if (facts.length) parts.push('<h3>题面事实</h3>' + ul(facts.map((f) => li(f.item_id, esc(f.statement) + refs(f.source_refs)))))
  const tasks = orderedTasks(c)
  if (tasks.length) {
    parts.push(
      '<h3>子问题</h3>' +
        ul(tasks.map((t) => li(t.question_id + ' ' + t.objective, esc(t.task_verb)))),
    )
  }
  const syms = c?.symbols ?? []
  if (syms.length) {
    parts.push(
      '<h3>符号</h3>' +
        ul(syms.map((s) => li(s.symbol, esc(s.name) + (s.unit ? '（' + esc(s.unit) + '）' : '')))),
    )
  }
  parts.push('</div>')
  return parts.join('')
}

function liveQuestionBody(o: AnalysisOutcome, qi: number): string {
  const { task, route } = locate(o.contract, o.solutionMap, qi)
  const parts: string[] = ['<div class="doc" style="padding:0">']
  if (task) {
    parts.push('<h3>' + esc(task.question_id) + '　' + esc(task.objective) + '</h3>')
    parts.push(para(task.task_verb))
  }
  if (route?.mechanism) parts.push('<h3>机理</h3>' + para(route.mechanism))
  const models = route?.candidate_models ?? []
  if (models.length) {
    parts.push(
      '<h3>候选模型</h3>' +
        ul(models.map((m) => li(m.model_id + ' ' + m.name, esc(m.rationale)))),
    )
  }
  const steps = fmtList(route?.solving_steps)
  if (steps.length) parts.push('<h3>求解步骤</h3>' + ul(steps.map((s, i) => li(String(i + 1), esc(s)))))
  const valid = fmtList(route?.validation_plan)
  if (valid.length) parts.push('<h3>验证计划（未执行）</h3>' + ul(valid.map((s) => li('', esc(s)))))
  parts.push('</div>')
  return parts.join('')
}
