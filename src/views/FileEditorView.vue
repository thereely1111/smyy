<template>
  <section class="editor-view">
    <header class="ide-toolbar">
      <div class="ide-title"><el-icon><Monitor /></el-icon><strong>解题工作空间</strong><span class="session-tag">{{ sandbox.isMock ? '演示文件' : '当前会话' }}</span></div>
      <div class="toolbar-actions">
        <span class="interpreter" :class="{ ready: pythonVersion }" :title="runtimeStatus"><span class="status-dot" />Python {{ pythonVersion || '3.11' }}</span>
        <button class="tool-button" :disabled="busy || sandbox.treeLoading" title="刷新智能体生成的文件" @click="sandbox.refreshTree()"><el-icon><Refresh /></el-icon><span>刷新</span></button>
        <button class="tool-button" :disabled="!editable || !sandbox.dirty || sandbox.saving || busy" title="保存 Ctrl+S" @click="askSave"><el-icon><DocumentChecked /></el-icon><span>保存</span></button>
        <button v-if="busy" class="tool-button stop" @click="stopRun"><el-icon><VideoPause /></el-icon>停止</button>
        <button v-else class="tool-button primary" :disabled="!editable || !canRun" title="运行 Ctrl+Enter" @click="run"><el-icon><VideoPlay /></el-icon>运行文件</button>
      </div>
    </header>
    <div v-if="handoff" class="handoff"><el-icon><Connection /></el-icon>{{ handoff.qLabel }}<span>{{ handoff.codeName }}</span><span class="dim">打开代码后即可运行</span><RouterLink to="/work">返回对话</RouterLink></div>
    <div class="ide-body">
      <IdeSplit direction="horizontal" :initial="23" :min="16" :max="48" storage-key="smyy.ide.solution-width" label="调整文件区宽度">
        <template #first>
          <aside class="project-panel">
            <div class="panel-heading"><el-icon><Document /></el-icon><strong>问题代码</strong><span class="count" title="Python 代码文件数">{{ solutionFiles.length }}</span></div>
            <p class="solution-caption">每个问题的 Python 解题代码</p>
            <div v-if="sandbox.treeError" class="panel-empty error" role="alert">{{ sandbox.treeError }}<button class="tool-button" @click="sandbox.refreshTree()">重试</button></div>
            <div v-else-if="!solutionFiles.length" class="panel-empty">{{ sandbox.treeLoading ? '正在同步代码' : '等待生成解题代码' }}<small>智能体生成的 Python 代码会显示在这里</small></div>
            <div v-else class="solution-list" aria-label="问题解题代码">
              <TransitionGroup name="file">
                <section v-for="group in solutionGroups" :key="group.key" class="question-group">
                  <div class="question-label"><span>{{ group.label }}</span><span v-if="group.files.length > 1" class="question-count">{{ group.files.length }} 份代码</span></div>
                  <button v-for="file in group.files" :key="file.path" class="solution-choice" :class="{ selected: file.path === sandbox.selectedPath }" :title="file.path" :aria-label="file.name" :aria-current="file.path === sandbox.selectedPath ? 'true' : undefined" :disabled="busy || sandbox.saving" @click="pick(file)">
                    <span class="python-mark"><el-icon><Document /></el-icon></span><span class="solution-name">{{ file.name }}</span><span v-if="file.path === sandbox.selectedPath && sandbox.dirty" class="dirty-dot" /><el-icon v-else-if="file.path === sandbox.selectedPath" class="selected-mark"><Right /></el-icon>
                  </button>
                </section>
              </TransitionGroup>
            </div>
            <div class="project-footer"><el-icon><Connection /></el-icon><span>代码生成后自动同步</span></div>
          </aside>
        </template>
        <template #second>
          <IdeSplit direction="vertical" :initial="66" :min="28" :max="82" storage-key="smyy.ide.editor-height" label="调整编辑区和终端高度">
            <template #first>
              <div class="editor-panel">
                <div class="editor-tab"><el-icon><Document /></el-icon><span>{{ currentName || '编辑器' }}</span><span v-if="sandbox.dirty" class="dirty-dot" title="未保存" /><button v-if="sandbox.dirty" class="revert-button" :disabled="busy" @click="revert">还原</button><span class="language-tag">{{ language }}</span></div>
                <div v-if="sandbox.selectedPath && !canRun" class="file-note">{{ whyNotRunnable(sandbox.selectedPath) }}</div>
                <div class="breadcrumb" :title="sandbox.selectedPath">{{ sandbox.selectedPath || '选择问题解题代码' }}</div>
                <Transition name="pane" mode="out-in">
                  <div v-if="!sandbox.selectedPath" key="welcome" class="editor-welcome"><el-icon><Monitor /></el-icon><h2>打开代码 开始求解</h2><p>左侧是智能体生成的逐问解题代码</p><p>选择 Python 文件 运行并查看结果</p><div><kbd>Ctrl</kbd> + <kbd>Enter</kbd><span>运行</span><kbd>Ctrl</kbd> + <kbd>S</kbd><span>保存</span></div></div>
                  <div v-else-if="sandbox.contentLoading" key="loading" class="panel-empty">正在打开文件</div>
                  <div v-else-if="sandbox.contentError" key="error" class="panel-empty error" role="alert">{{ sandbox.contentError }}<button class="tool-button" @click="retryOpen">重试</button></div>
                  <div v-else-if="unreadable" key="binary" class="panel-empty">{{ sandbox.content?.reason || '此文件不支持文本编辑' }}</div>
                  <CodeEditor v-else :key="sandbox.selectedPath" :model-value="sandbox.content?.content ?? ''" :path="sandbox.selectedPath" :readonly="busy || sandbox.saving" @update:model-value="sandbox.editContent" @run="run" @save="askSave" @cursor="(line, column) => cursor = `${line}:${column}`" />
                </Transition>
              </div>
            </template>
            <template #second>
              <section class="terminal-panel">
                <div class="terminal-header"><div class="terminal-tabs" role="tablist" aria-label="终端类型"><button id="output-tab" role="tab" :aria-selected="terminalTab === 'output'" aria-controls="terminal-content" :class="{ active: terminalTab === 'output' }" @click="terminalTab = 'output'"><el-icon><VideoPlay /></el-icon>运行输出</button><button id="console-tab" role="tab" :aria-selected="terminalTab === 'console'" aria-controls="terminal-content" :class="{ active: terminalTab === 'console' }" @click="terminalTab = 'console'"><el-icon><Cpu /></el-icon>Python 控制台</button></div><span v-if="busy" class="terminal-status">{{ runtimeStatus }}</span><button class="icon-button" title="清空终端输出" aria-label="清空终端输出" :disabled="busy" @click="clearOutput"><el-icon><Delete /></el-icon></button></div>
                <div id="terminal-content" ref="terminalEl" class="terminal-content" role="tabpanel" :aria-labelledby="terminalTab === 'output' ? 'output-tab' : 'console-tab'" tabindex="0">
                  <div v-if="!(terminalTab === 'output' ? output : consoleOutput).length" class="terminal-placeholder">{{ terminalTab === 'output' ? '运行结果会显示在这里' : '输入 Python 指令 支持访问当前运行后的变量' }}<span>{{ terminalTab === 'output' ? 'Python 3.11 · Ctrl+Enter 运行当前文件' : '示例 print(1 + 1) · 此处执行 Python 语句' }}</span></div>
                  <pre v-for="(item, index) in terminalTab === 'output' ? output : consoleOutput" :key="index" :class="item.stream">{{ item.text }}</pre>
                </div>
                <form v-if="terminalTab === 'console'" class="console-input" @submit.prevent="runConsole"><label for="python-command">&gt;&gt;&gt;</label><input id="python-command" v-model="command" autocomplete="off" spellcheck="false" placeholder="输入 Python 指令 按 Enter 执行" :disabled="busy" /><button class="icon-button" :disabled="busy || !command.trim()" aria-label="执行 Python 指令"><el-icon><Right /></el-icon></button></form>
                <div v-if="generated.length" class="generated-files"><span>本地生成</span><a v-for="file in generated" :key="file.path" :href="file.url" :download="file.path.split('/').pop()"><el-icon><Download /></el-icon>{{ file.path }}</a></div>
              </section>
            </template>
          </IdeSplit>
        </template>
      </IdeSplit>
    </div>
    <footer class="ide-statusbar"><span><span class="status-dot" :class="{ running: busy }" />{{ busy ? '执行中' : '就绪' }}</span><Transition name="pane" mode="out-in"><span :key="msg" class="status-message" role="status">{{ msg || (isPython(sandbox.selectedPath) ? 'Python 在浏览器内运行 生成文件可在终端下载' : '选择文件后编辑运行') }}</span></Transition><span>{{ language }}</span><span>UTF-8</span><span>行 列 {{ cursor }}</span></footer>
    <Transition name="dialog">
      <div v-if="confirming" class="mask" @click.self="closeConfirm"><div class="save-dialog" role="dialog" aria-modal="true" aria-labelledby="save-title" @keydown="onDialogKeydown"><el-icon><DocumentChecked /></el-icon><h2 id="save-title">保存到产物目录</h2><p>将覆盖智能体生成的原始文件</p><code>{{ sandbox.selectedPath }}</code><div class="dialog-actions"><button ref="cancelButton" class="tool-button" @click="closeConfirm">取消</button><button class="tool-button primary" @click="doSave">确认保存</button></div></div></div>
    </Transition>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ElIcon } from 'element-plus'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { Connection, Cpu, Delete, Document, DocumentChecked, Download, Monitor, Refresh, Right, VideoPause, VideoPlay } from '@element-plus/icons-vue'
