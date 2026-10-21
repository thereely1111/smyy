<template>
  <section class="panel artifact">
    <div class="p-head">
      <span>产物</span>
      <span v-if="sandbox.tree?.length" class="count">{{ sandbox.tree.length }} 项</span>
    </div>

    <div class="p-body">
      <div v-if="sandbox.treeLoading" class="empty-hint">加载中…</div>

      <div v-else-if="sandbox.treeError" class="notice warn">{{ sandbox.treeError }}</div>

      <!-- 空状态必须区分原因：没产物 vs 被清理 -->
      <div v-else-if="!sandbox.tree?.length" class="empty-hint">
        <template v-if="sandbox.emptyReason === 'cleaned'">
          <strong>产物已按策略清理</strong><br />
          这个会话曾经产出过文件，但保留期已过。
          <span v-if="sandbox.retentionDays > 0">当前保留策略：{{ sandbox.retentionDays }} 天。</span>
        </template>
        <template v-else>
          <strong>这个会话还没有产物</strong><br />
          让智能体跑一段代码，生成的文件会出现在这里。
        </template>
      </div>

      <ul v-else class="tree">
        <li v-for="{ node: n, depth } in visibleNodes" :key="n.path">
          <button type="button" class="node" :class="{ on: n.path === sandbox.selectedPath }"
            :style="{ paddingLeft: `${8 + depth * 16}px` }"
            :aria-pressed="n.type === 'file' ? n.path === sandbox.selectedPath : undefined"
            :aria-expanded="n.type === 'dir' ? !collapsed.has(n.path) : undefined"
            @click="open(n)">
            <ArrowRight v-if="n.type === 'dir'" class="chevron" :class="{ expanded: !collapsed.has(n.path) }" aria-hidden="true" />
            <component :is="iconOf(n)" class="ico" aria-hidden="true" />
            <span class="name" :title="n.path">{{ n.name }}</span>
            <span v-if="n.size != null" class="size">{{ fmtSize(n.size) }}</span>
          </button>
        </li>
      </ul>
    </div>

    <!-- 内容预览 -->
    <div v-if="sandbox.selectedPath" class="preview">
      <div class="pv-head">
        <span class="pv-path" :title="sandbox.selectedPath">{{ sandbox.selectedPath }}</span>
        <!-- 代码文件：一键跳到编辑页去改与跑，闭环入口 -->
        <button
          v-if="runnable"
          class="btn tiny edit"
          title="打开编辑与运行页"
          @click="editAndRun"
        >
          编辑并运行
        </button>
        <button class="btn tiny" @click="sandbox.closeFile()">关闭</button>
      </div>
      <div v-if="sandbox.contentLoading" class="empty-hint">读取中…</div>
      <div v-else-if="sandbox.contentError" class="pv-note" role="alert">
        {{ sandbox.contentError }}
        <button class="btn tiny" type="button" @click="retryFile">重试</button>
      </div>
      <div v-else-if="unreadable" class="pv-note">
        {{ sandbox.content?.reason || '这个文件没有可预览的文本内容' }}
      </div>
      <pre v-else class="pv-body">{{ sandbox.content?.content }}</pre>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, Document, Folder, Grid, Picture } from '@element-plus/icons-vue'

import { isRunnable } from '@/engine/runnable'
import { useSandboxStore } from '@/stores/sandbox'
import type { ArtifactNode } from '@/types/sandbox'

const sandbox = useSandboxStore()
const router = useRouter()
const route = useRoute()
const collapsed = ref(new Set<string>())
const visibleNodes = computed(() => {
  const nodes: { node: ArtifactNode; depth: number }[] = []
  const visit = (items: ArtifactNode[], depth: number) => {
    for (const node of items) {
      nodes.push({ node, depth })
      if (node.type === 'dir' && !collapsed.value.has(node.path)) visit(node.children ?? [], depth + 1)
    }
  }
  visit(sandbox.tree ?? [], 0)
  return nodes
})

/** 当前预览的文件能否运行（决定是否给「编辑并运行」入口） */
const runnable = computed(() => (/\.py$/i.test(sandbox.selectedPath) || isRunnable(sandbox.selectedPath)))

