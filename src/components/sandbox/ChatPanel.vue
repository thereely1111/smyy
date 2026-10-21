<template>
  <section class="panel chat">
    <div class="p-head">
      <span>对话</span>
      <span class="count">{{ sandbox.messages.length }}</span>
    </div>

    <div ref="scroller" class="p-body" @scroll.passive="onScroll">
      <div v-if="!sandbox.messages.length" class="empty-hint">
        在下面描述你要做的事，或直接贴一段代码让智能体运行。
      </div>

      <div v-for="m in sandbox.messages" :key="m.id" class="msg" :class="m.role">
        <div class="who">{{ m.role === 'user' ? '我' : '智能体' }}</div>
        <div class="bubble">{{ m.text || '…' }}</div>
      </div>

      <div v-if="sandbox.state === 'running'" class="msg assistant">
        <div class="who">智能体</div>
        <div class="bubble typing"><i /><i /><i /></div>
      </div>

      <div v-for="intr in sandbox.interrupts" :key="interruptId(intr)" class="hitl-card">
        <div class="hitl-title">{{ toolName(intr) }}</div>
        <div v-if="intr.message" class="hitl-message">{{ intr.message }}</div>
        <div v-for="item in fields(intr)" :key="item.key" class="hitl-field">
          <label :id="fieldId(intr, item.key, 'label')" :for="item.kind === 'radio-group' || item.kind === 'checkbox-group' ? undefined : fieldId(intr, item.key)">
            {{ item.label || item.key }}<span v-if="item.required" class="required">*</span>
          </label>

          <div v-if="item.kind === 'radio-group'" class="choices" role="group" :aria-labelledby="fieldId(intr, item.key, 'label')">
            <label v-for="option in item.options || []" :key="String(option)" class="choice">
              <input
                type="radio"
                :name="`${interruptId(intr)}-${item.key}`"
                :checked="valueOf(intr, item.key) === option"
                @change="setValue(intr, item.key, option)"
              />
              <span>{{ option }}</span>
            </label>
          </div>
          <div v-else-if="item.kind === 'checkbox-group'" class="choices" role="group" :aria-labelledby="fieldId(intr, item.key, 'label')">
            <label v-for="option in item.options || []" :key="String(option)" class="choice">
              <input
                type="checkbox"
                :checked="checked(intr, item.key, option)"
                @change="toggleValue(intr, item.key, option)"
              />
              <span>{{ option }}</span>
            </label>
          </div>
          <select v-else-if="item.kind === 'select'" :id="fieldId(intr, item.key)" :value="String(valueOf(intr, item.key) ?? '')" @change="setFromEvent(intr, item.key, $event)">
            <option value="">请选择</option>
            <option v-for="option in item.options || []" :key="String(option)" :value="String(option)">{{ option }}</option>
          </select>
          <textarea
            v-else-if="item.kind === 'textarea'"
            :id="fieldId(intr, item.key)"
            :value="String(valueOf(intr, item.key) ?? '')"
            :placeholder="item.placeholder"
            rows="3"
            @input="setFromEvent(intr, item.key, $event)"
          />
          <input
            v-else
            :id="fieldId(intr, item.key)"
            :type="item.kind === 'number' ? 'number' : 'text'"
            :value="String(valueOf(intr, item.key) ?? '')"
            :placeholder="item.placeholder"
            :min="item.min"
            :max="item.max"
            :step="item.step"
            @input="setFromEvent(intr, item.key, $event, item.kind === 'number')"
          />
        </div>
        <div class="hitl-actions">
          <button class="btn" :disabled="sandbox.state === 'running'" @click="cancelInterrupt(intr)">取消</button>
          <button class="btn primary" :disabled="sandbox.state === 'running' || !valid(intr)" @click="submitInterrupt(intr)">
            {{ submitLabel(intr) }}
          </button>
        </div>
      </div>

      <div v-if="sandbox.approvalNotice" class="hitl-message" role="status">{{ sandbox.approvalNotice }}</div>
      <div v-if="sandbox.progressText" class="hitl-message" role="status">{{ sandbox.progressText }}</div>
      <div v-if="sandbox.syncError" class="err" role="alert">{{ sandbox.syncError }}</div>
      <div v-if="sandbox.runError" class="err" role="alert">{{ sandbox.runError }}</div>
    </div>

    <Transition name="new-message">
      <button v-if="showNewMessages" type="button" class="new-messages" @click="jumpToLatest">
        查看新消息 <ArrowDown aria-hidden="true" />
      </button>
    </Transition>

    <div class="p-foot">
      <textarea
        v-model="draft"
        class="input"
        rows="3"
        placeholder="例如：把附件 1 读进来，按 144 段做数据清洗"
        :disabled="sandbox.interrupts.length > 0"
        @keydown.enter.exact.prevent="submit"
      />
      <div class="actions">
        <span class="tip">Enter 发送 · Shift+Enter 换行<span v-if="sandbox.interrupts.length"> · 请先处理上方确认</span></span>
        <button v-if="sandbox.state === 'running'" class="btn" @click="sandbox.stop()">停止</button>
        <button class="btn primary" :disabled="!canSend" @click="submit">发送</button>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ArrowDown } from '@element-plus/icons-vue'
