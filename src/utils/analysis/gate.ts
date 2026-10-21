/**
 * 闸门（HITL）表单的归一化
 *
 * 平台侧两个 external 工具会挂起 run 等用户填表：
 *   `ui_form_advanced` —— 产品级表单，`pages[].items[]`，7 种 kind
 *   `ui_gate`          —— 人工审批，同一套 pages 协议，字段固定为 decision + feedback
 *   （旧的 `ui_form` 走 JSON Schema，这里也认，免得换版本就白屏）
 *
 * 为什么单独一个文件：渲染那一层是 core.ts 的桥接段（纯 JS、不在 Vue 里），
 * 它只该拿一份「字段是什么、怎么画」的平铺描述，不该自己解析平台的两套表单协议。
 * 解析放这里（有类型、可单测），渲染放桥接层（只认这份描述）—— 协议变了只改这里。
 *
 * 纪律：**认不出来就不要硬画**。拿不到 key 的字段一律丢弃，宁可少一个输入框，
 * 也不能画一个提交后什么都不带的空控件（那会让智能体收到一份缺字段的答复）。
 */

import type { AguiInterrupt } from '@/types/agui'

/** 用户提交的字段值（key → 值；多选是数组） */
export type GateValues = Record<string, unknown>

/**
 * 一次闸门提交（或取消）的回执。
 *
 * 为什么非要有它：提交是**异步**的（要发 run、要读流），而卡片在 core 那边只有
 * 同步的「点了」这一个信号。没有回执就只能先定格成「已提交、失败也不管」——
 * 2026-10-16 实测的故障正是这个形状：前端一个请求都没发出去（重建闸门拿不到
 * runId 就提前 bail），卡片却写着「已提交，智能体继续处理…」，用户和排查的人
 * 都看不到失败。
 *
 * 语义边界：**只表示「后端受理了这次提交」**，不表示整条链路跑完 —— 受理之后
 * 收产物可能还要十几分钟，那不归提交回执管（卡片该随即定格）。
 */
export interface GateSubmitResult {
  ok: boolean
  /** 失败原因（人话，直接摊在卡片上）；ok 时为空串 */
  reason: string
}

export type GateKind = 'text' | 'textarea' | 'number' | 'select' | 'radio-group' | 'checkbox-group'

export interface GateField {
  key: string
  label: string
  kind: GateKind
  /** 下拉 / 单选 / 多选的候选（other-text 的「其他」选项由渲染层补） */
  options: string[]
  required: boolean
  placeholder: string
  initialValue?: unknown
  min?: number
  max?: number
  step?: number
}

/** 一张挂起的闸门 —— 渲染层据此画卡片，提交后按 (threadId, runId) 续跑 */
export interface GateSpec {
  interruptId: string
  toolName: string
  title: string
  message: string
  submitLabel: string
  fields: GateField[]
}

/** 认得的 kind → 站点侧控件。`other-text` 归到单选（「其他」由渲染层补一个输入框） */
const KIND_ALIAS: Record<string, GateKind> = {
  text: 'text',
  'other-text': 'radio-group',
  textarea: 'textarea',
  number: 'number',
  select: 'select',
  'radio-group': 'radio-group',
  'checkbox-group': 'checkbox-group',
}

function asObject(raw: unknown): Record<string, unknown> | null {
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) return raw as Record<string, unknown>
  if (typeof raw === 'string') {
    try {
      const parsed = JSON.parse(raw) as unknown
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed as Record<string, unknown>
    } catch {
      /* 后端只给了提示文本时不算错 —— 上层会降级成一张「仅确认」的卡 */
    }
  }
  return null
}

function num(v: unknown): number | undefined {
  return typeof v === 'number' && Number.isFinite(v) ? v : undefined
}

/** 一个 `pages[].items[]` 项 → 字段描述；缺 key 的一律丢弃 */
function itemField(raw: unknown): GateField | null {
  const o = asObject(raw)
  if (!o) return null
  const key = typeof o.key === 'string' ? o.key.trim() : ''
  if (!key) return null
  let kind: GateKind = KIND_ALIAS[String(o.kind ?? '')] ?? 'text'
  const options = Array.isArray(o.options) ? o.options.map((x) => String(x)) : []
  /* 没有候选的「选择类」控件退化成单行输入 —— 画一个空下拉等于让用户没得选 */
  if (!options.length && (kind === 'select' || kind === 'radio-group' || kind === 'checkbox-group')) kind = 'text'
  return {
    key,
    label: typeof o.label === 'string' && o.label.trim() ? o.label : key,
    kind,
    options,
    required: o.required === true,
    placeholder: typeof o.placeholder === 'string' ? o.placeholder : '',
    initialValue: o.initialValue,
    min: num(o.min),
    max: num(o.max),
    step: num(o.step),
  }
}

