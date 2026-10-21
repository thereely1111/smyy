<template>
  <div class="case-admin">
    <div class="case-admin-head">
      <div>
        <div class="eyebrow">OUTSOURCED CASE LIBRARY</div>
        <h1>案例管理</h1>
        <p>管理用于对外展示的完整案例，与智能体运行结果库隔离。</p>
      </div>
      <div class="head-actions">
        <button class="btn secondary" @click="goHome">返回主站</button>
        <button class="btn primary" @click="openNew">提交案例</button>
      </div>
    </div>

    <div v-if="error" class="notice error">{{ error }}</div>
    <div v-if="!isAdmin" class="notice warn">当前账号没有 admin:access 权限，只能返回主站；提交、替换和删除需要管理员账号。</div>

    <div class="case-grid">
      <article v-for="item in cases" :key="item.protoId" class="case-card">
        <div class="case-card-top">
          <span class="case-id">{{ item.protoId }}</span>
          <span class="status" :class="item.status.toLowerCase()">{{ statusText(item.status) }}</span>
        </div>
        <h2>{{ item.title }}</h2>
        <p class="meta">{{ item.tag || '未填写标签' }} · {{ item.category || '未填写赛别' }} · v{{ item.version }}</p>
        <div class="section-chips">
          <span v-for="section in sectionList" :key="section.key" :class="{ empty: !sectionValue(item, section.key) }">
            {{ section.label }} {{ sectionValue(item, section.key) ? '已录入' : '空白' }}
          </span>
        </div>
        <div class="card-actions">
          <button class="btn secondary" @click="openEdit(item)">编辑 / 替换</button>
          <button class="text-danger" @click="removeCase(item)">删除</button>
        </div>
      </article>
      <div v-if="!cases.length && !loading" class="empty">暂无外包案例。</div>
    </div>

    <div v-if="dialogOpen" class="modal-backdrop" @click.self="closeDialog">
      <form class="case-form" @submit.prevent="submit">
        <div class="form-head">
          <div>
            <div class="eyebrow">CASE SUBMISSION</div>
            <h2>{{ editing ? '编辑案例' : '提交案例' }}</h2>
          </div>
          <button type="button" class="close" @click="closeDialog">×</button>
        </div>
        <div class="form-grid">
          <label>题目编号<select v-model="form.protoId" :disabled="!!editing" @change="applyProblemMeta(form.protoId)"><option v-for="p in problems" :key="p.id" :value="p.id">{{ p.id }} · {{ p.title }}</option></select></label>
          <label>案例名称<input v-model.trim="form.title" required maxlength="200" placeholder="例如：SEM 广告投放策略优化" /></label>
          <label>标签<input v-model.trim="form.tag" maxlength="80" placeholder="2026国赛E题" /></label>
          <label>赛别<input v-model.trim="form.category" maxlength="80" placeholder="国赛专科" /></label>
        </div>
        <div class="naming-tip">自动命名：<code>{{ previewName('guide') }}</code>、<code>{{ previewName('animation') }}</code>、<code>{{ previewName('code') }}</code>、<code>{{ previewName('result') }}</code>、<code>{{ previewName('replay') }}</code>。替换时版本号自动递增，不覆盖历史文件名。</div>
        <div class="upload-section">
          <div class="upload-section-head"><div><strong>实训指导书</strong><small>HTML / Markdown / TXT，上传后会在案例中按文档预览</small></div><span class="upload-state" :class="{ ready: sectionReady('guide') }">{{ sectionState('guide') }}</span></div>
          <label class="upload-zone" @dragover.prevent @drop.prevent="dropSectionFiles($event, 'guide')">
            <input type="file" multiple accept=".html,.htm,.md,.txt,text/html,text/plain,text/markdown" @change="readSectionFiles($event, 'guide')" />
            <span class="upload-icon">↑</span><span class="upload-main">点击选择或拖入指导书文件</span><span class="upload-sub">支持同时选择多个 HTML、Markdown、TXT 文件</span>
          </label>
          <div v-if="formFiles.guide.length" class="selected-file-list"><div v-for="(file, index) in formFiles.guide" :key="`${file.name}-${index}`" class="selected-file"><span>文件</span><b>{{ file.name }}</b><em>{{ formatBytes(file.size) }}</em><button type="button" @click="removeSectionFile('guide', index)">移除</button></div></div>
        </div>
        <div class="upload-section animation-upload">
          <div class="upload-section-head"><div><strong>可视化演示</strong><small>每段上传一个独立 HTML，案例页会直接运行预览，不显示源码</small></div><button type="button" class="btn secondary" @click="addAnimationSegment">＋ 添加问题章节</button></div>
          <div v-for="(segment, index) in animationSegments" :key="segment.id" class="animation-segment">
            <div class="segment-head"><div class="segment-index">{{ String(index + 1).padStart(2, '0') }}</div><input v-model="segment.label" :aria-label="`第 ${index + 1} 段标题`" placeholder="全局分析 / 问题一" /><button v-if="animationSegments.length > 1" type="button" class="text-danger" @click="removeAnimationSegment(segment.id)">移除</button></div>
            <label class="upload-zone compact" @dragover.prevent @drop.prevent="dropAnimationFile($event, segment.id)">
              <input type="file" accept=".html,.htm,text/html" @change="readAnimationFile($event, segment.id)" />
              <span class="upload-icon">◇</span><span class="upload-main">{{ segment.fileName || '选择本段 HTML 文件' }}</span><span class="upload-sub">支持独立 HTML、内嵌 CSS / JavaScript</span>
            </label>
          </div>
        </div>
        <div class="upload-section">
          <div class="upload-section-head"><div><strong>代码</strong><small>PY / TXT / R / M / JS / TS，案例页按代码编辑器样式展示</small></div><span class="upload-state" :class="{ ready: sectionReady('code') }">{{ sectionState('code') }}</span></div>
          <label class="upload-zone" @dragover.prevent @drop.prevent="dropSectionFiles($event, 'code')">
            <input type="file" multiple accept=".py,.txt,.r,.m,.js,.ts,text/plain" @change="readSectionFiles($event, 'code')" />
            <span class="upload-icon">&lt;/&gt;</span><span class="upload-main">点击选择或拖入代码文件</span><span class="upload-sub">支持同时选择多个代码文件，提交后按文件分别展示</span>
          </label>
          <div v-if="formFiles.code.length" class="selected-file-list"><div v-for="(file, index) in formFiles.code" :key="`${file.name}-${index}`" class="selected-file"><span>代码</span><b>{{ file.name }}</b><em>{{ formatBytes(file.size) }}</em><button type="button" @click="removeSectionFile('code', index)">移除</button></div></div>
        </div>
        <div class="upload-section">
          <div class="upload-section-head"><div><strong>成果</strong><small>HTML / Markdown / TXT，HTML 会在案例中直接预览</small></div><span class="upload-state" :class="{ ready: sectionReady('result') }">{{ sectionState('result') }}</span></div>
          <label class="upload-zone" @dragover.prevent @drop.prevent="dropSectionFiles($event, 'result')">
            <input type="file" multiple accept=".html,.htm,.md,.txt,text/html,text/plain,text/markdown" @change="readSectionFiles($event, 'result')" />
            <span class="upload-icon">▤</span><span class="upload-main">点击选择或拖入成果文件</span><span class="upload-sub">支持同时选择多个 HTML、Markdown、TXT 文件</span>
          </label>
          <div v-if="formFiles.result.length" class="selected-file-list"><div v-for="(file, index) in formFiles.result" :key="`${file.name}-${index}`" class="selected-file"><span>成果</span><b>{{ file.name }}</b><em>{{ formatBytes(file.size) }}</em><button type="button" @click="removeSectionFile('result', index)">移除</button></div></div>
        </div>
        <div class="upload-section">
          <div class="upload-section-head"><div><strong>复现</strong><small>单个 HTML，案例页「在平台复现一遍」会全屏运行该交互演示</small></div><span class="upload-state" :class="{ ready: sectionReady('replay') }">{{ sectionState('replay') }}</span></div>
          <label class="upload-zone" @dragover.prevent @drop.prevent="dropSectionFiles($event, 'replay')">
            <input type="file" accept=".html,.htm,text/html" @change="readSectionFiles($event, 'replay')" />
            <span class="upload-icon">⟳</span><span class="upload-main">点击选择或拖入复现 HTML</span><span class="upload-sub">支持独立 HTML、内嵌 CSS / JavaScript 的完整交互复现页</span>
          </label>
          <div v-if="formFiles.replay.length" class="selected-file-list"><div v-for="(file, index) in formFiles.replay" :key="`${file.name}-${index}`" class="selected-file"><span>复现</span><b>{{ file.name }}</b><em>{{ formatBytes(file.size) }}</em><button type="button" @click="removeSectionFile('replay', index)">移除</button></div></div>
        </div>
        <div v-if="formError" class="notice error">{{ formError }}</div>
        <div class="form-actions"><button type="button" class="btn secondary" @click="closeDialog">取消</button><button class="btn primary" :disabled="saving">{{ saving ? '保存中…' : '保存案例' }}</button></div>
      </form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { outsourcedCaseApi, type CaseSection, type OutsourcedCase } from '@/api/outsourcedCases'
