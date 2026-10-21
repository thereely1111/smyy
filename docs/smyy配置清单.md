# smyy 配置清单

> 骨架期只列**内容清单**，不落代码文件。实现时照此创建即可。
> 配置项以本项目实际运行环境为准。
>
> 目录：`0` 后端与数据库对接 · `1–8` 六个配置文件与装配 · `9` 端口与地址 · **`10` 沙箱对接** · `11` 待确认项
>
> **前置约束**：复用 `E:\象数演易\agent-platform-java\agent-platform-java` 的既有能力，**不改动该后端项目任何代码**。
> **已拍板（2026-09-28）**：沙箱形态 =「一次会话 = 一个沙箱」；工作台**内嵌**；执行输出**结构化**；磁盘**自动清理**；环境具备 Docker（容器层在部署侧）。详见 §11.1。
> **范围**：只做沙箱（多页模块）。不含首页、题库、动画剧场、会员、**登录**（登录由他人负责）。

---

## 0. 后端与数据库对接（先行确认）

### 0.1 数据库

smyy **自己有一整套 PostgreSQL**，参数如下（已由用户确认，`2026-09-28`）：

| 项 | 值 |
| --- | --- |
| 类型 | **PostgreSQL** |
| 主机 | `192.168.1.245` |
| 端口 | `5432` |
| 库名 | `agent_platform` |
| 用户名 | `agent_platform` |
| 密码 | `agent_platform` |

> **⚠️ 库名与 agent-platform 的库撞了，但这是 smyy 自己的库。**
> 「同名 ≠ 同库」。配置时**不要**因为看到库名一样就以为在连 agent-platform 的库。
> 后端连接参数不进前端仓库。前端只知道「后端地址」，由 Vite 代理转发。

### 0.2 与 agent-platform 的关系（重要）

数模演易的**智能体能力**由 `agent-platform-java` 提供，该后端自己也要连一个 PostgreSQL。**两边的连接参数可能长得一样，但是两条独立的线**：

| 线 | 谁在用 | 存什么 |
| --- | --- | --- |
| **smyy 自己的 PG** | smyy 业务后端 | 赛题库、会员订单、额度批次、邀请关系、用户建模任务成果 |
| agent-platform 的 PG | 智能体运行后端 | 会话 transcript、产物、扩展字段、`users` 表 |

**两条线之间唯一需要对齐的东西**：用户身份。

smyy 库里的用户主键，需要能与 agent-platform 产出的 JWT 里 `userId` 对得上。对不上就会出现「能登录、但产物接口 401、工作台产物面板恒空」这种最难受的中间态。

> 这一条是**当前最大的未决项**（见 §11 第 3 项）。在它落定之前，产物面板只能靠 mock 演示。

**交接点**：智能体产出的 `problem_contract.json` 里的 `case_id` ↔ smyy 赛题的 `problem_id`。

### 0.3 后端地址

| 环境 | 地址 |
| --- | --- |
| 本机开发 | `http://localhost:8080`（默认，由 `VITE_BACKEND_TARGET` 覆盖） |

**前端一律写相对路径**（`/api/...`、`/agui/...`），由 Vite 代理转发。**不要写死 host** —— 后端未配 CORS，直连会被跨域拦截。

---

## 1. `package.json`

```jsonc
{
  "name": "smyy",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "description": "数模演易 · AI 驱动的数学建模协作工作台（Vue3 + TS + Element Plus）",
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc -b && vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "@element-plus/icons-vue": "^2.3.1",
    "@types/markdown-it": "^14.2.0",
    "element-plus": "^2.8.0",
    "markdown-it": "^14.3.1",
    "pinia": "^2.2.0",
    "vue": "^3.4.38",
    "vue-router": "^4.4.3"
  },
  "devDependencies": {
    "@agentscope-ai/agentscope": "^0.0.15",
    "@types/node": "^20.14.0",
    "@vitejs/plugin-vue": "^5.1.2",
    "sass": "^1.77.0",
    "typescript": "^5.5.0",
    "vite": "^5.4.2",
    "vue-tsc": "^2.1.0"
  },
  "packageManager": "pnpm@11.23.0+sha512.0000000..."
}
```

**依赖清单说明**：

| 项 | 值 | 说明 |
| --- | --- | --- |
| `name` | `smyy` | 项目标识 |
| `markdown-it` | 保留 | 论文正文、指导书渲染需要 |
| `@agentscope-ai/agentscope` | 保留（devDep） | AG-UI 类型定义，仅 `import type`，运行时不含该库 |
| 图表库 | **不引入** | 走 Canvas 2D + 原生 SVG；引图表库会与「零 CDN + 深度定制」冲突 |
| `packageManager` | pnpm 11.23.0 | hash 串按实机 `pnpm -v` 生成 |

---

## 2. `vite.config.ts`

```ts
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const backendTarget = env.VITE_BACKEND_TARGET || 'http://localhost:8080'
  const proxyOptions = {
    '/api':    { target: backendTarget, changeOrigin: true },
    '/agui':   { target: backendTarget, changeOrigin: true },  // AG-UI 流式对话
    '/public': { target: backendTarget, changeOrigin: true },  // 公开接口（赛题列表等）
  }

  return {
    plugins: [vue()],
    base: './',
    resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
    server: {
      port: 5181,          // ⚠️ 见下方说明
      strictPort: true,
      open: true,
      host: true,
      proxy: proxyOptions,
    },
    preview: { port: 5181, strictPort: true, proxy: proxyOptions },
    build: {
      assetsInlineLimit: 4096,
      chunkSizeWarningLimit: 2048,
    },
  }
})
```

