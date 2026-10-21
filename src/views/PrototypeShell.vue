<!-- 由 scripts/port/build-port.mjs 从原型外壳生成，勿手改 -->
<!--
  站点外壳：原型四个视图（首页 / 题目详情 / 工作台 / 我的成果）+ 顶栏 + 全屏演示层。
  模板逐字取自原型，id 与 class 全部保留 —— 因为原型那 162 个函数就是靠
  这些 id 找 DOM 的（`$('view-home')` 之类）。改模板上的 id/class 会静默打断原型逻辑。
-->
<template>
<div class="smyy-proto">
<header class="nav">
  <div class="nav-inner">
    <a class="brand" onclick="go('home')" style="cursor:pointer">
      <div class="brand-mark">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round">
          <path d="M4 19V6M4 19h16M8 19v-6M12 19V9M16 19v-8M20 19v-4"/>
        </svg>
      </div>
      <div class="brand-txt">
        <span class="brand-name">数模演易</span>
        <span class="brand-sub">MathModeling Studio</span>
      </div>
    </a>
    <nav class="nav-links">
      <button id="nv-home" class="on" onclick="go('home')">首页</button>
      <button id="nv-mine" onclick="go('mine')">我的成果</button>
      <button v-if="isAdmin" id="nv-case-admin" @click="openCaseAdmin">案例管理</button>
      <button id="nv-work" style="display:none" @click="openWorkTask()">工作台</button>
    </nav>
    <div class="nav-right">
      <template v-if="!userStore.isLogged">
        <button class="btn-ghost" @click="openLogin">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
          学生入口
        </button>
        <button class="btn-login" @click="openLogin">登录</button>
      </template>
      <template v-else>
        <span class="nav-user" :title="userStore.displayName">{{ userStore.displayName }}</span>
        <button class="btn-ghost" @click="onLogout">退出</button>
      </template>
    </div>
  </div>
</header>

<!-- ==================== 首页 ==================== -->
<section id="view-home" class="view on">
  <div class="hero">
    <div class="hero-inner">
      <div class="hero-badge"><i class="dot"></i>AI 驱动的数学建模协作工作台</div>
      <h1>把历年真题，变成<span class="g">你的练习场</span></h1>
      <p class="sub">读懂、拆解、求解、成文，AI 一路陪你走完。专为大学生数学建模竞赛设计。</p>

      <div class="hero-reco">
        <div class="sec-head">
          <h2>2026 年竞赛真题</h2>
          <span class="sub">已完成完整解答 · 点击查看全部产物</span>
          <div class="grow"></div>
          <div class="sec-acts">
            <button class="icon-btn" id="car-prev" onclick="carMove(-1)" title="上一组">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 18l-6-6 6-6"/></svg>
            </button>
            <button class="icon-btn" id="car-next" onclick="carMove(1)" title="下一组">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18l6-6-6-6"/></svg>
            </button>
          </div>
        </div>
        <div class="car-view" id="car-view"><div class="car-track" id="car-track"></div></div>
        <div class="car-dots" id="car-dots"></div>
      </div>

      <div class="composer big">
        <textarea id="home-input" rows="2" placeholder="输入数学建模题目，或上传题目图片..."></textarea>
        <div class="cbar">
          <button class="btn-ghost" data-pick="problem" onclick="pickProblem('home')">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3v4.5a1.5 1.5 0 0 0 1.5 1.5H20"/><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M9 13h6M9 17h4"/></svg>
            上传题目
          </button>
          <button class="btn-ghost" data-pick="data" onclick="pickData('home')">
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="M17 8l-5-5-5 5"/><path d="M12 3v12"/></svg>
            上传附件
          </button>
          <span class="hint">题目支持 PDF / Word / 图片；附件支持 Excel / CSV 等数据文件</span>
          <div class="spacer"></div>
          <button class="btn-send" id="home-send" onclick="sendFromHome()">
            开始演易
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14M13 6l6 6-6 6"/></svg>
          </button>
        </div>
      </div>
      <div class="chips">
        <button class="chip" onclick="demoProblem()">2026 国赛 C 题 · 微网电力调控（进入演示）</button>
        <button class="chip" onclick="useSample(0)">2026 国赛 A 题 · 药材烘干</button>
        <button class="chip" onclick="useSample(1)">2026 国赛 B 题 · 干扰源定位</button>
        <button class="chip" onclick="useSample(2)">2026 MCM-A · 手机电池建模</button>
      </div>
    </div>
  </div>

  <div class="section" style="padding-top:30px">
    <div class="sec-head">
      <h2>全部赛题</h2>
      <span class="sub">2026 年真题 · 按赛别与题型筛选</span>
    </div>
    <div class="filters" id="filters"></div>
    <div class="grid" id="grid"></div>
  </div>

  <div class="foot">
    <div class="foot-in">
      <span>数模演易 · MathModeling Studio</span>
      <div class="grow"></div>
      <span>面向高职与本科生的数学建模竞赛实训平台</span>
    </div>
  </div>