import { outsourcedCaseState, loadOutsourcedCases, reloadOutsourcedCases } from '@/stores/outsourcedCases'
import { type AnimationSegmentSeed } from '@/data/outsourcedCaseSeeds'
import { PROBLEMS } from '@/legacy/prototype/core'

const router = useRouter()
const userStore = useUserStore()
const state = outsourcedCaseState()
const cases = computed(() => state.cases.value)
const loading = computed(() => state.loading.value)
const error = computed(() => state.error.value)
const isAdmin = computed(() => userStore.user?.permissions?.includes('admin:access') || userStore.user?.role?.toLowerCase().includes('admin') || false)
onMounted(() => {
  if (!isAdmin.value) void router.replace({ name: 'home' })
})
const problems = PROBLEMS.map((p) => ({ id: p.id, title: p.title }))
const sectionList: { key: CaseSection; label: string }[] = [
  { key: 'guide', label: '实训指导书' },
  { key: 'animation', label: '可视化演示' },
  { key: 'code', label: '代码' },
  { key: 'result', label: '成果' },
  { key: 'replay', label: '复现' },
]
const dialogOpen = ref(false)
const editing = ref<OutsourcedCase | null>(null)
const saving = ref(false)
const formError = ref('')
type UploadFile = { name: string; text: string; size: number }
type UploadSection = Exclude<CaseSection, 'animation'>
const form = reactive({ protoId: 'p1', title: '', tag: '', category: '', sections: { guide: '', animation: '', code: '', result: '', replay: '' } as Record<CaseSection, string> })
const formFiles = reactive<Record<UploadSection, UploadFile[]>>({ guide: [], code: [], result: [], replay: [] })
/** 各板块数据库原值（打开编辑时记录），移除本次选择后回落到此，避免误清空已存内容 */
const originalSections = reactive<Record<UploadSection, string>>({ guide: '', code: '', result: '', replay: '' })
/** 各板块数据库是否已有内容，用于状态标签区分「已上传」与「已准备」 */
const stored = reactive<Record<UploadSection, boolean>>({ guide: false, code: false, result: false, replay: false })
const animationSegments = ref<(AnimationSegmentSeed & { id: number })[]>([{ id: 1, key: 'global', label: '全局分析', fileName: '', html: '' }])
let nextSegmentId = 2

