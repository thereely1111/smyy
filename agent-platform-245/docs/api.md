# Agent Platform API（Markdown 版）

> 版本 1.0.0 · 由 [`openapi.yaml`](./openapi.yaml) 同步渲染（生成时间 2026-09-23）。
> 接口细节以 `openapi.yaml` 为准；本文是便于阅读的 Markdown 版本，两者由生成脚本保持一致。

智能体平台（agent-platform-java）后端 HTTP 接口文档。

## 通用约定

- **Base URL**：`http://localhost:8080`（无 context-path）。
- **鉴权**：除「公开端点」外，所有请求必须携带 `Authorization: Bearer <accessToken>`（JWT HS256）。
  401 时响应体为 `{"code":"UNAUTHORIZED","message":"..."}`，并附带 `WWW-Authenticate: Bearer realm="agent-platform"` 头。
- **公开端点**（免 token）：`/api/auth/login`、`/api/auth/refresh`、`/api/auth/callback`、`/api/auth/oauth/login`、`/api/users/register`、`/api/users/captcha/*`、`/health`、`/actuator/*`，以及所有 `OPTIONS` 预检。
- **无统一成功包装**：成功响应直接返回业务 DTO（字段 camelCase）；错误响应统一为
  `{"code": "<大写错误码>", "message": "<中文描述>"}`。已知错误码：
  `VALIDATION_ERROR`、`BAD_REQUEST`、`CONFLICT`、`FILE_TOO_LARGE`、`NOT_FOUND`、`UNAUTHORIZED`、`FORBIDDEN`、`INTERNAL_ERROR`。
- **分页约定**：列表端点统一 `?search=&status=&page=&size=`（page 默认 0，size 默认 20），
  返回 `PageResponse<T>`：`{"content":[...],"page":0,"size":20,"totalElements":100,"totalPages":5}`。
  知识库、群组等少数域使用自定义包装（见各端点说明）。
- **会话归属**：所有 `/api/agents/{agentId}/sessions/{sessionId}/**` 资源按 JWT 用户（`sub`）校验归属
  （owner 或 admin）；非本人会话返回 **404**（不暴露存在性）。
- **管理端权限**：按权限码控制——标注 **[admin]** 的端点要求 `admin:access` 权限码
  （与前端 `/admin` 路由守卫一致），无权限码返回 403；部分端点要求特定权限码
  （如 `credential:create` 创建平台共享凭证、`knowledge:create` 创建平台知识库、
  `agent:menu` 查看智能体完整字段），见各端点说明。不再信任 `X-User-Role` 请求头。
- **归属即权限**：用户级资源（模型凭证、通知渠道、通知）按 JWT 用户归属校验，
  越权访问他人资源返回 404（不暴露存在性）。
- **SSE 超时**：AG-UI 流 `sse-timeout` 与 `run-timeout` 均为 2h（人工确认审阅可能持续 1-2h，需要 2h 余量）；超时/中断时连接正常结束，不写错误体。长时间无响应的 run 由 `RunSilenceDetector`（20min 告警 / 60min 判定失效并自动恢复）处理。
- **run 互斥**：同一会话同时只允许一个活跃 run；已有 run 在跑时新 AG-UI 请求返回 **409**。停滞 run 由静默探测器检测（默认 60 分钟无事件）并自动恢复。


## 目录

- **Auth**：`POST /api/auth/login`、`GET /api/auth/me`、`POST /api/auth/refresh`、`POST /api/auth/logout`、`POST /api/auth/register`、`GET /api/auth/oauth/login`、`POST /api/auth/callback`
- **Users**：`POST /api/users/register`、`POST /api/users/password`、`GET /api/users/captcha/phone/{phone}`、`GET /api/users/captcha/email/{email}`
- **Agents**：`GET /api/agents`、`POST /api/agents`、`GET /api/agents/{id}`、`PUT /api/agents/{id}`、`DELETE /api/agents/{id}`、`PATCH /api/agents/{id}/status`、`POST /api/agents/{id}/assemble`
- **Sessions**：`GET /api/agents/{agentId}/sessions`、`PATCH /api/agents/{agentId}/sessions/{sessionId}/title`、`GET /api/agents/{agentId}/sessions/{sessionId}/transcript`、`GET /api/agents/{agentId}/sessions/{sessionId}/progress`、`GET /api/agents/{agentId}/sessions/{sessionId}/form-interrupts`、`PUT /api/agents/{agentId}/sessions/{sessionId}/form-interrupts`、`DELETE /api/agents/{agentId}/sessions/{sessionId}/form-interrupts`、`PATCH /api/agents/{agentId}/sessions/{sessionId}/archive`、`POST /api/agents/{agentId}/sessions/{sessionId}/clear`、`POST /api/agents/{agentId}/sessions/{sessionId}/recover`、`POST /api/agents/{agentId}/sessions/{sessionId}/interrupt`
- **Attachments**：`GET /api/agents/{agentId}/sessions/{sessionId}/attachments`、`POST /api/agents/{agentId}/sessions/{sessionId}/attachments`、`DELETE /api/agents/{agentId}/sessions/{sessionId}/attachments/{fileId}`
- **Artifacts**：`GET /api/agents/{agentId}/sessions/{sessionId}/artifacts`、`GET /api/agents/{agentId}/sessions/{sessionId}/artifacts/content`
- **SubagentReports**：`GET /api/agents/{agentId}/sessions/{sessionId}/subagents`
- **SessionKnowledge**：`GET /api/agents/{agentId}/sessions/{sessionId}/knowledge`、`PUT /api/agents/{agentId}/sessions/{sessionId}/knowledge`
- **SessionExtensions**：`GET /api/agents/{agentId}/sessions/{sessionId}/extensions`、`PUT /api/agents/{agentId}/sessions/{sessionId}/extensions`、`DELETE /api/agents/{agentId}/sessions/{sessionId}/extensions`、`POST /api/agents/{agentId}/sessions/{sessionId}/extensions/entries`、`DELETE /api/agents/{agentId}/sessions/{sessionId}/extensions/entries/{key}`
- **KnowledgeBases**：`GET /api/knowledge-bases`、`POST /api/knowledge-bases`、`POST /api/knowledge-bases/platform`、`GET /api/knowledge-bases/{id}`、`PUT /api/knowledge-bases/{id}`、`DELETE /api/knowledge-bases/{id}`、`GET /api/knowledge-bases/{id}/documents`、`POST /api/knowledge-bases/{id}/documents`、`GET /api/knowledge-bases/{id}/documents/status`、`DELETE /api/knowledge-bases/{id}/documents/{docId}`、`GET /api/knowledge-bases/{id}/grants`、`POST /api/knowledge-bases/{id}/grants`、`DELETE /api/knowledge-bases/{id}/grants/{grantId}`、`POST /api/knowledge-bases/{id}/search`
- **Skills**：`GET /api/skills`、`POST /api/skills`、`GET /api/skills/{id}`、`PUT /api/skills/{id}`、`DELETE /api/skills/{id}`、`PATCH /api/skills/{id}/status`
- **Tools**：`GET /api/tools`、`POST /api/tools`、`GET /api/tools/{id}`、`PUT /api/tools/{id}`、`DELETE /api/tools/{id}`、`PATCH /api/tools/{id}/status`
- **SkillRepositories**：`GET /api/skill-repositories`、`POST /api/skill-repositories/sync`、`GET /api/skill-repositories/skills`、`GET /api/skill-repositories/skills/{id}`、`POST /api/skill-repositories/skills/{id}/import`
- **Tags**：`GET /api/tags`、`GET /api/admin/tags`、`POST /api/admin/tags`、`PUT /api/admin/tags/{id}`、`DELETE /api/admin/tags/{id}`
- **ModelCredentials**：`GET /api/credentials`、`POST /api/credentials`、`POST /api/credentials/platform`、`GET /api/credentials/default-model`、`PUT /api/credentials/default-model`、`GET /api/credentials/schemas`、`PATCH /api/credentials/{id}`、`DELETE /api/credentials/{id}`
- **ModelCatalog**：`GET /api/models`、`GET /api/tts-models`、`GET /api/embedding-models`
- **Channels**：`GET /api/channels/types`、`GET /api/channels`、`POST /api/channels`、`PATCH /api/channels/{id}`、`DELETE /api/channels/{id}`、`POST /api/channels/{id}/enable`、`POST /api/channels/{id}/disable`、`GET /api/channels/{id}/status`、`POST /api/channels/{id}/test`
- **Notifications**：`GET /api/notifications`、`GET /api/notifications/unread-count`、`POST /api/notifications/{id}/read`、`POST /api/notifications/read-all`、`GET /api/notify/stream`、`GET /api/sessions/{sessionId}/alert-push`、`PUT /api/sessions/{sessionId}/alert-push`
- **AguiEvents**：`GET /api/agui-events`
- **Groups**：`GET /api/groups`、`POST /api/groups`、`GET /api/groups/{id}`、`DELETE /api/groups/{id}`、`POST /api/groups/{id}/members`、`DELETE /api/groups/{id}/members/{userId}`
- **Agui**：`POST /agui/run/{agentId}`

## Auth

认证（登录/刷新/OAuth）

### POST `/api/auth/login`

**登录**

鉴权：**公开**（免 token）

统一委托外部认证服务（tipdm OAuth2.0 密码模式）校验账号密码，并同步用户至本地 users 镜像表 （外部明确拒绝凭据时返回 401，不回退本地密码校验）。校验通过后拉取权限码存入 Redis， 最终签发本地 JWT（access/refresh）。未配置外部认证服务（auth-server-url/client-id）时 登录直接拒绝（503）。


**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `username` | string | 是 |  |
| `password` | string | 是 |  |

- **200**：TokenResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `accessToken` | string | 否 |  |
| `refreshToken` | string | 否 |  |
| `tokenType` | string | 否 |  |
| `userId` | string | 否 |  |
| `username` | string | 否 |  |
| `role` | string | 否 |  | 枚举：admin, developer, end_user |
| `name` | string \| null | 否 | 显示名（OAuth 用户取 userAlias，本地为空可null） |
- **401**
- **502**：外部认证服务异常（AUTH_ERROR）
- **503**：统一认证服务未启用或不可达（AUTH_UNAVAILABLE）

