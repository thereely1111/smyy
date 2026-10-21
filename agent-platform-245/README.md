# AgentHub（智能体平台）

基于 **AgentScope Java 2.0** 的通用智能体开发与运行平台。智能体支持两种定义方式：
**管理端表单配置**（存数据库，适合简单智能体）与**文件型定义**
（`workspace/agents/` 下用 AGENTS.md 编排规则 + 多个子智能体协作，适合复杂场景）；
技能/工具从平台资源中心选择，子智能体以 Markdown 文件声明。运行时统一走
AG-UI SSE 对话协议（`/agui/run/{agentId}`），通过动态表单、内容卡片、任务进度与
人工确认（HITL）驱动交互，并配套知识库、会话产物与后台续跑（wakeup）管理。

## 技术栈

| 层 | 选型 |
|----|------|
| Agent 框架 | AgentScope Java `2.0.3-tipdm` |
| 后端 | Java 21 + Spring Boot 3.5.x + Spring Data JPA + Flyway |
| 数据库 | PostgreSQL 16 + pgvector |
| 缓存 | Redis |
| 前端 | React 19 + TypeScript + Vite + Tailwind v4 + shadcn/radix-ui |
| 运行时事件 | SSE（后端 `/api` 与 `/agui` 两个通道） |

## 仓库结构

```text
├── backend/                  # Spring Boot + AgentScope Java
│   ├── src/main/java/com/tipdm/agentplatform/
│   ├── src/main/resources/db/migration/   # Flyway SQL（V1–V62）
│   └── workspace/            # 运行时生成
├── frontend/                 # 前端：Vite + React 19 + TS + Tailwind v4 + shadcn
├── deploy/                   # Kubernetes/Helm 部署清单
├── docs/                     # 规范文档（OpenAPI、智能体对接指南、表单契约、知识库设计等，索引见 docs/README.md）
├── scripts/                  # 工具脚本（api.md 渲染、任务进度报告等）
├── workspace/                # 平台工作区（agents/ 下为 file-based 智能体定义）
├── docker/                   # docker/postgres/Dockerfile
└── docker-compose.yml        # 本地备用基础设施（PostgreSQL 16 + pgvector）
```

## 本地启动

```bash
# 1. 后端默认连接服务器上的 PostgreSQL / Redis（192.168.1.245）
#    PostgreSQL: 192.168.1.245:5432 / agent_platform / agent_platform / agent_platform
#    Redis:      192.168.1.245:6379，无密码
#    如需完全本地运行，再执行：docker compose up -d postgres

# 2. 后端（:8080）
#    Maven 直接可用：~/.m2/settings.xml 已配置
#      localRepository -> /Volumes/Data/maven-repository
#      Nexus mirror    -> 192.168.1.186:8081（central）
cd backend
mvn spring-boot:run

# 3. 前端（:5174，strictPort；/api 与 /agui 代理到 :8080）
cd frontend
pnpm install   # 首次
pnpm dev
```

> 登录委托外部**统一认证中心**（OAuth 密码模式，`agent-platform.auth.oauth.*`），
> 未配置 `AGENT_PLATFORM_OAUTH_SERVER_URL` 时登录接口直接拒绝（503）。
> 生产环境应通过环境变量 `AGENT_PLATFORM_JWT_SECRET` 覆盖默认 JWT 密钥。

## 前端（frontend/）

`frontend/` 是平台唯一前端（React 19 + Vite + Tailwind v4 + shadcn，Cal Sans/Graphite 设计体系，
详见 [frontend/DESIGN.md](frontend/DESIGN.md)），认证对接统一认证中心（JWT Bearer：
`/api/auth/login` 委托认证中心校验后签发 access/refresh 双 token，前端 401 时自动刷新重试），
页面与操作按钮按**权限码**控制（`PermissionGuard`，权限码登录后持久化到本地）。

- **管理端 `/admin`**（按 `admin:access` 等权限码控制）：Agent 管理（卡片网格 + 标签）、
  平台知识库、模型凭证（平台共享）、标签管理、渠道管理、工具管理、MCP。
- **Space `/space`**（终端用户，对话仅要求登录；各子菜单按 `space:index` + 专用码
  显隐，见下方权限码清单）：智能体广场（标签筛选）、对话、定时任务、任务列表与任务结果、
  个人知识库、我的模型（个人模型 API 凭证）、平台技能库、个人设置。
- 对话渲染管线：SSE 事件经 `frontend/src/hooks/usePlatformMessages.ts` 映射为
  AgentScope Msg[]，由 `ChatContent` → `ASMessageBubble` → `ToolBullet` 渲染；
  流式 Markdown / `ui_form` 动态表单 / `ui_card` 内容卡片 / HITL 确认渲染器位于
  `frontend/src/components/chat/`（含 `tool-renderers/`、`ConfirmCard`、`FormCard`）。

