# 配置驱动表单（form）契约设计

> 配套前端：frontend 的 `FormBuilder`（图形化构建器）+ `EditAgentDialog` 的「偏好表单」分区。
>
> **实现状态**：配置存储与 `on_session_start` 路径已实现——`agent_configs.form`/`formTrigger` 字段（V16）、
> API 读写、`FormBuilder`/`EditAgentDialog` 编辑、空会话渲染 `agent.form`（回退 `welcomeForm`）。
> `on_round_start` / `once` 的后端注入流程与 `POST /{id}/form/answer` 端点为设计稿，未实现；
> 表单答案的实际回传由 AG-UI HITL interrupt 通道承载（见 `vertical-agent-dev-guide.md` §6）。

## 1. 背景与问题

「偏好询问机器人」类 Agent 当前把问卷 JSON 直接写进**系统提示词**（如 `ui_survey` 的 `pages/items` 结构）。这带来两类问题：

1. **使用门槛高**：运营/产品需在自然语言提示词里手敲 JSON（知道 `kind` 取值、`pages/items` 嵌套），写错一个逗号或拼错 `checkbox-group` 即失效。
2. **提示词反模式**：让模型「从提示词里复述问卷结构去调 `ui_survey`」= 把本应确定性的配置交给模型现编，随时可能跑偏、漏字段。

且既有 `ui_survey` 的 7 种 `kind` 可用类型只写在 `src/api/platform.ts` 注释里，写提示词的人**无从发现**有哪些组件可用。

## 2. 总体方案：配置驱动 + 运行时注入

把「问卷定义」从「提示词」解耦出来，改为 **Agent 配置项**：

- 写提示词的人只写**行为指令**（“每轮开始发偏好问卷”），不再碰任何 JSON；
- 问卷本身在图形化构建器里可视化定义，产出即 `PlatformFormBlock`（与 `ui_survey` 入参结构**完全一致**），存为 agent 配置字段 `form`；
- 触发策略 `formTrigger` 决定运行时**何时**把这份确定性表单注入对话；
- 模型触发 `ui_survey` 时不再依赖提示词里的 JSON，而是由后端把 `form` 注入（或继续允许模型**主动**一次性发问，两条路径并存）。

### 复用关系（不重复造轮子）

| 既有能力 | 新 `form` 机制如何复用 |
|----------|------------------------|
| `welcomeForm`（JSONB） | `formTrigger = on_session_start` 直接复用其「空会话渲染 + 合成启动」路径，只是数据源从 `welcomeForm` 改为 `form` |
| `ui_survey` 工具 + `SURVEY_REQUEST` 事件 | `formTrigger = on_round_start / once` 的注入，复用 `SURVEY_REQUEST` 事件与 `FormCard` 渲染；区别是**由后端主动注入**，而非模型调用工具 |
| `submitModelForm` / `submitWelcomeForm` | 答案回传复用同一套 resume / 合成消息模式 |

## 3. 数据模型

`agent_configs` 新增两列：

| 列 | 类型 | 说明 |
|----|------|------|
| `form` | `jsonb` | 表单结构本体（`PlatformFormBlock`：title/pages/items…），`null` = 未配置 |
| `formTrigger`（DB 列名 `form_trigger`） | `varchar(16)` | 触发策略枚举（见下），默认 `disabled` |

> `PlatformFormBlock` 的 7 种 `kind`：`checkbox-group` / `radio-group` / `select` / `text` / `textarea` / `number` / `other-text`，与 `UiTools.surveyProperties()` 的 `enum` 完全对齐。

## 4. 触发策略 `FormTrigger`

| 值 | 含义 | 复用/实现 |
|----|------|-----------|
| `disabled` | 不启用（默认） | 不注入任何表单 |
| `on_session_start` | 进入会话时发一次 | 等同既有 `welcomeForm` 行为：空会话渲染，提交后合成消息启动对话 |
| `on_round_start` | 每轮对话开始都发 | 每次 `chat/stream` 先在流首派发 `SURVEY_REQUEST` 并挂起，答后继续 |
| `once` | 同一用户在该 agent 生命周期内仅一次 | 同 `on_round_start`，但按 `user × agent` 持久化「已答」标记去重 |

启用判定：`form != null && formTrigger != disabled`。

## 5. 运行时注入流程

### 5.1 `on_session_start`（复用 welcome 路径）

- 前端在**空会话**挂载时直接渲染 `agent.form`（替代 `agent.welcomeForm`；`form` 优先，回退 `welcomeForm` 以兼容旧数据）；
- 提交 → `POST /{id}/form/answer`（welcome 语义）把答案格式化为合成用户消息，复用 `chat/stream` 静默启动对话（无用户气泡）。

### 5.2 `on_round_start` / `once`（每轮/一次性注入）

在 `streamChat` 入口新增一道**前置门（gate）**：