### 端口选择

| 项目 | 端口 |
| --- | --- |
| smyy dev | **5181** |
| agent-platform 后端 | 8080 |
| agent-platform 前端 | 5174 |

### 三条必须照搬的配置

1. **`preview` 也要配 proxy** —— 预览构建产物时同样需要转发后端请求，只配 `server` 会在 preview 下全部 404。
2. **`base: './'`** —— 相对路径 base，便于任意子路径部署。
3. **`tsconfig.node.json` 产物目录改到 `node_modules/.tmp`** —— 否则 `vue-tsc -b` 会在根目录生成 `vite.config.js`，**Vite 会优先加载它而忽略 `.ts`**，改配置不生效且极难排查。

---

## 3. `tsconfig.json`

```jsonc
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "module": "ESNext",
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "preserve",
    "strict": true,
    "noUnusedLocals": false,
    "noUnusedParameters": false,
    "noFallthroughCasesInSwitch": true,
    "baseUrl": ".",
    "paths": { "@/*": ["src/*"] }
  },
  "include": ["src/**/*.ts", "src/**/*.d.ts", "src/**/*.tsx", "src/**/*.vue"],
  "references": [{ "path": "./tsconfig.node.json" }]
}
```

**`noUnusedLocals` / `noUnusedParameters` 显式关闭**：数模演易的动画幕代码会有大量暂存变量（节拍中间值、调试用中间量），开着会淹没真正的错误。

## 4. `tsconfig.node.json`

```jsonc
{
  "compilerOptions": {
    "composite": true,
    "skipLibCheck": true,
    "module": "ESNext",
    "moduleResolution": "bundler",
    "allowSyntheticDefaultImports": true,
    "strict": true,
    "noEmit": true,
    "outDir": "node_modules/.tmp"     // ⚠️ 关键：防止生成根目录 vite.config.js
  },
  "include": ["vite.config.ts"]
}
```

> `outDir` 指向 `node_modules/.tmp` 是**必需**的。若不设，`vue-tsc -b` 会在根目录产出 `vite.config.js`。

---

## 5. `.env.example`

```bash
# 复制本文件为 .env.local 后按需修改（.env.local 不入库）

# ── 登录对接模式 ──────────────────────────────────────────
#   real = 对接后端 /api/auth/*（默认，需要后端已启动）
#   mock = 沿用本地演示账号，后端没启动时也能演示
VITE_AUTH_MODE=real

# ── 后端地址（Vite 代理目标） ──────────────────────────────
#   本机开发：http://localhost:8080
VITE_BACKEND_TARGET=http://localhost:8080

# ── 智能体（File Agent）中文目录名 ────────────────────────
#   其 Agent ID = UUID.nameUUIDFromBytes(此名)，与后端
#   workspace/agents/<此名>/ 目录一一对应。
#   改名只改这里，无需手算 UUID、也不用改代码。
VITE_SMYY_AGENT_NAME=数模演易

# ── 沙箱（复用智能体会话运行底座） ─────────────────────────
#   无独立沙箱接口：一次会话 = 一个沙箱。
#   「跑代码」= 发一条消息让模型调 execute 工具；产物走 artifacts 接口。
VITE_SANDBOX_MODE=real
#   real = 走既有 /agui + artifacts + attachments 接口；mock = 本地假会话
VITE_SANDBOX_ARTIFACTS_POLL=0
#   产物树轮询间隔（ms）。0 = 不轮询，仅在 run 结束 / 手动刷新时拉一次（推荐）
VITE_SANDBOX_ATTACH_MAX_MB=20
#   附件单文件上限（与后端 MAX_ATTACH_BYTES 一致；改这里必须同步后端）
VITE_SANDBOX_EXEC_STRUCTURED=true
#   是否把 execute 的工具结果解析为 {stdout, stderr, exitCode, elapsed}。
#   false = 只展示原始文本。解析失败会自动降级为原始文本，不会空白。
VITE_SANDBOX_RETENTION_DAYS=0
#   会话产物保留天数，仅用于**前端文案提示**（0 = 不提示）。
#   真正的清理由部署侧 agent-platform.runtime-gc-enabled 决定；
#   这里填的数必须与部署侧实际策略一致，填错会让用户看到假承诺。

# ── 功能开关（按阶段逐步开放） ─────────────────────────────
VITE_FEATURE_INVITE=true
VITE_FEATURE_AMBASSADOR=true
VITE_FEATURE_PARTNER=false
VITE_FEATURE_THEATER=true
VITE_FEATURE_SANDBOX=true
```

**只写人类可读的名字，不写 UUID** —— 推导交给 `src/utils/nameUuid.ts`。

> `.env` 的维护原则（沿用既有约定）：**只追加、不删改**。

---

## 6. `.gitignore`

```gitignore
# 依赖
node_modules/
.pnpm-store/

# 构建产物
dist/
dist-ssr/
build/
.output/
.vite/
.vite-cache/

# 类型检查产物（⚠️ vite.config.js 必须忽略）
*.tsbuildinfo
tsconfig.tsbuildinfo
tsconfig.node.tsbuildinfo
vite.config.js
vite.config.d.ts

# 环境变量（只保留 .env.example）
.env
.env.*
!.env.example

# 日志
logs/
*.log
npm-debug.log*
yarn-debug.log*
pnpm-debug.log*

# 编辑器
.vscode/*
!.vscode/extensions.json
.idea/
*.suo

# 系统
.DS_Store
Thumbs.db
```