## 认证体系（统一认证中心对接）

- **登录委托**：登录统一经外部认证服务校验（OAuth2.0 密码模式 + Authorization Code +
  PKCE），认证服务未启用/不可达时直接拒绝，不回退本地密码校验；
- **注册分流**：公开注册入口 `POST /api/users/register`（用户名/手机号/邮箱 + 验证码，
  验证码经 tipdm 认证服务下发）；管理端建户 `POST /api/auth/register`（admin，仅账密）；
  登录用户改密 `POST /api/users/password`；
- **账号存储**：本地 `users` 表保存注册创建的账号与 OAuth 同步镜像用户，
  种子 admin 用户已删除（V36）；
- **权限码**：登录后从认证中心拉取用户权限码（`permissions-path`，scope=智能体基座）
  存入 Redis，登出时清理；后端接口与前端路由/按钮统一按权限码控制
  （后端角色 id 硬编码映射已移除）；
- **JWT**：平台本地签发 access/refresh 双 token（HS256，iss=`agent-platform`），
  `JwtAuthFilter`/`JwtInterceptor` 解析 Bearer（无效/过期 401）；
- **鉴权**：按权限码控制——端点标注 `@RequirePermission("xxx")`，由 `PermissionAspect`
  切面查 Redis 中的用户权限码校验。角色拦截器（`RoleInterceptor`）与 `@RequireAdmin` 已移除，
  不再存在「角色」与「权限码」两套体系，也不再信任 `X-User-Role` 请求头；
- **配置**：`agent-platform.auth.*`（JWT 密钥/有效期、`oauth.*`：auth-server-url、
  client-id/secret、userinfo-url、tipdm RSA 公钥等）。

## 权限码清单

权限码登录后由统一认证中心下发（存 Redis），前端路由/菜单/按钮（`PermissionGuard`、
`NAV_SECTIONS`）与后端接口（`@RequirePermission` 切面）按同一套码控制。
资源结构与码值以 [docs/智能体平台资源权限清单.xlsx](docs/智能体平台资源权限清单.xlsx) 为配置依据，
下表为按使用者角色梳理后的现状（已与代码核对，截至 2026-09-21）。

**动词与菜单码约定（2026-09-21 统一）**：写操作一律 `create`（新增）/ `update`（编辑）/
`delete`（删除）/ `status`（启停），历史拼写 `new` / `edit` / `switch` / `remove` / `block`
已全部迁移（tool / skill / credential 三个资源域）；页面菜单准入一律 `*:menu`
（特例：技能广场 `skill:hub`、原 `tool:manage` / `mcp:manage` / `channel:manage` /
`credential:manage` / `skills:hub` 已迁移，`knowledge:manage` 拆分为菜单 `knowledge:menu` +
动作 `knowledge:create` / `knowledge:update` / `knowledge:delete`——2026-09-22 按认证中心
权限树定版）；`tag:*` 为自 `admin:access` 拆出的标签管理细粒度域。
`manage` 类码已全部退役。
认证中心侧权限树需同步替换配置，替换完成前对应操作会 403。菜单码（`*:menu`、
`space:*`、分组结构码 `admin:*` / `resource:menu`）与动作码命名空间分离，不混用。

**终端用户（工作台 `/space`）**——对话等核心能力仅要求登录；侧边栏各子菜单
自 2026-09-21 起细粒度化，每个菜单项都要求 `space:index` + 各自专用码同时持有
（all-match），菜单显隐与路由守卫同口径，未持有的码对应菜单隐藏：

| 权限码 | 用途 | 类型 | 校验现状 |
|--------|------|------|----------|
| `space:index` | 工作台分组 | 菜单（结构节点） | 前端本地生成分组，不单独下发 |
| `space:agents` | 智能体广场 | 菜单 | 前端菜单显隐与广场路由守卫均已校验（`space:index` + `space:agents` all-match） |
| `space:schedules` | 定时任务菜单 | 菜单 | 前端菜单与路由守卫均已校验；后端接口未校验（`/schedule/` 接口尚未实现） |
| `space:models` | 我的模型菜单（个人模型凭证） | 菜单 | 前端菜单与路由守卫均已校验；接口走登录态个人凭证域 |
| `space:knowledge` | 我的知识库菜单（`scope=PERSONAL`） | 菜单 | 前端菜单与路由守卫均已校验（含 `:kbId` 子路由）；接口走登录态个人库域 |
| `space:skills` | 我的技能菜单 | 菜单 | 前端菜单与路由守卫均已校验；列表 GET 全员可读 |
| `agent:execute` | 运行/对话智能体 | 按钮 | 前端 Agent 管理「使用」按钮已接；广场、对话路由与 AG-UI 后端入口尚未校验 |
| `skill:create` / `skill:update` / `skill:status` / `skill:delete` | 我的技能（`/space/skills`）维护 | 按钮 | 前后端均已校验；列表 GET 全员可读，无码则页面只读。平台库写操作按 scope 分流（个人库 owner/管理员自治）；`skill:create` 同时覆盖技能仓库同步/导入。原 new/edit/switch/remove 已废弃 |

