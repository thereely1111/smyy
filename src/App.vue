<!--
  App 只做一件事：按路由选布局。
   - /sandbox/*  → 沙箱外壳（顶栏 + 内容区）。这块与改造前逐字一致，
                   只多了一个「返回站点」，保证沙箱模块零回归。
   - 其余（站点四视图 / 登录）→ 自己管版式，App 不插手。
-->
<template>
  <div v-if="isSandbox" class="app-shell">
    <header class="app-top">
      <div class="brand">
        <span class="dot" />
        <strong>数模演易</strong>
        <span class="sub">沙箱工作台</span>
      </div>

      <nav class="nav">
        <!-- 环境列表与工作台入口已撤：前者随沙箱预留题目数据删除，
             后者待改由站点内全屏层承载（见 router/index.ts 的说明）。 -->
        <RouterLink v-if="sandbox.problemId" :to="`/sandbox/artifacts/${sandbox.problemId}`">产物浏览</RouterLink>
        <RouterLink v-if="sandbox.problemId" :to="`/sandbox/editor/${sandbox.problemId}`">文件编辑</RouterLink>
      </nav>

      <div class="right">
        <span v-if="sandbox.isMock" class="mock-flag" title="后端未就绪，当前为本地演示数据">MOCK 演示数据</span>
        <span v-else class="live-flag" title="已对接真实后端">真实后端</span>
        <RouterLink class="back-site" to="/">返回站点</RouterLink>
      </div>
    </header>

    <main class="app-body">
      <RouterView />
    </main>
  </div>

  <RouterView v-else />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'

import { useSandboxStore } from '@/stores/sandbox'

const route = useRoute()
const sandbox = useSandboxStore()

/** 沙箱是独立模块，只有路径以 /sandbox 开头时才套沙箱外壳 */
const isSandbox = computed(() => route.path.startsWith('/sandbox'))
</script>

<style scoped lang="scss">
.app-shell {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.app-top {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 0 20px;
  height: 52px;
  background: var(--c-surface);
  border-bottom: 1px solid var(--c-line);
  flex-shrink: 0;
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--c-primary-ink);

  .dot {
    width: 10px;
    height: 10px;
    border-radius: 50%;
    background: var(--c-primary);
  }

  .sub {
    color: var(--c-muted);
    font-weight: 400;
    font-size: 13px;
  }
}

.nav {
  display: flex;
  gap: 4px;

  a {
    padding: 4px 12px;
    border-radius: var(--radius-sm);
    color: var(--c-ink-2);
    text-decoration: none;
    font-size: 13px;
    transition: all 0.15s ease;

    &:hover {
      background: var(--c-primary-soft);
      color: var(--c-primary-ink);
    }

    &.router-link-active {
      background: var(--c-primary-soft);
      color: var(--c-primary-ink);
      font-weight: 600;
    }
  }
}

.right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 10px;
}

.mock-flag {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 999px;
  background: #fdf3e0;
  color: var(--c-warn-ink);
  font-size: 12px;
  font-weight: 600;
}

.live-flag {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--c-primary-soft);
  color: var(--c-primary-ink);
  font-size: 12px;
  font-weight: 600;
}

.back-site {
  color: var(--c-ink-2);
  font-size: 13px;
  text-decoration: none;

  &:hover {
    color: var(--c-primary-ink);
  }
}

.app-body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

@media (max-width: 600px) {
  .app-top {
    height: auto;
    min-height: 52px;
    flex-wrap: wrap;
    gap: 6px 12px;
    padding: 8px 14px;
  }

  .brand .sub { display: none; }
  .nav { order: 3; width: 100%; overflow-x: auto; }
  .nav a { white-space: nowrap; min-height: 36px; display: inline-flex; align-items: center; }
  .right { flex-wrap: wrap; justify-content: flex-end; }
}
</style>