function resetForm(item?: OutsourcedCase) {
  animationSegments.value = [{ id: 1, key: 'global', label: '全局分析', fileName: '', html: '' }]
  nextSegmentId = 2

  editing.value = item || null
  form.protoId = item?.protoId || 'p1'
  if (item) {
    form.title = item.title
    form.tag = item.tag || ''
    form.category = item.category || ''
  } else {
    applyProblemMeta(form.protoId)
  }
  form.sections.guide = item?.guideHtml || ''
  form.sections.animation = item?.animationHtml || ''
  form.sections.code = item?.codeText || ''
  form.sections.result = item?.resultHtml || ''
  form.sections.replay = item?.replayHtml || ''
  originalSections.guide = form.sections.guide
  originalSections.code = form.sections.code
  originalSections.result = form.sections.result
  originalSections.replay = form.sections.replay
  stored.guide = !!form.sections.guide
  stored.code = !!form.sections.code
  stored.result = !!form.sections.result
  stored.replay = !!form.sections.replay
  formFiles.guide = []
  formFiles.code = []
  formFiles.result = []
  formFiles.replay = []
  restoreFiles(formFiles.guide, item?.guideFilesJson, item?.guideFileName, item?.guideHtml)
  restoreFiles(formFiles.code, item?.codeFilesJson, item?.codeFileName, item?.codeText)
  restoreFiles(formFiles.result, item?.resultFilesJson, item?.resultFileName, item?.resultHtml)
  // 复现板块库里只存单个 HTML 文本（replayHtml），无独立文件名/列表字段，重建一个文件对象用于显示已上传文件名
  if (item?.replayHtml) formFiles.replay = [{ name: 'replay.html', text: item.replayHtml, size: item.replayHtml.length }]
  if (item?.animationSegmentsJson) {
    try {
      const parsed = JSON.parse(item.animationSegmentsJson) as AnimationSegmentSeed[]
      animationSegments.value = parsed.map((segment) => ({ ...segment, id: nextSegmentId++ }))
      form.sections.animation = animationSegments.value[0]?.html || form.sections.animation
    } catch {
      animationSegments.value = [{ id: 1, key: 'global', label: '全局分析', fileName: item.animationFileName || '', html: form.sections.animation }]
      nextSegmentId = 2
    }
  } else if (form.sections.animation) {
    animationSegments.value = [{ id: 1, key: 'global', label: '全局分析', fileName: item?.animationFileName || '', html: form.sections.animation }]
    nextSegmentId = 2
  }
  formError.value = ''
}