</section>

<!-- ==================== 题目详情页 ==================== -->
<section id="view-detail" class="view">
  <div class="dtop">
    <div class="dtop-in">
      <button class="btn-back" onclick="go('home')">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 12H5M11 18l-6-6 6-6"/></svg>
        返回首页
      </button>
      <h1 id="d-title">—</h1>
      <div class="dmeta" id="d-meta"></div>
      <div class="dacts">
        <button class="w o" onclick="openWorkFromDetail()">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/></svg>
          在平台复现一遍
        </button>
        <button class="w" onclick="toast('指导书下载为演示功能')">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="M7 10l5 5 5-5"/><path d="M12 15V3"/></svg>
          下载实训指导书
        </button>
      </div>
    </div>
  </div>
  <div class="tabs">
    <div class="tabs-in" id="d-tabs">
      <button class="tab on" data-tab="guide">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>
        实训指导书
      </button>
      <button class="tab" data-tab="anim">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 3v17a1 1 0 0 0 1 1h17"/><rect x="7" y="12" width="3" height="6"/><rect x="12.5" y="8" width="3" height="10"/><rect x="18" y="4.5" width="3" height="13.5"/></svg>
        可视化演示
      </button>
      <button class="tab" data-tab="code">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M16 18l6-6-6-6"/><path d="M8 6l-6 6 6 6"/></svg>
        代码
      </button>
      <button class="tab" data-tab="result">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><path d="M22 4L12 14.01l-3-3"/></svg>
        成果
      </button>
    </div>
  </div>
  <div class="dbody">
    <div class="panel on" id="panel-guide"></div>
    <div class="panel" id="panel-anim"></div>
    <div class="panel" id="panel-code"></div>
    <div class="panel" id="panel-result"></div>
  </div>
</section>

<!-- ==================== 工作台 ==================== -->
<section id="view-work" class="view">
  <div class="wtop">
    <button class="icon-btn" onclick="go('home')" title="返回首页">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M19 12H5M11 18l-6-6 6-6"/></svg>
    </button>
    <span class="wtb" id="w-tag">新题目</span>
    <div class="wt" id="w-title">—</div>
    <div class="steps" id="w-steps"></div>
    <div style="flex:1"></div>
    <span class="env-badge inactive" id="w-env-badge"><span>未创建环境</span></span><span class="stag" id="w-stage"><i></i>等待你的指令</span>
    <button class="btn-ghost" id="btn-drawer-close" style="display:none" onclick="closeDrawer()">
      <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M18 6L6 18M6 6l12 12"/></svg>
      收起产物
    </button>
  </div>
  <div class="wbody">
    <!-- 任务列表：切任务 / 看阶段 / 暂停 / 删除都在这里（任务仓在 stores/tasks.ts） -->
    <aside class="wside" :class="{ pin: workSidePinned }">
      <div class="ws-head" title="展开 / 收起任务列表" @click="workSidePinned = !workSidePinned">
        <i class="ws-fold"></i><span>任务</span><b>{{ workTasks.length }}</b>
      </div>
      <div class="ws-list">
        <div v-if="!workTasks.length" class="ws-empty">还没有任务。回首页输入一道题，它就会出现在这里。</div>
        <div v-for="t in workTasks" :key="t.id" class="ws-row" :class="{ on: t.id === workCurrentId }" @click="openWorkTask(t.id)">
          <i class="ws-dot" :class="{ live: taskLive(t), paused: t.paused }"></i>
          <div class="ws-main">
            <div class="ws-name" :title="t.title">{{ shortTitle(t.title) }}</div>
            <div class="ws-meta">{{ taskMeta(t) }}</div>
          </div>
          <div class="ws-acts">
            <button class="ws-act" :disabled="busyTask === t.id" @click.stop="togglePauseTask(t.id)">{{ t.paused ? '继续' : '暂停' }}</button>
            <button class="ws-act" :class="{ hot: dropAsk === t.id }" :disabled="busyTask === t.id" @click.stop="askDropTask(t.id)">{{ dropAsk === t.id ? '确认删除' : '删除' }}</button>
          </div>
        </div>
      </div>
    </aside>
    <div class="chat">
      <div class="stream" id="stream"><div class="stream-in" id="stream-in"></div></div>
      <div class="composer-bar">
        <div class="cw">
          <div class="composer">
            <textarea id="work-input" rows="2" placeholder="和 AI 对话：问它下一步、提出修改意见，或直接在下方点快捷指令——你说一句，它才走一步"></textarea>
            <div class="cbar">
              <button class="btn-ghost" data-pick="problem" onclick="pickProblem('work')">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3v4.5a1.5 1.5 0 0 0 1.5 1.5H20"/><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M9 13h6M9 17h4"/></svg>
                上传题目
              </button>
              <button class="btn-ghost" data-pick="data" onclick="pickData('work')">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="M17 8l-5-5-5 5"/><path d="M12 3v12"/></svg>
                上传附件
              </button>
              <span class="hint">可继续补充题目材料或数据文件</span>
              <div class="spacer"></div>
              <button class="btn-send" onclick="sendInWork()">
                发送
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14M13 6l6 6-6 6"/></svg>
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
    <!-- 右栏：闸门 + 产物 + 演示（2026-10-12 用户定版：左对话 / 右闸门 + 产物；2026-10-13 增「演示」） -->
    <aside class="wrail" id="wrail">
      <div class="rail-tabs">
        <button type="button" class="rail-tab on" data-rail="gate">闸门<b class="rb" id="rail-gate-badge" style="display:none"></b></button>
        <button type="button" class="rail-tab" data-rail="art">产物<b class="rb" id="rail-art-badge" style="display:none"></b></button>
        <button type="button" class="rail-tab" data-rail="anim">演示<b class="rb" id="rail-anim-badge" style="display:none"></b></button>
        <div class="grow"></div>
        <button type="button" class="rail-min" id="rail-min" title="收起 / 展开右栏">›</button>
      </div>
      <div class="rail-pane on" id="rail-pane-gate">
        <div class="rail-gate-list" id="rail-gate-list"></div>
      </div>
      <div class="rail-pane" id="rail-pane-anim">
        <!-- 「可视化演示」面板：core.ts animRailRender() 画（解答做到哪一步，右边就出哪一步的） -->
        <div class="rail-anim-body" id="rail-anim-body"></div>
      </div>
      <div class="rail-pane" id="rail-pane-art">
        <div class="rail-art-list" id="rail-art-list"></div>
    <aside class="drawer" id="drawer">
      <div class="dh">
        <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="#12A594" stroke-width="2" stroke-linecap="round"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M9 9h6v6H9z"/></svg>
        <h3 id="d-title2">产物预览<span class="sub" id="d-sub2">—</span></h3>
        <button class="icon-btn" onclick="closeDrawer()">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><path d="M18 6L6 18M6 6l12 12"/></svg>
        </button>
      </div>
      <div class="db" id="drawer-body"></div>
    </aside>
      </div>
    </aside>
  </div>