---

## 7. `index.html`

```html
<!DOCTYPE html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <link rel="icon" type="image/svg+xml" href="/favicon.svg" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <meta name="description" content="数模演易 · AI 驱动的数学建模协作工作台，把历年真题变成你的练习场" />
    <title>数模演易 · AI 驱动的数学建模协作工作台</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
```

> **不要在这里引 CDN 字体或图标库**。字体本地自托管（`src/styles/fonts.scss`），图标内联 SVG。

---

## 8. `src/main.ts` 装配顺序

```ts
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import App from './App.vue'
import router from './router'
import { useUserStore } from '@/stores/user'
import { vReveal } from '@/directives/reveal'
import '@/styles/global.scss'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.directive('reveal', vReveal)

// real 模式：尽快恢复登录会话。不阻塞首屏渲染；
// 受保护路由的守卫会 await 同一个 Promise。
void useUserStore(pinia).ensureRestored().catch(() => undefined)

app.mount('#app')
```

> `ensureRestored()` **必须用 `void` 不 await** —— 阻塞首屏渲染是错误做法。守卫里再 await 同一个 Promise 即可。

---

## 9. 端口与地址一览

| 配置项 | 值 | 说明 |
| --- | --- | --- |
| `name` | `smyy` | 项目标识 |
| dev / preview 端口 | **5181** | `strictPort: true`，避开 agent-platform 前端的 5174 |
| `VITE_SMYY_AGENT_NAME` | **数模演易** | 对应后端 `workspace/agents/数模演易/`，UUID 由 `nameUuid.ts` 推导 |
| 新增 env | `VITE_FEATURE_*`、`VITE_SANDBOX_*` | 功能按阶段开放，清单见 §5 |
| 代理路径 | `/api` `/agui` `/public` | 后端一套，dev 与 preview 都必须配 |
| `tsconfig.node.json` 的 `outDir` | `node_modules/.tmp` | 必需，否则 `vite.config.js` 抢占配置 |
| `preview.proxy` | 与 `server.proxy` 同 | 必需，否则预览构建产物时接口全 404 |
| 后端地址 | `http://localhost:8080` | 由 `VITE_BACKEND_TARGET` 覆盖，前端一律写相对路径 |

---

## 10. 沙箱对接（复用既有智能体运行底座）

> **前提（本方案的地基）**：复用 `agent-platform-java` 的既有能力，**不改动该后端项目任何代码**。
> 因此“沙箱”不是一套新接口，而是把**已有的会话运行底座**当成沙箱来用。

### 10.1 关键结论：沙箱能力已经存在

盘过后端代码后确认，设计方案里规划的四件事，agent-platform 里**全都有现成实现**：

| 设计方案的要求 | 后端既有实现 | 位置 |
| --- | --- | --- |
| 云端沙箱，免环境配置 | 会话工作区 `runtimes/{agentId}/{sessionId}/`，装配期自动创建 | `SessionWorkspaceManager` |
| 浏览器内 Jupyter，数据预载 | 附件上传落到 `uploads/`，模型侧按 `@uploads/{文件名}` 引用展开 | `AttachmentController` |
| 代码运行 | `execute` 工具（真实 shell，默认超时 600s，上限 3600s），cwd = 会话目录 | `AgentAssemblyService.PlatformShellExecuteTool` |
| 按需启停、限额管控 | `IsolationScope.SESSION` 命名空间隔离 + `PathPolicy` 允许根 + `inheritEnv=false` | `AgentAssemblyService.RuntimeLocalFilesystemSpec` |

**所以本项目的沙箱形态是**：

```
smyy 前端
   │  ① POST /agui/run/{agentId}   「请运行这段代码」→ 模型调 execute 工具
   │  ② GET  .../artifacts        看产物   ③ GET .../artifacts/content  看文件内容
   ▼
agent-platform（既有代码，零改动）
   │
   ▼
runtimes/{agentId}/{sessionId}/        ← 会话工作区，即“沙箱”
   ├── uploads/                          赛题附件（数据预载）
   └── artifacts/                        模型跑出来的代码、结果、图表
```

**一句话**：**一次会话 = 一个沙箱**。沙箱不是后端的一个模块，而是会话工作区本身。

#### 「一会话一环境」到底是什么意思

先分清三样东西，就不绕了：

| 概念 | 在这里指什么 | 一句话 |
| --- | --- | --- |
| **会话**（session） | 你和智能体的**一整段连续对话**，由 `threadId` 标识 | 相当于「开了一个聊天窗口」 |
| **环境**（environment） | 一个**能存文件的目录** + 一套运行时（Python 等），代码在这里跑、产物存在这里 | 相当于「一台自己的电脑」 |
| **一会话一环境** | 上面的「聊天窗口」和「电脑」是**一对一绑定**的 | 窗口不关，电脑上的文件就在；换窗口 = 换电脑 |

**放到实际使用里**：

```
用户打开「2026 国赛 C 题」工作台
   └─ 前端新建会话 threadId = main-xxxx
        └─ 后端分配 runtimes/{agentId}/main-xxxx/   ← 这就是他的「环境」
             ├─ uploads/      他传的赛题附件住这里
             └─ artifacts/    模型跑出来的代码、结果、图表住这里

   同一题里切到「问题二」Tab —— 📌 threadId 不变 → 还是同一个目录
        问题一写的 clean.py 还在，问题二能直接读

用户关掉，明天从「2026 国赛 A 题」重新进 —— 📌 新建 threadId → 新目录
        两个赛题的附件和产物互不干扰
```