function makeSegmentKey(label: string, index: number) {
  if (index === 0) return 'global'
  return `problem${index}`
}

function syncAnimationSection() {
  form.sections.animation = animationSegments.value[0]?.html || ''
}

function addAnimationSegment() {
  const index = animationSegments.value.length
  animationSegments.value.push({ id: nextSegmentId++, key: makeSegmentKey('', index), label: `问题${toChineseNumber(index)}`, fileName: '', html: '' })
}

function removeAnimationSegment(id: number) {
  animationSegments.value = animationSegments.value.filter((segment) => segment.id !== id)
  syncAnimationSection()
}

function toChineseNumber(value: number) {
  return ['零', '一', '二', '三', '四', '五', '六', '七', '八', '九', '十'][value] || String(value)
}

async function readTextFile(event: Event): Promise<UploadFile | null> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return null
  const text = await file.text()
  input.value = ''
  return { name: file.name, text, size: file.size }
}

function applyProblemMeta(protoId: string, preserveContent = false) {
  const problem = problems.find((item) => item.id === protoId)
  if (!problem) return
  if (!preserveContent || !editing.value) {
    form.title = problem.title
    const source = PROBLEMS.find((item) => item.id === protoId) as { tag?: string; cat?: string } | undefined
    form.tag = source?.tag || ''
    form.category = source?.cat || ''
  }
}

function restoreFiles(target: UploadFile[], json: string | null | undefined, fallbackName: string | null | undefined, fallbackText: string | null | undefined) {
  if (json) {
    try {
      const parsed = JSON.parse(json) as UploadFile[]
      if (Array.isArray(parsed)) target.push(...parsed.filter((file) => file?.name && typeof file.text === 'string'))
    } catch {
      // 旧数据格式不影响编辑
    }
  }
  if (!target.length && fallbackName && fallbackText) target.push({ name: fallbackName, text: fallbackText, size: fallbackText.length })
}

async function applySectionFiles(files: UploadFile[], section: UploadSection) {
  formFiles[section].push(...files)
  form.sections[section] = formFiles[section][0]?.text || ''
}

async function readSectionFiles(event: Event, section: UploadSection) {
  try {
    const input = event.target as HTMLInputElement
    const selected = Array.from(input.files || [])
    input.value = ''
    await applySectionFiles(await Promise.all(selected.map(async (file) => ({ name: file.name, text: await file.text(), size: file.size }))), section)
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '文件读取失败'
  }
}

async function dropSectionFiles(event: DragEvent, section: UploadSection) {
  const selected = Array.from(event.dataTransfer?.files || [])
  try {
    await applySectionFiles(await Promise.all(selected.map(async (file) => ({ name: file.name, text: await file.text(), size: file.size }))), section)
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '文件读取失败'
  }
}