</section>

<!-- ==================== 我的成果 ==================== -->
<section id="view-mine" class="view">
  <div class="mine">
    <div class="mine-head">
      <h1>示例成果</h1>
      <div class="grow"></div>
      <button class="btn-ghost" onclick="go('home')">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14"/></svg>
        新建建模任务
      </button>
    </div>
    <p class="mine-sub">这里展示预置演示案例，不是你的真实成果。分享开关仅演示交互，仅本次访问有效，不会公开文件。</p>
    <div id="mine-list"></div>
  </div>
</section>

<!-- 全屏演示遮罩层：挂载内联到本文件的动画 template（无 iframe、无外部文件），顶部 tab 可直接切换。
     放在所有 view section 之外——.view{display:none} 的祖先会吞掉 fixed 后代 -->
<div class="afs" id="afs" aria-hidden="true">
  <div class="afs-top">
    <span class="afs-title">可视化演示 · 全屏</span>
    <div class="afs-tabs" id="afs-tabs"></div>
    <button type="button" class="afs-close" id="afs-close" onclick="closeAnimFs()" title="退出全屏（ESC）">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 6L6 18"/><path d="M6 6l12 12"/></svg>
      <span>退出全屏</span>
    </button>
  </div>
  <div class="afs-body"><div class="afs-stage" id="afs-stage" aria-live="polite"></div></div>
</div>

<div class="microgrid-replay" id="microgrid-replay" aria-hidden="true">
  <div class="microgrid-replay-top">
    <span>案例复现</span>
    <button type="button" onclick="closeMicrogridReplay()" title="退出复现">退出复现</button>
  </div>
  <iframe id="microgrid-replay-frame" title="案例复现交互演示" src="about:blank"></iframe>
</div>

<div class="toast" id="toast"></div>
<input type="file" id="file-input" multiple style="display:none">
</div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { bootPrototype, shutdownPrototype } from '@/legacy/prototype/boot'
import {
  setPrototypeAnalysisHooks,
  setPrototypeAnimationHooks,
  setPrototypeCodegenHooks,
  setPrototypeNavHooks,
  setPrototypeWorkHooks,
} from '@/legacy/prototype/hooks'
import * as core from '@/legacy/prototype/core'
import { mountPrototypeCss, unmountPrototypeCss } from '@/styles/prototypeCss'
/* 分析通道自带的三张样式表（对话流 / 产物卡 / 报告卡）原本只在各自的渲染函数里
   懒注入。对话区是**可恢复**的：刷新后它是从任务快照整块贴回来的，那一刻没有任何
   渲染函数被调用 —— 那些 lv-* / art-* / rep-* 节点就全掉回浏览器默认样式
   （「对话内容没样式」，2026-10-12 用户报）。所以挂载时统一补一次，幂等。 */