import CodeEditor from '@/components/sandbox/CodeEditor.vue'
import IdeSplit from '@/components/sandbox/IdeSplit.vue'
import { isRunnable, whyNotRunnable } from '@/engine/runnable'
import { PythonRunner, type PythonOutputFile } from '@/engine/pythonRunner'
import { flattenArtifacts, pythonSnapshot } from '@/engine/pythonWorkspace'
import { fetchArtifactBytes } from '@/api/artifact'
import { AGENT_ID } from '@/config'
import { mockArtifactContent } from '@/data/mockSandbox'
import { requestFor } from '@/data/runHandoff'
import { useProblemSessionStore } from '@/stores/problemSession'
import { useSandboxStore } from '@/stores/sandbox'
import type { ArtifactNode } from '@/types/sandbox'
const props = defineProps<{ problemId: string }>()
const sandbox = useSandboxStore()
const ps = useProblemSessionStore()
const msg = ref('')
const confirming = ref(false)
const cancelButton = ref<HTMLButtonElement>()
let dialogTrigger: HTMLElement | null = null
const cursor = ref('1:1')
const pythonBusy = ref(false)
const busy = computed(() => pythonBusy.value || sandbox.isRunning)
const pythonVersion = ref('')
const runtimeStatus = ref('Python 3.11 尚未初始化')
const terminalTab = ref<'output' | 'console'>('output')
const terminalEl = ref<HTMLElement>()
const command = ref('')
let outputTarget: 'output' | 'console' = 'output'
type OutputLine = { stream: 'stdout' | 'stderr' | 'system'; text: string }
const output = ref<OutputLine[]>([])
const consoleOutput = ref<OutputLine[]>([])
const generated = ref<Array<{ path: string; url: string }>>([])
const handoff = computed(() => requestFor(props.problemId))
const files = computed(() => flattenArtifacts(sandbox.tree ?? []))
const isPython = (path: string) => /\.py$/i.test(path)
const canRun = computed(() => isPython(sandbox.selectedPath) || isRunnable(sandbox.selectedPath))
const currentName = computed(() => sandbox.selectedPath.split('/').pop())
const language = computed(() => isPython(sandbox.selectedPath) ? 'Python' : /\.[cm]?js$/i.test(sandbox.selectedPath) ? 'JavaScript' : /\.ts$/i.test(sandbox.selectedPath) ? 'TypeScript' : /\.json$/i.test(sandbox.selectedPath) ? 'JSON' : '文本')
const unreadable = computed(() => sandbox.content != null && sandbox.content.content == null)
const editable = computed(() => !!sandbox.content && sandbox.content.content != null && !sandbox.contentLoading && !sandbox.contentError)
// Preserve real filenames and paths; never invent question assignments for unknown names.
const solutionFiles = computed(() => files.value.filter(file => /\.py$/i.test(file.path) && !/(?:^|\/)(?:__[^/]*|conftest|test_[^/]*|[^/]*_test)\.py$/i.test(file.path)))
function questionNumber(file: ArtifactNode): number | undefined {
  const match = file.path.match(/(?:^|[\/_\-])(?:q|question|problem)[_\-]?(\d+)(?=$|[\/_.\-])/i) ?? file.path.match(/(?:问题|第)[_\-]?(\d+)(?:问|题)?/)
  return match ? Number(match[1]) : undefined
}
const solutionGroups = computed(() => {
  const groups = new Map<string, { key: string; label: string; number: number; files: ArtifactNode[] }>()
  for (const file of solutionFiles.value) {
    const number = questionNumber(file), key = number == null ? 'other' : String(number)
    if (!groups.has(key)) groups.set(key, { key, label: number == null ? '解题代码' : '问题 ' + number, number: number ?? Infinity, files: [] })
    groups.get(key)!.files.push(file)
  }
  return [...groups.values()].sort((a, b) => a.number - b.number).map(group => ({ ...group, files: group.files.sort((a, b) => a.name.localeCompare(b.name, 'zh-CN', { numeric: true })) }))
})
function append(stream: OutputLine['stream'], text: string) {
  const list = outputTarget === 'output' ? output.value : consoleOutput.value
  const last = list.at(-1)
  if (last?.stream === stream) last.text += text
  else list.push({ stream, text })
  void nextTick(() => { const el = terminalEl.value; if (el) el.scrollTop = el.scrollHeight })
}
const runner = new PythonRunner({ output: append, status: text => runtimeStatus.value = text, ready: version => { pythonVersion.value = version; runtimeStatus.value = `Python ${version} 已就绪` } })
let lifecycle = 0
let snapshotController: AbortController | undefined
function clearGenerated() { for (const file of generated.value) URL.revokeObjectURL(file.url); generated.value = [] }
function receiveFiles(files: PythonOutputFile[]) {
  for (const file of files) {
    const previous = generated.value.findIndex(item => item.path === file.path)
    if (previous >= 0) { URL.revokeObjectURL(generated.value[previous].url); generated.value.splice(previous, 1) }
    const bytes = new Uint8Array(file.bytes)
    generated.value.push({ path: file.path, url: URL.createObjectURL(new Blob([bytes])) })
  }
}
async function bind() {
  const generation = ++lifecycle
  stopRun()
  output.value = []; consoleOutput.value = []; clearGenerated()
  const tid = ps.enterProblem(props.problemId)
  if (sandbox.sessionId !== tid) await sandbox.attach(props.problemId, tid)
  else await sandbox.refreshTree()
  if (generation !== lifecycle) return
  if (sandbox.selectedPath) {
    const node = files.value.find(file => file.path === sandbox.selectedPath)
    if (node && !sandbox.content) await sandbox.openFile(node)
  } else await autoOpen()
}
async function autoOpen() {
  if (sandbox.selectedPath || sandbox.dirty) return
  const requested = handoff.value?.codeName
  const codes = solutionFiles.value
  const node = codes.find(file => file.name === requested || file.path.endsWith('/' + requested)) ?? [...codes].sort((a, b) => Number(isPython(b.path)) - Number(isPython(a.path)) || (b.updatedAt ?? 0) - (a.updatedAt ?? 0))[0]
  if (node) await sandbox.openFile(node)
}
function pick(node: ArtifactNode) {
  if (node.path === sandbox.selectedPath || sandbox.saving || busy.value) return
  if (sandbox.dirty && !window.confirm('当前文件有未保存的修改 确定放弃修改并切换文件吗')) return
  cursor.value = '1:1'
  void sandbox.openFile(node)
}
function retryOpen() { const node = files.value.find(file => file.path === sandbox.selectedPath); if (node) void sandbox.openFile(node) }
async function run() {
  if (busy.value || !editable.value || !canRun.value) return
  const code = sandbox.content?.content ?? '', path = sandbox.selectedPath, session = sandbox.sessionId, generation = lifecycle
  terminalTab.value = 'output'; outputTarget = 'output'; output.value = []; clearGenerated()
  append('system', `${isPython(path) ? 'python' : 'run'} ${path}\n`)
  msg.value = sandbox.dirty ? '正在运行编辑器内容 修改尚未保存' : ''
  if (!isPython(path)) {
    await sandbox.runFile(code, path)
    if (generation !== lifecycle) return
    const result = sandbox.latestExec
    if (result) { append('stdout', result.stdout); append('stderr', result.stderr); append('system', `\n进程结束 退出码 ${result.exitCode ?? '未知'} · ${result.elapsedMs ?? 0} ms\n`) }
    else if (sandbox.runError) append('stderr', sandbox.runError)
    return
  }
  pythonBusy.value = true
  snapshotController = new AbortController()
  const signal = snapshotController.signal
  try {
    const snapshot = await pythonSnapshot(files.value, path, code, async node => {
      if (signal.aborted) throw new Error('已取消运行')
      if (sandbox.isMock) return mockArtifactContent(node.path).content
      const bytes = await fetchArtifactBytes(AGENT_ID, session, node.path, signal)
      return /\.(py|csv|tsv|txt|json|ya?ml|md|js|ts)$/i.test(node.path) ? new TextDecoder('utf-8', { fatal: true }).decode(bytes) : bytes
    })
    if (signal.aborted || generation !== lifecycle) return
    const result = await runner.run(code, path, snapshot)
    if (generation !== lifecycle) return
    receiveFiles(result.files)
    append('system', `\n进程结束 退出码 ${result.exitCode} · ${result.elapsedMs} ms\n`)
  } catch (error) { if (generation === lifecycle) append('stderr', `${error instanceof Error ? error.message : error}\n`) }
  finally { if (generation === lifecycle) { pythonBusy.value = false; snapshotController = undefined; runtimeStatus.value = '就绪' } }
}
async function runConsole() {
  const source = command.value.trim()
  if (!source || busy.value) return
  command.value = ''; terminalTab.value = 'console'; outputTarget = 'console'; append('system', `>>> ${source}\n`)
  const generation = lifecycle
  pythonBusy.value = true
  try { const result = await runner.repl(source); if (generation === lifecycle) receiveFiles(result.files) }
  catch (error) { if (generation === lifecycle) append('stderr', `${error instanceof Error ? error.message : error}\n`) }
  finally { if (generation === lifecycle) { pythonBusy.value = false; runtimeStatus.value = '就绪' } }
}
function stopRun() { snapshotController?.abort(); runner.stop(); pythonVersion.value = ''; pythonBusy.value = false; if (sandbox.isRunning) sandbox.stop() }
function clearOutput() { if (terminalTab.value === 'output') output.value = []; else consoleOutput.value = [] }
function askSave() {
  if (!editable.value || !sandbox.dirty || sandbox.saving || busy.value) return
  dialogTrigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
  confirming.value = true
  void nextTick(() => cancelButton.value?.focus())
}
function closeConfirm() { confirming.value = false; void nextTick(() => dialogTrigger?.focus()) }
function onDialogKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { closeConfirm(); return }
  if (event.key !== 'Tab') return
  const buttons = Array.from((event.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('button:not(:disabled)'))
  const index = buttons.indexOf(document.activeElement as HTMLButtonElement)
  if (event.shiftKey && index === 0) { event.preventDefault(); buttons.at(-1)?.focus() }
  if (!event.shiftKey && index === buttons.length - 1) { event.preventDefault(); buttons[0]?.focus() }
}
function revert() { sandbox.revertFile(); msg.value = '已恢复到打开文件时的内容' }
async function doSave() { closeConfirm(); const ok = await sandbox.saveFile(); msg.value = ok ? '已保存回产物目录' : '保存失败 请检查连接后重试' }
function allowLeave() { if (sandbox.saving) return false; return !sandbox.dirty || window.confirm('当前文件有未保存的修改 确定离开并放弃修改吗') }
onBeforeRouteLeave(allowLeave)
onBeforeRouteUpdate(allowLeave)
function beforeUnload(event: BeforeUnloadEvent) { if (sandbox.dirty || sandbox.saving) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
watch(() => props.problemId, () => void bind(), { immediate: true })
watch(() => files.value.map(file => file.path).join('\n'), () => void autoOpen())
onBeforeUnmount(() => { lifecycle++; stopRun(); clearGenerated(); window.removeEventListener('beforeunload', beforeUnload); sandbox.detach() })
</script>

<style scoped lang="scss">
.el-icon { display: inline-flex; align-items: center; justify-content: center; width: 1em; height: 1em; flex-shrink: 0; :deep(svg) { width: 1em; height: 1em; } }
.file-note { padding: 5px 16px; color: var(--c-muted); font-size: 11px; background: var(--c-bg); }
.editor-view { height: 100%; min-height: 0; min-width: 0; display: flex; flex-direction: column; background: var(--c-surface); color: var(--c-ink); --accent: var(--c-primary); font-size: 12px; animation: workspace-in .25s ease both; }
.ide-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 58px; padding: 10px 18px; border-bottom: 1px solid var(--c-line); }
.ide-title, .toolbar-actions, .tool-button, .interpreter, .panel-heading, .editor-tab, .terminal-header, .terminal-tabs, .project-footer, .ide-statusbar, .handoff { display: flex; align-items: center; gap: 8px; }
.ide-title { font-size: 15px; white-space: nowrap; .el-icon { font-size: 19px; color: var(--accent); } }
.session-tag, .count { font-size: 11px; font-weight: 400; border-radius: 4px; background: var(--c-bg); color: var(--c-muted); padding: 3px 6px; }
button, input, a { font: inherit; }
button { cursor: pointer; }
button, a { transition: background .16s, color .16s, border-color .16s, transform .16s, box-shadow .16s; }
button:hover:not(:disabled) { transform: translateY(-1px); }
button:active:not(:disabled) { transform: scale(.97); }
button:disabled { opacity: .45; cursor: not-allowed; }
button:focus-visible, a:focus-visible, input:focus-visible { outline: 2px solid var(--c-primary); outline-offset: -2px; }
.tool-button { border: 1px solid var(--c-line); border-radius: 6px; padding: 7px 11px; background: var(--c-surface); color: var(--c-ink-2); white-space: nowrap; &:hover { background: var(--c-bg); } &.primary { color: var(--c-surface); border-color: var(--accent); background: var(--accent); &:hover { background: var(--c-primary-ink); } } &.stop { color: var(--c-error); border-color: var(--c-error-soft); } }
.interpreter { border: 1px solid var(--c-line); padding: 6px 10px; border-radius: 5px; color: var(--c-muted); &.ready { color: var(--c-primary-ink); } }
.status-dot { display: inline-block; width: 6px; height: 6px; border-radius: 50%; background: var(--c-muted); .ready &, &.running { background: var(--c-primary); } &.running { animation: pulse 1s ease infinite; } }
.handoff { padding: 8px 18px; border-bottom: 1px solid var(--c-primary-soft); background: var(--c-primary-soft); color: var(--c-primary-ink); a { margin-left: auto; color: inherit; } }
.dim { color: var(--c-muted); }
.ide-body { flex: 1; min-height: 0; overflow: hidden; }
.project-panel, .editor-panel, .terminal-panel { height: 100%; display: flex; flex-direction: column; min-height: 0; min-width: 0; }
.project-panel { background: var(--c-bg); }
.panel-heading { min-height: 42px; padding: 0 14px; border-bottom: 1px solid var(--c-line); .count { margin-left: auto; } }
.solution-caption { margin: 0; padding: 16px 16px 10px; color: var(--c-muted); font-size: 11px; }
.solution-list { flex: 1; min-height: 0; overflow: auto; padding: 0 10px 16px; }
.question-group { margin-top: 12px; }
.question-label { display: flex; align-items: center; gap: 8px; padding: 0 6px 8px; color: var(--c-ink-2); font-size: 12px; font-weight: 600; }
.question-count { margin-left: auto; font-size: 10px; font-weight: 400; color: var(--c-muted); }
.solution-choice { display: flex; align-items: center; gap: 9px; width: 100%; min-height: 46px; margin-bottom: 5px; padding: 9px 10px; border: 1px solid transparent; border-radius: var(--radius); background: transparent; color: var(--c-ink-2); text-align: left; &:hover { background: var(--c-surface); border-color: var(--c-line); } &.selected { background: var(--c-primary-soft); border-color: var(--c-line-strong); color: var(--c-primary-ink); box-shadow: var(--shadow-sm); } }
.python-mark { display: flex; align-items: center; justify-content: center; width: 26px; height: 28px; flex-shrink: 0; color: var(--c-primary); background: var(--c-surface); border: 1px solid var(--c-line); border-radius: 6px; }
.solution-name { min-width: 0; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }
.selected-mark { flex-shrink: 0; font-size: 12px; }
.dirty-dot { width: 6px; height: 6px; background: var(--c-primary); border-radius: 50%; flex-shrink: 0; }
.project-footer { border-top: 1px solid var(--c-line); color: var(--c-muted); padding: 10px 12px; font-size: 11px; }
.editor-tab { min-height: 41px; padding: 0 16px; background: var(--c-bg); border-bottom: 1px solid var(--c-line); > .el-icon { color: var(--c-primary); } > span:first-of-type { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } }
.language-tag { margin-left: auto; color: var(--c-muted); font-size: 11px; }
.revert-button { border: 0; background: transparent; color: var(--c-muted); padding: 4px 6px; }
.breadcrumb { min-height: 29px; padding: 6px 16px; color: var(--c-muted); border-bottom: 1px solid var(--c-bg); font-size: 11px; text-overflow: ellipsis; overflow: hidden; white-space: nowrap; }
.editor-welcome { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; color: var(--c-muted); padding: 20px; > .el-icon { color: var(--c-line-strong); font-size: 48px; margin-bottom: 18px; } h2 { font-size: 18px; font-weight: 500; color: var(--c-ink-2); margin: 0 0 16px; } p { margin: 4px 0; } > div { display: flex; align-items: center; gap: 5px; margin-top: 24px; font-size: 11px; } kbd { border: 1px solid var(--c-line); border-radius: 3px; padding: 2px 5px; } div span { margin: 0 10px 0 3px; } }
.panel-empty { padding: 24px 14px; text-align: center; color: var(--c-muted); line-height: 1.8; small { display: block; font-size: 11px; } .tool-button { margin: 10px auto; } &.error { color: var(--c-error); } }
.terminal-header { min-height: 39px; background: var(--c-bg); border-bottom: 1px solid var(--c-line); padding-right: 8px; }
.terminal-tabs { height: 39px; gap: 0; button { display: flex; align-items: center; gap: 6px; height: 100%; padding: 0 16px; border: 0; border-bottom: 2px solid transparent; color: var(--c-muted); background: transparent; white-space: nowrap; &.active { color: var(--c-primary); border-bottom-color: var(--c-primary); background: var(--c-surface); } } }
.terminal-status { margin-left: auto; color: var(--c-muted); font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.icon-button { display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; margin-left: auto; border: 0; border-radius: 4px; color: var(--c-muted); background: transparent; &:hover { background: var(--c-line); } }
.terminal-content { flex: 1; min-height: 0; overflow: auto; padding: 12px 18px; font: 12px/1.7 'Cascadia Code', Consolas, monospace; background: var(--c-surface); pre { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; } .stderr { color: var(--c-error); } .system { color: var(--c-muted); } }
.terminal-placeholder { color: var(--c-muted); padding-top: 5px; span { display: block; margin-top: 5px; color: var(--c-muted); font-size: 11px; } }
.console-input { display: flex; align-items: center; gap: 10px; padding: 4px 12px; border-top: 1px solid var(--c-bg); font: 12px Consolas, monospace; label { color: var(--c-primary); } input { flex: 1; min-width: 0; border: 0; outline: 0; padding: 8px 0; color: var(--c-ink); background: transparent; } }
.generated-files { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; max-height: 90px; overflow: auto; padding: 7px 14px; background: var(--c-bg); color: var(--c-muted); font-size: 11px; a { display: inline-flex; align-items: center; gap: 4px; color: var(--c-primary); text-decoration: none; padding: 3px 5px; border-radius: 3px; &:hover { background: var(--c-primary-soft); } } }
.ide-statusbar { min-height: 28px; padding: 4px 14px; border-top: 1px solid var(--c-line); color: var(--c-muted); gap: 18px; font-size: 11px; > span { white-space: nowrap; } .status-message { flex: 1; overflow: hidden; text-overflow: ellipsis; } }
.mask { position: fixed; inset: 0; background: #0f172a55; display: flex; align-items: center; justify-content: center; z-index: 1000; backdrop-filter: blur(3px); }
.save-dialog { width: min(420px, calc(100vw - 32px)); padding: 28px; background: var(--c-surface); border-radius: 12px; box-shadow: 0 20px 80px #0f172a33; > .el-icon { font-size: 26px; color: var(--c-primary); } h2 { font-size: 18px; margin: 15px 0; } p { color: var(--c-muted); } code { display: block; color: var(--c-ink-2); padding: 12px; background: var(--c-bg); border-radius: 6px; overflow-wrap: anywhere; } }
.dialog-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 24px; }
.pane-enter-active, .pane-leave-active { transition: opacity .12s, transform .12s; }
.pane-enter-from, .pane-leave-to { opacity: 0; transform: translateY(3px); }
.file-enter-active, .file-leave-active, .file-move { transition: opacity .18s, transform .18s; }
.file-enter-from, .file-leave-to { opacity: 0; transform: translateX(-6px); }
.file-leave-active { position: absolute; }
.dialog-enter-active, .dialog-leave-active { transition: opacity .18s; .save-dialog { transition: transform .18s; } }
.dialog-enter-from, .dialog-leave-to { opacity: 0; .save-dialog { transform: scale(.97) translateY(6px); } }
@keyframes workspace-in { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }
@keyframes pulse { 50% { opacity: .35; } }
@media(max-width: 760px) { .solution-caption { padding: 12px 8px 4px; } .solution-list { padding: 0 5px 12px; } .solution-choice { gap: 5px; padding: 7px 5px; } .python-mark { display: none; } .question-label { padding-left: 3px; } .question-count { display: none; } .panel-heading { padding: 0 8px; gap: 5px; white-space: nowrap; } .project-footer { padding: 8px; } .ide-toolbar { padding: 8px; gap: 6px; } .ide-title { font-size: 13px; } .session-tag, .interpreter, .toolbar-actions .tool-button span, .handoff .dim { display: none; } .toolbar-actions { gap: 5px; } .tool-button { padding: 6px 8px; } .terminal-tabs button { padding: 0 8px; } .ide-statusbar { gap: 8px; padding: 4px 8px; } .ide-statusbar > span:last-child { display: none; } }
@media(prefers-reduced-motion: reduce) { *, *::before, *::after { animation: none !important; transition: none !important; } }
</style>
