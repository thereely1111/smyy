<template>
  <div class="workbench">
    <!-- 顶栏：赛题 + 问题 Tab + 运行状态 -->
    <div class="wb-top">
      <button v-if="embedded" class="btn tiny back" @click="emit('exit')">← 返回对话</button>
      <div class="ttl">{{ title }}</div>

      <div v-if="questions.length" class="q-tabs">
        <button
          v-for="q in questions"
          :key="q"
          class="q-tab"
          :class="{ on: q === activeQ }"
          @click="activeQ = q"
        >
          {{ q }}
        </button>
      </div>

      <div class="run-state" :class="sandbox.state">
        <span class="d" />
        <span>{{ stateText }}</span>
      </div>
    </div>

    <!-- 会话提示条：把「一题一会话」显式告诉使用者 -->
    <div class="sess-bar">
      <span class="k">会话</span>
      <code>{{ sandbox.sessionId || '（未建立）' }}</code>
      <span class="hint">切问题 Tab 不会换会话，环境不变</span>
      <button class="btn tiny" @click="reload">刷新产物</button>
    </div>

    <!-- 交接单提示条：只有从站点对话「进入沙箱运行」过来时才有 -->
    <div v-if="req" class="handoff" :class="{ done: delivered }">
      <span class="hd-tag">{{ delivered ? '产物已回传' : '来自对话' }}</span>
      <span class="hd-text">
        {{ req.qLabel }} · {{ req.codeName }}
        <em v-if="delivered">已生成 {{ fileCount }} 个产物，回到对话就能看到清单</em>
        <em v-else>运行指令已填进左下输入框，确认后点发送</em>
      </span>
      <button v-if="!embedded" class="btn tiny" @click="backToTalk">返回对话</button>
    </div>

    <nav class="pane-switch" aria-label="工作台面板">
      <button v-for="pane in panes" :key="pane.id" type="button"
        :aria-pressed="activePane === pane.id" :aria-controls="`wb-${pane.id}`"
        :class="{ on: activePane === pane.id }" @click="activePane = pane.id">
        {{ pane.label }}
      </button>
    </nav>

    <div class="wb-body">
      <ChatPanel id="wb-chat" class="wb-pane" :class="{ 'mobile-hidden': activePane !== 'chat' }" :prefill="req?.instruction ?? ''" />
      <RunPanel id="wb-run" class="wb-pane" :class="{ 'mobile-hidden': activePane !== 'run' }" />
      <ArtifactPanel id="wb-artifact" class="wb-pane" :class="{ 'mobile-hidden': activePane !== 'artifact' }" />
    </div>

    <!-- 底部附件条 -->
    <FileBar />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import ArtifactPanel from '@/components/sandbox/ArtifactPanel.vue'
import ChatPanel from '@/components/sandbox/ChatPanel.vue'
import FileBar from '@/components/sandbox/FileBar.vue'
import RunPanel from '@/components/sandbox/RunPanel.vue'
import {
  clearRequest,
  putReceipt,
  requestFor,
  type HandoffRequest,
} from '@/data/runHandoff'
import { useProblemSessionStore } from '@/stores/problemSession'
import { useSandboxStore } from '@/stores/sandbox'

const props = defineProps<{
  problemId: string
  /** 以站点内全屏层形态出现时为真：多一个「返回对话」，改为 emit('exit') 退出 */
  embedded?: boolean
  /** 顶栏标题；不传就用 problemId */
  title?: string
  /** 问题 Tab；不传（或空）时整条 tab 不渲染 */
  questions?: string[]
}>()
const emit = defineEmits<{ exit: [] }>()

const sandbox = useSandboxStore()
const ps = useProblemSessionStore()
const router = useRouter()

const title = computed(() => props.title || props.problemId)
const questions = computed(() => props.questions ?? [])
const activeQ = ref(props.questions?.[0] ?? '')
const panes = [
  { id: 'chat', label: '对话' },
  { id: 'run', label: '执行' },
  { id: 'artifact', label: '产物' },
] as const
const activePane = ref<(typeof panes)[number]['id']>('chat')

/* ── 交接单（只有从站点对话派过来时才有）────────────────────────────
   在 setup 里同步读，不走 bind() 那套异步流程 —— ChatPanel 是在本组件
   首次渲染时就挂上的，异步读会让预填晚一拍、到不了输入框。 */
const req = ref<HandoffRequest | null>(requestFor(props.problemId))
/** 回执是否已经交出去（决定提示条文案） */
const delivered = ref(false)
const fileCount = computed(() => sandbox.tree?.length ?? 0)

const stateText = computed(() => {
  if (sandbox.state === 'running') return '运行中'
  if (sandbox.state === 'error') return '出错'
  return '空闲'
})

/** 进题：建立（或复用）会话映射，再绑定到 store */
async function bind(): Promise<void> {
  const tid = ps.enterProblem(props.problemId)
  await sandbox.attach(props.problemId, tid)
}

function reload(): void {
  void sandbox.refreshTree()
  void sandbox.loadAttachments()
}

/** 回到站点那条对话 —— 这次运行的产物该回去的地方 */
function backToTalk(): void {
  // 全屏层形态：退出由外层壳负责（关层 + 追回执），这里只管发信号
  if (props.embedded) {
    emit('exit')
    return
  }
  // 没跑过就把去程单撤掉，免得下次进沙箱又冒出一条过期的「来自对话」
  if (!delivered.value) clearRequest()
  void router.push('/work')
}