import { ensureAnalysisStyles } from '@/utils/analysis/styles'
import { useUserStore } from '@/stores/user'
import { loadOutsourcedCases } from '@/stores/outsourcedCases'
import { createAnalysisHooks } from '@/legacy/bridge/analysisHooks'
import {
  currentCase,
  forgetSessions,
  resolveCaseIdFor,
  setThreadLandedSink,
  suspend,
  threadIdForTask,
  threadIdsForCase,
  threadIdsForTask,
} from '@/stores/analysis'
import { builtinProblem } from '@/data/problemStatement'
import { createAnimationHooks } from '@/legacy/bridge/animationHooks'
import { createCodegenHooks } from '@/legacy/bridge/codegenHooks'
import {
  beginTask,
  currentId as workCurrentId,
  currentTask,
  isLive,
  relTime,
  removeTask,
  saveRail,
  saveStage,
  saveStep,
  saveStream,
  selectTask,
  setPaused,
  setTaskCase,
  setTaskThread,
  shortTitle,
  flushRemote,
  hydrateTasks,
  tasks as workTasks,
  type WorkTask,
} from '@/stores/tasks'
import { clearSession, interruptSession } from '@/api/taskSync'
import {
  clearRequest,
  putRequest,
  saveStreamSnapshot,
  takeReceipt,
  takeStreamSnapshot,
} from '@/data/runHandoff'

/**
 * 站点题目 → 沙箱会话键。
 *
 * 沙箱不是另一套题库：它必须复用分析通道当前 CaseRef 的 caseId，才能与
 * S1/S2/S3 共享同一个 thread/session 和产物目录。详情页内置题目由
 * currentCase() 提供（例如 p3 → 2026-C），自定义题目同样沿用其 user-* caseId。
 */
function sandboxIdOf(protoId?: string | null): string {
  const active = currentCase()
  // 内置题目切换后，currentCase 可能短暂保留上一题；必须先校验 protoId，
  // 避免把新题目误绑到旧题目的 thread/session 和产物目录。
  if (active && active.protoId === protoId && active.caseId) return active.caseId
  if (protoId) return builtinProblem(protoId)?.caseId || protoId
  if (active?.caseId) return active.caseId
  return ''
}

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const isAdmin = computed(() => userStore.user?.permissions?.includes('admin:access') || userStore.user?.role?.toLowerCase().includes('admin') || false)

/** 视图 → URL 路径（detail 要看当前题目 id） */
function pathOf(view: string): string {
  if (view === 'detail') return `/p/${core.state.detailId ?? ''}`
  return view === 'home' ? '/' : `/${view}`
}

// 路由 ←→ 视图 双向同步。syncing 用来掐断「同步过去、又被监听到再同步回来」的循环。
let syncing = false

setPrototypeNavHooks({
  isLogged: () => userStore.isLogged,
  requireLogin: (view) => {
    void router.push({ name: 'login', query: { redirect: pathOf(view) } })
  },
  syncRoute: (view) => {
    const target = pathOf(view)
    if (route.path === target) return
    syncing = true
    void router.replace(target).finally(() => {
      syncing = false
    })
  },

  /** 视图切完顺带对账顶栏「工作台」按钮 —— 有任务时它在任何视图都该点得到 */
  syncNav: refreshNav,

  /* ── 沙箱衔接（本轮新增）──────────────────────────────────────────────
     对话里代码生成好之后给一条通往真沙箱的路，跑完的产物再回到这条对话。
     这里只负责「跳转 + 记交接单」，消息长什么样由 core.ts 的桥接层决定；
     产物回执的读取放在 onMounted 里（见 deliverSandboxReceipt）。 */

  /** 当前这道题在真沙箱里有没有环境 —— 决定「进入沙箱运行」按钮出不出现 */
  sandboxReady: () => sandboxIdOf(core.state.detailId) !== '',

  /** 「进入沙箱运行」：写下交接单再过去，沙箱那边据此预填运行指令 */
  gotoSandboxRun: (qn) => {
    const sid = sandboxIdOf(core.state.detailId)
    if (!sid) {
      toastTip('这道题的沙箱环境还没有开放，先用上面的演示流程走一遍', '')
      return
    }
    const q = core.QUIZ[qn] ?? {}
    putRequest({
      protoId: String(core.state.detailId ?? ''),
      sandboxId: sid,
      qn,
      qLabel: q.cn ? `问题${q.cn}` : `第 ${qn + 1} 问`,
      codeName: q.codeName || 'main.py',
      instruction: `运行 ${q.codeName || 'main.py'}，把结果写进产物目录`,
      at: Date.now(),
    })
    leaveTalk()
    void router.push('/sandbox/work/' + sid)
  },

  /** 对话里那张回执卡片的「在沙箱中查看产物」 */
  gotoSandboxArtifacts: (sandboxId) => {
    void router.push('/sandbox/artifacts/' + sandboxId)
  },
})