import { useProblemSessionStore } from '@/stores/problemSession'
import { useSandboxStore } from '@/stores/sandbox'
import type { AguiInterrupt } from '@/types/agui'

interface HitlItem {
  key: string
  label?: string
  kind?: string
  options?: unknown[]
  required?: boolean
  placeholder?: string
  initialValue?: unknown
  min?: number
  max?: number
  step?: number
}

const props = withDefaults(defineProps<{ prefill?: string }>(), { prefill: '' })
const sandbox = useSandboxStore()
const ps = useProblemSessionStore()
const draft = ref(props.prefill)
const scroller = ref<HTMLElement | null>(null)
const showNewMessages = ref(false)
const hitlDrafts = ref<Record<string, Record<string, unknown>>>({})

watch(() => props.prefill, (v) => { if (v) draft.value = v })
const canSend = computed(() => draft.value.trim().length > 0 && sandbox.state !== 'running' && sandbox.interrupts.length === 0)

function interruptId(intr: AguiInterrupt): string { return intr.id || intr.interruptId || '' }
function fieldId(intr: AguiInterrupt, key: string, suffix = 'input'): string {
  return `hitl-${encodeURIComponent(interruptId(intr))}-${encodeURIComponent(key)}-${suffix}`
}
function toolName(intr: AguiInterrupt): string {
  const name = intr.metadata?.toolName
  return typeof name === 'string' && name ? name : '智能体需要确认'
}

function toolInput(intr: AguiInterrupt): Record<string, unknown> {
  const raw = intr.metadata?.toolInput ?? intr.metadata?.toolContent
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) return raw as Record<string, unknown>
  if (typeof raw === 'string') {
    try {
      const parsed = JSON.parse(raw) as unknown
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed as Record<string, unknown>
    } catch { /* 后端只给了提示文本时，仍显示确认卡 */ }
  }
  return {}
}

function fields(intr: AguiInterrupt): HitlItem[] {
  const input = toolInput(intr)
  const pages = input.pages
  if (Array.isArray(pages)) {
    return pages.flatMap((page) => {
      if (!page || typeof page !== 'object') return []
      const items = (page as Record<string, unknown>).items
      return Array.isArray(items) ? items.filter((item): item is HitlItem => Boolean(item && typeof item === 'object' && typeof (item as Record<string, unknown>).key === 'string')) : []
    })
  }

  // 基础 ui_form 传的是 JSON Schema，而不是 ui_form_advanced 的 pages。
  // 这里把常用 schema 类型转换成同一套轻量控件，保证基础表单也能 resume。
  const rawSchema = input.schema ?? input.inputSchema
  const schema = parseObject(rawSchema)
  const properties = schema?.properties
  if (!properties || typeof properties !== 'object' || Array.isArray(properties)) return []
  const required = new Set(Array.isArray(schema.required) ? schema.required.filter((v): v is string => typeof v === 'string') : [])
  return Object.entries(properties as Record<string, unknown>).flatMap(([key, raw]) => {
    const prop = parseObject(raw)
    if (!prop) return []
    const enumValues = Array.isArray(prop.enum) ? prop.enum : undefined
    const type = typeof prop.type === 'string' ? prop.type : ''
    const items = parseObject(prop.items)
    const itemEnum = items && Array.isArray(items.enum) ? items.enum : undefined
    const kind = enumValues ? 'select' : type === 'array' && itemEnum ? 'checkbox-group' : type === 'number' || type === 'integer' ? 'number' : type === 'string' && prop.format === 'textarea' ? 'textarea' : type === 'boolean' ? 'radio-group' : 'text'
    const options = enumValues ?? itemEnum ?? (type === 'boolean' ? [true, false] : undefined)
    return [{
      key,
      label: typeof prop.title === 'string' ? prop.title : key,
      kind,
      options,
      required: required.has(key),
      placeholder: typeof prop.description === 'string' ? prop.description : undefined,
      initialValue: prop.default,
      min: typeof prop.minimum === 'number' ? prop.minimum : undefined,
      max: typeof prop.maximum === 'number' ? prop.maximum : undefined,
      step: typeof prop.multipleOf === 'number' ? prop.multipleOf : undefined,
    }]
  })
}