**所以它其实是个「随用随开、坏了不影响别人」的轻量环境**，不是常驻的虚拟机。好处是零运维（不需要预先启停容器、不需要管资源池）；代价是它跟会话同生命周期——用户如果**为每个小问题都新建会话**，就会以为自己「环境丢了」。

> 📌 这就是为什么 §10.5 要把 `problemId → threadId` 的映射钉死：**一道赛题全程只用一个会话**，环境的连续性才成立。

**和 B 组报告「一题一环境」的差别**：B 组要的是「同一个用户 + 同一道题 = 永远同一个环境」，哪怕关掉浏览器重来也是同一个；本方案是「同一个会话 = 同一个环境」，会话结束目录就不再被复用（文件还在磁盘上，只是不再当工作区用）。差别的实际影响只有一条：**用户换会话就换环境**，靠前端约束把「一题」和「一会话」对齐来消除。

### 10.2 与 B 组《云端沙箱与赛题环境说明报告》的口径差异（必须知情）

B 组报告设计的 `SandboxController` + 9 个接口 + 一题一环境表结构，是**在 agent-platform 里新增代码**的方案。既然本轮约束是“不动那个后端项目”，那套设计**无法落地**。两条路线对照：

| 维度 | B 组报告方案 | 本方案（复用既有底座） |
| --- | --- | --- |
| 后端改动 | 新增 Controller / Service / Domain / 迁移文件 | **零**（执行能力在既有装配层） |
| 环境粒度 | 一题一环境（`userId + problemId` 唯一） | **一会话一环境**（`agentId + sessionId`） |
| 隔离强度 | Docker 容器 | **Docker 容器**（见下方说明，与 B 组一致） |
| 持久化 | Docker 数据卷 | 会话目录（磁盘） |
| 生命周期接口 | start / stop / rebuild | 无独立接口，随会话创建；**自动清理**（见 10.9） |
| 用户身份 | JWT → `userId` | 同（既有归属校验） |

#### 关于隔离强度：容器与进程两层，各管一段

这里要说准确，避免误以为「上了容器就万事大吉」：

| 层 | 谁提供 | 挡住什么 |
| --- | --- | --- |
| **路径层** | 既有 `IsolationScope.SESSION` + `PathPolicy`（零改动） | 模型读写越出会话目录、读到别人的会话、污染宿主环境变量 |
| **容器层** | 执行侧以 Docker 运行（环境已具备） | 代码层面的逃逸、资源耗尽、系统调用越权 |

**两层的分工**：路径层管「模型跑偏」，容器层管「代码作恶」。日常教学场景（模型帮学生跑数据处理脚本）前者就够了；一旦要跑**用户自己提交的、未经审查的代码**，后者是必需的。

> **为什么仍然不动后端代码**：既有的执行工具（`PlatformShellExecuteTool`）本身不限定执行载体，容器化是**执行侧的环境准备**（镜像、运行时挂载），不是后端 Java 代码的职责。因此「上容器」与「零后端改动」并不冲突——**前提是容器这一层在部署侧做，而不是要求后端改代码去创建容器。**

#### 两条必须向上说明的取舍

1. **“一题一环境”变成“一会话一环境”**。B 组报告第十条验收清单第 2 条要求「问题一写入的文件，问题四仍可在同一环境读取」——本方案下**只要问题一至四在同一会话里提问，就满足**；但若前端为每个问题新建会话，就不满足。**这条要作为前端设计约束钉下去**（见 10.5）。
2. **多用户隔离仍然成立**（不同用户 = 不同会话），但**同用户跨赛题不在同一会话**时也不共享。

### 10.3 实际可用的接口（全部既有，零改动）

本方案**不消费** `/api/sandbox/**`（那套不存在）。真正要用的是这三组：

| 用途 | 接口 | 说明 |
| --- | --- | --- |
| 跑代码 | `POST /agui/run/{agentId}` | SSE 流式。把「运行代码」的意图作为用户消息发出，模型调 `execute` 工具执行 |
| 看产物树 | `GET /api/agents/{agentId}/sessions/{sessionId}/artifacts` | 返回合并后的产物树（含子会话） |
| 看文件内容 | `GET /api/agents/{agentId}/sessions/{sessionId}/artifacts/content?path=…` | 单文件 ≤ 5MB 文本预览；二进制只回占位说明 |
| 改文件 | `PUT .../artifacts/content?path=…` | 覆盖写，**只能改已存在的文件**（不新建不删除），≤ 2MB |
| 传附件 | `POST /api/agents/{agentId}/sessions/{sessionId}/attachments` | ≤ 20MB，扩展名白名单，落 `uploads/` |
| 列附件 | `GET .../attachments` | 返回 `fileId` / `ref` / `fileName` / `size` |
| 删附件 | `DELETE .../attachments/{fileId}` | — |

**两个关键事实**：

1. **执行能力长在智能体身上，不是独立接口**。「跑代码」= 发一条消息，让模型调 `execute`。后端返回的是**自然语言 + 工具结果混合的流**，不是现成的 `{stdout, stderr, exitCode}`。**结构化由前端从 SSE 事件里抽**（见下方「结构化执行输出」）。
2. **产物目录名可配**：`agent-platform.artifacts-dir`（默认 `artifacts`）。前端若硬编码 `artifacts/` 前缀，后端改配置就会失配——**产物路径一律用接口返回的 `path` 原样回传**。

