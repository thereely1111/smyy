import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './styles/global.scss'

import { useUserStore } from '@/stores/user'
import { loadOutsourcedCases } from '@/stores/outsourcedCases'
import { ensureAnalysisStyles } from '@/utils/analysis/styles'

/**
 * 分析通道的三张样式表（对话流 / 产物卡 / 报告卡）在这里就挂上。
 *
 * 这三张表的选择器只有 `lv-*` / `art-*` / `rep-*` 三个前缀，与沙箱模块零重叠，
 * 全局挂是安全的（原型那 67K 不在此列，它仍只在站点外壳按需挂）。
 *
 * 为什么必须提到入口：这些样式是**只在 JS 里存在**的（不在任何 .scss 里），
 * 而挂载点（分析气泡 / 产物卡 / 报告卡）的内容可以来自三条不同路径 ——
 * 实时渲染、任务快照贴回、会话产物读回。只要有一条路径先落地 DOM、而没有任何
 * 注入点跑过，用户看到的就是「内容在、样式没了」。2026-10-12 连着报了两次，
 * 所以不再指望「某个钩子一定会跑」，入口先挂一次是这条链上最可靠的一环。
 */
ensureAnalysisStyles()

const pinia = createPinia()

const app = createApp(App)
app.use(pinia)
app.use(router)
app.mount('#app')

/**
 * 会话恢复（kuaike 范式）。
 *
 * 必须在 mount 之后触发：`ensureRestored` 是幂等的模块级 Promise，
 * 路由守卫里 await 的是同一个。这里提前点火，刷新页面时用户信息
 * 与路由守卫是并行的，而不是串行等两次。
 *
 * 不 await 是有意的：挂载不等网络，后端没起来也能正常进首页。
 */
void useUserStore(pinia)
  .ensureRestored()
  .catch(() => undefined)
void loadOutsourcedCases().catch(() => undefined)