function parseObject(raw: unknown): Record<string, any> | null {
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) return raw as Record<string, any>
  if (typeof raw === 'string') {
    try {
      const parsed = JSON.parse(raw) as unknown
      return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed as Record<string, any> : null
    } catch { /* 非 JSON 的提示文本 */ }
  }
  return null
}
function draftFor(intr: AguiInterrupt): Record<string, unknown> {
  const id = interruptId(intr)
  if (!hitlDrafts.value[id]) {
    const initial: Record<string, unknown> = {}
    for (const item of fields(intr)) if (item.initialValue !== undefined) initial[item.key] = item.initialValue
    hitlDrafts.value[id] = initial
  }
  return hitlDrafts.value[id]
}
function valueOf(intr: AguiInterrupt, key: string): unknown { return draftFor(intr)[key] }
function setValue(intr: AguiInterrupt, key: string, value: unknown): void { draftFor(intr)[key] = value }
function setFromEvent(intr: AguiInterrupt, key: string, event: Event, numberValue = false): void {
  const target = event.target as HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement
  setValue(intr, key, numberValue && target.value !== '' ? Number(target.value) : target.value)
}
function checked(intr: AguiInterrupt, key: string, option: unknown): boolean {
  const value = valueOf(intr, key)
  return Array.isArray(value) && value.includes(option)
}
function toggleValue(intr: AguiInterrupt, key: string, option: unknown): void {
  const current = Array.isArray(valueOf(intr, key)) ? [...(valueOf(intr, key) as unknown[])] : []
  const index = current.indexOf(option)
  if (index >= 0) current.splice(index, 1)
  else current.push(option)
  setValue(intr, key, current)
}
function valid(intr: AguiInterrupt): boolean {
  return fields(intr).every((item) => {
    if (!item.required) return true
    const value = valueOf(intr, item.key)
    return !(value === undefined || value === null || value === '' || (Array.isArray(value) && value.length === 0))
  })
}
function submitLabel(intr: AguiInterrupt): string {
  const label = toolInput(intr).submitLabel
  return typeof label === 'string' && label ? label : toolName(intr) === 'ui_gate' ? '提交审批' : '提交'
}
function submitInterrupt(intr: AguiInterrupt): void {
  const name = toolName(intr)
  const values = draftFor(intr)
  const payload = name === 'ui_gate' || name === 'ui_form' || name === 'ui_form_advanced'
    ? { output: JSON.stringify(values) }
    : { approved: values['decision'] === '通过' }
  void sandbox.resumeInterrupt(interruptId(intr), payload)
}
function cancelInterrupt(intr: AguiInterrupt): void {
  void sandbox.resumeInterrupt(interruptId(intr), { approved: false }, 'cancelled')
}

function submit(): void {
  if (!canSend.value) return
  const text = draft.value
  draft.value = ''
  const runId = ps.beginRun(sandbox.problemId)
  void sandbox.send(text, { threadId: sandbox.sessionId, runId })
}

function nearBottom(el: HTMLElement): boolean {
  return el.scrollHeight - el.scrollTop - el.clientHeight < 64
}

function onScroll(): void {
  if (scroller.value && nearBottom(scroller.value)) showNewMessages.value = false
}

function jumpToLatest(): void {
  if (scroller.value) scroller.value.scrollTop = scroller.value.scrollHeight
  showNewMessages.value = false
}

watch(() => [sandbox.messages.length, sandbox.messages.at(-1)?.text, sandbox.state, sandbox.interrupts.length, sandbox.runError], async () => {
  const el = scroller.value
  if (!el) return
  const follow = nearBottom(el)
  const oldHeight = el.scrollHeight
  await nextTick()
  if (el !== scroller.value) return
  if (follow) jumpToLatest()
  else if (el.scrollHeight > oldHeight) showNewMessages.value = true
})
</script>
<style scoped lang="scss">
.panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--c-surface);
}