#### 结构化执行输出（已定：要结构化）

既有的 `execute` 工具只把结果当文本回，所以结构化**必须在前端做**，从 SSE 事件流里抽：

| 要拿的东西 | 从哪里拿 | 说明 |
| --- | --- | --- |
| `stdout` / `stderr` | `TOOL_RESULT_*` 事件的返回体 | 工具回传原文，按行归到两路 |
| `exitCode` | 同上，从返回体里解析 | 拿不到时**回填 `null` 并在 UI 显式标注「退出码未知」**，不要伪造 0 |
| 命令行本身 | `TOOL_CALL_*` 事件的入参 | 用于在面板顶部回显“正在执行什么” |
| 耗时 | 前两个事件的时间戳相减 | 前端本地计时，不依赖后端字段 |

**三条实现纪律**：

1. **抽取逻辑写成纯函数**（`parseToolExec(event) => ExecResult | null`），放 `src/utils/`，**可单测**，不要写在组件里。
2. **解析失败要降级而不是丢结果**：抽不出结构就原样把文本塞进 `stdout` 展示，标一句「原始输出」。宁可难看，不可空白。
3. **`TOOL_CALL_*` / `TOOL_RESULT_*` 事件名以后端实际发出为准**，落地前先用一次真实运行抓一遍原始 SSE。**不要照抄推断出来的事件名**——这份清单里的名字若与后端不符，以前端抓到的为准。

### 10.4 附件白名单（前端必须同步的硬约束）

`AttachmentController.ALLOWED_EXTENSIONS` 是一份固定白名单，前端选择文件时必须**前置校验**，不要等后端 400：

```
txt md markdown json csv tsv
py java js ts tsx jsx html css sql xml yaml yml sh
pdf doc docx xls xlsx ppt pptx
png jpg jpeg
```

**两条容易踩的坑**：

| 坑 | 现象 | 前端要做的事 |
| --- | --- | --- |
| 中文文件名被 ASCII 化 | 后端把 `K8s灾备演练.pdf` 存成 `K8s.pdf`，纯中文名存成 `file.pdf`，靠 `uploads/.names.json` 记原名 | **展示用 `fileName` 字段**（原始名），`ref` 只用于发消息引用，不要把 `ref` 当展示名 |
| 单文件 20MB 上限 | multipart 全局 25MB，附件单独限 20MB | 选文件时先看 `size`，超限直接提示，不发请求 |

### 10.5 一题一环境的前端约束（本方案必须钉死的设计）

因为环境粒度是会话级，B 组报告那条验收「问题一写入的文件，问题四仍可读取」**只能靠前端不换会话来保证**：

| 约束 | 做法 |
| --- | --- |
| **一道赛题 = 一个会话** | `threadId` 与 `problemId` 绑定，不做“每个问题一个新会话” |
| 问题一至四在同一 `threadId` 里追问 | 用户切问题 Tab 时**只切 UI**，不改 `threadId` |
| 换赛题才换会话 | 离开赛题或换 `problemId` 时新建 `threadId`，旧会话产物保留 |
| `threadId` 前缀固定 `main-` | 后端按该前缀识别前端新建会话，不一致会导致产物目录分叉 |
| `threadId` + `runId` 一并持久化 | 只存 `threadId` 会导致刷新后点提交失败 |

**存储位置建议**：`localStorage` 里存 `problemId → threadId` 映射，进入工作台先查映射；命中就复用，未命中才新建。

#### agentId 不要运行时推导（2026-09-28 实测踩过）

后端 `FileSystemAgentLoader.java:107` 用 `UUID.nameUUIDFromBytes(目录名)` 推导 agentId。
前端若要复刻，必须实现完整的 MD5 + v3 位运算 —— **浏览器没有同步 crypto**，
手写一份 MD5 是 200 行且极易出错。

**开发期实测**：手写版算出的 UUID 与后端**不一致**（三个样例全错），
症状是「接口全通但产物面板恒空」，**没有任何报错**，排查代价极高。

**规则**：agentId 写成**常量**，配套一个换算校验脚本：

```bash
node scripts/check-agent-id.mjs            # 校验常量与目录名是否匹配
node scripts/check-agent-id.mjs 数模演易    # 换目录名时重新换算
```

当前值：`数模演易` → `bc68f09f-4705-3bf6-ab31-31c93410f59d`

### 10.6 代码落位

> ⚠️ **2026-10-12 校正**：下表原按 2026-09-28 的**规划名**书写；工程在 2026-10-10 做过一次分层重构
> （照 `kuaike` 基准——顶层按层切，原 `src/modules/` 摊平到 `src/{api,stores,views,components,utils,types}/`）。
> 下面「实际路径」列已按**当前工程**校正（2026-10-12 逐项 `[ -f ]` 核对）。照旧名去
> `src/stores/chat.ts`、`src/components/workbench/*` 找会**找不到**。