/**
 * 跑完一次，把产物写成回执，交给对话那边取走。
 *
 * 同时盯着 tree：`send()` 是「先置 idle，再 await refreshTree()」，
 * 只盯 state 会在产物树还没拉回来时就落笔，回执里就成了一份空清单。
 * 重复写是幂等的（同一份内容反复覆盖），所以不怕多触发几次。
 */
function writeReceipt(): void {
  if (!req.value) return
  if (sandbox.state !== 'idle' || !sandbox.hasExecuted) return
  const last = sandbox.latestExec
  putReceipt({
    sandboxId: props.problemId,
    qLabel: req.value.qLabel,
    command: last?.command || req.value.codeName,
    exitCode: last?.exitCode ?? null,
    files: (sandbox.tree ?? []).map((n) => ({ path: n.name, size: n.size ?? 0 })),
    at: Date.now(),
  })
  delivered.value = true
}

watch(() => [sandbox.state, sandbox.tree], writeReceipt)

// 切题时重绑（切问题 Tab 不触发这里 —— 这是关键）
watch(() => props.problemId, () => void bind(), { immediate: true })

onBeforeUnmount(() => sandbox.detach())
</script>

<style scoped lang="scss">
.workbench {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.wb-top {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 10px 18px;
  background: var(--c-surface);
  border-bottom: 1px solid var(--c-line);
  flex-shrink: 0;

  .ttl {
    font-weight: 600;
    font-size: 14px;
    color: var(--c-ink);
    max-width: 360px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.q-tabs {
  display: flex;
  gap: 4px;

  .q-tab {
    padding: 4px 12px;
    border: 1px solid var(--c-line);
    border-radius: 999px;
    background: transparent;
    color: var(--c-ink-2);
    font-size: 12px;
    transition: all 0.15s ease;

    &:hover {
      border-color: var(--c-primary);
      color: var(--c-primary-ink);
    }

    &.on {
      background: var(--c-primary);
      border-color: var(--c-primary);
      color: #fff;
      font-weight: 600;
    }
  }
}

.run-state {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
  font-size: 12px;
  color: var(--c-muted);

  .d {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    background: var(--c-line-strong);
  }

  &.running .d {
    background: var(--c-warn);
    animation: pulse 1s infinite;
  }

  &.error .d {
    background: var(--c-error);
  }
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.3; }
}

.sess-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 18px;
  background: var(--c-primary-soft);
  border-bottom: 1px solid var(--c-line);
  font-size: 12px;
  color: var(--c-ink-2);
  flex-shrink: 0;

  .k {
    color: var(--c-muted);
  }

  code {
    font-size: 12px;
    color: var(--c-primary-ink);
  }

  .hint {
    color: var(--c-muted);
    margin-left: 4px;
  }

  .btn.tiny {
    margin-left: auto;
    padding: 2px 10px;
    font-size: 12px;
  }
}

/* 三栏：左固定 / 中自适应 / 右固定 */
.wb-body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: 380px minmax(0, 1fr) 320px;
  gap: 1px;
  background: var(--c-line);

  > .wb-pane { min-width: 0; }
}

.pane-switch {
  display: none;
}

@media (max-width: 1180px) {
  .pane-switch {
    display: flex;
    flex-shrink: 0;
    padding: 8px 14px;
    gap: 4px;
    border-bottom: 1px solid var(--c-line);
    background: var(--c-surface);

    button {
      flex: 1;
      min-height: 40px;
      border: 1px solid transparent;
      border-radius: var(--radius-sm);
      background: transparent;
      color: var(--c-ink-2);
      transition: background 0.16s ease, color 0.16s ease, transform 0.16s ease;

      &:hover { background: var(--c-bg); transform: translateY(-1px); }
      &:active { transform: scale(0.98); }
      &:focus-visible { outline: 2px solid var(--c-primary); outline-offset: -2px; }
      &.on { background: var(--c-primary-soft); color: var(--c-primary-ink); font-weight: 600; }
    }
  }

  .wb-body {
    grid-template-columns: minmax(0, 1fr);
    > .mobile-hidden { display: none; }
  }

  .wb-top { flex-wrap: wrap; gap: 8px; padding: 8px 14px; }
  .wb-top .ttl { flex: 1; min-width: 0; }
  .q-tabs { order: 3; width: 100%; overflow-x: auto; }
  .q-tabs .q-tab { flex-shrink: 0; min-height: 36px; }
  .sess-bar { padding: 6px 14px; }
  .sess-bar code { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .sess-bar .hint { display: none; }
  .handoff { padding: 7px 14px; }
}

@media (prefers-reduced-motion: reduce) {
  .pane-switch button, .q-tabs .q-tab { transition: none; }
  .pane-switch button:hover, .pane-switch button:active { transform: none; }
  .run-state.running .d { animation: none; }
}

/* 交接单提示条：与 .sess-bar 同款底子；产物回传后转成绿调，一眼看出这趟跑完了 */
.handoff {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 18px;
  background: var(--c-bg);
  border-bottom: 1px solid var(--c-line);
  font-size: 12px;
  color: var(--c-ink-2);
  flex-shrink: 0;

  .hd-tag {
    padding: 1px 8px;
    border-radius: 999px;
    background: var(--c-primary);
    color: #fff;
    font-size: 11px;
    font-weight: 600;
  }

  .hd-text {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;

    em {
      font-style: normal;
      color: var(--c-muted);
      margin-left: 6px;
    }
  }

  &.done {
    background: var(--c-primary-soft);
  }
}
</style>