.p-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 14px;
  border-bottom: 1px solid var(--c-line);
  font-size: 13px;
  font-weight: 600;
  color: var(--c-ink-2);
  flex-shrink: 0;

  .count {
    padding: 0 6px;
    border-radius: 999px;
    background: var(--c-bg);
    color: var(--c-muted);
    font-size: 11px;
    font-weight: 400;
  }
}

.p-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px 14px;
}

.new-messages {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  align-self: center;
  flex-shrink: 0;
  margin: 4px 0;
  padding: 6px 12px;
  border: 1px solid var(--c-line-strong);
  border-radius: var(--radius-sm);
  background: var(--c-surface);
  color: var(--c-primary-ink);
  font-size: 12px;
  transition: transform 0.16s ease, background 0.16s ease;

  &:hover { background: var(--c-primary-soft); transform: translateY(-1px); }
  &:active { transform: scale(0.98); }
  &:focus-visible { outline: 2px solid var(--c-primary); outline-offset: 2px; }
  svg { width: 14px; height: 14px; }
}

.new-message-enter-active, .new-message-leave-active { transition: opacity 0.16s ease, transform 0.16s ease; }
.new-message-enter-from, .new-message-leave-to { opacity: 0; transform: translateY(6px); }

.msg {
  margin-bottom: 14px;

  .who {
    margin-bottom: 4px;
    color: var(--c-muted);
    font-size: 11px;
  }

  .bubble {
    padding: 8px 12px;
    border-radius: var(--radius);
    font-size: 13px;
    line-height: 1.7;
    white-space: pre-wrap;
    word-break: break-word;
  }

  &.user .bubble {
    background: var(--c-blue);
    color: #fff;
  }

  &.assistant .bubble {
    background: var(--c-bg);
    color: var(--c-ink);
    border: 1px solid var(--c-line);
  }
}

.typing {
  display: flex;
  gap: 4px;
  align-items: center;
  width: fit-content;

  i {
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: var(--c-muted);
    animation: blink 1.2s infinite;

    &:nth-child(2) { animation-delay: 0.2s; }
    &:nth-child(3) { animation-delay: 0.4s; }
  }
}

@keyframes blink {
  0%, 100% { opacity: 0.25; }
  50% { opacity: 1; }
}

.err {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  background: var(--c-error-soft);
  color: var(--c-error);
  font-size: 12px;
}

.p-foot {
  padding: 10px 14px;
  border-top: 1px solid var(--c-line);
  flex-shrink: 0;
}

.input {
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--c-line-strong);
  border-radius: var(--radius-sm);
  font-family: inherit;
  font-size: 13px;
  line-height: 1.6;
  color: var(--c-ink);
  resize: vertical;

  &:focus {
    outline: none;
    border-color: var(--c-primary);
  }
}

.actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;

  .tip {
    color: var(--c-muted);
    font-size: 11px;
    margin-right: auto;
  }
}

.hitl-card {
  margin: 12px 0;
  padding: 12px;
  border: 1px solid var(--c-primary);
  border-radius: var(--radius);
  background: var(--c-primary-soft);
}

.hitl-title {
  color: var(--c-primary-ink);
  font-size: 13px;
  font-weight: 600;
}

.hitl-message {
  margin-top: 5px;
  color: var(--c-ink-2);
  font-size: 12px;
  white-space: pre-wrap;
}

.hitl-field {
  margin-top: 10px;

  > label {
    display: block;
    margin-bottom: 4px;
    color: var(--c-ink-2);
    font-size: 12px;
    font-weight: 600;
  }

  input:not([type='radio']):not([type='checkbox']),
  textarea,
  select {
    width: 100%;
    padding: 6px 8px;
    border: 1px solid var(--c-line-strong);
    border-radius: var(--radius-sm);
    background: var(--c-surface);
    color: var(--c-ink);
    font: inherit;
    font-size: 12px;
  }

  textarea { resize: vertical; }
}

.required { margin-left: 2px; color: var(--c-error); }
.choices { display: flex; flex-wrap: wrap; gap: 8px; }
.choice { display: inline-flex; align-items: center; gap: 4px; color: var(--c-ink-2); font-size: 12px; }
.hitl-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 12px; }

@media (prefers-reduced-motion: reduce) {
  .typing i { animation: none; }
  .new-messages { transition: none; }
  .new-messages:hover, .new-messages:active { transform: none; }
  .new-message-enter-active, .new-message-leave-active { transition: none; }
}
</style>