| 实际路径（当前工程） | 内容 | 原规划名 |
| --- | --- | --- |
| `src/api/http.ts` | 统一请求层（Bearer + 401 静默刷新 + 并发去重） | 同 |
| `src/api/agui.ts` | AG-UI 客户端：`POST /agui/run/{agentId}` + SSE 解析 | 同 |
| `src/api/artifact.ts` | 产物树 / 内容读取 / 覆盖写 | 同 |
| `src/api/attachment.ts` | 附件上传 / 列表 / 删除 | 同 |
| `src/types/agui.ts` | AG-UI 事件类型 + 中断 schema | 同 |
| `src/stores/analysis.ts` | `threadId` / `runId` 语义 + 流式收发 + 历史恢复（对话主状态） | `stores/chat.ts` |
| `src/stores/sandbox.ts` | 产物树 / 选中节点 / 文件内容 + 执行状态 / 结构化输出 / 附件（**合并了原 artifact 与执行两摊**） | `stores/artifact.ts` |
| `src/stores/problemSession.ts` | **`problemId → threadId` 映射**（保证一题一会话） | 同 |
| `src/components/sandbox/ChatPanel.vue` | 对话流 | `components/workbench/ChatStream.vue` |
| `src/components/sandbox/RunPanel.vue` | 「运行代码」入口 + 结构化执行结果面板（stdout / stderr 分色、退出码徽标、耗时） | `CodeRunner.vue` + `ExecOutput.vue` |
| `src/components/sandbox/ArtifactPanel.vue` | 产物树 + 文件内容查看 | `components/workbench/ArtifactDrawer.vue` |
| `src/components/sandbox/FileBar.vue` | 附件上传下载（含白名单与体积前置校验） | `components/workbench/FilePanel.vue` |
| `src/views/WorkbenchView.vue` | 组装：对话 + 产物抽屉 + 工作台（三栏内嵌布局） | 同 |
| `src/utils/parseToolExec.ts` | **纯函数**：从 `TOOL_CALL_*` / `TOOL_RESULT_*` 事件抽 `{command, stdout, stderr, exitCode, elapsed}`，可单测，解析失败降级为原始文本 | 同 |
| `src/engine/localRunner.ts`、`src/engine/runnable.ts` | 本地执行引擎（`new Function` 跑 js/ts）+ 可执行性判定 —— **纯前端演示通道，与 §10 的「后端 execute」无关** | — |

#### 执行通道的现状（2026-10-12 复核，避免再走弯路）

本方案的沙箱**只有一条通道**，就是 §10.3 的「跑代码 = 发消息让模型调 `execute` 工具」（走 AG-UI）。
**不存在也不需要**一个前端直连后端的 `POST .../shell/run` 端点 —— 这是 §11.1 D1「零后端代码改动」的直接推论，
后端侧也确实没有这样的 Controller（只有智能体 `@Tool` 的 `PlatformShellExecuteTool`）。

> ⚠️ `.env.local` 里那条 `VITE_SANDBOX_EXEC_CHANNEL=backend` 的注释（「后端接口尚未上线（契约已冻结）」）
> 描述的是**已被 D1 否掉的路线**：该变量的**读取代码在工程里已不存在**（全库 grep 0 命中），
> `.env.example`（§5）里也从未收录它。看到它不要把「补一个 shell/run 端点」当成待办。

### 10.7 与 AG-UI 的约定（不能搞错）

| 约定 | 原因 |
| --- | --- |
| 每轮**新消息**生成新 `runId` | 避免与未结束的旧 run 冲突 |
| resume 提交表单**复用同一 `runId`** | 否则后端认不出是哪次中断 |
| `threadId` 前缀固定 `main-` | 后端与平台前端以该前缀识别新建会话 |
| HITL 中断从 `RUN_FINISHED.outcome.interrupts[]` 取 | 工具确认 / 外部执行两类中断都映射为 `reason: 'tool_call'` |
| **不做乐观更新** | 执行是异步流，靠 SSE 事件收敛状态，不要点完就置“已完成” |

### 10.8 两条硬约束

1. **不做「自动运行」**：代码必须由用户点运行。与设计方案里「不代替用户执行」的既有口径一致，也符合智能体「只生成、不代跑」的角色定位。
2. **mock 模式要能完整演示交互**：`VITE_SANDBOX_MODE=mock` 时用本地假会话（含假产物树与假执行输出），保证后端未起时工作台不白屏。

### 10.9 工作台形态：内嵌（已定）

**形态定为一页内嵌工作台**，不做弹窗、不做跳转新页：

```
┌───────────────────────────────────────────────────────┐
│ 顶栏：赛题名 / 问题 Tab（一/二/三/四）/ 运行状态        │
├──────────────┬────────────────────┬───────────────────┤
│ 对话流        │ 代码 / 执行面板      │ 产物抽屉           │
│ ChatStream   │ CodeRunner          │ ArtifactDrawer    │
│              │  ├ 代码块            │  ├ 文件树          │
│              │  ├ [▶ 运行]          │  └ 内容预览        │
│              │  └ stdout/stderr     │                   │
│              │     + exitCode       │                   │
├──────────────┴────────────────────┴───────────────────┤
│ 附件条：FilePanel（上传 / 列表 / 删除，白名单前置校验）  │
└───────────────────────────────────────────────────────┘
```

**布局要点**：

| 点 | 做法 |
| --- | --- |
| 三栏宽度 | 对话流与产物抽屉可折叠（左 380–420px / 右 320–360px），中间自适应 |
| Tab 切问题 | **只切 UI 内容，不换 `threadId`**（见 10.5）——这是全节最容易被做错的一处 |
| 窄屏 | 三栏折叠为「对话 / 工作台」两个 Tab，产物抽屉改为抽屉式浮层 |
| 运行状态 | 顶栏一个状态点即可（空闲 / 运行中 / 出错），不要在页面里撒 toast |

