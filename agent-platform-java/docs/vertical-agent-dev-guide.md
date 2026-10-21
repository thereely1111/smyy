# 垂类智能体（自定义 UI）技术对接文档

> 面向开发者：如何在 AgentHub 上开发一个垂直领域智能体，并为其对接/定制对话 UI。  
> 接口细节见同目录 [`openapi.yaml`](./openapi.yaml)；本文聚焦**开发流程、机制约定与硬性契约**，内容以当前代码为准。  
> 「停止按钮」中断 run 走 `POST /api/agents/{agentId}/sessions/{sessionId}/interrupt`（断开 SSE 不会取消后端 run）。

---

## 目录

1. [平台架构速览](#1-平台架构速览)
2. [快速开始：创建一个垂类智能体](#2-快速开始创建一个垂类智能体)
3. [AGENTS.md：规则驱动编排](#3-agentsmd规则驱动编排)
4. [子智能体定义（subagents/）](#4-子智能体定义subagents)
5. [路径与产物契约](#5-路径与产物契约)
6. [HITL 人机协同：表单与闸门](#6-hitl-人机协同表单与闸门)
7. [AG-UI 事件协议与自定义 UI](#7-ag-ui-事件协议与自定义-ui)
8. [后台续跑（wakeup）与转录同步](#8-后台续跑wakeup与转录同步)
9. [平台内置工具清单与权限](#9-平台内置工具清单与权限)
10. [知识库接入](#10-知识库接入)
11. [常见坑与硬契约汇总](#11-常见坑与硬契约汇总)
12. [接入 Checklist](#12-接入-checklist)

---

## 1. 平台架构速览

```
┌─────────────┐   AG-UI SSE (POST /agui/run/{agentId})   ┌──────────────────────┐
│  前端 React  │ ◄──────────────────────────────────────► │  后端 Spring Boot     │
│  ChatContent │    REST /api/**（转录/附件/产物/知识库）   │  AgentScope harness   │
│  usePlatform │                                          │  ├─ 父智能体 (AGENTS.md) │
│  Messages    │                                          │  ├─ 子智能体 (subagents)│
└─────────────┘                                          │  └─ 运行态 runtimes/    │
                                                          └──────────────────────┘
```

- **智能体运行时**：AgentScope Java harness 装配，单轮 ReAct 迭代上限由 `agent-platform.max-iters` 配置（当前默认 10，环境变量 `AGENT_PLATFORM_MAX_ITERS` 可覆盖；长流程子任务请显式声明 `steps`，见 §4.1）。**模型按轮次指定**：run 请求 `forwardedProps.credential_id + model` → 后端解密凭证装配本轮覆盖模型实例；未指定时优先平台默认模型（全局唯一，凭证详情页设置，仅 PLATFORM 凭证下启用模型可设，删除/禁用自动清除），动态默认未设置/解析失败才回落 `agent-platform.model` 配置。智能体定义不承载模型属性。
- **对话协议**：唯一端点 `POST /agui/run/{agentId}`（AG-UI v2，SSE）。
- **工作区两层**：
  - 定义区 `workspace/agents/{agentId}/`——AGENTS.md、subagents/、skills/（可版本管理）；
  - 运行区 `workspace/runtimes/{agentId}/{sessionId}/`——每个会话独立的产物空间。
- **会话隔离**：会话状态按 `(userId, sessionId)` 分区（PostgreSQL）；userId 取 JWT `sub`。
- **账户体系**：系统不设默认 admin 账户。注册分流：公开注册入口 `POST /api/users/register`（用户名/手机号/邮箱 + 验证码；手机/邮箱注册在认证服务完成、登录后同步落库，用户名注册先同步认证服务再落本地）或管理端建户 `POST /api/auth/register`（admin，仅账密）；**登录统一委托**外部认证服务（tipdm OAuth 密码模式）校验，认证服务未启用/不可达时直接拒绝（503）、不回退本地密码校验；权限码走 Redis 缓存（登出时清理）。生产环境务必用 `AGENT_PLATFORM_JWT_SECRET` 覆盖 JWT 密钥。

---

## 2. 快速开始：创建一个垂类智能体

### 2.1 两种定义通道

| 通道         | 方式                                             | 适用场景                                                                                |
| ---------- | ---------------------------------------------- | ----------------------------------------------------------------------------------- |
| **DB 配置型** | `POST /api/agents`（admin）或管理界面                 | 简单智能体：系统提示词 + 勾选 skillIds/toolIds/subagentIds，保存时自动生成 `workspace/agents/{agentId}/` |
| **文件型**    | 直接在 `workspace/agents/<名称>/` 放 `AGENTS.md` 等文件 | 复杂垂类编排（规则驱动、状态机、多子智能体）。文件型不携带模型属性，与 DB 型一样由对话轮次的模型选择/平台默认模型决定 |

文件型智能体由 `FileSystemAgentLoader` 扫描目录名自动加载；平台启动时把所有 `active` DB 智能体 + 文件型智能体注册进 AG-UI registry，注册后即可通过 `POST /agui/run/{agentId}` 调用。

**文件型在管理端只读**：文件型的真相源就是定义目录（磁盘），数据库里没有它的配置行。管理界面只能「看」不能「改」——列表页打「文件驱动」徽标，操作列只保留对话入口，编辑 / 启停 / 删除全部置灰；后端 `PUT / PATCH /{id}/status / DELETE /api/agents/{id}` 对文件型一律返回 `409 CONFLICT`，文案指向"请直接编辑定义目录"。详情接口 `GET /api/agents/{id}` 走 DB → 文件兜底，并对文件型额外返回 `definition` 快照（AGENTS.md 字节数/更新时间、`skills/` 目录数、`subagents/*.md` 数）；直接访问编辑 URL 落到只读页而不是一张空表单。

不要为了"能编辑"就把文件型物化成 DB 型后继续维护文件：`WorkspaceGenerator` 按 **agentId** 建目录（`agents/{uuid}/`），而文件型的 id 是**目录名的 UUIDv3 哈希**，重建出来的目录与 `workspace/agents/<名称>/` 不是同一个——结果是两份并存的配置，装配用哪一份取决于加载顺序。

改文件型配置的唯一路径：**改定义目录下的文件 → 重启服务重新装配**（AGENTS.md 是装配期读入的，热改不生效）。

### 2.2 推荐目录结构（参考样板：`workspace/agents/教材开发小助手/`）

```
workspace/agents/教材开发小助手/
├── AGENTS.md                  # 父编排智能体：系统提示词 + 硬约束 + 状态机
├── subagents/                 # 子智能体（每个一个 .md，frontmatter 定义工具面）
│   ├── 目录生成智能体.md
│   ├── 章节生成智能体.md
│   └── ...
└── skills/                    # 技能目录（SKILL.md + references/ + scripts/）
    ├── jc-0-路径工具/SKILL.md
    └── ...
```

### 2.3 最小可跑示例

`AGENTS.md` 正文即系统提示词。一个最小垂类智能体：

```markdown
你是 XX 助手，负责……（领域职责）

## 工作流程
1. …
2. 需要用户确认时，必须调用 ui_gate 渲染审批表单，禁止自行推进。

## 输出约定
- 任务完成时输出【TASK_RESULT】YAML 块（若派发子任务）。
- 每轮结束输出进度行：[进度] 阶段=… 完成=[…] 进行中=[…] 剩余=[…] 下步=… 状态=…
```

DB 型保存为 active 后即可对话；文件型在平台启动时扫描注册，新增定义目录需重启服务后生效（修改文件型配置同样需重启，见上文）。

---

## 3. AGENTS.md：规则驱动编排

AGENTS.md 是编排智能体的顶层规则文件。样板项目的章节结构可直接复用：

### 3.1 建议章节

1. **硬约束**——编号列出不可违反的规则（如「单表单原则」「HITL 统一通道」）。
2. **环境与路径约定**——产物写相对路径、跨会话读绝对路径（见 §5）。
3. **子智能体注册表**——`agent_id` = 文件名去掉 `.md`；每个子智能体的职责一句话。
4. **全流程状态机表**——领域流程的每个阶段、进入条件、退出条件、所需产物。
5. **表单规范**——哪个阶段用 `ui_form_advanced` 收集什么字段、哪个阶段用 `ui_gate` 闸门。
6. **输出协议**——`【TASK_RESULT】` 契约（见 §4.3）与进度行格式。

### 3.2 平台级硬约束（必须写进你的 AGENTS.md 并遵守）

| 约束                                                                                                                 | 原因                                                                                                                                  |
| ------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------- |
| **单表单原则**：同一时刻只允许一个结构化表单调用在途                                                                                       | AG-UI 线程硬契约：resume 必须覆盖全部 open interrupts，多表单并发会报 `Thread has unresolved interrupts; RunAgentInput.resume must address all of them` |
| **HITL 统一通道**：人工审批一律 `ui_gate`，信息收集一律 `ui_form_advanced`，子智能体遇审批需求返回 `[HITL_REQUIRED]` 由父接管                        | 平台守卫 `UiTools` 会**拒绝**在 `ui_card` 入参里出现「闸门/审批/approve」等审批语义词（防内部卡片伪造审批结论）                                                                              |
| **子任务等待以任务状态为准**：`task_output(task_id, block=false)` / `task_list`，返回 `timeout_promoted` 或后台化提示时**立即结束本轮**，等完成通知唤醒 | 阻塞轮询会撑爆同步等待（`agent_spawn` 同步等待超 1 分钟自动后台化）                                                                                          |
| **spawn 前必须 `task_list` 查重、识别 REJECT**                                                                             | 防重复派发与死循环                                                                                                                           |
| 进度行 `[进度] …` 六个字段的取值是**机器契约**                                                                                      | 后端 `SessionProgressService` 按字面值解析（`执行中/部分完成/等待人工/已完成/已阻塞`）；改口径必须先改代码                                                               |

### 3.3 不要依赖的东西

- **路径不支持 `${WORKSPACE_ROOT}` / `${SESSION_ID}` 占位符**。路径写法见 §5。

---

## 4. 子智能体定义（subagents/）

每个子智能体是一个 `.md` 文件：frontmatter 声明元信息与工具面，正文是子智能体系统提示词。

### 4.1 frontmatter 规范

```markdown
---
description: 教材目录的调度者。不生成内容，只负责调用相关 Skill。……   # 必填
mode: subagent
steps: 50            # 单轮迭代上限（平台默认 max-iters=10，长流程会被强制收尾，务必显式给足）
workspace:
  mode: shared       # 官方 harness 只在 SHARED 分支传递父的文件系统后端，必须写 shared
tools: [load_skill_through_path, read_file, grep_files, glob_files, write_file, execute, web_search, web_fetch]
---
```

要点：

- `description` 必填；正文 frontmatter 校验规则同 `POST /api/subagents/validate`（可先调该接口校验）。
- **`tools:` 白名单物理决定子智能体的工具面**。要防止子智能体再派发子任务，就让白名单里**不含 `agent_spawn`**——这是平台强制手段，不是提示词约定。
- 子智能体声明自己的 subagent 时，自动继承父的 compaction/eviction 配置。

### 4.2 调用方式

父编排智能体经 `agent_spawn` 派发：

- `agent_id` = 文件名去掉 `.md`；
- 返回头三行 `agent_key / agent_id / session_id`；
- 同步等待超 1 分钟自动后台化，父收到占位提示「DO NOT poll」，之后靠完成通知唤醒（见 §8）。

**只允许父编排派发**（子智能体工具面里没有 agent_spawn），禁止子智能体互调。

### 4.3 【TASK_RESULT】汇报契约

子智能体 return 前必须输出**唯一一个** YAML 块：

```yaml
【TASK_RESULT】
status: COMPLETED        # COMPLETED | PARTIAL | BLOCKED | FAILED
completed: ["…"]
in_progress: ["…"]
remaining: ["…"]
artifacts:               # 逐文件登记运行态根相对路径；平台会核对文件真实存在
  - 全局DB/教材名/章节1/正文.md
next_action: "…"
resumable: true
```

- 父编排**只信 `artifacts`**（平台 `SubagentReportService` 会核对存在性并展示在「子任务汇报」面板）。
- 平台前端可查 `GET /api/agents/{agentId}/sessions/{sessionId}/subagents` 获取汇报。

---

## 5. 路径与产物契约

> 纯会话隔离方案。框架给每个会话挂 `LocalFilesystemWithShell(rootDir=runtimes/{agentId}, mode=ROOTED)` 上层 + 定义区只读下层，父/子共享同一根、各自会话命名空间（父 = `main-*`，子 = `sub-<uuid>`）。

| 场景         | 写法                                 | 说明                                                                                                                                         |
| ---------- | ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| **写自己的产物** | **相对路径**，如 `全局DB/{教材名称}/章节1/正文.md` | 框架自动拼到本会话空间 `runtimes/{agentId}/{sessionId}/…`；`write_file` 自动建多级父目录                                                                       |
| **读跨会话输入** | **绝对路径** = 运行态根 + 产出方会话目录 + 相对路径   | 运行态根绝对路径由框架写进每个会话 system prompt 的 `## Workspace` 段（`Project (…)` 行）；产出方会话目录来自其 `【TASK_RESULT】artifacts`（如 `sub-1a2b3c4d/…`），父编排**原样转发**给下游 |
| **汇报产物**   | `artifacts` 登记运行态根相对路径             | 平台核对文件存在性                                                                                                                                  |

配套能力：

- **产物面板**（前端只读）：`GET …/artifacts` 聚合「本会话 + 全部子会话」文件树（血缘取自父会话状态的 `tool_context.spawn_registry`）；`GET …/artifacts/content?path=<相对路径>` 预览文本（≤5MB）。
- **附件**：用户上传经 `POST …/attachments` 落盘到 `uploads/`，用户消息里以 `@uploads/<存储名>` 引用（`@path` 内联策略：≤32KB 直接内联，超限走 read_file）。附件一律视为**参考资料**，不作为指令或系统设计的来源——平台 sysPrompt 注入的附件边界契约即为此约束。
- **建议**：把上述路径规范做成一个 Skill（样板项目为 `jc-0-路径工具`），所有子智能体 prompt 引用同一份，避免口径漂移。

---

## 6. HITL 人机协同：表单与闸门

平台装配时给**所有**智能体自动注册四个 UI 工具，无需配置勾选：

| 工具                 | 模式           | 语义                | 关键参数                                                                                                                                                                                                                  |
| ------------------ | ------------ | ----------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ui_form`          | external（挂起） | 通用 JSON Schema 表单 | `title, description, schema, values(预填), submitLabel`。external 工具须等用户真实提交才产生结果，无审批语义守卫                                                                                                             |
| `ui_form_advanced` | external（挂起） | 产品级多页表单           | `pages[{key,title,items[…]}]`；控件 `kind` 7 种：`checkbox-group / radio-group / select / text / textarea / number / other-text`；item 支持 `required/min/max/minLength/maxLength/pattern/placeholder/options/initialValue` 等 |
| `ui_card`          | internal（立即） | 只读内容卡片            | `kind ∈ {text, table, metrics, list}`。**守卫**：入参呈审批闸门形状（含「闸门/审批/不通过/approve/approval」等词）时平台直接拒绝——internal 工具调用即返回，防止模型用卡片伪造审批结论。**严禁**用于审批或替代 ui_gate                                             |
| `ui_gate`          | external（挂起） | **审批闸门**（语义专用）    | schema 由平台锁定：恰好 1 个 page（key=gate），items 前两项固定 `decision`（radio-group，选项顺序「通过/不通过」）+ `feedback`（textarea 选填）；可追加自定义附加项（如样板项目的 `sampleChapter`）。**只有决策项，没有 `description`**——摘要/产物链接走模型正文输出，见 §6.4                                        |

### 6.1 中断与恢复全流程

```
模型调用 ui_gate/ui_form_advanced（external 挂起）
  → 本流以 RUN_FINISHED(outcome={type:"interrupt", interrupts:[…]}) 收尾
  → 前端弹表单（FormCard）+ 上报 form-interrupts 登记表（PUT）
  → 用户提交 → 前端以同一 (threadId, runId) 重新 POST /agui/run/{agentId}
     resume=[{interruptId, status:"resolved", payload:{output: JSON.stringify(values)}}]
  → 模型拿到表单结果继续跑
```

**关键约定（硬契约）：**

1. **`interruptId` 格式**：`{replyId}:{toolCallId}`（前端 `aguiInterruptKey()` 与后端 starter 拼法一致）。
2. **interrupts 元数据**：`metadata.interruptKind ∈ {"external_execution", "permission_confirm"}`、`toolName`、`toolInput`、`replyId`。
3. **resume 必须覆盖全部 open interrupts**；同批同构兄弟 interrupt 按一次决定同时覆盖。
4. **external_execution 类中断只传 `output`，不传 `approved`**（否则框架按权限确认处理、忽略 output）。
5. **权限确认类**（internal 工具 `askConfirmation`）：`payload={approved: true/false, editedArgs?}`。
6. **取消**：`status:"cancelled"`。前端在发送新消息前若仍有 open interrupts，会先全部 cancel。
7. **单表单原则**：见 §3.2。

### 6.2 刷新与重启的恢复语义

| 场景     | 机制                                                                                                                                                                    |
| ------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 用户刷新页面 | 前端拉转录 → 扫描末尾未配对 `tool_result` 的 `ui_form_advanced/ui_gate` tool_call → 重建 FormCard → 从 **form-interrupts 登记表**（`GET …/form-interrupts`，进程内存）取回 interruptId 走标准 resume |
| 后端重启   | 登记表与框架中断状态均清空 → resume 报 "does not match any open interrupt" → 前端**降级**：把表单答案格式化为 user message（前缀 `（用户通过表单提交以下信息）`）发起新 run                                            |
| 权限确认卡死 | `GET …/progress` 返回 `hitlStuck=true` → 前端显示「恢复会话」→ `POST …/recover` 清除残留                                                                                              |

**前后端稳定契约**：表单提交文案前缀 `（用户通过表单提交以下信息）` / `（用户提交了表单，但未填写字段）`——后端 `HitlRecoveryGuardMiddleware` 靠它识别「这是表单提交而非新请求」，防止闸门被误判为假放行。**改文案 = 两端同步改**。

**服务端自愈**：`HitlRecoveryGuardMiddleware` 会在检测到「闸门未决但输入不像表单提交」时注入 `[HITL_RECOVERY]` 指示，强制模型重新渲染闸门而不是当作已通过。因此**智能体提示词里不要试图绕过 ui_gate**。

### 6.3 提示词写法建议

- 明确列出每个阶段用哪个工具收集哪些字段（写「表单规范」章节）；
- 不要在提示词里改 ui_gate 的字段名（decision/feedback 由平台 schema 锁定）；
- 闸门通过/不通过后的分支行为要显式写出（不通过 → 收集 feedback → 修正 → 重新闸门）。

### 6.4 闸门只承载决策（ui_gate 载荷约束）

**原则：`ui_gate` 只装「决策项 + 审核意见 + 业务必须项」，说明性内容全部作为模型正文输出。**

| 内容                          | 归属        | 说明                                             |
| --------------------------- | --------- | ---------------------------------------------- |
| 审核结论 `decision`、审核意见 `feedback` | `ui_gate` | 闸门核心，schema 锁定                                 |
| 业务必须项（如 `sampleChapter`）      | `ui_gate` | 影响后续流程走向的字段                                    |
| 产物摘要、产物路径、评审依据、问题清单、复审结论     | **模型正文**  | 先用普通文本（markdown 表格/列表均可）输出给用户，再调用 `ui_gate`       |

**平台侧两道闸（`UiTools`）：**

1. **schema 层**：`gateProperties()` 不含 `description`，只剩 `title` / `submitLabel` / `pages` 三个入参。
2. **运行时层**：`findOversizedGateField()` 扫描入参——单个字符串 > `MAX_GATE_FIELD_CHARS`（200）或文本总量 > `MAX_GATE_INPUT_CHARS`（1000）时，`callAsync` 直接返回 `ToolResultState.ERROR` 并说明正确写法，而不是挂起等提交。模型收到后应改为「正文输出摘要 + 精简参数重新调用」。

**设计原因**：复审报告全文贴进 `description` 会随每轮对话重复占用上下文，且在闸门卡片里无法完整展示。摘要以正文输出后，用户在对话流里正常阅读全文、产物走产物面板打开，闸门卡片只保留决策控件。

---

## 7. AG-UI 事件协议与自定义 UI

### 7.1 请求与事件总览

`POST /agui/run/{agentId}`，请求体：

```json
{
  "threadId": "main-<uuid>",
  "runId": "<agentId>",
  "messages": [{ "id": "…", "role": "user", "content": [{ "type": "text", "text": "…" }] }],
  "resume": [ … ],
  "forwardedProps": { … }
}
```

**forwardedProps 可选字段**（run 级生效，下一轮重新指定）：

- `credential_id` + `model`：指定本轮使用的模型凭证与模型标识（见 §1）；
- `web_search`：联网搜索开关。未携带时默认开启（默认联网收集），携带非 `"true"` 值（前端约定传 `"off"`）即关闭；解析不抛异常，不会 fail run。服务端需配置搜索 Key（`BOCHA_API_KEY`，不进前端），`GET /api/web-search/status` 只回 `configured` 布尔。
  - 关闭时本轮不装配平台版搜索工具 `bocha_web_search`（模型工具列表里直接不可见，子智能体随主 toolkit 深拷贝一并失去）；
  - 框架自带的 `web_fetch` / Tavily 版 `web_search` 由框架无条件注册，不在开关覆盖范围（缺 Key 时返回错误文本，无害）。

响应 SSE（每行 `data: {JSON}`，兼容纯 JSON 行）。前端消费的事件：

| 事件                                     | 关键字段                                                        | 用途                                           |
| -------------------------------------- | ----------------------------------------------------------- | -------------------------------------------- |
| `RUN_STARTED` / `RUN_FINISHED`         | `outcome: {type: success\|interrupt\|error, interrupts?[]}` | 一次 run 的生命周期；interrupt 触发表单/确认               |
| `RUN_ERROR`                            | `message`                                                   | 运行错误                                         |
| `TEXT_MESSAGE_START/CONTENT/END`       | `messageId`, `delta`                                        | 回复文本增量                                       |
| `THINKING_BLOCK_START/DELTA/END`       | `delta`                                                     | 思考折叠条                                        |
| `TOOL_CALL_START/ARGS/END`             | `toolCallId`, `toolCallName`, `input`                       | 工具调用                                         |
| `TOOL_CALL_RESULT` / `TOOL_RESULT_END` | `toolCallId`, `output`, `state`                             | 工具结果；`state=RUNNING` 且是结构化表单工具时直接重建 FormCard |
| `DATA_BLOCK_*` / `CUSTOM`              | —                                                           | 数据块与自定义事件（如 `team_updated`/`state_updated`）  |


### 7.2 自定义 UI：渲染管线

前端唯一的对话渲染管线：

```
usePlatformMessages (hook)
  ├─ SSE 事件 → AgentScope Msg (content: ContentBlock[])
  ├─ transcriptSync (通知流信号 + 兜底轮询补齐后台段落)
  └─ form / pendingInterrupts / hitlStuck 状态
        ↓
ChatContent
  ├─ MessageScrollArea   ← turn 边界规则（见下）
  ├─ ASMessageBubble → ASBlock → ToolBullet（tool-summary.ts 图标/文案映射）
  ├─ ConfirmCard（权限确认 + ui_form SchemaForm，底部 FlipCard）
  └─ FormCard（ui_form_advanced/ui_gate 真实表单，多页导航）
```

**turn 边界规则**：`role==user` 开新 turn；连续 assistant 消息折叠为一个 turn（只显示一次头像/meta，meta 按整个 turn 计算）。

**表单双通道呈现**（改表单交互时两处都要看）：

1. **对话流内**：`tool_call_group` 里的描述行（`ToolBullet`）；
2. **真实表单**：`ui_form/ui_form_advanced/ui_gate/ui_card` 在气泡内统一被 `HANDLED_BY_CONFIRM_CARD` 过滤，改为渲染 FormCard（流内，位于用户当轮气泡下方）或 ConfirmCard（底部）。

**表单提交后的本地事件**：提交插入带 `metadata.formSubmission=true` 的 user Msg，渲染为「✓ 已提交：{title}」事件条而非普通气泡。

### 7.3 扩展自定义渲染的步骤

1. 新增工具渲染：在 `tool-summary.ts` 补图标/文案映射；若工具需要专属卡片，新增 tool-renderer 并挂进 `ASBlock` 分派。
2. **注意过滤口径**：确认新工具不在 `HANDLED_BY_CONFIRM_CARD` 集合内（会被气泡过滤）且不被 `hasUserFacingAssistantContent` 误过滤。
3. 若后端新增**内部注入消息**（role=user 的 seed），必须把前缀同步登记进 `transcriptSync.INTERNAL_USER_PREFIXES`（现为 `['[异步任务完成通知]', '[停滞自检]', '[HITL_RECOVERY]']`），否则会冒充用户气泡——这是平台明确契约。
4. 后端 `metadata.synthetic===true` 的消息在转录中静默渲染，不要重复渲染。

### 7.4 前端运行纪律（改 usePlatformMessages 前必读）

- 所有 run（消息/确认/表单 resume/cancel）经 `streamQueueRef` 严格串行；`epochRef` 防旧流写脏新会话。
- hooks 必须无条件执行，早退（return null）只能出现在最后——否则 React hooks 顺序错乱。
- 交付标准：`cd frontend && ./node_modules/.bin/tsc -b && ./node_modules/.bin/vite build` 通过；并核对路由→组件链路，确认页面实际渲染的是改动的组件。
- 前端运行环境：Node `^20.19.0 || >=22.12.0`（`frontend/package.json` engines；仓库用 `frontend/.nvmrc` 固定版本）。

---

## 8. 后台续跑（wakeup）与转录同步

### 8.1 wakeup 链路

```
子智能体完成 → push 父会话 inbox（轮询 3s）
  → WakeupDispatcher 唤醒空闲会话
  → AguiWakeupTarget：归属解析（必须解析到「父」智能体）+ 闸门守卫
  → drain inbox 拼成一条 user 消息注入：
     「[异步任务完成通知] 收到以下来自后台子智能体的完成事件，请据此继续推进流程：…」
  → 父会话续跑
```

- **注入语义**：wakeup 不是空输入 run，而是把 inbox 的 hint 拼成 seed UserMessage；inbox 为空则不 run。
- **兜底**：`InboxWatchdogWakeupService`（有未消费条目但会话空闲 → 补醒，冷却 120s）；停滞看护（`CONTINUE` 且父会话空闲超过 stall 阈值（默认 8 分钟）且 inbox 空 → 注入自检补醒，两次唤醒最小间隔 15 分钟；阈值可经环境变量 `AGENT_PLATFORM_STALL_MIN_MINUTES` / `AGENT_PLATFORM_WAKE_COOLDOWN_MINUTES` 覆写；`WAIT_HITL`/`DONE` 永不补醒）。
- **闸门守卫**：会话有未决闸门时不注入；「陈旧闸门」（重启遗留、无存活登记）放行一次重放让模型重新渲染。

### 8.2 前端如何看到后台段落

SSE 只推前端自己发起的 run——**wakeup 续跑产生的消息前端收不到**，必须靠「通知流信号 + 转录同步」补齐：

- **主感知路径**：订阅用户级实时通知流 `GET /api/notify/stream`（SSE）。信号只带游标 `{sessionId, runId, eventSequence, reason}`（reason ∈ run-finished/wakeup/interrupt），不含消息内容；当前会话收到信号 → 立即触发一次转录同步（数据仍由 transcript 接口拉取，内容去重合并）；
- **兜底轮询**：`syncTranscript()` 定期拉 `GET …/transcript` 增量合并，覆盖 SSE 不保交付不保序的丢失场景——通知流断开/重连中每 30 秒一次，通知流健康时降频至 2 分钟（run 进行中/有待答中断/页面不可见时跳过）；
- `restoreTranscript()`：进入已有会话一次性还原；
- `[异步任务完成通知]` 等内部 seed 在合并与渲染层均被跳过，不会冒充用户气泡。

**对接要点**：如果你的客户端不走前端 React 管线（如自建 UI），必须实现「run 之外同步转录」的逻辑——优先订阅 `GET /api/notify/stream` 拿实时信号，至少要有周期性转录拉取兜底，否则会漏掉后台续跑的段落。

---

## 9. 平台内置工具清单与权限

装配期自动注册（无需配置）：

| 类别     | 工具                                                                                                             | 说明                                |
| ------ | -------------------------------------------------------------------------------------------------------------- | --------------------------------- |
| 文件     | `read_file, write_file, edit_file, grep_files, glob_files, list_files`                                         | ROOTED 会话命名空间；`write_file` 自动建父目录 |
| 执行     | `execute`                                                                                                      | 平台版 Shell，默认 600s 超时，cwd=运行态根     |
| 记忆/会话  | `memory_save/get/search, session_search/list/history`                                                          |                                   |
| 技能     | `load_skill_through_path, read_skill`                                                                          | 配合 skills/ 目录                     |
| 子任务    | `agent_spawn, agent_send, agent_list, agent_generate, task_output, task_list, task_cancel, wait_async_results` | **父智能体才有 agent_spawn**            |
| UI 四件套 | `ui_form / ui_card / ui_form_advanced / ui_gate`                                                               | 见 §6                              |
| 多模态    | `read_image`                                                                                                   | DashScope VL 读图                   |
| 联网     | `bocha_web_search, web_search, web_fetch`                                                                    | 平台版搜索=博查实现（`BOCHA_API_KEY`），受对话联网开关 run 级控制（见 §7.1）；框架自带的 Tavily 版 `web_search` 与 `web_fetch` 由框架无条件注册，不受开关覆盖 |
| 知识库    | `knowledge_search`                                                                                             | 检索范围为会话挂载的知识库集合（见 §10）            |
| Plan   | `plan_enter / plan_write / plan_exit`                                                                          | AgentConfig.planEnabled 开启        |

**权限模型**：UI 工具与 `agent_spawn` 平台策略永久放行；文件系统受 PathPolicy 限制（运行根内读写 + 定义区只读）。DB 工具市场（`/api/tools`）注册的工具经 `PlatformTool` 包装：`internal+askConfirmation` 触发人工确认中断；`external` 挂起等 resume 回传。

---

## 10. 知识库接入

1. **建库**：`POST /api/knowledge-bases`，`type ∈ {rag, external, text}`（rag=本地 pgvector 向量检索，向量维度固定 1536；external=外部 RAG 平台 RAGFlow/Dify/百炼；text=文本 grep 检索）。
2. **挂到会话**：`PUT /api/agents/{agentId}/sessions/{sessionId}/knowledge`（body `{"knowledgeBaseIds":[…]}`，覆盖式）。
3. **使用**：挂库后 `SessionKnowledgeContextMiddleware` 自动把库清单注入 system prompt，`knowledge_search` 工具自动可用（`query` + `top_k≤10`，运行期按库做可见性校验防越权）。

---

## 11. 常见坑与硬契约汇总

| #  | 坑/契约                                           | 后果（若违反）                                                              |
| -- | ---------------------------------------------- | -------------------------------------------------------------------- |
| 1  | resume 必须覆盖全部 open interrupts；一次只允许一张表单在途      | `Thread has unresolved interrupts…` 拒绝 run                           |
| 2  | external_execution 中断只传 `output` 不传 `approved` | 框架按权限确认处理，output 被忽略                                                 |
| 3  | 后端重启 → form-interrupts 丢失                      | resume 报 "does not match any open interrupt"，需降级为 user-message 新 run |
| 4  | 不支持 `${WORKSPACE_ROOT}/${SESSION_ID}` 占位符       | 子智能体报参数校验失败；请改用 §5 路径契约                                              |
| 5  | 子智能体 `steps` 框架默认仅 10                          | 复杂任务中途截断；显式 `steps: 50`                                              |
| 6  | 表单提交文案前缀是前后端契约                                 | 改动会破坏 `HitlRecoveryGuardMiddleware` 识别，导致闸门假放行                       |
| 7  | 后端新增内部 seed 消息前缀须登记 `INTERNAL_USER_PREFIXES`   | 内部消息冒充用户气泡                                                           |
| 8  | `[进度]` 行字段取值是机器契约                              | 停滞看护/nextAction 解析失效                                                 |
| 9  | 同一 thread 的 run 严格串行                           | 并发 run 被拒绝（"Thread already has an active run"）                       |
| 10 | wakeup 段落 SSE 推不到                              | 自建 UI 须订阅 `GET /api/notify/stream` 或定期拉转录，否则漏消息                     |
| 11 | 知识库路径是 `/api/knowledge-bases`（kebab-case）      | `/api/knowledge_bases` 404                                           |
| 12 | 非本人会话一律 404                                    | 权限探测无意义；调试时注意归属用户                                                    |

---

## 12. 接入 Checklist

**定义智能体**

- [ ] 确定定义通道（DB 型 / 文件型）；文件型在 `workspace/agents/<名>/` 建 AGENTS.md
- [ ] AGENTS.md 包含：硬约束、路径约定、子智能体注册表、状态机、表单规范、输出协议
- [ ] 进度行格式与平台解析口径一致（§3.2 #5）

**子智能体**

- [ ] frontmatter：`description` 必填、`steps: 50`、`workspace.mode: shared`、`tools` 白名单（不含 agent_spawn）
- [ ] 正文约定【TASK_RESULT】契约 + 路径规范（artifacts 逐文件登记）
- [ ] 可用 `POST /api/subagents/validate` 预校验

**HITL**

- [ ] 审批点全部走 `ui_gate`；信息收集走 `ui_form_advanced`；遵守单表单原则
- [ ] 未在 `ui_card` 入参里出现审批语义词（呈审批形状会被平台拒绝）

**路径与产物**

- [ ] 产物相对路径写入；跨会话输入绝对路径（来自产出方 artifacts + Workspace 段的运行态根）
- [ ] 未使用 `${WORKSPACE_ROOT}` / `${SESSION_ID}` 占位符

**UI 对接**

- [ ] 复用内置管线（usePlatformMessages + ChatContent）或自建 UI 实现「事件消费 + 通知流/转录同步」
- [ ] 处理 `RUN_FINISHED(interrupt)` → 表单/确认 → resume 全流程（含降级路径）
- [ ] forwardedProps 按需携带 `credential_id`/`model` 与 `web_search`（关闭才传 `"off"`，缺省开启）
- [ ] 新增自定义渲染核对过滤口径与 `INTERNAL_USER_PREFIXES`

**验证**

- [ ] `tsc -b && vite build` 通过（前端）；后端 `mvn -o compile` 通过
- [ ] 真实会话跑通：发起 → 表单 → 闸门 → 子任务 → wakeup 续跑 → 转录补齐 → 产物面板