1. `streamChat` 收到用户消息 `M` 与 `sessionId`；
2. 若 `form` 需注入（trigger ∈ {on_round_start} 或 {once 且本 user×agent 未答}）：
   - 在 SSE 流**最开始**即派发 `SURVEY_REQUEST`（携带 `form` 结构 + 新 `formId`），随后以 `DONE` 结束首条流，`awaitingResume=true`；
   - 把原始消息 `M` **暂存**（key = `sessionId`，带超时，与既有 pendingModelForms 同生命周期）；
   - 登记 pending form（key = `agentId:sessionId:formId`）。
3. 前端渲染 `FormCard`；用户提交 → `POST /{id}/form/answer`（round 语义）上传 `values`；
4. 后端移除 pending，以**合成消息 = 格式化的表单答案 + 原始消息 M** 重新调用 `streamChat`，本次真正驱动 Agent 推理。

> `on_round_start` 每轮重注入（满足「每轮开始发偏好问卷」）；`once` 注入一次后在 `agent_form_answers` 记持久化标记，后续轮次跳过。

### 5.3 去重与持久化

- 会话级去重：内存 `pendingForms`（key = `agentId:sessionId:formId`），重启失效（与既有 HITL pending 一致）；
- `once` 跨会话去重：新增表 `agent_form_answers(agent_id, user_id, survey_id, created_at)`，唯一约束 `(agent_id, user_id)`；注入前查询、提交后写入。

## 6. 前端契约

- `FormBuilder` 产出即 `PlatformFormBlock`，可直接作为 `form` 字段值（与 `ui_survey` 入参同源）；
- `EditAgentDialog` 的「偏好表单」分区：创建/编辑表单 + `formTrigger` 下拉（不启用 / 进入会话时 / 每轮开始时 / 同一用户仅一次）；保存时仅在 `form` 非空时随 agent 配置 PATCH `form` 与 `formTrigger`；
- `GET /agent/{id}` 返回 `form` + `formTrigger`，前端据此决定空会话是否渲染、以及 `formTrigger` 选择器回显。

## 7. API 契约

| 方法 | 路径 | 变更 |
|------|------|------|
| POST / PUT | `/api/agents` `/api/agents/{id}` | body 增加 `form`（jsonb，可空）、`formTrigger`（`disabled` 默认） |
| GET | `/api/agents/{id}` | 响应增加 `form`、`formTrigger` |
| POST | `/api/agents/{id}/form/answer`（新增，SSE） | 表单答案回传：`{sessionId, formId, values, trigger}`；welcome 语义→合成消息启动，round 语义→合成消息 + 暂存原始消息续推 |
| 已有 | `/hitl/form/answer`、`welcome-form/answer` | 保留；新 `form` 路径优先；`welcome-form/answer` 后端同时支持读 `form` 或 `welcomeForm`（form 优先） |

`SURVEY_REQUEST` 事件 payload（复用既有定义）新增可选 `trigger` 字段，前端据此区分「welcome / round」提交路由。

## 8. 提示词解耦

- 删除提示词中内嵌的 `ui_survey` JSON 结构；只保留行为指令，例如：
  > “每轮对话开始时系统会自动向用户发起偏好问卷，你无需自行构造问卷，按用户提交内容回应即可。”
- 模型**主动**一次性发问仍可走 `ui_survey` 工具（保持不变）。

## 9. 向前兼容

- `welcomeForm`（列 + 前端渲染 + `/welcome-form/answer`）**全部保留**；对既有 agent，`form` 为空且 `welcomeForm` 非空时按 `on_session_start` 处理；
- 模型驱动的 `ui_survey` 调用**保持不变**；`form` 是新增的**配置驱动**机制，二者互不干扰；
- `welcomeForm` 可视为 `formTrigger = on_session_start` 的特例，后续可迁移统一（不在本期强制）。

## 10. 校验与上限（防滥用）

保存 `form` 时校验：

- 7 种 `kind` 合法；`title` / `pages[].items[]` 非空；选项列表非空；数值 `min/max/step` 合法（`min ≤ max`）；
- 上限：`pages ≤ 10`、`items 总数 ≤ 50`、单选项 `≤ 30` 项、文本 `maxLength ≤ 2000`、单字段 `key` 长度 `≤ 64`；
- `formTrigger ∈ 枚举`，非法值按 `disabled` 处理或拒绝（建议 400）。

## 11. 授权与边界

- 写操作（`POST/PUT` 带 `form`/`formTrigger`）由 `@RequirePermission("agent:create" / "agent:update")` 把门；
- 会话内表单答案回传属会话域操作，接 `SessionAccessGuard` 归属校验；
- 表单 JSON 体积上限（如 64KB）防止超大体量拖慢交互。

---

*实现说明：本文保留设计稿结构，实现范围以上方「实现状态」为准；历史沿革见 git。*