/* ── 真实分析通道（本轮新增）────────────────────────────────────────────
   注册给 core 用：进 C 题工作台时先让「数模演易」智能体读题并生成分析，
   分析与候选模型优先用它；未登录、后端不可用、跑失败都自动回落到内置剧本。
   注册表为空时 core 会当作「没有这条通道」，所以单跑原型逻辑不受影响。 */
setPrototypeAnalysisHooks(createAnalysisHooks())
/* 动画与代码两条通道同构注册 —— 注册表为空时 core 当作「没有这条通道」，
   单跑原型逻辑（脚本 / 测试）不受影响。 */
setPrototypeAnimationHooks(createAnimationHooks())
setPrototypeCodegenHooks(createCodegenHooks())

/* ── 工作台任务仓（2026-10-08 新增）────────────────────────────────────────
   为什么有它：工作台的状态只在内存里，一次刷新或一次热更新重载就全没了 ——
   表现是「聊着聊着跳回首页，地址栏还停在 #/work」。这里把每条任务（题面、
   题号、阶段、对话快照）落盘，并给首页一个常驻入口，能回到正在进行的任务、
   也能在多个任务之间切换。

   分工：落盘与列表在 stores/tasks.ts；core 侧只负责「开题登记」与
   「把某条任务贴回工作台」。两边通过 hooks 注册表对话，core 不认识 localStorage。 */
setPrototypeWorkHooks({
  currentId: () => workCurrentId.value,
  begin: (init) => {
    const t = beginTask(init)
    /* 开题这一刻把**会话键**记下来 —— 附件与题面都还在手上，算得准；此后暂停/删除
       一律按它查会话，不再重新推导（内存暂存区一刷新就没了）。
       ⚠ 2026-10-09 修：这里原来只有 setTaskThread(threadIdForTask(...))，而当时
       threadIdForTask 会顺手**新建**一条会话 → start() 里 ownedThreadForRun
       读到的 previous 恒非空 → 对这条刚建、后端还不认识的会话探活必然 404 → 判成
       「别人的会话」而 rotateThread。结果每开一道新题都白建一条死会话，任务仓记的
       正是它，暂停/删除打上去 404（实测 probe-pause：请求打到 main-bdbc1d63，后端 404）。
       会话 id 本身改由 start() 真正落地后回写（见下面的 setThreadLandedSink）。 */
    setTaskCase(t.id, resolveCaseIdFor(init.protoId, init.title, init.fromDetail, init.paperNames))
    setTaskThread(t.id, threadIdForTask(init.protoId, init.title, init.fromDetail))
  },
})

/**
 * 真正落地用的会话 id 由分析通道回报（start() 里 ownedThreadForRun /
 * prepareAttachments 都可能换过会话）。
 *
 * 按 caseId 认领任务，多任务并行时不会串台；一条都没认领到（任务仓里 caseId 为空，
 * 比如升级前留下的老任务）就兜给前台那条。
 */
setThreadLandedSink((caseId, threadId) => {
  if (!threadId) return
  let hit = false
  for (const t of workTasks.value) {
    if (t.caseId && t.caseId === caseId) {
      setTaskThread(t.id, threadId)
      hit = true
    }
  }
  if (hit) return
  const id = workCurrentId.value
  if (id) {
    setTaskCase(id, caseId)
    setTaskThread(id, threadId)
  }
})

/** 左侧任务栏的界面时钟：15 秒一跳，只为让「进行中」的小圆点自己灭掉 */
const nowTick = ref(Date.now())
/** 左栏被钉住（点了标题）：不靠鼠标悬停也保持展开 —— 触屏与「边看边切」时要用 */
const workSidePinned = ref(false)
/** 正在等后端回应的那条任务（暂停/删除要走网络，期间禁用按钮，避免连点） */
const busyTask = ref('')
/** 「删除」的两步确认：第一次点只是把它变成「确认删除」 */
const dropAsk = ref('')

function taskLive(t: WorkTask): boolean {
  return isLive(t, nowTick.value)
}

/** 侧栏每行的副标题：暂停态优先，其次才是阶段，末尾挂相对时间 */
function taskMeta(t: WorkTask): string {
  const phase = t.paused ? '已暂停' : t.stage || '尚未开始'
  return phase + ' · ' + relTime(t.updatedAt)
}

/** 顶栏「工作台」按钮：原型只在 work 视图里显示它；有任务时任何视图都该点得到 */
function refreshNav(): void {
  const btn = document.getElementById('nv-work')
  if (!btn) return
  btn.style.display = core.state.view === 'work' || workTasks.value.length ? 'block' : 'none'
}

/** 原型那个 toast 是两参数签名（第二参是图标），包一层免得每处都补个空串 */
function toastTip(msg: string, icon?: string): void {
  core.toast(msg, icon || '')
}

/** 回到某条任务的工作台（不传 id = 回最近动过的那条） */
function openWorkTask(id?: string): void {
  const t = (id ? workTasks.value.find((x) => x.id === id) : null) ?? currentTask()
  if (!t) return
  selectTask(t.id)
  if (core.state.view === 'work' && core.state.workTitle === t.title) return
  core.workSessionResume(t)
}