async function applyAnimationFile(file: { name: string; text: string }, id: number) {
  const segment = animationSegments.value.find((item) => item.id === id)
  if (!segment) return
  segment.html = file.text
  segment.fileName = file.name
  syncAnimationSection()
}

async function readAnimationFile(event: Event, id: number) {
  try {
    const file = await readTextFile(event)
    if (file) await applyAnimationFile(file, id)
  } catch (e) {
    formError.value = e instanceof Error ? e.message : 'HTML 文件读取失败'
  }
}

async function dropAnimationFile(event: DragEvent, id: number) {
  const file = event.dataTransfer?.files?.[0]
  if (!file) return
  try {
    await applyAnimationFile({ name: file.name, text: await file.text() }, id)
  } catch (e) {
    formError.value = e instanceof Error ? e.message : 'HTML 文件读取失败'
  }
}

function removeSectionFile(section: UploadSection, index: number) {
  formFiles[section].splice(index, 1)
  form.sections[section] = formFiles[section][0]?.text || originalSections[section]
}
function formatBytes(size: number) {
  return size < 1024 ? `${size} B` : `${(size / 1024).toFixed(1)} KB`
}
function openNew() { resetForm(); applyProblemMeta(form.protoId); dialogOpen.value = true }
function openEdit(item: OutsourcedCase) { resetForm(item); dialogOpen.value = true }
function closeDialog() { if (!saving.value) dialogOpen.value = false }
function goHome() { void router.push('/') }
function sectionValue(item: OutsourcedCase, section: CaseSection) { return section === 'guide' ? item.guideHtml : section === 'animation' ? item.animationHtml : section === 'code' ? item.codeText : section === 'result' ? item.resultHtml : section === 'replay' ? item.replayHtml : null }
function statusText(status: string) { return status === 'PUBLISHED' ? '已发布' : status === 'BLANK' ? '空白' : '草稿' }
/** 板块上传状态：本次新选文件→已准备；数据库已有内容→已上传；都没有→待上传 */
function sectionState(section: UploadSection): string {
  if (formFiles[section].length) return '已准备'
  if (stored[section]) return '已上传'
  return '待上传'
}
function sectionReady(section: UploadSection): boolean {
  return formFiles[section].length > 0 || stored[section]
}
function slugPreview() { return form.title.trim().toLowerCase().replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '') || 'case' }
function previewName(section: CaseSection) { return `${form.protoId}_${slugPreview()}_${section}_v${(editing.value?.version || 0) + 1}.${section === 'code' ? 'py' : 'html'}` }
async function submit() {
  if (!isAdmin.value) { formError.value = '当前账号没有管理员权限'; return }
  if (!form.title.trim()) { formError.value = '案例名称不能为空'; return }
  saving.value = true; formError.value = ''
  try {
    syncAnimationSection()
    const animationSegmentsJson = JSON.stringify(animationSegments.value.map(({ id, ...segment }) => segment))
    const filesPayload = (section: UploadSection) => JSON.stringify(formFiles[section].map((file, index) => ({
      ...file,
      name: `${form.protoId}_${slugPreview()}_${section}_${String(index + 1).padStart(2, '0')}_v${(editing.value?.version || 0) + 1}.${file.name.split('.').pop() || (section === 'code' ? 'py' : 'html')}`,
    })))
    await outsourcedCaseApi.upsert({ protoId: form.protoId, title: form.title, tag: form.tag, category: form.category, sections: { animation: form.sections.animation, animationSegments: animationSegmentsJson, guideFiles: filesPayload('guide'), codeFiles: filesPayload('code'), resultFiles: filesPayload('result'), guide: form.sections.guide || null, code: form.sections.code || null, result: form.sections.result || null, replay: form.sections.replay || null } })
    await reloadOutsourcedCases(true)
    dialogOpen.value = false
  } catch (e) { formError.value = e instanceof Error ? e.message : '保存失败' } finally { saving.value = false }
}
async function removeCase(item: OutsourcedCase) {
  if (!isAdmin.value) return
  if (!window.confirm(`确认删除「${item.title}」？删除后案例展示将变为空白，且不可恢复。`)) return
  try { await outsourcedCaseApi.remove(item.protoId); await reloadOutsourcedCases(true) } catch (e) { window.alert(e instanceof Error ? e.message : '删除失败') }
}
void loadOutsourcedCases(true)
</script>