/**
 * 内容不可预览（二进制 / 超限 / 非 UTF-8）。
 *
 * 后端这类情况**不抛错**，只把 `content` 置 null 并在 `reason` 里写明原因。
 * 早先这里认的是后端并不返回的 `binary` / `truncated`，结果这类文件既不提示原因，
 * `content: null` 还会被当成「空文件」塞进沙箱文件清单。
 */
const unreadable = computed(() => sandbox.content != null && sandbox.content.content == null)

function open(n: ArtifactNode): void {
  if (n.type === 'dir') {
    const next = new Set(collapsed.value)
    if (next.has(n.path)) next.delete(n.path)
    else next.add(n.path)
    collapsed.value = next
    return
  }
  void sandbox.openFile(n)
}

function retryFile(): void {
  const node = sandbox.tree?.find((item) => item.path === sandbox.selectedPath)
  if (node) open(node)
}

/** 跳到编辑页 —— problemId 从当前路由取，保证进的是同一个会话 */
function editAndRun(): void {
  const pid = String(route.params.problemId ?? '')
  if (!pid) return
  void router.push(`/sandbox/editor/${pid}`)
}

function iconOf(n: ArtifactNode) {
  if (n.type === 'dir') return Folder
  const ext = n.name.slice(n.name.lastIndexOf('.') + 1).toLowerCase()
  if (['csv', 'tsv', 'xlsx', 'xls'].includes(ext)) return Grid
  if (['png', 'jpg', 'jpeg', 'pdf'].includes(ext)) return Picture
  return Document
}

function fmtSize(bytes: number): string {
  if (bytes < 1024) return `${bytes}B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}K`
  return `${(bytes / 1024 / 1024).toFixed(1)}M`
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
  padding: 8px;
}

.notice {
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  font-size: 12px;

  &.warn {
    background: var(--c-warn-soft);
    color: var(--c-warn-ink);
  }
}

.tree {
  margin: 0;
  padding: 0;
  list-style: none;
}

.node {
  display: flex;
  align-items: center;
  width: 100%;
  gap: 6px;
  min-height: 40px;
  padding: 6px 8px;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--c-ink-2);
  text-align: left;
  cursor: pointer;
  font-size: 12px;
  transition: background 0.12s ease, transform 0.12s ease;

  &:hover {
    background: var(--c-bg);
    transform: translateX(2px);
  }

  &:active { transform: scale(0.99); }
  &:focus-visible { outline: 2px solid var(--c-primary); outline-offset: -2px; }

  &.on {
    background: var(--c-primary-soft);
    color: var(--c-primary-ink);
    font-weight: 600;
  }

  .ico {
    width: 16px;
    height: 16px;
    color: var(--c-muted);
    text-align: center;
    flex-shrink: 0;
  }

  .chevron {
    width: 12px;
    height: 12px;
    flex-shrink: 0;
    transition: transform 0.12s ease;

    &.expanded { transform: rotate(90deg); }
  }

  .name {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .size {
    color: var(--c-muted);
    font-size: 11px;
    flex-shrink: 0;
  }
}

.preview {
  border-top: 1px solid var(--c-line);
  max-height: 46%;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}

.pv-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: var(--c-bg);
  border-bottom: 1px solid var(--c-line);

  .pv-path {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-family: var(--font-mono);
    font-size: 11px;
    color: var(--c-ink-2);
  }

  .btn.tiny {
    padding: 1px 8px;
    font-size: 11px;
  }

  .btn.edit {
    border-color: var(--c-primary);
    color: var(--c-primary-ink);
    background: var(--c-primary-soft);

    &:hover {
      background: var(--c-primary);
      color: #fff;
    }
  }
}

.pv-note {
  padding: 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--c-muted);

  .btn { margin: 8px 0 0; display: block; }
}

@media (prefers-reduced-motion: reduce) {
  .node, .chevron { transition: none; }
  .node:hover, .node:active { transform: none; }
}

.pv-body {
  margin: 0;
  padding: 8px 10px;
  overflow: auto;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--c-ink);
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