/**
 * 暂停 / 继续某条任务。
 *
 * 「暂停」要做两件事，顺序不能反：
 *   ① 让**后端**把在跑的 run 按中断路径收尾（产物目录保留，什么都不删）；
 *   ② 让**本地**的分析通道停止空转等产物。
 * 少了 ①，用户以为停了、后台还在烧模型；少了 ②，harvestUntil 会照旧空转最多 40 分钟。
 *
 * 后端那一步失败（后端没起、会话已不在）也照样打「已暂停」标记并如实说一句 ——
 * 用户点的是「停住这个任务」，本地至少要真的停住。
 *
 * 「继续」只清标记：会话与产物都还在，用户接着发消息就是新一轮 run。
 */
async function togglePauseTask(id: string): Promise<void> {
  if (busyTask.value) return
  const t = workTasks.value.find((x) => x.id === id)
  if (!t) return
  if (t.paused) {
    setPaused(id, false)
    toastTip('已继续。这条会话与产物都还在，接着往下走就行')
    return
  }
  busyTask.value = id
  try {
    /* 会话 id 两个来源合并：
       ① 任务仓里那个**会话键**（开题时记下，刷新/切视图都不丢）—— 主来源；
       ② 现按题面重算一次 —— 只为兼容升级前的老任务（那些没有 caseId）。
       ⚠ 必须取**本题全部会话**（全局那次 + 四问深挖各一条）：真正在烧模型的
       往往正是某一问 —— 单问深挖跑在独立的 -qN 会话里，只中断全局那次会出现
       「界面说已暂停、后端还在跑」。 */
    const fromFlow = [...new Set([
      ...threadIdsForCase(t.caseId),
      ...threadIdsForTask(t.protoId, t.title, t.fromDetail),
    ])]
    const sids = fromFlow.length ? fromFlow : t.threadId ? [t.threadId] : []
    if (sids.length && sids[0] !== t.threadId) setTaskThread(id, sids[0])
    if (!sids.length) {
      if (id === workCurrentId.value) suspend()
      toastTip('已暂停（这条任务还没在后端建会话，只停了本地等待）')
      return
    }
    let stopped = 0
    let failed = 0
    for (const sid of sids) {
      try {
        if (await interruptSession(sid)) stopped += 1
      } catch {
        failed += 1
      }
    }
    if (id === workCurrentId.value) suspend()
    toastTip(stopped
      ? '已暂停：后端这一轮（含单问深挖）已中断'
      : failed
        ? '已停住本地等待；后端中断没成功（' + failed + ' 个会话）'
        : '已暂停（后端这几条本来就没在跑）')
  } catch (e) {
    if (id === workCurrentId.value) suspend()
    toastTip('已停住本地等待；后端中断没成功（' + (e instanceof Error ? e.message : '未知原因') + '）')
  } finally {
    setPaused(id, true)
    busyTask.value = ''
  }
}

/** 「删除」是两步：先点一次变成「确认删除」，再点才真删（不可逆，且连后端会话一起清） */
function askDropTask(id: string): void {
  if (dropAsk.value !== id) {
    dropAsk.value = id
    return
  }
  void dropWorkTask(id)
}

/**
 * 删掉一条任务：**本地任务条 + 后端会话**一起清。
 *
 * 后端那步调的是「POST /sessions/{sid}/clear」：先中断在跑的 run，再删产物目录 / 状态行 /
 * 归属行（不可逆）。会话 id 为空的（题面太短、还没建过会话）就只删本地。
 * 后端失败也照样删本地 —— 否则用户会卡在一条删不掉的任务上；失败原因如实说一句。
 *
 * ⚠ **本题全部会话都要清**（全局那次 + 四问深挖各一条）：只清全局那次的话，
 * 某一问的 run 还在后台烧模型、产物目录也还在 —— 用户看到的正是「删了它还在跑」。
 */
async function dropWorkTask(id: string): Promise<void> {
  if (busyTask.value) return
  const t = workTasks.value.find((x) => x.id === id)
  dropAsk.value = ''
  if (!t) return
  const wasCurrent = workCurrentId.value === id
  /* 与「暂停」同一口径：任务仓的会话键优先，现算只兜升级前的老任务 */
  const fromFlow = [...new Set([
    ...threadIdsForCase(t.caseId),
    ...threadIdsForTask(t.protoId, t.title, t.fromDetail),
  ])]
  const sids = fromFlow.length ? fromFlow : t.threadId ? [t.threadId] : []
  if (sids.length) {
    busyTask.value = id
    let failed = 0
    try {
      for (const sid of sids) {
        try {
          await clearSession(sid)
        } catch {
          failed += 1
        }
      }
      if (failed) toastTip('有 ' + failed + ' 个会话没清掉，已只删本地任务')
    } finally {
      busyTask.value = ''
    }
    /* 后端置了墓碑 ⇒ 本地也不能再认这座会话：清「刚新建」豁免与 caseId→threadId 映射，
       否则同一道题再输一遍会指回墓碑，run 被 410 拒（界面表现为反复「会话已删除」）。 */
    forgetSessions(sids)
  }
  removeTask(id)
  core.workSessionDrop(id)
  if (!wasCurrent) return
  /* 删掉的是前台那条：还有别的任务就接着上一条，一条都不剩才回首页 */
  if (workTasks.value.length) openWorkTask()
  else if (core.state.view === 'work') core.go('home')
}