<style scoped>
.case-admin {
  min-height: 100vh;
  background:
    radial-gradient(circle at top left, rgba(18, 165, 148, 0.12), transparent 34%),
    linear-gradient(180deg, #f7fbfb 0%, #eef5f4 100%);
  color: #15333a;
  padding: 42px clamp(20px, 5vw, 72px);
  font-family: Inter, "Microsoft YaHei", sans-serif;
}
.case-admin-head {
  max-width: 1200px;
  margin: 0 auto 30px;
  display: flex;
  justify-content: space-between;
  gap: 24px;
  align-items: flex-end;
}
.eyebrow {
  font-size: 11px;
  letter-spacing: .14em;
  color: #0f9f91;
  font-weight: 800;
}
.case-admin h1 {
  margin: 8px 0;
  font-size: 34px;
  color: #102f36;
}
.case-admin-head p {
  margin: 0;
  color: #647b83;
}
.head-actions,
.card-actions,
.form-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}
.btn {
  border: 1px solid #cddddd;
  border-radius: 10px;
  padding: 10px 16px;
  background: #ffffff;
  color: #25464d;
  cursor: pointer;
  font-weight: 800;
  box-shadow: 0 6px 16px rgba(18, 50, 56, .06);
}
.btn.primary {
  background: #12a594;
  border-color: #12a594;
  color: #ffffff;
}
.btn.primary:disabled {
  opacity: .62;
  cursor: not-allowed;
}
.btn.secondary:hover {
  border-color: #12a594;
  color: #0b887b;
}
.case-grid {
  max-width: 1200px;
  margin: auto;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}