**管理员（管理控制台 `/admin`）**——准入码 `admin:access`，同时是全平台「管理员」判定的唯一口径：

| 权限码 | 用途 | 类型 | 校验现状 |
|--------|------|------|----------|
| `admin:access` | 管理控制台路由与菜单准入（管理员判定唯一口径） | 菜单 | 前端路由守卫已接；原绑定的标签管理接口 2026-09-21 起改走 `tag:*` 细粒度码 |
| `agent:menu` | Agent 管理菜单 | 菜单/接口 | 前端已接；后端详情接口 `GET /api/agents/{id}` 已校验（列表接口刻意不加码——广场数据源，对非管理员脱敏 systemPrompt/subagents） |
| `agent:create` / `agent:update` / `agent:delete` | 智能体新建/编辑启停/删除 | 按钮 | 前后端均已对齐 |
| `resource:menu` | 资源管理分组 | 菜单（结构节点） | 前端分组准入已接；后端不适用 |
| `skill:hub` | 技能广场（`/admin/skill-hub`） | 菜单 | 前端路由已接（与 `resource:menu` 同时校验）。菜单码特例：技能广场用专名 `hub`，不走 `*:menu` |
| `tool:menu` | 工具管理菜单 | 菜单 | 前端已接；写接口走 `tool:*` 细粒度码。原 `tool:manage` 已废弃 |
| `tool:create` / `tool:update` / `tool:status` / `tool:delete` | 工具新建/编辑/启停/删除 | 按钮 | 前后端均已对齐。原 new/edit/block/remove 已废弃 |
| `mcp:menu` | MCP 管理菜单 | 菜单 | 前端路由已接（与 `resource:menu` 同时校验）；后端接口未校验。原 `mcp:manage` 已废弃 |
| `knowledge:menu` | 平台知识库（`scope=PLATFORM`）菜单 | 菜单 | 前端已接。原与后端动作码同名双用，2026-09-21 拆分：菜单准入 `knowledge:menu` |
| `knowledge:create` / `knowledge:update` / `knowledge:delete` | 平台知识库创建/编辑/删除（`POST /api/knowledge-bases/platform`、`PUT|DELETE /api/knowledge-bases/{id}`） | 按钮/接口 | 2026-09-22 按认证中心权限树定版（原 `knowledge:manage` 退役）。创建为接口层无条件校验；编辑/删除按实体 scope 分流（仅 PLATFORM 要求，个人库靠 owner/WRITE 数据授权）；前端平台库新建按钮按 `knowledge:create` 显隐 |
| `admin:integration` + `channel:menu` | 集成分组 + 渠道管理 | 菜单 | 前端路由已按两码同时校验；后端接口未校验。原 `channel:manage` 已废弃 |
| `admin:system` + `credential:menu` | 系统分组 + 模型凭证菜单（平台共享 scope） | 菜单 | 前端路由已按两码同时校验；后端写接口走下方细粒度码。原 `credential:manage` 已废弃 |
| `tag:menu` | 标签管理菜单（`/admin/tags`） | 菜单/接口 | 前端路由守卫 + 后端 `GET /api/admin/tags` 均已校验 |
| `tag:create` / `tag:update` / `tag:delete` | 标签新建/编辑启停（含排序）/删除 | 按钮/接口 | 前后端均已对齐（2026-09-21 自 `admin:access` 拆分的细粒度域） |
| `credential:create` / `credential:update` / `credential:delete` | 平台凭证创建/编辑/删除（`POST /api/credentials/platform`、`PATCH|DELETE /api/credentials/{id}`） | 接口 | 后端均已校验；创建为注解无条件校验，编辑/删除按实体 scope 分流（仅 PLATFORM 要求，个人凭证靠归属校验，越权 404）。原 new/edit 已废弃 |
| `user:create` | 管理端建户（`POST /api/auth/register`） | 接口 | 后端 `@RequirePermission` 已校验 |