### GET `/api/auth/me`

**当前用户信息**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **200**：UserInfoResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `userId` | string | 否 |  |
| `username` | string | 否 |  |
| `role` | string | 否 |  | 枚举：admin, developer, end_user |
| `createdAt` | string | 否 |  |
| `name` | string \| null | 否 | 显示名 |
| `email` | string \| null | 否 |  |
| `phone` | string \| null | 否 | 脱敏展示（如 138****1234） |
- **401**

### POST `/api/auth/refresh`

**刷新 token**

鉴权：**公开**（免 token）

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `refreshToken` | string | 是 |  |

- **200**：新的 token 对（access 默认 30 分钟，refresh 默认 7 天）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `accessToken` | string | 否 |  |
| `refreshToken` | string | 否 |  |
| `tokenType` | string | 否 |  |
| `userId` | string | 否 |  |
| `username` | string | 否 |  |
| `role` | string | 否 |  | 枚举：admin, developer, end_user |
| `name` | string \| null | 否 | 显示名（OAuth 用户取 userAlias，本地为空可null） |
- **401**

### POST `/api/auth/logout`

**登出**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

JWT 无状态，服务端不吊销 token，但会清理该用户的 Redis 权限缓存；客户端同时清除本地存储。返回 204。

- **204**：无内容

### POST `/api/auth/register`

**创建账号 [admin]**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

