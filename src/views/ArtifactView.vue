<template>
  <div class="art-view">
    <div class="head">
      <h1>产物浏览</h1>
      <p class="lead">
        查看当前会话的文件与运行结果
      </p>
      <div class="sess">
        <span class="k">会话</span>
        <code>{{ sandbox.sessionId || '（未建立）' }}</code>
        <button class="btn tiny" @click="sandbox.refreshTree()">刷新</button>
      </div>
    </div>

    <div class="split">
      <div class="col tree-col">
        <ArtifactPanel />
      </div>
      <div class="col meta-col">
        <div class="meta-card">
          <div class="t">环境信息</div>
          <dl>
            <dt>赛题</dt>
            <dd>{{ problemId }}</dd>
            <dt>会话数</dt>
            <dd>1（一题一会话）</dd>
            <dt>产物文件</dt>
            <dd>{{ sandbox.tree?.length ?? 0 }} 个</dd>
            <dt>数据来源</dt>
            <dd>
              <span v-if="sandbox.isMock" class="mock-flag">MOCK</span>
              <span v-else>真实后端</span>
            </dd>
          </dl>
        </div>

        <div class="meta-card">
          <div class="t">保留策略</div>
          <p v-if="sandbox.retentionDays > 0">
            产物保留 {{ sandbox.retentionDays }} 天，到期由后端自动清理。
          </p>
          <p v-else class="warn">
            当前未配置前端可见的保留天数。后端若已开启自动清理，
            请把实际策略同步到 <code>VITE_SANDBOX_RETENTION_DAYS</code>，
            否则这里不会显示保留提示。
          </p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, watch } from 'vue'
import ArtifactPanel from '@/components/sandbox/ArtifactPanel.vue'
import { useProblemSessionStore } from '@/stores/problemSession'
import { useSandboxStore } from '@/stores/sandbox'

const props = defineProps<{ problemId: string }>()
const sandbox = useSandboxStore()
const ps = useProblemSessionStore()

async function bind(): Promise<void> {
  const tid = ps.enterProblem(props.problemId)
  // 只绑定上下文与产物，不重放对话
  if (sandbox.sessionId !== tid) await sandbox.attach(props.problemId, tid)
  else await sandbox.refreshTree()
}

watch(() => props.problemId, () => void bind(), { immediate: true })
onBeforeUnmount(() => sandbox.detach())
</script>

<style scoped lang="scss">
.art-view {
  height: 100%;
  overflow: auto;
  padding: 24px 28px 32px;
}

.head {
  max-width: 780px;
  margin-bottom: 18px;

  h1 {
    margin: 0 0 6px;
    font-size: 20px;
  }

  .lead {
    margin: 0 0 10px;
    color: var(--c-muted);
    font-size: 13px;
    line-height: 1.8;
  }
}

.sess {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;

  .k {
    color: var(--c-muted);
  }

  code {
    color: var(--c-primary-ink);
  }

  .btn.tiny {
    padding: 1px 10px;
    font-size: 12px;
  }
}

.split {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(260px, 1fr);
  gap: 16px;
  align-items: start;
}

.col {
  min-width: 0;
}

.tree-col {
  height: 560px;
  border: 1px solid var(--c-line);
  border-radius: var(--radius);
  overflow: hidden;
  background: var(--c-surface);
}

.meta-col {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.meta-card {
  padding: 14px 16px;
  background: var(--c-surface);
  border: 1px solid var(--c-line);
  border-radius: var(--radius);

  .t {
    margin-bottom: 10px;
    font-weight: 600;
    font-size: 13px;
    color: var(--c-ink-2);
  }

  p {
    margin: 0;
    font-size: 12.5px;
    line-height: 1.8;
    color: var(--c-muted);

    &.warn {
      color: var(--c-warn-ink);
    }
  }

  dl {
    display: grid;
    grid-template-columns: 72px 1fr;
    gap: 6px 10px;
    margin: 0;
    font-size: 12.5px;

    dt {
      color: var(--c-muted);
    }

    dd {
      margin: 0;
      color: var(--c-ink);
      word-break: break-all;
    }
  }
}

@media (max-width: 980px) {
  .split {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 600px) {
  .art-view { padding: 16px 14px 24px; }
  .head { margin-bottom: 12px; }
  .sess { flex-wrap: wrap; }
  .sess code { min-width: 0; overflow-wrap: anywhere; }
  .tree-col { height: clamp(320px, 62vh, 560px); }
}
</style>