/** 旧的 `ui_form`：JSON Schema → 同一套字段描述 */
function schemaFields(input: Record<string, unknown>): GateField[] {
  const schema = asObject(input.schema ?? input.inputSchema)
  const properties = asObject(schema?.properties)
  if (!properties) return []
  const required = new Set(
    Array.isArray(schema?.required) ? (schema?.required as unknown[]).filter((v): v is string => typeof v === 'string') : [],
  )
  const out: GateField[] = []
  for (const [key, raw] of Object.entries(properties)) {
    const prop = asObject(raw)
    if (!prop) continue
    const enumValues = Array.isArray(prop.enum) ? prop.enum.map((x) => String(x)) : []
    const items = asObject(prop.items)
    const itemEnum = items && Array.isArray(items.enum) ? items.enum.map((x) => String(x)) : []
    const type = typeof prop.type === 'string' ? prop.type : ''
    let kind: GateKind = 'text'
    if (enumValues.length) kind = 'select'
    else if (type === 'array' && itemEnum.length) kind = 'checkbox-group'
    else if (type === 'number' || type === 'integer') kind = 'number'
    else if (type === 'string' && prop.format === 'textarea') kind = 'textarea'
    else if (type === 'boolean') kind = 'radio-group'
    out.push({
      key,
      label: typeof prop.title === 'string' && prop.title ? prop.title : key,
      kind,
      options: enumValues.length ? enumValues : itemEnum.length ? itemEnum : type === 'boolean' ? ['true', 'false'] : [],
      required: required.has(key),
      placeholder: typeof prop.description === 'string' ? prop.description : '',
      initialValue: prop.default,
      min: num(prop.minimum),
      max: num(prop.maximum),
      step: num(prop.multipleOf),
    })
  }
  return out
}

/** `ui_gate` 的兜底：审批决策 + 意见。模型没给 pages 时按平台约定补出来 */
function gateDefaults(): GateField[] {
  return [
    {
      key: 'decision',
      label: '审核结论',
      kind: 'radio-group',
      options: ['通过', '不通过'],
      required: true,
      placeholder: '',
    },
    { key: 'feedback', label: '审核意见', kind: 'textarea', options: [], required: false, placeholder: '' },
  ]
}

/**
 * 把一次中断归一化成可渲染的闸门；认不出来（没有 id / 没有可用字段）返回 null。
 *
 * 返回 null 时调用方**不能**假装闸门已处理 —— 应当把 run 按失败收尾并说明原因，
 * 否则那条 run 会永远挂着，对话区一直转圈。
 */
export function gateFromInterrupts(list: AguiInterrupt[]): GateSpec | null {
  const first = Array.isArray(list) ? list.find((x) => x && typeof x === 'object') : null
  if (!first) return null
  const interruptId = (first.id || first.interruptId || '').trim()
  if (!interruptId) return null

  const toolName = typeof first.metadata?.toolName === 'string' ? first.metadata.toolName : ''
  const input = asObject(first.metadata?.toolInput ?? first.metadata?.toolContent) ?? {}

  const fields: GateField[] = []
  const pages = input.pages
  if (Array.isArray(pages)) {
    for (const page of pages) {
      const items = asObject(page)?.items
      if (!Array.isArray(items)) continue
      for (const item of items) {
        const f = itemField(item)
        if (f) fields.push(f)
      }
    }
  }
  if (!fields.length) fields.push(...schemaFields(input))
  if (!fields.length && /gate/i.test(toolName)) fields.push(...gateDefaults())
  if (!fields.length) return null

  const title = typeof input.title === 'string' && input.title.trim()
    ? input.title
    : toolName === 'ui_gate'
      ? '智能体请求确认'
      : '智能体需要你确认'
  const message = typeof first.message === 'string' && first.message.trim()
    ? first.message
    : typeof input.description === 'string'
      ? input.description
      : ''
  const submitLabel = typeof input.submitLabel === 'string' && input.submitLabel.trim()
    ? input.submitLabel
    : toolName === 'ui_gate'
      ? '提交审批'
      : '提交'

  return { interruptId, toolName, title, message, submitLabel, fields }
}

/** 提交载荷：平台按 `{ output: JSON 字符串 }` 回传成工具结果 */
export function gatePayload(values: GateValues): { output: string } {
  return { output: JSON.stringify(values ?? {}) }
}