管理端创建账号仅支持用户名（账密）方式；手机号/邮箱注册请使用公开入口 `POST /api/users/register`。

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `principal` | string | 是 | 账密注册时为用户名；phone/email 注册时为对应手机号/邮箱 |
| `password` | string | 是 |  |
| `role` | string | 否 |  | 枚举：admin, developer, end_user |
| `registerType` | string | 否 |  | 枚举：username, phone, email |
| `captcha` | string \| null | 否 | phone/email 注册必填（先经 /api/users/captcha/* 获取） |

- **201**：UserInfoResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `userId` | string | 否 |  |
| `username` | string | 否 |  |
| `role` | string | 否 |  | 枚举：admin, developer, end_user |
| `createdAt` | string | 否 |  |
| `name` | string \| null | 否 | 显示名 |
| `email` | string \| null | 否 |  |
| `phone` | string \| null | 否 | 脱敏展示（如 138****1234） |
- **400**
- **403**

### GET `/api/auth/oauth/login`

**获取 OAuth 登录参数**

鉴权：**公开**（免 token）

OAuth2.0 Authorization Code + PKCE。未配置 OAuth 时报错。

- **200**：登录跳转参数

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `loginUrl` | string | 否 |  |
| `state` | string | 否 |  |
| `redirectUri` | string | 否 |  |

### POST `/api/auth/callback`

**OAuth 回调换取本地 token**

鉴权：**公开**（免 token）

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `code` | string | 是 |  |
| `state` | string | 是 |  |

- **200**：TokenResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `accessToken` | string | 否 |  |
| `refreshToken` | string | 否 |  |
| `tokenType` | string | 否 |  |
| `userId` | string | 否 |  |
| `username` | string | 否 |  |
| `role` | string | 否 |  | 枚举：admin, developer, end_user |
| `name` | string \| null | 否 | 显示名（OAuth 用户取 userAlias，本地为空可null） |

## Users

用户注册与账号安全

### POST `/api/users/register`

**公开注册**

鉴权：**公开**（免 token）

免登录。`registerType`：`username`（默认，账密注册）/ `phone` / `email`；
`principal` 在账密时为用户名，phone/email 时为对应手机号/邮箱（且必须携带先经
`/api/users/captcha/*` 获取的验证码 `captcha`）。手机号/邮箱注册不在本地创建账号记录，
响应为字段为空的 UserInfoResponse，调用方随后直接调用登录接口。


**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `principal` | string | 是 | 账密注册时为用户名；phone/email 注册时为对应手机号/邮箱 |
| `password` | string | 是 |  |
| `role` | string | 否 |  | 枚举：admin, developer, end_user |
| `registerType` | string | 否 |  | 枚举：username, phone, email |
| `captcha` | string \| null | 否 | phone/email 注册必填（先经 /api/users/captcha/* 获取） |

- **201**：UserInfoResponse（手机号/邮箱注册时字段为空）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `userId` | string | 否 |  |
| `username` | string | 否 |  |
| `role` | string | 否 |  | 枚举：admin, developer, end_user |
| `createdAt` | string | 否 |  |
| `name` | string \| null | 否 | 显示名 |
| `email` | string \| null | 否 |  |
| `phone` | string \| null | 否 | 脱敏展示（如 138****1234） |
- **400**

### POST `/api/users/password`

**修改自己的密码**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

身份以 JWT 为准，不接受请求传 userId。返回 204。

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `oldPassword` | string | 是 |  |
| `newPassword` | string | 是 |  |

- **204**：无内容
- **400**
- **401**

### GET `/api/users/captcha/phone/{phone}`

**发送手机号注册验证码**

鉴权：**公开**（免 token）

免登录，转发外部（tipdm）验证码服务。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `phone` | path | string | 是 |  |

- **200**：验证码发送受理标识

### GET `/api/users/captcha/email/{email}`

**发送邮箱注册验证码**

鉴权：**公开**（免 token）

免登录，转发外部（tipdm）验证码服务。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `email` | path | string | 是 |  |

- **200**：验证码发送受理标识

## Agents

智能体管理与装配

### GET `/api/agents`

**智能体分页列表（智能体广场数据源）**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

此端点不要求管理端权限码，普通登录用户通过它获取智能体广场数据。对非管理员 （无 `admin:access` 权限码）隐藏 `systemPrompt`（编排契约 + 提示词）； 管理端编辑页需 `agent:menu` 权限码才能获取完整字段。


| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `search` | query | string | 否 |  |
| `tag` | query | string | 否 | 按标签 ID 筛选（UUID） |
| `page` | query | integer | 否 |  |
| `size` | query | integer | 否 |  |

- **200**：PageResponse&lt;AgentConfigResponse&gt;（响应项额外带 tags[{id,name,color}]）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` | integer | 否 |  |
| `size` | integer | 否 |  |
| `totalElements` | integer | 否 |  |
| `totalPages` | integer | 否 |  |
| `content` | [AgentConfig](#agentconfig)[] | 否 |  |
- **401**

### POST `/api/agents`

**创建智能体 [admin]**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

保存时由 WorkspaceGenerator 生成 `workspace/agents/{agentId}/`（AGENTS.md + skills/ + subagents/）。

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `systemPrompt` | string | 否 |  |
| `model` | string | 是 |  |
| `status` | string | 否 |  | 枚举：draft, active |
| `skillIds` | string[] | 否 |  |
| `toolIds` | string[] | 否 |  |
| `subagentIds` | string[] | 否 |  |
| `launchTemplates` | object[] | 否 |  |
| `planEnabled` | boolean | 否 |  |
| `skillSedimentation` | boolean | 否 |  |
| `welcomeForm` | object | 否 | 欢迎表单（见 docs/form-config-contract.md） |
| `form` | object | 否 | 配置驱动表单（M11） |
| `formTrigger` | string | 否 |  | 枚举：disabled, on_session_start, on_round_start, once |
| `tagIds` | string[] | 否 | 关联标签（全量替换语义，重复提交以最后一次为准） |

- **201**：AgentConfigResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `systemPrompt` | string | 否 |  |
| `model` | string | 是 |  |
| `status` | string | 否 |  | 枚举：draft, active |
| `skillIds` | string[] | 否 |  |
| `toolIds` | string[] | 否 |  |
| `subagentIds` | string[] | 否 |  |
| `launchTemplates` | object[] | 否 |  |
| `planEnabled` | boolean | 否 |  |
| `skillSedimentation` | boolean | 否 |  |
| `welcomeForm` | object | 否 | 欢迎表单（见 docs/form-config-contract.md） |
| `form` | object | 否 | 配置驱动表单（M11） |
| `formTrigger` | string | 否 |  | 枚举：disabled, on_session_start, on_round_start, once |
| `tagIds` | string[] | 否 | 关联标签（全量替换语义，重复提交以最后一次为准） |
| `id` | string | 否 |  |
| `workspacePath` | string | 否 |  |
| `fileBased` | boolean | 否 | true = 文件型智能体（workspace/agents/ 下直接定义，frontmatter tags 按名同步进标签池） |
| `tags` | [TagView](#tagview)[] | 否 | 关联标签（展示字段，脱敏时保留） |
| `createdAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |
- **400**
- **403**

### GET `/api/agents/{id}`

**智能体详情**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **200**：AgentConfigResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `systemPrompt` | string | 否 |  |
| `model` | string | 是 |  |
| `status` | string | 否 |  | 枚举：draft, active |
| `skillIds` | string[] | 否 |  |
| `toolIds` | string[] | 否 |  |
| `subagentIds` | string[] | 否 |  |
| `launchTemplates` | object[] | 否 |  |
| `planEnabled` | boolean | 否 |  |
| `skillSedimentation` | boolean | 否 |  |
| `welcomeForm` | object | 否 | 欢迎表单（见 docs/form-config-contract.md） |
| `form` | object | 否 | 配置驱动表单（M11） |
| `formTrigger` | string | 否 |  | 枚举：disabled, on_session_start, on_round_start, once |
| `tagIds` | string[] | 否 | 关联标签（全量替换语义，重复提交以最后一次为准） |
| `id` | string | 否 |  |
| `workspacePath` | string | 否 |  |
| `fileBased` | boolean | 否 | true = 文件型智能体（workspace/agents/ 下直接定义，frontmatter tags 按名同步进标签池） |
| `tags` | [TagView](#tagview)[] | 否 | 关联标签（展示字段，脱敏时保留） |
| `createdAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |
- **404**

### PUT `/api/agents/{id}`

**更新智能体 [admin]**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

更新后重建工作区。

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `systemPrompt` | string | 否 |  |
| `model` | string | 是 |  |
| `status` | string | 否 |  | 枚举：draft, active |
| `skillIds` | string[] | 否 |  |
| `toolIds` | string[] | 否 |  |
| `subagentIds` | string[] | 否 |  |
| `launchTemplates` | object[] | 否 |  |
| `planEnabled` | boolean | 否 |  |
| `skillSedimentation` | boolean | 否 |  |
| `welcomeForm` | object | 否 | 欢迎表单（见 docs/form-config-contract.md） |
| `form` | object | 否 | 配置驱动表单（M11） |
| `formTrigger` | string | 否 |  | 枚举：disabled, on_session_start, on_round_start, once |
| `tagIds` | string[] | 否 | 关联标签（全量替换语义，重复提交以最后一次为准） |

- **200**：AgentConfigResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `systemPrompt` | string | 否 |  |
| `model` | string | 是 |  |
| `status` | string | 否 |  | 枚举：draft, active |
| `skillIds` | string[] | 否 |  |
| `toolIds` | string[] | 否 |  |
| `subagentIds` | string[] | 否 |  |
| `launchTemplates` | object[] | 否 |  |
| `planEnabled` | boolean | 否 |  |
| `skillSedimentation` | boolean | 否 |  |
| `welcomeForm` | object | 否 | 欢迎表单（见 docs/form-config-contract.md） |
| `form` | object | 否 | 配置驱动表单（M11） |
| `formTrigger` | string | 否 |  | 枚举：disabled, on_session_start, on_round_start, once |
| `tagIds` | string[] | 否 | 关联标签（全量替换语义，重复提交以最后一次为准） |
| `id` | string | 否 |  |
| `workspacePath` | string | 否 |  |
| `fileBased` | boolean | 否 | true = 文件型智能体（workspace/agents/ 下直接定义，frontmatter tags 按名同步进标签池） |
| `tags` | [TagView](#tagview)[] | 否 | 关联标签（展示字段，脱敏时保留） |
| `createdAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |

### DELETE `/api/agents/{id}`

**删除智能体 [admin]**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

递归清理工作区。返回 204。

- **204**：无内容

### PATCH `/api/agents/{id}/status`

**修改智能体状态 [admin]**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `id` | path | string | 是 |  |

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `status` | string | 是 |  | 枚举：draft, active |

- **200**：已更新
- **403**

### POST `/api/agents/{id}/assemble`

**装配 HarnessAgent**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `id` | path | string | 是 |  |

- **200**：AssembleResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `agentId` | string | 否 |  |
| `name` | string | 否 |  |
| `model` | string | 否 |  |
| `workspacePath` | string | 否 |  |
| `tools` | string[] | 否 |  |
| `subagents` | string[] | 否 |  |
| `skills` | string[] | 否 |  |
| `stateStore` | string | 否 |  |

## Sessions

会话管理、转录、HITL 恢复

### GET `/api/agents/{agentId}/sessions`

**当前用户的会话列表**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

按 (agentId, 当前用户) 列会话。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `agentId` | path | string | 是 |  |
| `includeArchived` | query | boolean | 否 |  |

- **200**：SessionInfo[]

### PATCH `/api/agents/{agentId}/sessions/{sessionId}/title`

**修改会话标题**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `agentId` | path | string | 是 |  |
| `sessionId` | path | string | 是 |  |

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `title` | string | 是 |  |

- **204**：无内容

### GET `/api/agents/{agentId}/sessions/{sessionId}/transcript`

**读取会话转录**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

返回按 (userId, sessionId) 分区存储的完整转录。除 SSE 推送外，
「后台 wakeup 续跑」产生的段落必须通过本接口拉取补齐（前端默认 5 秒轮询）。


| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `agentId` | path | string | 是 |  |
| `sessionId` | path | string | 是 |  |

- **200**：SessionTranscriptResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionId` | string | 否 |  |
| `messages` | object[] | 否 |  |
| `messages[].id` | string | 否 |  |
| `messages[].role` | string | 否 |  |
| `messages[].name` | string \| null | 否 |  |
| `messages[].createdAt` | string | 否 |  |
| `messages[].metadata` | object | 否 |  |
| `messages[].content` | object / object / object / object[] | 否 | 与 AG-UI ContentBlock 对齐 |
- **404**

### GET `/api/agents/{agentId}/sessions/{sessionId}/progress`

**会话进度快照**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

会话停滞监控的进度快照；`hitlStuck=true` 表示权限确认长时间未处理，前端可调用 recover 恢复。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `agentId` | path | string | 是 |  |
| `sessionId` | path | string | 是 |  |

- **200**：SessionProgressResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionId` | string | 否 |  |
| `phase` | string | 否 |  |
| `nextAction` | string | 否 |  | 枚举：CONTINUE, WAIT_HITL, DONE, IDLE, UNKNOWN |
| `progressLine` | string | 否 |  |
| `lastText` | string | 否 |  |
| `lastActiveAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |
| `stallNow` | boolean | 否 |  |
| `hitlStuck` | boolean | 否 |  |

### GET `/api/agents/{agentId}/sessions/{sessionId}/form-interrupts`

**查询未决表单中断**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

前端在收到 `RUN_FINISHED(outcome=interrupt)` 时上报登记（不等待结果）；
刷新页面后由此接口取回 interruptId 再走标准 resume 恢复。
进程内存存储：后端重启即清空。

返回前把登记条目回灌进框架 `pendingInterruptsByThread`（幂等修复）：框架映射会被
自身 run 收尾的整体 put 抹掉条目，而 resume 契约要求 resume[] 与该映射精确相等，
不修复则 resume 被拒（`Thread has unresolved interrupts`）。登记表是权威事实源，
本接口不剔除条目；已消费条目的清理由超时巡检在巡检周期（60s）内完成。


- **200**：PendingFormInterrupt[]

### PUT `/api/agents/{agentId}/sessions/{sessionId}/form-interrupts`

**上报未决表单中断**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `interruptId` | string | 是 | {replyId}:{toolCallId} |
| `replyId` | string | 否 |  |
| `toolCallId` | string | 是 |  |
| `toolName` | string | 否 |  |
| `toolInput` | object | 否 |  |
| `createdAt` | string | 否 |  |

- **204**：无内容

### DELETE `/api/agents/{agentId}/sessions/{sessionId}/form-interrupts`

**清空未决表单中断**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

run 正常收尾（success）后由前端调用。

- **204**：无内容

### PATCH `/api/agents/{agentId}/sessions/{sessionId}/archive`

**归档 / 取消归档（幂等）**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `agentId` | path | string | 是 |  |
| `sessionId` | path | string | 是 |  |

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `archived` | boolean | 是 |  |

- **204**：无内容

### POST `/api/agents/{agentId}/sessions/{sessionId}/clear`

**清空会话**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

清空状态、产物目录、会话扩展与 form-interrupts。返回 204。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `agentId` | path | string | 是 |  |
| `sessionId` | path | string | 是 |  |

- **204**：无内容

### POST `/api/agents/{agentId}/sessions/{sessionId}/recover`

**恢复会话**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

清除遗留 ASKING/interrupt 状态；保留会话 ID 与产物目录。用于权限确认停滞（hitlStuck）后的恢复。返回 204。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `agentId` | path | string | 是 |  |
| `sessionId` | path | string | 是 |  |

- **204**：无内容

### POST `/api/agents/{agentId}/sessions/{sessionId}/interrupt`

**中断正在运行的 run（停止按钮）**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

经框架 interrupt 真正停掉后端 run（前端停止按钮的后端支撑）。尽力等待收敛（最多 3s）后返回； 需会话归属（等同写操作）。幂等：会话无在跑 run（HITL 挂起等表单等场景）时 为静默 no-op，仍返回 202（interrupted=false）。注意：挂起态调用本端点 不会解除 open interrupts——挂起表单的解除由下次 send 前置对账完成。


| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `agentId` | path | string | 是 |  |
| `sessionId` | path | string | 是 |  |

- **202**：已受理（或幂等 no-op）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `session_id` | string | 否 |  |
| `interrupted` | boolean | 否 | 是否实际触发了中断（false=本就无在跑 run） |
- **404**

## Attachments

会话附件

### GET `/api/agents/{agentId}/sessions/{sessionId}/attachments`

**附件列表**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **200**：ListAttachmentsResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `items` | object[] | 否 |  |
| `items[].fileId` | string | 否 |  |
| `items[].ref` | string | 否 | 如 @uploads/<存储名>，用户消息中以此引用 |
| `items[].fileName` | string | 否 |  |
| `items[].size` | integer | 否 |  |
| `items[].createdAt` | string | 否 |  |
| `total` | integer | 否 |  |

### POST `/api/agents/{agentId}/sessions/{sessionId}/attachments`

**上传附件**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

单文件 ≤ 20MB；扩展名白名单（txt/md/json/csv/代码/pdf/office/png/jpg 等）。
保存到 `runtimes/{agentId}/{sessionId}/uploads/{uuid}-{ASCII名}`。


- **201**：AttachmentResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `fileName` | string | 否 | 原始文件名 |
| `ref` | string | 否 | @uploads/<存储名>，用户消息里携带的 @path 引用 |
| `size` | integer | 否 |  |
| `mimeType` | string | 否 |  |
| `createdAt` | string | 否 |  |
- **413**

### DELETE `/api/agents/{agentId}/sessions/{sessionId}/attachments/{fileId}`

**删除单个附件**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **204**：无内容

## Artifacts

会话产物（只读）

### GET `/api/agents/{agentId}/sessions/{sessionId}/artifacts`

**产物文件树**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

聚合「本会话 + 全部子会话」（子会话各挂一个以 `sub-…` 命名的目录节点）。
递归 ≤ 6 层；隐藏 dotfile 与内部目录（agents/default/memory/plan/MEMORY.md）。
子会话路径仅接受当前会话派生的子会话 ID（防止越权访问）。


| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `agentId` | path | string | 是 |  |
| `sessionId` | path | string | 是 |  |

- **200**：ArtifactNode[]

### GET `/api/agents/{agentId}/sessions/{sessionId}/artifacts/content`

**读取产物内容**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `agentId` | path | string | 是 |  |
| `sessionId` | path | string | 是 |  |
| `path` | query | string | 是 | 会话内相对路径 |

- **200**：文本预览 ≤ 5MB；二进制/超限/非 UTF-8 时返回 content=null 并附 reason。


| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `path` | string | 否 |  |
| `content` | string \| null | 否 |  |
| `reason` | string \| null | 否 |  |

## SubagentReports

子智能体汇报（只读）

### GET `/api/agents/{agentId}/sessions/{sessionId}/subagents`

**子智能体汇报列表**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

该会话派发的子智能体 + 最近【TASK_RESULT】汇报，平台核对产物存在性。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `agentId` | path | string | 是 |  |
| `sessionId` | path | string | 是 |  |

- **200**：SubagentReport[]

## SessionKnowledge

会话级知识库关联

### GET `/api/agents/{agentId}/sessions/{sessionId}/knowledge`

**查询会话关联的知识库**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **200**：关联的知识库 ID 集合

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `knowledgeBaseIds` | string[] | 否 |  |

### PUT `/api/agents/{agentId}/sessions/{sessionId}/knowledge`

**覆盖式设置会话知识库**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

空数组 = 解除全部关联。运行期由 RuntimeContext 注入供 knowledge_search 工具使用。

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `knowledgeBaseIds` | string[] | 是 |  |

- **200**：关联的知识库 ID 集合

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `knowledgeBaseIds` | string[] | 否 |  |

## SessionExtensions

会话扩展信息 KV

### GET `/api/agents/{agentId}/sessions/{sessionId}/extensions`

**查询会话扩展 KV**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **200**：ExtensionResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 否 |  |
| `agentId` | string | 否 |  |
| `sessionId` | string | 否 |  |
| `userId` | string | 否 |  |
| `entries` | object[] | 否 |  |
| `entries[].key` | string | 否 |  |
| `entries[].value` | string | 否 |  |
| `createdAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |

### PUT `/api/agents/{agentId}/sessions/{sessionId}/extensions`

**整体覆盖会话扩展 KV**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `entries` | object[] | 是 |  |
| `entries[].key` | string | 否 |  |
| `entries[].value` | string | 否 |  |

- **200**：ExtensionResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 否 |  |
| `agentId` | string | 否 |  |
| `sessionId` | string | 否 |  |
| `userId` | string | 否 |  |
| `entries` | object[] | 否 |  |
| `entries[].key` | string | 否 |  |
| `entries[].value` | string | 否 |  |
| `createdAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |

### DELETE `/api/agents/{agentId}/sessions/{sessionId}/extensions`

**清空会话扩展 KV**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **204**：无内容

### POST `/api/agents/{agentId}/sessions/{sessionId}/extensions/entries`

**新增/更新单条扩展 KV**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `agentId` | path | string | 是 |  |
| `sessionId` | path | string | 是 |  |

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `key` | string | 否 |  |
| `value` | string | 否 |  |

- **200**：ExtensionResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 否 |  |
| `agentId` | string | 否 |  |
| `sessionId` | string | 否 |  |
| `userId` | string | 否 |  |
| `entries` | object[] | 否 |  |
| `entries[].key` | string | 否 |  |
| `entries[].value` | string | 否 |  |
| `createdAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |

### DELETE `/api/agents/{agentId}/sessions/{sessionId}/extensions/entries/{key}`

**删除单条扩展 KV**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `agentId` | path | string | 是 |  |
| `sessionId` | path | string | 是 |  |
| `key` | path | string | 是 |  |

- **204**：无内容

## KnowledgeBases

知识库与检索

### GET `/api/knowledge-bases`

**当前用户可见的知识库列表**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

不分页，返回 {knowledgeBases, total}。按当前用户（JWT sub）过滤可见性 （owner / USER grant / GROUP grant；平台共享库 scope=PLATFORM 对全员可见）。


| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `tag` | query | string | 否 | 按标签 ID 筛选（UUID） |
| `scope` | query | string | 否 | 按归属范围筛选 |

- **200**：知识库列表

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `knowledgeBases` | [KnowledgeBase](#knowledgebase)[] | 否 |  |
| `total` | integer | 否 |  |
- **401**

### POST `/api/knowledge-bases`

**创建知识库（固定归属个人）**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

scope 由路由决定，不接受请求体传参（防止请求体覆盖）：本端点固定创建 `scope=PERSONAL`（owner=当前用户）；平台知识库走 `POST /api/knowledge-bases/platform`。


**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `type` | string | 否 |  | 枚举：rag, external, text |
| `embeddingModelConfig` | object | 否 | 向量维度固定 1536 |
| `externalProvider` | string | 否 |  |
| `externalEndpoint` | string | 否 |  |
| `externalApiKey` | string | 否 | 不回显；编辑时留空 = 不重加密 |
| `externalExtra` | object | 否 |  |
| `chunkSize` | integer | 否 |  |
| `chunkOverlap` | integer | 否 |  |

- **201**：KnowledgeBaseView

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 否 |  |
| `name` | string | 否 |  |
| `description` | string | 否 |  |
| `type` | string | 否 | rag=本地 pgvector；external=外部 RAG 平台（RAGFlow/Dify/百炼）；text=文本 grep 检索 | 枚举：rag, external, text |
| `owner` | object | 否 |  |
| `owner.id` | string | 否 |  |
| `owner.name` | string | 否 |  |
| `embeddingModelConfig` | object | 否 |  |
| `externalProvider` | string \| null | 否 |  |
| `externalEndpoint` | string \| null | 否 |  |
| `chunkSize` | integer \| null | 否 |  |
| `chunkOverlap` | integer \| null | 否 |  |
| `scope` | string | 否 | PERSONAL=个人知识库（owner 可见）；PLATFORM=平台共享（V52 迁移后全员可见） | 枚举：PERSONAL, PLATFORM |
| `tags` | [TagView](#tagview)[] | 否 | 关联标签（id/name/color） |
| `editable` | boolean | 否 |  |
| `permission` | string | 否 | 当前用户对该库的有效权限 |
| `createdAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |

### POST `/api/knowledge-bases/platform`

**创建平台知识库 [admin]**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

固定创建 `scope=PLATFORM`（全员可见），要求 `knowledge:create` 权限码 （与「平台知识库」管理入口一致）；请求体与个人创建相同。 平台库的编辑/删除（`PUT|DELETE /{id}`）按实体 scope 分流要求 `knowledge:update` / `knowledge:delete`（个人库靠 owner/WRITE 数据授权）。


**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `type` | string | 否 |  | 枚举：rag, external, text |
| `embeddingModelConfig` | object | 否 | 向量维度固定 1536 |
| `externalProvider` | string | 否 |  |
| `externalEndpoint` | string | 否 |  |
| `externalApiKey` | string | 否 | 不回显；编辑时留空 = 不重加密 |
| `externalExtra` | object | 否 |  |
| `chunkSize` | integer | 否 |  |
| `chunkOverlap` | integer | 否 |  |

- **201**：KnowledgeBaseView

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 否 |  |
| `name` | string | 否 |  |
| `description` | string | 否 |  |
| `type` | string | 否 | rag=本地 pgvector；external=外部 RAG 平台（RAGFlow/Dify/百炼）；text=文本 grep 检索 | 枚举：rag, external, text |
| `owner` | object | 否 |  |
| `owner.id` | string | 否 |  |
| `owner.name` | string | 否 |  |
| `embeddingModelConfig` | object | 否 |  |
| `externalProvider` | string \| null | 否 |  |
| `externalEndpoint` | string \| null | 否 |  |
| `chunkSize` | integer \| null | 否 |  |
| `chunkOverlap` | integer \| null | 否 |  |
| `scope` | string | 否 | PERSONAL=个人知识库（owner 可见）；PLATFORM=平台共享（V52 迁移后全员可见） | 枚举：PERSONAL, PLATFORM |
| `tags` | [TagView](#tagview)[] | 否 | 关联标签（id/name/color） |
| `editable` | boolean | 否 |  |
| `permission` | string | 否 | 当前用户对该库的有效权限 |
| `createdAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |
- **403**

### GET `/api/knowledge-bases/{id}`

**知识库详情**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **200**：KnowledgeBaseView

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 否 |  |
| `name` | string | 否 |  |
| `description` | string | 否 |  |
| `type` | string | 否 | rag=本地 pgvector；external=外部 RAG 平台（RAGFlow/Dify/百炼）；text=文本 grep 检索 | 枚举：rag, external, text |
| `owner` | object | 否 |  |
| `owner.id` | string | 否 |  |
| `owner.name` | string | 否 |  |
| `embeddingModelConfig` | object | 否 |  |
| `externalProvider` | string \| null | 否 |  |
| `externalEndpoint` | string \| null | 否 |  |
| `chunkSize` | integer \| null | 否 |  |
| `chunkOverlap` | integer \| null | 否 |  |
| `scope` | string | 否 | PERSONAL=个人知识库（owner 可见）；PLATFORM=平台共享（V52 迁移后全员可见） | 枚举：PERSONAL, PLATFORM |
| `tags` | [TagView](#tagview)[] | 否 | 关联标签（id/name/color） |
| `editable` | boolean | 否 |  |
| `permission` | string | 否 | 当前用户对该库的有效权限 |
| `createdAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |
- **404**

### PUT `/api/knowledge-bases/{id}`

**更新知识库**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

externalApiKey 不回显；编辑时留空 = 不重新加密。

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `type` | string | 否 |  | 枚举：rag, external, text |
| `embeddingModelConfig` | object | 否 | 向量维度固定 1536 |
| `externalProvider` | string | 否 |  |
| `externalEndpoint` | string | 否 |  |
| `externalApiKey` | string | 否 | 不回显；编辑时留空 = 不重加密 |
| `externalExtra` | object | 否 |  |
| `chunkSize` | integer | 否 |  |
| `chunkOverlap` | integer | 否 |  |

- **200**：KnowledgeBaseView

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 否 |  |
| `name` | string | 否 |  |
| `description` | string | 否 |  |
| `type` | string | 否 | rag=本地 pgvector；external=外部 RAG 平台（RAGFlow/Dify/百炼）；text=文本 grep 检索 | 枚举：rag, external, text |
| `owner` | object | 否 |  |
| `owner.id` | string | 否 |  |
| `owner.name` | string | 否 |  |
| `embeddingModelConfig` | object | 否 |  |
| `externalProvider` | string \| null | 否 |  |
| `externalEndpoint` | string \| null | 否 |  |
| `chunkSize` | integer \| null | 否 |  |
| `chunkOverlap` | integer \| null | 否 |  |
| `scope` | string | 否 | PERSONAL=个人知识库（owner 可见）；PLATFORM=平台共享（V52 迁移后全员可见） | 枚举：PERSONAL, PLATFORM |
| `tags` | [TagView](#tagview)[] | 否 | 关联标签（id/name/color） |
| `editable` | boolean | 否 |  |
| `permission` | string | 否 | 当前用户对该库的有效权限 |
| `createdAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |

### DELETE `/api/knowledge-bases/{id}`

**删除知识库**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **204**：无内容

### GET `/api/knowledge-bases/{id}/documents`

**文档列表（创建时间倒序）**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **200**：文档列表

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `documents` | [KnowledgeDocument](#knowledgedocument)[] | 否 |  |
| `total` | integer | 否 |  |

### POST `/api/knowledge-bases/{id}/documents`

**上传文档（异步入库）**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **201**：入库受理

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `documentId` | string | 否 |  |
| `filename` | string | 否 |  |
| `status` | string | 否 |  |

### GET `/api/knowledge-bases/{id}/documents/status`

**轮询文档入库进度**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `id` | path | string | 是 |  |
| `ids` | query | string | 是 | 逗号分隔的文档 ID |

- **200**：入库状态

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `items` | object[] | 否 |  |
| `items[].id` | string | 否 |  |
| `items[].status` | string | 否 |  |
| `items[].chunkCount` | integer \| null | 否 |  |
| `items[].error` | string \| null | 否 |  |

### DELETE `/api/knowledge-bases/{id}/documents/{docId}`

**删除文档**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `id` | path | string | 是 |  |
| `docId` | path | string | 是 |  |

- **204**：无内容

### GET `/api/knowledge-bases/{id}/grants`

**查询授权列表**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **200**：授权与可选群组

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `grants` | [KnowledgeGrant](#knowledgegrant)[] | 否 |  |
| `groups` | object[] | 否 |  |
| `groups[].id` | string | 否 |  |
| `groups[].name` | string | 否 |  |

### POST `/api/knowledge-bases/{id}/grants`

**新增授权**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `principalType` | string | 是 |  | 枚举：USER, GROUP |
| `principalId` | string | 是 |  |
| `permission` | string | 是 |  | 枚举：READ, WRITE |

- **201**：GrantView

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 否 |  |
| `principalType` | string | 否 |  | 枚举：USER, GROUP |
| `principalId` | string | 否 |  |
| `principalName` | string | 否 |  |
| `permission` | string | 否 | WRITE 包含 READ | 枚举：READ, WRITE |
| `grantedBy` | string | 否 |  |
| `createdAt` | string | 否 |  |

### DELETE `/api/knowledge-bases/{id}/grants/{grantId}`

**删除授权**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `id` | path | string | 是 |  |
| `grantId` | path | string | 是 |  |

- **204**：无内容

### POST `/api/knowledge-bases/{id}/search`

**知识库检索**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

按库类型返回两种结构：
- **rag / external**（向量型）：`{documentId, chunkIndex, score, content}`
- **text**（文本型，由服务端 grep 实现）：`{file, line, content, context[]}`


| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `id` | path | string | 是 |  |

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `query` | string | 是 |  |
| `topK` | integer | 否 |  |

- **200**：检索结果（按库 type 分支）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | 否 |  | 枚举：rag, external, text |
| `results` | object / object[] | 否 |  |

## Skills

技能资源中心

### GET `/api/skills`

**技能分页列表**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `search` | query | string | 否 |  |
| `status` | query | string | 否 |  |
| `page` | query | integer | 否 |  |
| `size` | query | integer | 否 |  |

- **200**：PageResponse&lt;SkillResponse&gt;

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` | integer | 否 |  |
| `size` | integer | 否 |  |
| `totalElements` | integer | 否 |  |
| `totalPages` | integer | 否 |  |
| `content` | [ResourceItem](#resourceitem)[] | 否 |  |

### POST `/api/skills`

**创建技能 [admin]**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `status` | string | 否 |  | 枚举：active, inactive |
| `createdBy` | string | 否 |  |

- **201**：SkillResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `status` | string | 否 |  | 枚举：active, inactive |
| `createdBy` | string | 否 |  |
| `id` | string | 否 |  |
| `implementationType` | string | 否 |  |
| `createdAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |

### GET `/api/skills/{id}`

**技能详情**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **200**：SkillResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `status` | string | 否 |  | 枚举：active, inactive |
| `createdBy` | string | 否 |  |
| `id` | string | 否 |  |
| `implementationType` | string | 否 |  |
| `createdAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |

### PUT `/api/skills/{id}`

**更新技能 [admin]**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `status` | string | 否 |  | 枚举：active, inactive |
| `createdBy` | string | 否 |  |

- **200**：SkillResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `status` | string | 否 |  | 枚举：active, inactive |
| `createdBy` | string | 否 |  |
| `id` | string | 否 |  |
| `implementationType` | string | 否 |  |
| `createdAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |

### DELETE `/api/skills/{id}`

**删除技能 [admin]**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **204**：无内容

### PATCH `/api/skills/{id}/status`

**修改技能状态 [admin]**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `id` | path | string | 是 |  |

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `status` | string | 是 |  | 枚举：active, inactive |

- **200**：已更新

## Tools

工具资源中心

### GET `/api/tools`

**工具分页列表**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `search` | query | string | 否 |  |
| `status` | query | string | 否 |  |
| `page` | query | integer | 否 |  |
| `size` | query | integer | 否 |  |

- **200**：PageResponse&lt;ToolResponse&gt;

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` | integer | 否 |  |
| `size` | integer | 否 |  |
| `totalElements` | integer | 否 |  |
| `totalPages` | integer | 否 |  |
| `content` | [ToolItem](#toolitem)[] | 否 |  |

### POST `/api/tools`

**创建工具 [admin]**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

`executionMode=internal` + `askConfirmation=true` 时，调用触发人工确认中断；
`executionMode=external` 时挂起等待前端经 AG-UI `resume[].payload.output` 回传结果。


**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `toolType` | string | 否 |  |
| `executionMode` | string | 否 |  | 枚举：internal, external |
| `askConfirmation` | boolean | 否 |  |
| `inputSchema` | object | 否 | JSON Schema |
| `mcpEndpoint` | string | 否 |  |
| `status` | string | 否 |  | 枚举：active, inactive |

- **201**：ToolResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `toolType` | string | 否 |  |
| `executionMode` | string | 否 |  | 枚举：internal, external |
| `askConfirmation` | boolean | 否 |  |
| `inputSchema` | object | 否 | JSON Schema |
| `mcpEndpoint` | string | 否 |  |
| `status` | string | 否 |  | 枚举：active, inactive |
| `id` | string | 否 |  |
| `createdAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |

### GET `/api/tools/{id}`

**工具详情**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **200**：ToolResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `toolType` | string | 否 |  |
| `executionMode` | string | 否 |  | 枚举：internal, external |
| `askConfirmation` | boolean | 否 |  |
| `inputSchema` | object | 否 | JSON Schema |
| `mcpEndpoint` | string | 否 |  |
| `status` | string | 否 |  | 枚举：active, inactive |
| `id` | string | 否 |  |
| `createdAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |

### PUT `/api/tools/{id}`

**更新工具 [admin]**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `toolType` | string | 否 |  |
| `executionMode` | string | 否 |  | 枚举：internal, external |
| `askConfirmation` | boolean | 否 |  |
| `inputSchema` | object | 否 | JSON Schema |
| `mcpEndpoint` | string | 否 |  |
| `status` | string | 否 |  | 枚举：active, inactive |

- **200**：ToolResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `toolType` | string | 否 |  |
| `executionMode` | string | 否 |  | 枚举：internal, external |
| `askConfirmation` | boolean | 否 |  |
| `inputSchema` | object | 否 | JSON Schema |
| `mcpEndpoint` | string | 否 |  |
| `status` | string | 否 |  | 枚举：active, inactive |
| `id` | string | 否 |  |
| `createdAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |

### DELETE `/api/tools/{id}`

**删除工具 [admin]**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **204**：无内容

### PATCH `/api/tools/{id}/status`

**修改工具状态 [admin]**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `id` | path | string | 是 |  |

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `status` | string | 是 |  | 枚举：active, inactive |

- **200**：已更新

## SkillRepositories

技能仓库（外部技能源浏览与导入）

### GET `/api/skill-repositories`

**技能仓库信息**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

外部技能源（远程仓库）元信息与同步状态。

- **200**：SkillRepoInfo

### POST `/api/skill-repositories/sync`

**触发技能仓库同步 [admin]**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **200**：同步后的 SkillRepoInfo

### GET `/api/skill-repositories/skills`

**仓库技能分页列表**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `search` | query | string | 否 |  |
| `page` | query | integer | 否 |  |
| `size` | query | integer | 否 |  |

- **200**：PageResponse&lt;SkillRepoCard&gt;

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` | integer | 否 |  |
| `size` | integer | 否 |  |
| `totalElements` | integer | 否 |  |
| `totalPages` | integer | 否 |  |

### GET `/api/skill-repositories/skills/{id}`

**仓库技能详情**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **200**：SkillRepoCard

### POST `/api/skill-repositories/skills/{id}/import`

**导入仓库技能到平台技能中心 [admin]**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

权限码 `skill:create`。

- **200**：SkillResponse

## Tags

标签（公开标签池 + 管理端 CRUD）

### GET `/api/tags`

**公开标签池**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

登录即可访问，只返回启用标签的精简视图（id/name/color），供广场与知识库列表的筛选项及表单下拉使用。

- **200**：标签列表

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `tags` | [TagView](#tagview)[] | 否 |  |
| `total` | integer | 否 |  |
- **401**

### GET `/api/admin/tags`

**标签管理列表 [admin]**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

返回完整视图（含 sort/enabled 与 agentCount/kbCount 使用计数），包含停用标签。

- **200**：标签列表

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `tags` | [TagView](#tagview)[] | 否 |  |
| `total` | integer | 否 |  |
- **403**

### POST `/api/admin/tags`

**创建标签 [admin]**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `color` | string | 否 | HEX 颜色 |
| `sort` | integer \| null | 否 |  |
| `enabled` | boolean \| null | 否 |  |

- **201**：TagView

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 否 |  |
| `name` | string | 否 |  |
| `color` | string | 否 | HEX 颜色（如 #2563eb） |
| `sort` | integer \| null | 否 |  |
| `enabled` | boolean \| null | 否 |  |
| `agentCount` | integer \| null | 否 |  |
| `kbCount` | integer \| null | 否 |  |
- **409**

### PUT `/api/admin/tags/{id}`

**更新标签（改名/换色/排序/启停） [admin]**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

停用（enabled=false）不做物理删除，已关联数据保留；停用后不再出现在公开标签池与筛选中。改名/换色会同步应用到所有关联位置。

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `color` | string | 否 | HEX 颜色 |
| `sort` | integer \| null | 否 |  |
| `enabled` | boolean \| null | 否 |  |

- **200**：TagView

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 否 |  |
| `name` | string | 否 |  |
| `color` | string | 否 | HEX 颜色（如 #2563eb） |
| `sort` | integer \| null | 否 |  |
| `enabled` | boolean \| null | 否 |  |
| `agentCount` | integer \| null | 否 |  |
| `kbCount` | integer \| null | 否 |  |
- **404**

### DELETE `/api/admin/tags/{id}`

**删除标签 [admin]**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

物理删除标签行，关联表行级联删除（FK CASCADE）。

- **204**：无内容
- **404**

## ModelCredentials

模型凭证（用户级，个人 / 平台共享）

### GET `/api/credentials`

**当前用户的模型凭证列表**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

按归属校验权限（访问他人资源返回 404）。密钥类字段脱敏回显（掩码），external_api_key 不回显。 每项含 `id/user_id/scope(USER|PLATFORM)/data(脱敏后配置，含 vendor_models 厂商核验模型清单)/ enabled_models(启用模型开关)/editable/created_at/updated_at`。


- **200**：凭证列表

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `credentials` | object[] | 否 |  |
| `total` | integer | 否 |  |
- **401**

### POST `/api/credentials`

**创建个人模型凭证**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

请求体为按供应商 schema 的动态 JSON（`data.type` 决定供应商）。本端点固定创建 `scope=USER` 个人凭证（scope 不经请求体传递，由端点承载）。 保存即核验 KEY：调用厂商 /models 验证可用性，成功后把厂商模型清单缓存进 `data.vendor_models`；失败 400 阻断保存。可选 `enabled_models`（字符串数组） 限定启用模型。平台共享凭证走 `POST /api/credentials/platform`。


**请求体**（application/json）：

（空对象）

- **200**：创建后的凭证视图（脱敏）
- **400**

### POST `/api/credentials/platform`

**创建平台共享模型凭证**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

创建 `scope=PLATFORM` 平台共享凭证（全员可在对话中选用），`credential:create` 注解把门（scope 由端点决定）。核验与字段语义同 `POST /api/credentials`。


**请求体**（application/json）：

（空对象）

- **200**：创建后的凭证视图（脱敏）
- **400**
- **403**

### GET `/api/credentials/default-model`

**平台默认模型（全局唯一）**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

登录可读。对话未选模型时后端按它兜底（V57 platform_model_default 单例行）； 未设置时 credential_id/model 为 null。仅 PLATFORM 凭证下的启用模型可被设为默认， 凭证删除/禁用自动清除。


- **200**：当前平台默认模型

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `credential_id` | string | 否 |  |
| `model` | string | 否 |  |
- **401**

### PUT `/api/credentials/default-model`

**设置平台默认模型（覆盖式）**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

body = {credential_id, model}，全局互斥（单例行覆盖更新）；权限复用 `credential:update`（与平台凭证编辑同级，不新增权限码）。只允许 PLATFORM 凭证下的启用模型，否则 400。


**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `credential_id` | string | 是 |  |
| `model` | string | 是 |  |

- **200**：设置后的平台默认模型
- **400**
- **403**

### GET `/api/credentials/schemas`

**供应商凭证 schema**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

新建/编辑表单按其动态渲染；同时作为模型目录枚举（/api/models 等）的 provider 来源。

- **200**：供应商 schema 列表

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `schemas` | object[] | 否 |  |

### PATCH `/api/credentials/{id}`

**更新模型凭证**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

密钥字段留空 = 保留原值（不回显、不覆盖）。按实体 scope 分流：PLATFORM 凭证 要求 `credential:update`（个人凭证靠归属校验，越权 404）。`enabled_models` 可单独改（详情页开关只传该字段）：null = 清除限制恢复全部可用；数组 = 全量替换。 带 data 时保存即重新核验 KEY 并刷新 vendor_models。


**请求体**（application/json）：

（空对象）

- **200**：更新后的凭证视图（脱敏）
- **404**

### DELETE `/api/credentials/{id}`

**删除模型凭证**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

按实体 scope 分流：PLATFORM 凭证要求 `credential:delete`（个人凭证靠归属校验， 越权 404）。删除默认模型所在凭证时平台默认模型自动清除。


- **200**：{'deleted': True}

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `deleted` | boolean | 否 |  |
- **404**

## ModelCatalog

模型目录（内建供应商模型枚举，按 provider 查询）

### GET `/api/models`

**对话模型目录**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

按供应商枚举内建目录中的对话模型（与凭证页「可用模型」同源）。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `provider` | query | string | 是 |  |

- **200**：模型列表

### GET `/api/tts-models`

**TTS 模型目录**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `provider` | query | string | 是 |  |

- **200**：模型列表

### GET `/api/embedding-models`

**Embedding 模型目录**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

知识库创建/编辑弹窗的 Embedding 模型下拉数据源（仅 status=active）。

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `provider` | query | string | 是 |  |

- **200**：模型列表

## Channels

通知渠道（企业微信/钉钉/飞书/通用 webhook）

### GET `/api/channels/types`

**渠道类型元数据**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

新建表单按 `credentials_schema` 动态渲染（wechat_work/dingtalk/feishu/webhook）。

- **200**：渠道类型列表

### GET `/api/channels`

**当前用户的通知渠道列表**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

按归属校验权限（访问他人资源返回 404）；凭据字段脱敏回显。

- **200**：渠道列表

### POST `/api/channels`

**创建通知渠道**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

**请求体**（application/json）：

（空对象）

- **200**：渠道视图

### PATCH `/api/channels/{id}`

**更新通知渠道**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

**请求体**（application/json）：

（空对象）

- **200**：渠道视图
- **404**

### DELETE `/api/channels/{id}`

**删除通知渠道**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **200**：{'deleted': True}

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `deleted` | boolean | 否 |  |
- **404**

### POST `/api/channels/{id}/enable`

**启用渠道**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **200**：渠道视图

### POST `/api/channels/{id}/disable`

**停用渠道**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **200**：渠道视图

### GET `/api/channels/{id}/status`

**渠道连通状态**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

出站型渠道由最近一次投递结果推导。

- **200**：状态视图

### POST `/api/channels/{id}/test`

**发送测试消息**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

结果同步返回并写入渠道状态。

- **200**：测试结果

## Notifications

平台通知（站内列表 + SSE 信号流）

### GET `/api/notifications`

**最近通知列表**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `unreadOnly` | query | boolean | 否 |  |
| `limit` | query | integer | 否 |  |

- **200**：通知列表（含 unreadCount）

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `count` | integer | 否 |  |
| `unreadCount` | integer | 否 |  |
| `notifications` | object[] | 否 |  |
| `notifications[].id` | string | 否 |  |
| `notifications[].level` | string | 否 |  |
| `notifications[].title` | string | 否 |  |
| `notifications[].body` | string | 否 |  |
| `notifications[].sessionId` | string \| null | 否 |  |
| `notifications[].agentId` | string \| null | 否 |  |
| `notifications[].readAt` | string \| null | 否 |  |
| `notifications[].createdAt` | string | 否 |  |
- **401**

### GET `/api/notifications/unread-count`

**未读通知数**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **200**：未读计数

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `count` | integer | 否 |  |

### POST `/api/notifications/{id}/read`

**标记单条通知已读**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **200**：已标记

### POST `/api/notifications/read-all`

**全部标记已读**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **200**：已标记

### GET `/api/notify/stream`

**通知 SSE 信号流**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

`text/event-stream`。通知信号与 event_sequence 增量补拉的设计见 `docs/realtime-notification-design.md`；前端收到信号后按需拉取 /api/notifications 与 /api/agents/.../transcript。


- **200**：SSE 事件流

### GET `/api/sessions/{sessionId}/alert-push`

**会话告警推送配置**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **200**：配置视图
- **404**

### PUT `/api/sessions/{sessionId}/alert-push`

**更新会话告警推送配置**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

绑定会话到通知渠道（渠道归属本人）；命中规则时经 AlertNotifier 分发到各渠道。

**请求体**（application/json）：

（空对象）

- **200**：配置视图
- **404**

## AguiEvents

AG-UI 事件审计记录查询（事件已持久化到数据库）

### GET `/api/agui-events`

**会话 AG-UI 事件审计记录**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

按用户 + 会话查询已持久化的 AG-UI 事件（V37 起异步批量写入数据库，键 (user, session, seq)）。 仅用于审计，前端对话渲染不使用此接口。支持 afterSequence 增量拉取。


| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `sessionId` | query | string | 是 |  |
| `afterSequence` | query | integer | 否 |  |
| `limit` | query | integer | 否 |  |

- **200**：事件记录列表
- **401**

## Groups

用户群组（知识库授权用）

### GET `/api/groups`

**群组列表**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **200**：简要群组列表

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `groups` | object[] | 否 |  |
| `groups[].id` | string | 否 |  |
| `groups[].name` | string | 否 |  |

### POST `/api/groups`

**创建群组**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `memberUserIds` | string[] | 否 |  |

- **201**：GroupView

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 否 |  |
| `name` | string | 否 |  |
| `createdBy` | string | 否 |  |
| `createdByName` | string | 否 |  |
| `members` | object[] | 否 |  |
| `members[].userId` | string | 否 |  |
| `members[].userName` | string | 否 |  |
| `createdAt` | string | 否 |  |

### GET `/api/groups/{id}`

**群组详情**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **200**：GroupView

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 否 |  |
| `name` | string | 否 |  |
| `createdBy` | string | 否 |  |
| `createdByName` | string | 否 |  |
| `members` | object[] | 否 |  |
| `members[].userId` | string | 否 |  |
| `members[].userName` | string | 否 |  |
| `createdAt` | string | 否 |  |

### DELETE `/api/groups/{id}`

**删除群组**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

- **204**：无内容

### POST `/api/groups/{id}/members`

**添加成员**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `id` | path | string | 是 |  |

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `userId` | string | 是 |  |

- **201**：GroupView

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 否 |  |
| `name` | string | 否 |  |
| `createdBy` | string | 否 |  |
| `createdByName` | string | 否 |  |
| `members` | object[] | 否 |  |
| `members[].userId` | string | 否 |  |
| `members[].userName` | string | 否 |  |
| `createdAt` | string | 否 |  |

### DELETE `/api/groups/{id}/members/{userId}`

**移除成员**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `id` | path | string | 是 |  |
| `userId` | path | string | 是 |  |

- **204**：无内容

## Agui

AG-UI 对话运行时（SSE）

### POST `/agui/run/{agentId}`

**发起 / 恢复一次对话运行（SSE）**

鉴权：需登录（`Authorization: Bearer <accessToken>`）

AG-UI v2 协议端点，响应为 `text/event-stream`（每行 `data: {JSON}`，兼容纯 JSON 行）。

**标识语义**
- `threadId` = 平台 `sessionId`（前端新会话生成 `main-{uuid}`，贯穿整个会话）。
- `runId` 平台约定填 agentId；HITL resume 复用同一 `(threadId, runId)` 直到本轮 `RUN_FINISHED`。
- 同一 thread 的 run 严格串行（并发 run 被框架拒绝："Thread already has an active run"）。
- **会话隔离**：userId 取 `Authorization: Bearer` JWT 的 `sub`，会话状态按 (userId, sessionId) 分区。

**HITL 恢复（resume）**
- 收到 `RUN_FINISHED(outcome={type:"interrupt", interrupts:[...]})` 后，
  以同一 `(threadId, runId)` 重新 POST，并在 `resume[]` 中对全部 open interrupts 给出答复；
  否则报 "Thread has unresolved interrupts; RunAgentInput.resume must address all of them"。
- `interruptId` 格式为 `{replyId}:{toolCallId}`。
- `status=resolved`：`payload.approved`（权限确认）、`payload.output`（external 工具/表单回传值）、
  `payload.editedArgs`（编辑工具入参）。注意 external_execution 类中断只传 output，不传 approved。
- `status=cancelled`：取消该中断。
- 后端重启后中断登记丢失（进程内存），resume 会报 "does not match any open interrupt"，
  前端应改为把答案作为 user message 发起新 run。

**可用事件**（详见事件说明）：`RUN_STARTED`、`RUN_FINISHED`、`RUN_ERROR`、
`TEXT_MESSAGE_START/CONTENT/END`、`THINKING_BLOCK_START/DELTA/END`、
`TOOL_CALL_START/ARGS/END`、`TOOL_CALL_RESULT` / `TOOL_RESULT_*`、
`DATA_BLOCK_START/DELTA/END`、`CUSTOM`。


| 参数 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `agentId` | path | string | 是 |  |

**请求体**（application/json）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `threadId` | string | 是 | = sessionId，如 main-<uuid> |
| `runId` | string | 是 | 平台约定填 agentId；resume 时复用 |
| `messages` | object[] | 是 |  |
| `messages[].id` | string | 否 |  |
| `messages[].role` | string | 否 |  | 枚举：user, assistant, system, tool |
| `messages[].content` | object[] | 否 |  |
| `messages[].content[].type` | string | 否 |  |
| `messages[].content[].text` | string | 否 |  |
| `messages[].metadata` | object | 否 | 如 {synthetic: true} 的消息在转录中不渲染 |
| `resume` | object[] | 否 | HITL 恢复项；必须覆盖全部 open interrupts |
| `resume[].interruptId` | string | 是 | {replyId}:{toolCallId} |
| `resume[].status` | string | 是 |  | 枚举：resolved, cancelled |
| `resume[].payload` | object | 否 |  |
| `resume[].payload.approved` | boolean | 否 | 权限确认类使用 |
| `resume[].payload.editedArgs` | object | 否 | 编辑工具入参 |
| `resume[].payload.output` | string | 否 | external 工具 / 表单提交值（JSON 字符串） |
| `resume[].metadata` | object | 否 |  |
| `state` | object | 否 |  |
| `tools` | object[] | 否 |  |
| `context` | object[] | 否 |  |
| `forwardedProps` | object | 否 | 平台扩展字段（均可省略）： - `credential_id` + `model`（成对出现）：对话轮次模型选择，按当前用户   解密凭证并校验可用性，在本轮 run 装配覆盖模型实例；携带但凭证不可用   仅降级不报错。未携带时优先平台默认模型（/api/credentials/default-model），   动态默认未设置/解析失败再回落 `agent-platform.model` 配置。   对话中可改，下一轮 run 重新装配生效；子智能体不跟随。 - `skill_invocations`: `[{skill_id, name}, …]` 消息级技能调用（「/ 调用技能」）。  |

- **200**：SSE 事件流。关键事件：
- `RUN_STARTED` / `RUN_FINISHED`（含 `outcome: {type: "success"|"interrupt"|"error"}`，
  interrupt 时携带 `interrupts[]`：`{id, reason:"tool_call", toolCallId, message,
  metadata:{agentscope.interruptKind, toolName, toolInput, toolContent, replyId}, responseSchema}`）
- `RUN_ERROR`（message）
- `TEXT_MESSAGE_START/CONTENT/END`（messageId, delta）
- `THINKING_BLOCK_START/DELTA/END`
- `TOOL_CALL_START/ARGS/END`（toolCallId, toolCallName, input）
- `TOOL_CALL_RESULT` / `TOOL_RESULT_END`（toolCallId, output, state）

- **401**

## 关键请求示例（curl）

### 登录并调用对话

```bash
# 1. 登录拿 token（或走 /api/auth/oauth/login + /api/auth/callback）
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"******"}' | jq -r .accessToken)

# 2. 发起对话（SSE）。threadId=sessionId（新会话前端生成 main-<uuid>）；
#    runId 平台约定填 agentId；HITL resume 复用同一 (threadId, runId)
curl -N -X POST http://localhost:8080/agui/run/教材开发小助手 \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "threadId": "main-3f6c2a90-1111-4ccc-8ddd-222233334444",
    "runId": "教材开发小助手",
    "messages": [
      { "id": "m-1", "role": "user",
        "content": [{ "type": "text", "text": "开始写《传感器技术》教材" }] }
    ]
  }'
```

### HITL：表单/人工确认中断与恢复

```bash
# 收到 RUN_FINISHED(outcome={type:"interrupt", interrupts:[…]}) 后，
# 以同一 (threadId, runId) 重新 POST，resume 覆盖全部 open interrupts：
curl -N -X POST http://localhost:8080/agui/run/教材开发小助手 \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{
    "threadId": "main-3f6c2a90-…",
    "runId": "教材开发小助手",
    "messages": [],
    "resume": [
      { "interruptId": "<replyId>:<toolCallId>", "status": "resolved",
        "payload": { "output": "{\"decision\":\"通过\",\"feedback\":\"…\"}" } }
    ]
  }'
# 注意：external_execution 类中断只传 output 不传 approved；
# 权限确认类传 { "approved": true, "editedArgs": {} }；取消用 status:"cancelled"。
```

### 附件与会话资源

```bash
AGENT_ID=…; SESSION_ID=main-3f6c2a90-…
# 上传附件（≤20MB，白名单扩展名），返回的 ref 形如 @uploads/xxx，
# 在用户消息里以该 @path 引用
curl -s -X POST "http://localhost:8080/api/agents/$AGENT_ID/sessions/$SESSION_ID/attachments" \
  -H "Authorization: Bearer $TOKEN" -F "file=@./讲义.pdf"

# 拉取转录（后台 wakeup 续跑产生的段落靠这里补齐，建议轮询）
curl -s "http://localhost:8080/api/agents/$AGENT_ID/sessions/$SESSION_ID/transcript" \
  -H "Authorization: Bearer $TOKEN"

# 读取产物内容（会话内相对路径）
curl -s "http://localhost:8080/api/agents/$AGENT_ID/sessions/$SESSION_ID/artifacts/content?path=全局DB/教材/目录.md" \
  -H "Authorization: Bearer $TOKEN"
```

### 知识库检索

```bash
# type=rag/external 返回 {documentId,chunkIndex,score,content}；
# type=text 返回 {file,line,content,context[]}
curl -s -X POST "http://localhost:8080/api/knowledge-bases/$KB_ID/search" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"query":"电容传感器的测量电路","topK":5}'
```

## 附：Schema 字典（components.schemas）

<a id="apierror"></a>
### ApiError

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `code` | string | 否 |  |
| `message` | string | 否 |  |

<a id="pageresponse"></a>
### PageResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `page` | integer | 否 |  |
| `size` | integer | 否 |  |
| `totalElements` | integer | 否 |  |
| `totalPages` | integer | 否 |  |

<a id="tokenresponse"></a>
### TokenResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `accessToken` | string | 否 |  |
| `refreshToken` | string | 否 |  |
| `tokenType` | string | 否 |  |
| `userId` | string | 否 |  |
| `username` | string | 否 |  |
| `role` | string | 否 |  | 枚举：admin, developer, end_user |
| `name` | string \| null | 否 | 显示名（OAuth 用户取 userAlias，本地为空可null） |

<a id="userinforesponse"></a>
### UserInfoResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `userId` | string | 否 |  |
| `username` | string | 否 |  |
| `role` | string | 否 |  | 枚举：admin, developer, end_user |
| `createdAt` | string | 否 |  |
| `name` | string \| null | 否 | 显示名 |
| `email` | string \| null | 否 |  |
| `phone` | string \| null | 否 | 脱敏展示（如 138****1234） |

<a id="registerrequest"></a>
### RegisterRequest

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `principal` | string | 是 | 账密注册时为用户名；phone/email 注册时为对应手机号/邮箱 |
| `password` | string | 是 |  |
| `role` | string | 否 |  | 枚举：admin, developer, end_user |
| `registerType` | string | 否 |  | 枚举：username, phone, email |
| `captcha` | string \| null | 否 | phone/email 注册必填（先经 /api/users/captcha/* 获取） |

<a id="agentconfigrequest"></a>
### AgentConfigRequest

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `systemPrompt` | string | 否 |  |
| `model` | string | 是 |  |
| `status` | string | 否 |  | 枚举：draft, active |
| `skillIds` | string[] | 否 |  |
| `toolIds` | string[] | 否 |  |
| `subagentIds` | string[] | 否 |  |
| `launchTemplates` | object[] | 否 |  |
| `planEnabled` | boolean | 否 |  |
| `skillSedimentation` | boolean | 否 |  |
| `welcomeForm` | object | 否 | 欢迎表单（见 docs/form-config-contract.md） |
| `form` | object | 否 | 配置驱动表单（M11） |
| `formTrigger` | string | 否 |  | 枚举：disabled, on_session_start, on_round_start, once |
| `tagIds` | string[] | 否 | 关联标签（全量替换语义，重复提交以最后一次为准） |

<a id="agentconfig"></a>
### AgentConfig

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `systemPrompt` | string | 否 |  |
| `model` | string | 是 |  |
| `status` | string | 否 |  | 枚举：draft, active |
| `skillIds` | string[] | 否 |  |
| `toolIds` | string[] | 否 |  |
| `subagentIds` | string[] | 否 |  |
| `launchTemplates` | object[] | 否 |  |
| `planEnabled` | boolean | 否 |  |
| `skillSedimentation` | boolean | 否 |  |
| `welcomeForm` | object | 否 | 欢迎表单（见 docs/form-config-contract.md） |
| `form` | object | 否 | 配置驱动表单（M11） |
| `formTrigger` | string | 否 |  | 枚举：disabled, on_session_start, on_round_start, once |
| `tagIds` | string[] | 否 | 关联标签（全量替换语义，重复提交以最后一次为准） |
| `id` | string | 否 |  |
| `workspacePath` | string | 否 |  |
| `fileBased` | boolean | 否 | true = 文件型智能体（workspace/agents/ 下直接定义，frontmatter tags 按名同步进标签池） |
| `tags` | [TagView](#tagview)[] | 否 | 关联标签（展示字段，脱敏时保留） |
| `createdAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |

<a id="sessioninfo"></a>
### SessionInfo

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionId` | string | 否 |  |
| `summary` | string | 否 |  |
| `title` | string | 否 |  |
| `updatedAt` | string | 否 |  |
| `archivedAt` | string \| null | 否 | null = 未归档 |

<a id="sessiontranscript"></a>
### SessionTranscript

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionId` | string | 否 |  |
| `messages` | object[] | 否 |  |
| `messages[].id` | string | 否 |  |
| `messages[].role` | string | 否 |  |
| `messages[].name` | string \| null | 否 |  |
| `messages[].createdAt` | string | 否 |  |
| `messages[].metadata` | object | 否 |  |
| `messages[].content` | object / object / object / object[] | 否 | 与 AG-UI ContentBlock 对齐 |

<a id="pendingforminterrupt"></a>
### PendingFormInterrupt

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `interruptId` | string | 是 | {replyId}:{toolCallId} |
| `replyId` | string | 否 |  |
| `toolCallId` | string | 是 |  |
| `toolName` | string | 否 |  |
| `toolInput` | object | 否 |  |
| `createdAt` | string | 否 |  |

<a id="artifactnode"></a>
### ArtifactNode

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 否 |  |
| `path` | string | 否 |  |
| `type` | string | 否 |  | 枚举：dir, file |
| `size` | integer \| null | 否 | 目录为 null |
| `updatedAt` | integer \| null | 否 | epoch 毫秒 |
| `children` | array \| null | 否 |  |

<a id="extensionresponse"></a>
### ExtensionResponse

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 否 |  |
| `agentId` | string | 否 |  |
| `sessionId` | string | 否 |  |
| `userId` | string | 否 |  |
| `entries` | object[] | 否 |  |
| `entries[].key` | string | 否 |  |
| `entries[].value` | string | 否 |  |
| `createdAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |

<a id="knowledgebase"></a>
### KnowledgeBase

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 否 |  |
| `name` | string | 否 |  |
| `description` | string | 否 |  |
| `type` | string | 否 | rag=本地 pgvector；external=外部 RAG 平台（RAGFlow/Dify/百炼）；text=文本 grep 检索 | 枚举：rag, external, text |
| `owner` | object | 否 |  |
| `owner.id` | string | 否 |  |
| `owner.name` | string | 否 |  |
| `embeddingModelConfig` | object | 否 |  |
| `externalProvider` | string \| null | 否 |  |
| `externalEndpoint` | string \| null | 否 |  |
| `chunkSize` | integer \| null | 否 |  |
| `chunkOverlap` | integer \| null | 否 |  |
| `scope` | string | 否 | PERSONAL=个人知识库（owner 可见）；PLATFORM=平台共享（V52 迁移后全员可见） | 枚举：PERSONAL, PLATFORM |
| `tags` | [TagView](#tagview)[] | 否 | 关联标签（id/name/color） |
| `editable` | boolean | 否 |  |
| `permission` | string | 否 | 当前用户对该库的有效权限 |
| `createdAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |

<a id="knowledgebaseupsert"></a>
### KnowledgeBaseUpsert

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `type` | string | 否 |  | 枚举：rag, external, text |
| `embeddingModelConfig` | object | 否 | 向量维度固定 1536 |
| `externalProvider` | string | 否 |  |
| `externalEndpoint` | string | 否 |  |
| `externalApiKey` | string | 否 | 不回显；编辑时留空 = 不重加密 |
| `externalExtra` | object | 否 |  |
| `chunkSize` | integer | 否 |  |
| `chunkOverlap` | integer | 否 |  |

<a id="knowledgedocument"></a>
### KnowledgeDocument

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 否 |  |
| `filename` | string | 否 |  |
| `size` | integer | 否 | 实时读盘 |
| `contentType` | string | 否 | 按扩展名推断 |
| `status` | string | 否 |  |
| `error` | string \| null | 否 |  |
| `chunkCount` | integer \| null | 否 |  |
| `createdAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |

<a id="knowledgegrant"></a>
### KnowledgeGrant

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 否 |  |
| `principalType` | string | 否 |  | 枚举：USER, GROUP |
| `principalId` | string | 否 |  |
| `principalName` | string | 否 |  |
| `permission` | string | 否 | WRITE 包含 READ | 枚举：READ, WRITE |
| `grantedBy` | string | 否 |  |
| `createdAt` | string | 否 |  |

<a id="resourceupsert"></a>
### ResourceUpsert

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `status` | string | 否 |  | 枚举：active, inactive |
| `createdBy` | string | 否 |  |

<a id="resourceitem"></a>
### ResourceItem

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `status` | string | 否 |  | 枚举：active, inactive |
| `createdBy` | string | 否 |  |
| `id` | string | 否 |  |
| `implementationType` | string | 否 |  |
| `createdAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |

<a id="toolupsert"></a>
### ToolUpsert

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `toolType` | string | 否 |  |
| `executionMode` | string | 否 |  | 枚举：internal, external |
| `askConfirmation` | boolean | 否 |  |
| `inputSchema` | object | 否 | JSON Schema |
| `mcpEndpoint` | string | 否 |  |
| `status` | string | 否 |  | 枚举：active, inactive |

<a id="toolitem"></a>
### ToolItem

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `description` | string | 否 |  |
| `toolType` | string | 否 |  |
| `executionMode` | string | 否 |  | 枚举：internal, external |
| `askConfirmation` | boolean | 否 |  |
| `inputSchema` | object | 否 | JSON Schema |
| `mcpEndpoint` | string | 否 |  |
| `status` | string | 否 |  | 枚举：active, inactive |
| `id` | string | 否 |  |
| `createdAt` | string | 否 |  |
| `updatedAt` | string | 否 |  |

<a id="tagview"></a>
### TagView

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 否 |  |
| `name` | string | 否 |  |
| `color` | string | 否 | HEX 颜色（如 #2563eb） |
| `sort` | integer \| null | 否 |  |
| `enabled` | boolean \| null | 否 |  |
| `agentCount` | integer \| null | 否 |  |
| `kbCount` | integer \| null | 否 |  |

<a id="tagupsert"></a>
### TagUpsert

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 |  |
| `color` | string | 否 | HEX 颜色 |
| `sort` | integer \| null | 否 |  |
| `enabled` | boolean \| null | 否 |  |

<a id="taglist"></a>
### TagList

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `tags` | [TagView](#tagview)[] | 否 |  |
| `total` | integer | 否 |  |

<a id="groupview"></a>
### GroupView

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | 否 |  |
| `name` | string | 否 |  |
| `createdBy` | string | 否 |  |
| `createdByName` | string | 否 |  |
| `members` | object[] | 否 |  |
| `members[].userId` | string | 否 |  |
| `members[].userName` | string | 否 |  |
| `createdAt` | string | 否 |  |