/**
 * 对话快照对账：每 1.5 秒把前台任务的对话区 HTML 存一次（内容没变就跳过）。
 *
 * 为什么是轮询而不是让 core 回调：往对话区写东西的地方有十几处
 * （pushMsg / thinking / analysisBoot / 各条真实通道 / 沙箱回执），逐个挂钩子
 * 既散又容易漏；而这条链路本来就有 700ms 的进度轮询，对账式读取与它同一口径。
 */
function snapshotWork(): void {
  const id = workCurrentId.value
  if (!id || core.state.view !== 'work') return
  const el = core.workHostEl()
  if (!el) return
  saveStream(id, el.innerHTML)
  const st = document.getElementById('w-stage')
  saveStage(id, st ? (st.textContent || '').trim() : '')
  /* 步骤条也要一起存（2026-10-12）：它只在 startWork() 里画过一次，刷新后没人补，
     顶上那块就是空的；恢复时得按「他当时停在第几步」画回来，不能一律第 0 步。 */
  saveStep(id, core.curStep)
  /* 右栏闸门区（2026-10-12）：闸门现在只活在右栏，不在对话区快照里 ——
     不单独存的话，刷新 / 切任务回来右栏会空掉，用户以为闸门丢了。 */
  const rg = document.getElementById('rail-gate-list')
  saveRail(id, rg ? rg.innerHTML : '')
  /* 右栏产物清单是对账出来的（来源是对话区里的 .arts .acard），
     顺手在这里对一次 —— 它与上面这条 1.5 秒的节拍天然同频。 */
  core.railSync()
}

/**
 * URL → 视图：直接输网址、刷新、前进后退都要能落到对的视图上。
 *
 * ⚠️ 「#/work」这一支要单独兜：工作台的状态（workTitle / detailId / 对话区）只在
 *    内存里，刷新或热更新重载后是空的，而 go('work') 第一行就是
 *    if(!state.workTitle){ toast(...); return } —— 不兜的话视图会停在首页、
 *    地址栏却还挂着 #/work，用户看到的就是「聊着聊着跳回首页」。
 *    所以这里先用任务仓里那条任务把工作台贴回来；贴不回来就把 URL 收回首页，
 *    让地址与视图口径一致，别留错乱。
 */
function applyRoute(path: string): void {
  const want = path.startsWith('/p/') ? 'detail' : path === '/mine' ? 'mine' : path === '/work' ? 'work' : 'home'
  if (core.state.view === want) return
  if (want === 'work' && !core.state.workTitle) {
    const t = currentTask()
    if (t) {
      selectTask(t.id)
      if (core.workSessionResume(t)) return
    }
    void router.replace('/')
    return
  }
  if (want === 'detail') core.openDetail(String(route.params.id))
  else core.go(want)
}

watch(
  () => route.path,
  (p) => {
    if (syncing) return
    applyRoute(p)
  },
)

function openLogin(): void {
  void router.push({ name: 'login', query: { redirect: route.fullPath } })
}

function openCaseAdmin(): void {
  if (!isAdmin.value) return
  void router.push({ name: 'case-admin' })
}

/* ── 沙箱入口（本轮新增）─────────────────────────────────────────────────
   双轨并存：原型那套演示工作台原样不动，这两个入口通向真沙箱。
   登录闸门不在按钮上做，交给路由 meta.requiresAuth —— 一个地方管，
   按钮不必各自判断，也就不会出现「有的入口拦、有的入口不拦」。 */

/**
 * 离开这条对话去沙箱之前，把对话区存一份。
 *
 * 沙箱走的是另一棵组件树（App.vue 里 /sandbox/* 挂的是沙箱外壳），
 * 一去一回 PrototypeShell 整个重建、对话区跟着空掉 —— 所以走之前留一份。
 * 快照只活在内存里（见 runHandoff.ts）：刷新后自然失效，
 * 不会把旧 DOM 贴回一个已经重置的状态上。
 */
function leaveTalk(): void {
  if (core.state.view !== 'work') return
  /* 取「当前任务的对话容器」而不是 #stream-in 本身 —— 容器是按任务分桶的，
     拿错了会把这份快照贴到别的任务上 */
  const stream = core.workHostEl()
  if (stream) saveStreamSnapshot(stream.innerHTML)
}

