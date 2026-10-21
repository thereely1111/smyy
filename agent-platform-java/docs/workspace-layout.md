# 工作区布局规范

平台工作区（`agent-platform.workspace-root`，默认 `${user.dir}/workspace`）规划如下：

```
workspace/
├── agents/{agentId}/              # 开发态定义区：AGENTS.md、skills/、subagents/（框架加载）
├── runtimes/{agentId}/{sessionId}/# 运行态产物区：会话产物（平台创建/回收）
├── knowledge_bases/{kbId}/{docId}/# 知识库文档（共享只读资产）
└── templates/                     # 模板（预留）
```

## 职责边界

- **定义区 `agents/{agentId}/`**：只承载定义文件（AGENTS.md、skills/、subagents/、plan/）。
  - 保存配置（`WorkspaceGenerator.generate`）只重建这些已知文件，不触碰其他内容；
  - 智能体文件工具（execute / read_file / write_file）的根为运行态区 `runtimes/{agentId}`，
    定义区不在 PathPolicy 允许根内，模型产物物理上无法写入定义区。
- **运行区 `runtimes/{agentId}/{sessionId}/`**：用户会话产物，统一写入其下的
  `artifacts/` 目录（目录名由 `agent-platform.artifacts-dir` 配置，可调整；
  平台装配期把「产物写入契约」注入所有 Agent 与平台声明的子智能体的 sysPrompt）。
  - 平台在每次 run（AG-UI 或平台 chat）前幂等创建该目录（`SessionWorkspaceManager.ensureSessionDir`）；
  - 框架把每个会话的 `RuntimeContext.sessionId` 作为命名空间追加到可写根之后，因此**相对路径
    天然落在自己的会话目录**；`agent_spawn` 会给每个子智能体一个新的 `sub-<uuid>` 会话，
    于是子智能体产物落在 `runtimes/{agentId}/sub-<uuid>/...`；
  - execute 的 cwd 即运行态根（`runtimes/{agentId}`），提示词里**没有任何占位符约定**；
    shell 环境只注入 `PATH`（不透传后端进程的环境变量）；
  - 清理会话（`POST /{id}/sessions/{sessionId}/clear`）联动删除状态与目录；
  - 删除智能体联动删除整个 `runtimes/{agentId}`；
  - 兜底回收：`agent-platform.runtime-gc-enabled=true` 开启后按目录 mtime 清理超期会话目录
    （`RuntimeWorkspaceGc` 按两层 `runtimes/{agentId}/{sessionId}` 扫描）。
- **旧会话归档**：更早期跑偏产物（仓库根 / 定义区里的 `wb-*`、`sub-*`）如需保留，
  迁移到 `runtimes/{agentId}/legacy/` 只读归档、不参与新会话；
  迁移方法见 [scripts/migrate-legacy-session-dirs.sh](../scripts/migrate-legacy-session-dirs.sh)。

## 文件工具与路径约定（装配期语义）

`AgentAssemblyService.buildRuntimeFilesystemSpec` 构造运行态文件系统
（`LocalFilesystemWithShell(rootDir=runtimes/{agentId}, mode=ROOTED, …)` + 定义区只读下层），
并以 `builder.abstractFilesystem(...)` 交给 harness：官方 harness 只把**后端**传给
`WorkspaceMode.SHARED` 的子智能体（`LocalFilesystemSpec` 只在 ISOLATED 分支被克隆，且会丢掉
SESSION 命名空间与只读定义层），因此平台自行构造后端是让父与子共享同一根、
各自会话命名的唯一可靠方式：

- **相对路径**：先拼会话命名空间前缀再锚定 `runtimes/{agentId}`，
  即相对路径落在 `runtimes/{agentId}/{sessionId}/...`（自身会话目录）。
  - 父编排智能体：直接写 `artifacts/...` 即落到自己会话目录的产物目录，**不要**再拼任何
    会话 ID（否则产生 `runtimes/{agentId}/{sessionId}/{sessionId}/...` 双层嵌套）；
  - 子智能体：`agent_spawn` 会给子进程独立的 sessionId（`sub-<uuid>`），
    其相对路径落在 `runtimes/{agentId}/sub-<uuid>/...`（自身隔离区）。
- **绝对路径**：ROOTED 模式下运行根内的绝对路径原样通过（运行根 = `runtimes/{agentId}`，
  因此可读写的绝对路径范围覆盖该智能体下的**所有**会话目录，含兄弟 `sub-*`）。
  - **契约 = 纯会话隔离**：**所有产物都写在各自的会话空间**（相对路径），
    且统一落会话目录下的 `artifacts/`（目录名取 `agent-platform.artifacts-dir` 配置）；
    会话之间**不共享目录**，跨会话只交接**路径**：
  - **输入读取**：一律用**绝对路径** = 运行态根 + 产出方会话目录 + 相对路径。
    运行态根的绝对路径由框架写进每个会话的 system prompt（`## Workspace` → `Project (…)`
    行）；产出方会话目录来自产出方 `【TASK_RESULT】artifacts`（`<会话目录>/<相对路径>`）；
  - **汇报**：子智能体在 return 末尾输出唯一 `【TASK_RESULT】` 块，`artifacts` 逐文件登记
    本次全部产出（运行态根相对路径）；父编排原样转发，不猜不扫；
  - **主编可见性**：产物树以 `artifacts` 为根，合并「本会话 + 其全部子会话」
    各自 `artifacts/` 的内容（同名目录 union；同名文件并存、子会话文件展示名带会话短标，
    `path` 仍是可回取的定位路径）；artifacts 之外的内容不展示
    （`SessionArtifactController` + `SessionLineageService`：血缘取自**框架 spawn
    registry**，即父会话状态里的 `tool_context.spawn_registry`；非本会话的子目录一律 404）；
  - **子任务汇报视图**：`GET /api/agents/{agentId}/sessions/{sessionId}/subagents`
    返回每个子智能体的状态/完成项/产出文件，并**核对其产物是否真实存在**
    （`SubagentReportService`）；产物面板**不展示**该节，接口保留；
  - 路径规范唯一权威定义见 `workspace/agents/教材开发小助手/skills/jc-0-路径工具/SKILL.md`。
- **定义区**：agents/{agentId} 不在允许根内，文件工具读不到也改不到定义文件；
  AGENTS.md / skills / subagents 由 Harness workspace 上下文、技能仓库与本地回退注入。

## 用户级隔离

会话状态（Postgres）与产物目录均按 `(userId, sessionId)` 分区；
产物目录为两层（不带 userId），用户归属由 `agent_session_owner` 表承载
（见 `SessionWorkspaceManager` 类注释）。userId 取自请求 JWT payload
（兜底 `X-User-ID` 头），不再使用按 Agent 共享的 `web:{agentId}` 命名空间。
