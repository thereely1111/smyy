<template>
  <section class="panel run">
    <div class="p-head">
      <span>执行</span>
      <span v-if="sandbox.execs.length" class="count">{{ sandbox.execs.length }} 次</span>
    </div>

    <div class="p-body">
      <div v-if="!sandbox.execs.length" class="empty-hint">
        还没有执行记录。<br />
        在左侧让智能体运行代码，这里会显示命令、标准输出与退出码。
      </div>

      <template v-else>
        <!-- 最近一次：完整展示 -->
        <div v-for="(e, i) in ordered" :key="e.toolCallId || i" class="exec" :class="{ err: e.isError }">
          <div class="exec-head">
            <span class="idx">#{{ ordered.length - i }}</span>
            <code class="cmd">{{ e.command || '（未取到命令）' }}</code>
            <span class="meta">
              <span v-if="e.exitCode === null" class="badge warn" title="后端未返回退出码">退出码未知</span>
              <span v-else class="badge" :class="e.exitCode === 0 ? 'ok' : 'bad'">
                exit {{ e.exitCode }}
              </span>
              <span v-if="e.elapsedMs !== null" class="ms">{{ fmtMs(e.elapsedMs) }}</span>
            </span>
          </div>

          <div v-if="e.stderr" class="stream">
            <div class="stream-label err-label">stderr</div>
            <pre class="out err-out">{{ e.stderr }}</pre>
          </div>

          <div class="stream">
            <div class="stream-label">
              stdout
              <span v-if="isRawFallback(e)" class="raw-tag" title="未能解析为结构化字段，按原始输出展示">
                原始输出
              </span>
            </div>
            <pre class="out">{{ e.stdout || '（无输出）' }}</pre>
          </div>
        </div>
      </template>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useSandboxStore } from '@/stores/sandbox'
import type { ExecResult } from '@/utils/parseToolExec'

const sandbox = useSandboxStore()

/** 倒序：最近一次在最上面 */
const ordered = computed<ExecResult[]>(() => sandbox.execs.slice().reverse())

/**
 * 是否是「解析失败后降级的原始输出」
 *
 * 判据：raw 与 stdout 相同，且退出码未知 —— 说明没抽到任何结构化字段。
 * 这种情况要在 UI 上标出来，让人知道看到的是原文不是解析结果。
 */
function isRawFallback(e: ExecResult): boolean {
  return e.exitCode === null && e.raw === e.stdout && e.raw.length > 0
}

function fmtMs(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}
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

.exec {
  margin-bottom: 16px;
  border: 1px solid var(--c-line);
  border-radius: var(--radius);
  overflow: hidden;

  &.err {
    border-color: #e6b3ac;
  }
}

.exec-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  background: var(--c-bg);
  border-bottom: 1px solid var(--c-line);

  .idx {
    color: var(--c-muted);
    font-size: 11px;
  }

  .cmd {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 12px;
    color: var(--c-ink);
  }

  .meta {
    display: flex;
    align-items: center;
    gap: 6px;
  }
}

.badge {
  padding: 1px 7px;
  border-radius: var(--radius-sm);
  font-size: 11px;
  font-weight: 600;
  background: var(--c-primary-soft);
  color: var(--c-primary-ink);

  &.bad {
    background: var(--c-error-soft);
    color: var(--c-error);
  }

  &.warn {
    background: var(--c-warn-soft);
    color: var(--c-warn-ink);
  }
}

.ms {
  color: var(--c-muted);
  font-size: 11px;
}

.stream {
  border-top: 1px dashed var(--c-line);

  &:first-of-type {
    border-top: none;
  }
}

.stream-label {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  color: var(--c-muted);
  font-size: 11px;

  &.err-label {
    color: var(--c-error);
  }
}

.raw-tag {
  padding: 0 6px;
  border-radius: var(--radius-sm);
  background: var(--c-warn-soft);
  color: var(--c-warn-ink);
  font-size: 10px;
}

.out {
  margin: 0;
  padding: 8px 10px;
  max-height: 260px;
  overflow: auto;
  background: #0b2e36;
  color: #d6ece8;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;

  &.err-out {
    color: #ffc9c0;
  }
}
</style>