// 顶栏「沙箱」与详情页「进入沙箱环境」由路由统一承载；
// 对话里的「进入沙箱运行」按钮由 hooks.sandboxReady() 决定。
// leaveTalk() 保留：离开对话去沙箱前仍要存一份对话快照。

async function onLogout(): Promise<void> {
  await userStore.logout()
  core.go('home')
}

/** 把走之前存下的对话贴回去（没有快照就什么都不做） */
function restoreTalkStream(): void {
  const snap = takeStreamSnapshot()
  if (!snap) return
  const box = core.workHostEl()
  if (box) box.innerHTML = snap
}

/**
 * 把沙箱交回来的运行凭据追加成一条对话消息。
 * takeReceipt 是「取走即清」—— 否则每次进 /work 都会把同一条再追加一遍。
 */
function deliverSandboxReceipt(): void {
  const rcp = takeReceipt()
  if (!rcp) return
  clearRequest()
  core.pushSandboxResult(rcp)
}

/** 任务仓的两条对账定时器（卸载时收掉） */
let snapTimer: number | null = null
let tickTimer: number | null = null
/** 任务正文推后端的慢节拍（对话快照几十 KB，不能跟着 1.5 秒的对账节奏推） */
let pushTimer: number | null = null

/** 任务列表变了（新增 / 移除）就顺带对账顶栏入口 */
watch(workTasks, () => {
  refreshNav()
})

/** 页面临走前把还没推上去的任务推一次（切标签页、关页面都算） */
function flushOnHide(): void {
  if (document.visibilityState === 'hidden') void flushRemote()
}

onMounted(async () => {
  // 沙箱回来时组件会重建。这条对话要不要接续，看的是「走的时候人在哪个视图」，
  // 而不是回来的 URL —— 用户点顶栏「返回站点」时落的是首页，可这条对话该留着，
  // 切到工作台就能看见。
  const wasInWork = core.state.view === 'work'

  // 样式按需挂载：原型那 44K 样式是全局的，与沙箱模块有 17 个类名重叠，
  // 直接全局引入会静默改掉沙箱页面观感。详见 styles/prototypeCss.ts。
  mountPrototypeCss()
  /* 分析通道那三张样式表：跑着的时候由各自的渲染函数懒注入，刷新后没人注入 ——
     而对话区/产物卡/报告卡是**从快照贴回来的**，那些 lv-* / art-* / rep-* 节点
     就会掉回浏览器默认样式（用户看到的「对话内容没样式」）。这里与原型 CSS 一起
     补挂，幂等。 */
  ensureAnalysisStyles()

  /* 任务与对话的真相源在后端（会话扩展，见 api/workbenchStore.ts）。
     顺序要紧：applyRoute → workSessionResume 是「把任务贴回工作台」的唯一时机，
     所以要**先拉回来再落视图** —— 否则先贴一份本地缓存、随后才被新数据覆盖，
     用户会看到对话闪一下。
     后端慢或挂了也不能卡住页面：1.5 秒拿不到就按本地缓存走（hydrateTasks 自己
     吞掉所有网络错误，这里只是加一道时间闸）。 */
  await Promise.race([
    hydrateTasks(),
    new Promise<void>((resolve) => window.setTimeout(resolve, 1500)),
  ])
  await Promise.race([
    loadOutsourcedCases(),
    new Promise<void>((resolve) => window.setTimeout(resolve, 1500)),
  ])

  // 原型逻辑必须在模板进 DOM 之后启动（它一上来就 $('d-tabs') 找元素）
  bootPrototype()
  applyRoute(route.path)

  // 顺序要紧：先把对话贴回来，再把沙箱的回执接在它后面
  if (wasInWork) {
    restoreTalkStream()
    deliverSandboxReceipt()
  }

  // 任务仓的三条对账：① 对话快照（1.5s）② 界面时钟（15s，让「进行中」自己灭）
  // ③ 任务正文推后端（10s —— 对话快照几十 KB，不跟着 1.5 秒的节拍推）
  refreshNav()
  snapTimer = window.setInterval(snapshotWork, 1500)
  tickTimer = window.setInterval(() => {
    nowTick.value = Date.now()
  }, 15000)
  pushTimer = window.setInterval(() => {
    void flushRemote()
  }, 10000)
  document.addEventListener('visibilitychange', flushOnHide)
})

onUnmounted(() => {
  if (snapTimer) window.clearInterval(snapTimer)
  if (tickTimer) window.clearInterval(tickTimer)
  if (pushTimer) window.clearInterval(pushTimer)
  snapTimer = null
  tickTimer = null
  pushTimer = null
  document.removeEventListener('visibilitychange', flushOnHide)
  /* 临走再推一次：切路由 / 关页面时不该把最后几句话丢在本地 */
  void flushRemote()
  shutdownPrototype()
  unmountPrototypeCss()
})
</script>

<style scoped>
.nav-user {
  font-size: 13px;
  font-weight: 600;
  color: var(--blue-900, #0b6e68);
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
