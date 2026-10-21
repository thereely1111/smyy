import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * 沙箱工作台 —— 只依赖 agent-platform 既有接口，零后端改动。
 *
 * 代理是必需品不是优化：agent-platform 未开放 CORS，
 * 浏览器直连后端端口会被跨域拦死，所以前端一律写相对路径。
 * preview 也必须配同一份 proxy，否则预览构建产物时接口全 404。
 *
 * 双后端分流（2026-10-21）：智能体侧与业务侧不是同一个部署，按路径前缀在代理层拆。
 * 环境变量 VITE_BACKEND_TARGET（业务）/ VITE_BACKEND_TARGET_AGENT（智能体）。
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  /** 业务侧后端（登录 / 用户 / 案例库 / 静态资源），库 = agent_platform */
  const backendTarget =
    process.env.VITE_BACKEND_TARGET || env.VITE_BACKEND_TARGET || 'http://192.168.1.245:8085'
  /** 智能体侧后端（AG-UI 运行流 / 会话 / 中断 / 通知流） */
  const agentTarget =
    process.env.VITE_BACKEND_TARGET_AGENT || env.VITE_BACKEND_TARGET_AGENT || 'http://192.168.1.245:8084'

  /**
   * 顺序敏感：vite 按 key 定义顺序做 startsWith 匹配、首个命中即止，
   * 所以智能体的具体前缀必须排在 `/api` 兜底之前。
   */
  const proxyOptions = {
    /**
     * 智能体侧的登录通道（双 token）：两端 JWT 密钥与本地 users 镜像都不通用，
     */
    '/agent-auth': {
      target: agentTarget,
      changeOrigin: true,
      rewrite: (p: string) => p.replace(/^\/agent-auth/, '/api/auth'),
    },
    '/agui': { target: agentTarget, changeOrigin: true },
    '/api/agents': { target: agentTarget, changeOrigin: true },
    '/api/agui-events': { target: agentTarget, changeOrigin: true },
    /** 用户级常驻 SSE，跟会话/中断同源，否则跨后端会串号 */
    '/api/notify': { target: agentTarget, changeOrigin: true },

    '/api': { target: backendTarget, changeOrigin: true },
    /**
     * 首页题库封面：后端由 ProblemCoverController 生成、落在 workspace/hero-covers/，
     * 经 WebConfig 的静态映射暴露在 /covers/**。不代理的话 dev 下会去 public/ 找 → 404。
     * 不必带鉴权：后端那条路由在 /api 之外，本来就免鉴权。
     */
    '/covers': { target: backendTarget, changeOrigin: true },
    /**
     * 案例正文：外置后 DB 只存相对路径，文件由后端 WebConfig 静态映射到内容根
     * （<仓库>/public/cases）暴露在 /case-files/**。同样在 /api 之外、免鉴权，
     * 不代理的话 dev 下会去 public/ 找 → 404。
     */
    '/case-files': { target: backendTarget, changeOrigin: true },
  }

  return {
    plugins: [vue()],
    base: './',
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: {
      port: 5181,
      strictPort: true,
      open: true,
      host: true,
      proxy: proxyOptions,
    },
    preview: {
      port: 5181,
      strictPort: true,
      proxy: proxyOptions,
    },
    build: {
      assetsInlineLimit: 4096,
      chunkSizeWarningLimit: 2048,
    },
  }
})
