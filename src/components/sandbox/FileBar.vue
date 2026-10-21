<template>
  <div class="file-bar">
    <div class="lb">附件</div>

    <div class="list">
      <div v-if="sandbox.attachLoading" class="hint">加载中…</div>
      <div v-else-if="!sandbox.attachments.length" class="hint">
        还没有附件。传上赛题数据，智能体就能引用它们。
      </div>

      <div v-for="f in sandbox.attachments" :key="f.fileId" class="chip">
        <!-- 展示用 fileName（原始名）；ref 是 ASCII 名，不能拿来显示 -->
        <span class="nm" :title="f.fileName">{{ f.fileName }}</span>
        <span class="sz">{{ fmtSize(f.size) }}</span>
        <button class="x" title="删除" @click="sandbox.removeAttachment(f.fileId)">×</button>
      </div>
    </div>

    <div class="right">
      <input
        ref="picker"
        type="file"
        multiple
        class="hidden-input"
        @change="onPick"
      />
      <button class="btn" @click="picker?.click()">上传附件</button>
    </div>

    <div v-if="problems.length" class="problems">
      <span v-for="p in problems" :key="p" class="pmsg">{{ p }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useSandboxStore } from '@/stores/sandbox'

const sandbox = useSandboxStore()
const picker = ref<HTMLInputElement | null>(null)
const problems = ref<string[]>([])

/**
 * 选文件：**先在前端做白名单 + 体积校验**，不要让用户选完才吃 400。
 * 被拒的文件在下面以提示形式列出，不上传。
 */
async function onPick(ev: Event): Promise<void> {
  const input = ev.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = '' // 允许重复选同一文件
  if (!files.length) return

  const msgs = await sandbox.uploadFiles(files)
  problems.value = msgs.map((m) => m.message)
  if (!msgs.length) return
  // 5s 后自动清掉提示，别一直挂着
  window.setTimeout(() => {
    problems.value = []
  }, 5000)
}

function fmtSize(bytes: number): string {
  if (bytes < 1024) return `${bytes}B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}K`
  return `${(bytes / 1024 / 1024).toFixed(1)}M`
}
</script>

<style scoped lang="scss">
.file-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 16px;
  background: var(--c-surface);
  border-top: 1px solid var(--c-line);
  flex-shrink: 0;
  flex-wrap: wrap;
}

.lb {
  color: var(--c-muted);
  font-size: 12px;
  flex-shrink: 0;
}

.list {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
  flex-wrap: wrap;
  max-height: 96px;
  overflow-y: auto;

  .hint {
    color: var(--c-muted);
    font-size: 12px;
  }
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 8px;
  border: 1px solid var(--c-line);
  border-radius: 999px;
  background: var(--c-bg);
  font-size: 12px;
  max-width: 260px;

  .nm {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    color: var(--c-ink-2);
  }

  .sz {
    color: var(--c-muted);
    font-size: 11px;
    flex-shrink: 0;
  }

  .x {
    border: none;
    background: transparent;
    color: var(--c-muted);
    font-size: 14px;
    line-height: 1;
    padding: 0 2px;

    &:hover {
      color: var(--c-error);
    }
  }
}

.hidden-input {
  display: none;
}

.right {
  margin-left: auto;
}

.problems {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 2px;

  .pmsg {
    color: var(--c-error);
    font-size: 12px;
  }
}
</style>