**未接入 / 预留**（认证中心可能已下发，但平台当前未实现对应能力，代码不校验）：
`schedule:manage`、`agent:publish`、`agent:unpublish`、`agent:version:list`、
`agent:version:rollback`、`agent:workflow:manage`、`workspace:manage`、
`agent:session:plan` / `team` / `skill` / `mcp` / `knowledge`。
已废弃：`admin:console`（统一改用 `admin:access`）、`skill:manage`（技能页迁入
工作台后路由不再依赖，菜单行已从清单删除，写操作改走 `skill:*` 细粒度码）、
`tool:manage` / `mcp:manage` / `channel:manage` / `credential:manage`
（菜单码统一为 `*:menu`；技能广场定为 `skill:hub`）。

> 角色说明：本地 `users.role`（ADMIN / DEVELOPER / END_USER）仅作记录，鉴权不按角色——
> 能进管理端当且仅当持有 `admin:access` 权限码。

## 功能概览

- **资源中心**：技能 / 工具 CRUD（搜索、上下架、上传 `.md` 与 YAML frontmatter 校验），
  技能仓库（外部技能源浏览与一键导入）；标签系统（管理员维护封闭标签集，
  智能体与知识库可关联彩色标签，广场/列表按标签筛选，file-based 智能体经
  AGENTS.md frontmatter `tags:` 同步）；
- **模型凭证**：用户级模型供应商 API Key 管理（`/api/credentials`），个人（USER）与
  平台共享（PLATFORM，`POST /api/credentials/platform` 需 `credential:create`，菜单
  准入为 `credential:menu`）两种 scope；**保存即核验**：调用厂商 `/models` 验证 KEY，
  成功缓存厂商模型清单（`vendor_models`，2026-09-22 起为可用模型的权威来源，静态目录
  仅元数据兜底），`enabled_models` 开关筛选启用模型；支持设置**平台默认模型**
  （全局唯一，`GET/PUT /api/credentials/default-model`，需 `credential:update`，
  对话未选模型时兜底）；对话中选择模型支持「平台共享模型 + 个人凭证自定义模型」；
  密钥不回显、编辑保旧；
- **Agent 装配**：表单配置 → 工作区生成（AGENTS.md + skills/ + subagents/）→
  `AgentAssemblyService` 装配 HarnessAgent（模型/Toolkit/技能/子 Agent/PostgresAgentStateStore），
  实例按 agentId 缓存；
- **运行时**：AG-UI 协议 SSE 事件流（`/agui`），动态表单 `ui_form`、内容卡片 `ui_card`、
  Plan 批准、人工确认 HITL（超时未审批自动取消，并在转录系统消息、
  `hitl_gate_timeout` 审计事件、AGENTS.md 约束三处留记录）、子 Agent 完成唤醒父会话续跑；
  对话模型按轮次指定（`forwardedProps.credential_id + model`，2026-09-22 平台化定版，
  未指定走平台默认模型）；运行中会话支持中断（`POST .../sessions/{sessionId}/interrupt`，
  停止按钮后端支撑）；事件异步写入数据库（agui_events，V37）供审计查询；
- **知识库**：两个入口——工作台「知识库」（个人，`scope=PERSONAL`）与管理端
  「平台知识库」（`scope=PLATFORM`，需 `knowledge:create`）；授权、pgvector 向量检索、
  分块配置（V22–V25），Embedding 模型下拉选择；会话支持本地文件上传与知识库关联；
- **通知与渠道**：站内通知（列表/未读数/SSE 信号流）+ 用户级出站渠道
  （企业微信/钉钉/飞书/webhook，连通测试），会话告警按绑定的渠道分发；
- **会话**：会话归属（owner 分区）、进度、归档、扩展（V26–V28/V33）与标题（V35）。

## 文档

> 接口细节以 `docs/openapi.yaml` 为准（`docs/api.md` 为其 Markdown 阅读版）；
> 开发流程与机制约定以 `docs/vertical-agent-dev-guide.md` 为准。完整索引见 [docs/README.md](docs/README.md)。

- [docs/openapi.yaml](docs/openapi.yaml) / [docs/api.md](docs/api.md) —— OpenAPI 3.1 接口规范与 Markdown 阅读版
- [docs/vertical-agent-dev-guide.md](docs/vertical-agent-dev-guide.md) —— 业务智能体技术对接指南
- [docs/智能体平台资源权限清单.xlsx](docs/智能体平台资源权限清单.xlsx) —— 资源权限码清单
  （菜单/操作权限字符串与页面路径对照，前端 `PermissionGuard` 与后端权限校验的配置依据）
- [docs/form-config-contract.md](docs/form-config-contract.md) —— 表单配置契约
- [docs/knowledge-base-module-design.md](docs/knowledge-base-module-design.md) —— 知识库模块设计
- [docs/workspace-layout.md](docs/workspace-layout.md) —— 工作区目录约定与路径契约
- [docs/agent-constraint-backstops.md](docs/agent-constraint-backstops.md) —— 平台硬约束与历史问题记录