> **为什么选内嵌而不是 JupyterLab 反代**：内嵌形态能共用同一套青绿设计令牌、同一套会话与产物状态、同一套权限；JupyterLab 反代则要额外承担反代配置、端口与鉴权桥接，且视觉割裂。既然后端没有 Jupyter 相关能力，**内嵌是唯一不需额外基础设施的选择**。

### 10.10 磁盘清理：自动（已定）

`runtime-gc-enabled` 默认是 **false**（不自动清理），**本方案要求打开它**——这是一处配置改动，不是代码改动。

| 项 | 做法 |
| --- | --- |
| 开启方式 | 部署侧 `application.yml` 的 `agent-platform.runtime-gc-enabled: true` |
| 谁做 | **部署人员**（前端与后端代码都不动） |
| 前端配合 | 产物抽屉显示「会话产物的保留时长」；过期后打开旧会话若产物为空，**提示「产物已按策略清理」而不是报错或白屏** |
| 待确认 | GC 的具体触发条件与保留时长（按天？按数量？），需部署侧给出后写进本节 |

**前端必须做的兼容**：产物树的空状态要区分两种原因——「这次会话确实还没产物」与「产物被清理了」。二者提示文案不同，否则用户会把清理当成 Bug 反馈。

> ⚠️ **若部署侧无法开启 GC**，则本项退化：磁盘只增不减，需要人工定期清理 `runtimes/` 下的旧会话目录。这种情况下**必须在前端的运维说明里写明「磁盘会持续增长」**，不要默认有人知道。

---
## 11. 待确认项

### 11.1 已拍板（2026-09-28 用户确认，勿再反复）

| # | 事项 | 决策 |
| --- | --- | --- |
| D1 | 沙箱形态 | **一次会话 = 一个沙箱**，复用 agent-platform 既有会话运行底座，**零后端代码改动** |
| D2 | Docker | 环境具备 Docker；容器化与「零后端改动」不冲突（容器层在**部署侧**做，非后端代码职责）。当前执行仍走既有 `execute` 工具 |
| D3 | 工作台形态 | **内嵌**（一页三栏：对话 / 执行 / 产物），不做 JupyterLab 反代（见 §10.9） |
| D4 | 执行输出 | **要结构化** `{command, stdout, stderr, exitCode, elapsed}`，由前端从 SSE 事件抽取（见 §10.3） |
| D5 | 磁盘清理 | **自动清理**，打开 `runtime-gc-enabled`（部署侧配置项，见 §10.10） |

### 11.2 仍然未决

| # | 事项 | 影响 | 谁能给答案 |
| --- | --- | --- | --- |
| 1 | smyy 自己那套 PG（`192.168.1.245:5432/agent_platform`）**表结构由谁定义**（后端 Flyway / 手写 DDL） | 决定 `src/api/*` 的响应结构 | 后端 |
| 2 | smyy 后端是**独立服务**还是与 agent-platform 同进程 | 决定 `VITE_BACKEND_TARGET` 是单地址还是双地址 | 后端 |
| 3 | **用户身份如何与 agent-platform 产出的 JWT `userId` 对齐**（当前最大未决项） | 不对齐则能登录但产物接口 401 → 工作台产物面板恒空 | 后端（两端都要动） |
| 4 | 支付渠道（微信 / 支付宝）与回调地址 | 决定 `api/member.ts` 的订单接口形态 | 业务 |
| 5 | 是否需要单文件 HTML 交付形态 | 若要，需独立构建脚本，不进主构建链 | 用户 |
| 6 | **GC 的具体触发条件与保留时长**（按天 / 按数量 / 按磁盘阈值） | 决定 §10.10 前端提示文案的「保留 N 天」填几 | 部署侧 |
| 7 | ~~`TOOL_CALL_*` / `TOOL_RESULT_*` 的准确事件名与返回体结构~~ | **已解决（2026-09-28 实测抓取，见 §12）** | 已自证 |
| 8 | 赛题数据「**内置兜底 + 远端增量**」的具体方案（内置集打包方式、增量拉取时机） | 决定 `src/data/` 与 `api/problem.ts` 的分工 | 用户（方向已定，待细化） |

## 12. 真实后端契约（2026-09-28 实测取证）

本节全部内容来自**对开发环境 `http://192.168.1.131:30100` 的真实请求**，
不是文档推断。抓取脚本与样本落在 `scripts/fixtures/real-sse-exec.txt`。

### 12.1 部署形态：前端页与 API 同端口

该地址由 nginx 反代，规则是：

| 路径前缀 | 去向 |
|---|---|
| `/api/**`、`/agui/**` | 转后端 API |
| 其余（含 `/actuator/health`、`/v3/api-docs`） | 回落前端 SPA（返回 `index.html`） |

**由此得到一条硬规则：判端点是否存在，不能只看 HTTP 状态码。**
未知的 GET 路径会被 SPA 兜底成 `200 + HTML`，看着像存在其实是假的。
必须看响应体的 `Content-Type` 是不是 JSON。

（本轮就吃过这个亏：先看到 `/actuator/health` 返回 200 + HTML，
一度判断「30100 是前端不是后端」，绕了一圈才发现 API 就在同一端口。）

### 12.2 鉴权

| 项 | 实测值 |
|---|---|
| 登录端点 | `POST /api/auth/login`，body `{ "username": "...", "password": "..." }` |
| 成功响应 | `{ accessToken, refreshToken, tokenType: "bearer", userId, username, role, permissions[] }` |
| JWT 算法 | **HS512**（不是 HS256，文档旧稿写错过） |
| accessToken 有效期 | **30 分钟**（`exp - iat = 1800`） |
| 未鉴权响应 | `401` + `{ "code": "UNAUTHORIZED", "message": "未登录或 token 缺失，请重新登录" }` |