.case-card {
  background: rgba(255, 255, 255, .92);
  border: 1px solid #d9e8e7;
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 14px 32px rgba(28, 67, 72, .08);
}
.case-card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.case-id {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: #0f8f83;
  font-size: 12px;
  font-weight: 800;
}
.status {
  font-size: 11px;
  padding: 4px 8px;
  border-radius: 99px;
  background: #dcf8f1;
  color: #08796f;
  font-weight: 800;
}
.status.blank {
  background: #fff5d6;
  color: #9b6500;
}
.status.draft {
  background: #eee7ff;
  color: #6550ad;
}
.case-card h2 {
  font-size: 19px;
  margin: 18px 0 8px;
  color: #163940;
}
.meta {
  color: #70868d;
  font-size: 13px;
}
.section-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 18px 0;
}
.section-chips span {
  font-size: 11px;
  padding: 6px 8px;
  border-radius: 7px;
  background: #e1f7f2;
  color: #08796f;
  font-weight: 700;
}
.section-chips span.empty {
  background: #eef2f3;
  color: #809098;
}
.text-danger {
  border: 0;
  background: transparent;
  color: #d84f5b;
  cursor: pointer;
  font-weight: 800;
}
.empty {
  grid-column: 1 / -1;
  padding: 60px;
  text-align: center;
  color: #83969d;
  background: rgba(255, 255, 255, .78);
  border: 1px dashed #ccdcdb;
  border-radius: 16px;
}
.notice {
  max-width: 1200px;
  margin: 0 auto 18px;
  padding: 12px 15px;
  border-radius: 10px;
  border: 1px solid transparent;
}
.notice.error {
  background: #fff0f2;
  color: #b9303e;
  border-color: #ffd1d8;
}
.notice.warn {
  background: #fff8e5;
  color: #8b6518;
  border-color: #f7df9f;
}
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(15, 38, 43, .42);
  display: grid;
  place-items: center;
  padding: 24px;
  z-index: 50;
  backdrop-filter: blur(6px);
}
.case-form {
  width: min(980px, 96vw);
  max-height: 92vh;
  overflow: auto;
  background: #ffffff;
  border: 1px solid #d9e8e7;
  border-radius: 20px;
  padding: 24px;
  box-shadow: 0 24px 80px rgba(17, 50, 56, .22);
}
.form-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.form-head h2 {
  margin: 8px 0 0;
  color: #173940;
}
.close {
  border: 0;
  background: #f2f7f7;
  color: #547078;
  border-radius: 10px;
  width: 36px;
  height: 36px;
  font-size: 24px;
  cursor: pointer;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  margin: 18px 0;
}
.case-form label {
  display: flex;
  flex-direction: column;
  gap: 8px;
  color: #34565e;
  font-size: 13px;
  font-weight: 800;
}
.case-form input,
.case-form select,
.case-form textarea {
  background: #fbfefe;
  border: 1px solid #cfe0df;
  border-radius: 10px;
  color: #15333a;
  padding: 10px 12px;
  font: inherit;
  outline: none;
}
.case-form input:focus,
.case-form select:focus,
.case-form textarea:focus {
  border-color: #12a594;
  box-shadow: 0 0 0 3px rgba(18, 165, 148, .14);
}
.case-form textarea {
  min-height: 130px;
  resize: vertical;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.naming-tip {
  background: #f4fbfa;
  border: 1px dashed #9ed5ce;
  border-radius: 12px;
  padding: 12px;
  color: #597177;
  font-size: 12px;
}
.naming-tip code {
  color: #08796f;
  font-weight: 800;
}
.upload-section {
  margin-top: 18px;
  padding: 16px;
  border: 1px solid #dbe9e8;
  border-radius: 16px;
  background: #fbfefe;
}
.upload-section-head,
.segment-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.upload-section-head strong {
  display: block;
  color: #173940;
  font-size: 15px;
}
.upload-section-head small {
  display: block;
  margin-top: 4px;
  color: #789096;
  font-size: 12px;
  font-weight: 500;
}
.upload-state {
  flex: none;
  border-radius: 999px;
  padding: 5px 10px;
  background: #eef2f3;
  color: #7d8e94;
  font-size: 12px;
  font-weight: 800;
}
.upload-state.ready {
  background: #ddf8f1;
  color: #08796f;
}
.upload-zone {
  position: relative;
  min-height: 96px;
  border: 1px dashed #9ed5ce;
  border-radius: 14px;
  background: linear-gradient(180deg, #f7fdfc, #edf8f6);
  display: grid;
  place-items: center;
  gap: 4px;
  padding: 18px;
  text-align: center;
  cursor: pointer;
}
.upload-zone.compact {
  min-height: 78px;
  margin-bottom: 10px;
}
.upload-zone:hover {
  border-color: #12a594;
  box-shadow: 0 0 0 3px rgba(18, 165, 148, .1);
}
.upload-zone input[type="file"] {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}
.upload-icon {
  width: 36px;
  height: 36px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  background: #12a594;
  color: #fff;
  font-weight: 900;
}
.upload-main {
  color: #213f46;
  font-weight: 900;
}
.upload-sub {
  color: #789096;
  font-size: 12px;
}
.selected-file {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 10px 0;
  padding: 9px 11px;
  border-radius: 10px;
  background: #f1f8f7;
  color: #49666d;
  font-size: 12px;
}
.selected-file b {
  color: #0f8f83;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  overflow-wrap: anywhere;
}
.selected-file em {
  color: #82949a;
  font-style: normal;
  white-space: nowrap;
}
.selected-file-list {
  display: grid;
  gap: 6px;
  margin-top: 10px;
}
.selected-file button {
  margin-left: auto;
  border: 0;
  background: transparent;
  color: #d84f5b;
  cursor: pointer;
  font-weight: 800;
}
.animation-segment {
  margin-top: 12px;
  padding: 14px;
  border: 1px solid #d9e8e7;
  border-radius: 14px;
  background: #ffffff;
}
.segment-index {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  background: #e1f7f2;
  color: #08796f;
  font-weight: 900;
}
.segment-head input {
  flex: 1;
}
.upload-section textarea,
.animation-segment textarea {
  margin-top: 10px;
  min-height: 128px;
}
.animation-segment textarea {
  min-height: 180px;
}
.form-actions {
  justify-content: flex-end;
  margin-top: 18px;
}
@media (max-width: 760px) {
  .case-admin-head {
    align-items: flex-start;
    flex-direction: column;
  }
  .form-grid {
    grid-template-columns: 1fr;
  }
  .head-actions {
    flex-wrap: wrap;
  }
}
</style>