### 12.3 agentId 推导（已用 4 个真实智能体反向验证）

后端 `FileSystemAgentLoader.java:107`：

```java
UUID id = UUID.nameUUIDFromBytes(dirName.getBytes(StandardCharsets.UTF_8));
```

用后端真实返回的 4 个智能体验证，**5/5 全部命中**（含自主推断的 `数模演易`）：

| 目录名 | agentId |
|---|---|
| `数模演易` | `bc68f09f-4705-3bf6-ab31-31c93410f59d` |
| `旅行规划师` | `30376c2e-c402-3feb-98f6-745460924435` |
| `会议纪要助手` | `ee74ae13-e7d8-379a-94bc-ff9dbf5831f4` |
| `数据分析助手` | `d01395cf-f5ba-33cb-aac7-8b789d362c8b` |
| `教材开发小助手` | `631f97d0-c074-3069-8196-e850831bbee0` |

校验命令：`node scripts/check-agent-id.mjs`（自检）/ `node scripts/check-agent-id.mjs 数模演易`（换算）。

另注意：`GET /api/agents` 返回的字段里有 `workspacePath: "agents/数模演易"`（**带 `agents/` 前缀**），
而 `definition.path` 是 `"agents/数据分析助手"`。写路径拼接时别漏这段前缀。

### 12.4 AG-UI 流式协议（真实事件序列）

请求：`POST /agui/run/{agentId}`，body `{ threadId, runId, messages: [{ role, content }] }`，
`Content-Type: application/json`，响应 `text/event-stream`。

**关键：即使 agentId 不存在，也返回 `200 + text/event-stream`**，
错误以 SSE 事件形式下发，不是 HTTP 4xx：

```
data: {"type":"RAW","threadId":"...","runId":"...","event":{"error":"Agent not found: bc68f09f-..."}}

data: {"type":"RUN_FINISHED","threadId":"...","runId":"..."}
```

所以前端**必须**把「流里的 `RAW.event.error`」也当成失败路径处理，
不能只依赖 HTTP 状态码 —— 否则会显示成一个空白的成功。

一次带工具调用的完整事件序列：

```
RUN_STARTED          回显 input（后端会把 messages 归一化，补 toolCalls/textContent 等）
TEXT_MESSAGE_START   { messageId, role: "assistant" }
TEXT_MESSAGE_CONTENT { delta }          ← 分片增量，逐条追加
TOOL_CALL_START      { toolCallId, toolCallName: "execute" }
TOOL_CALL_ARGS       { toolCallId, delta: "{\"command\": \"...\"}" }
TOOL_CALL_END        { toolCallId }
TOOL_CALL_RESULT     { toolCallId, content, role: "tool", messageId }
TEXT_MESSAGE_END
RUN_FINISHED
```

每个事件的顶层都带 `threadId` 与 `runId`（原样回显请求值）。

### 12.5 `execute` 工具结果的真实文本协议（最重要的一条）

`TOOL_CALL_RESULT.content` **不是 JSON**，是后端拼好的**纯文本**，
且外面套了一层 JSON 字符串字面量：

```
"Exit code: 0\n\nworkspace/n"                      ← 成功且有输出
"Exit code: 3\n\n<no output>\n\nExit code: 3"    ← 失败且无输出
```

拆解后的真实文本：

```
Exit code: 3
                      ← 空行
<no output>           ← 无输出时的占位符（不是空串！）
                      ← 空行
Exit code: 3          ← 尾部重复一次
```

因此抽取规则是：
1. 先 `JSON.parse` 一层，剥掉字符串字面量的转义
2. 用正则取头部的 `Exit code: (\-?\d+)`
3. 去掉尾部的重复 `Exit code: N`
4. 把 `<no output>` 占位符还原成空字符串

实现见 `src/utils/parseToolExec.ts` 的 `decodeContent()` / `splitExecText()`。
单测 `scripts/test-parse-exec.mjs` 里有**真实样本回放**用例。

**另一条观察**：同一条命令可能被模型调用**两次**（两个不同的 `toolCallId`），
说明有重试。所以 `ExecCollector` 必须按 `toolCallId` 分别记录，**不能去重**。

### 12.6 `TOOL_CALL_ARGS` 是分片还是整条？

实测单次调用里 `delta` 给的是**完整** JSON 字符串：
`"{\"command\": \"pwd && echo hello\"}"`。

但不能据此认为它永远是整条（AG-UI 协议本身允许分片）。
所以 `ExecCollector` 采用**累积拼接**策略：攒够再解析，
既能吃单条完整的，也能吃多条分片，且**单条时结果不变**。

### 12.7 还差一次实测的地方

| 项 | 为什么还没拿到 | 怎么补 |
|---|---|---|
| 产物树 / 附件接口的真实路径与响应 | 这些接口前端没调过，且 `数模演易` 目录尚未创建，无真实会话可测 | 等 `workspace/agents/数模演易/` 落地后跑一次真实会话 |
| `TOOL_CALL_RESULT` 里 stdout 与 stderr 如何区分 | 实测样本里成功/失败都只走 `content` 一个字段，没看到分开 | 跑一次「有 stderr 输出」的命令（如 `ls /nonexistent`）再看 |

