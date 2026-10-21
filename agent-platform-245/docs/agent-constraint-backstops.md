# 智能体硬约束的兜底映射

给维护者看的一张表：`workspace/agents/{agentId}/AGENTS.md` 的每条硬约束，**谁在代码里兜底**。

用途：判断某条约束「该靠 prompt，还是该由代码兜」。仅靠 prompt 表达的约束在长流程里
可靠性有限；会被模型反复偏离的约束，最终都应下沉为代码兜底。

判据：
- **机器消费者**：有平台代码解析该协议 → 措辞/字段是契约，**改文档前先改代码**。
- **平台已强制**：平台/schema/权限规则已经拦住了 → prompt 里再写只是强调，不承载正确性。
- **仅模型行为**：无兜底，只能靠 prompt 与看护兜。

## 一、现行 16 条

| # | 约束 | 机器消费者 | 平台兜底位置 | 类型 |
|---|---|---|---|---|
| 1 | 阶段顺序锁 | — | 无 | 仅模型行为 |
| 2 | 闸门渲染时机与单表单原则 | — | 闸门字段由工具 schema 锁定 `UiTools.java:229`（`const` + `prefixItems`，单 page、`key=gate`）；`ui_card` 当审批会被拒 `UiTools.java:76` | 协议契约（平台只锁字段，不锁时机） |
| 3 | 内审先行 | — | 无 | 仅模型行为 |
| 4 | 重试上限 | — | 无 | 仅模型行为 |
| 5 | 隔离与调用边界 | — | 子智能体工具集无 `agent_spawn`（`subagents/*.md` 的 `tools:` 白名单）→ 物理上无法互调 | 平台已强制 |
| 6 | REJECT 识别 | — | 无（`[REJECT]` 由子智能体自报，父模型读） | 协议契约（无解析器） |
| 7 | 输入路径注入 | — | 无兜底：框架把运行态根写进每个会话的 system prompt（`## Workspace → Project (…)`），父编排据此拼绝对路径；平台不再做任何占位符展开 | 协议契约（无解析器） |
| 8 | 子任务等待以状态为准 | — | 框架长工具后台化 + 平台自动唤醒（`AguiWakeupTarget`、`InboxWatchdogWakeupService`）；`task_cancel` 属框架内部工具（实际放行），AGENTS.md 以提示词约束不主动调用 | 平台已强制（判据部分） |
| 9 | 渐进式返回必须续跑 | **是** — `[进度]` 行由 `SessionProgressService.java:64` 解析、`:156` 按 `GATE_WAIT`/「闸门」/STALL 词分类，L3 看护据此补醒（`WAIT_HITL` 永不补醒） | 无输入兜底：模型不续跑时只能靠 L3 补醒（停滞 8 分钟 / 唤醒间隔 15 分钟，环境变量可覆写） | **协议契约 + 仅模型行为** |
| 10 | 结果与产出登记规范化 | **是** — `SubagentReportParser` 解析 `【TASK_RESULT】`（状态/清单/`artifacts`），`SubagentReportService` 核对每个产出文件是否真实存在并暴露到 `GET …/subagents` | `SubagentReportService` 只读展示、不阻断主流程 | **协议契约 + 平台只读核对** |
| 11 | 阶段化强制规划（Plan） | — | 框架 plan 模式：计划模式下写产物/可变 exec 被拦（`plan_enter`→`plan_write`→`plan_exit`）；但「每阶段都要规划」是模型自觉 | 平台部分强制 |
| 12 | 重启恢复（闸门未决不得放行） | — | `HitlRecoveryGuardMiddleware` 在闸门未决时自动注入同义恢复指示；陈旧闸门放行见 `AguiWakeupTarget#countLiveFormInterrupts` | 平台已强制 |
| 13 | 产出统一写入 `artifacts/` 目录 | — | `AgentAssemblyService.artifactsWriteContract()` 随 sysPrompt 注入所有 Agent 与平台声明的子智能体；目录名取 `agent-platform.artifacts-dir` 配置（可改，文件型智能体定义里硬编码的目录名需同步改） | 平台注入提示（仅模型行为） |
| 14 | 批量产物逐文件派发（防单轮超限整批作废） | — | 无：框架单轮迭代上限只兜「跑不完」，不拦 task 粒度；拆分粒度完全靠父编排模型自觉 | 仅模型行为 |
| 15 | 跨会话内容生成必须把规范要点写进任务书 | — | 无：新子会话读不到旧会话 `private/context-notes.md` 是会话空间隔离的必然（平台不跨会话共享私有目录）；注入义务纯靠父编排模型自觉 | 仅模型行为 |
| 16 | 批量脚本修改产物必须先备份并做 hash 校验，失败即回滚 | — | 无：脚本批量替换的静默损坏（行折叠/丢行）不改章节结构，粗看难察觉；备份/改前后 hash 比对/失败恢复纯靠子智能体执行任务书自觉 | 仅模型行为 |

**改文档前必须先动代码的一条**：第 9 条的 `[进度]` 字段名与 `状态` 取值
（`执行中`/`部分完成`/`等待人工`/`已完成`/`已阻塞`）。`SessionProgressService`
按这些字面值分派 `CONTINUE`/`WAIT_HITL`/`DONE`/`IDLE`，措辞一变看护就失效，
且失效是静默的（只会表现成「该补醒时没补醒」）。

## 二、目前没有兜底、值得考虑下沉到代码的项

按「事故复现概率 × 靠 prompt 的可靠性」排序：

1. **意向表单 schema**（现「表单规范 §1」）：目前只能靠 prompt 驱动，因为该 agent 的
   `welcomeForm` / `formTrigger` 均未配置（`AgentConfig` 的对应列）。
   迁到平台配置可让模型不必背诵 24 行 JSON，也消除字段漂移。
2. **闸门时机**（新 2）：平台只锁字段、不锁「何时该渲染」。可用状态机 + 产物齐备判定
   做一次服务端校验（例如章节产物齐备而未发闸门时注入提醒），比第 13 条 prompt 可靠。
3. **交付完整性**（新 9 末条）：平台已有 `SessionProgressService` 的产物/停滞检查能力，
   可扩展成「宣告完成但 `目录结构.json` 对应产物缺失 → 拒绝收尾」。
4. **内审先行**（新 3）：纯流程规则，若要强制需引入「审核智能体调用记录」作为前置条件。

## 三、路径契约：产物写各自会话空间

**契约（纯会话隔离）**（权威定义：
`workspace/agents/教材开发小助手/skills/jc-0-路径工具/SKILL.md`）：

| 类别 | 写法 | 谁读得到 |
|---|---|---|
| 输入 | 父编排在 task 注入的**绝对路径**（原样使用） | 读方自己 |
| **全部产物**（含需被下游读取的） | **相对路径**（落各自的会话空间）+ `【TASK_RESULT】artifacts` 登记（运行态根相对路径） | 父/下游按登记路径读；主编在产物面板看 |

**平台侧配套**：
- 产物面板聚合「本会话 + 其全部子会话」的产物（`SessionArtifactController` 合并子树，
  `SessionLineageService` 按**框架 spawn registry**（父会话状态里的
  `tool_context.spawn_registry`）判定归属；非本会话的子目录 404）；面板同时隐藏运行态内部目录
  （`agents/default/memory/plan/MEMORY.md`、dotfile）。
- 子任务汇报视图（`GET …/subagents`）：把「派发了谁 + 子会话最后的 `【TASK_RESULT】` +
  产出是否存在」合成一份列表（产物面板不展示该节，接口保留）。

**会话清理按框架血缘**：`SessionWorkspaceManager.deleteSessionDir`
按 `SessionLineageService`（框架 spawn registry）取子会话列表后逐个删除，
不读任何文件内容（`SubagentSessionMaintenance` 的 7 天保留期仍作为最后兜底）。

## 四、文件系统后端与汇报协议

平台使用官方框架版本；**平台侧自己把文件系统后端交给 harness**
（`builder.abstractFilesystem(...)`）：框架只在子智能体 `WorkspaceMode.SHARED`
且父级存在后端时才传递后端，而平台子智能体正是 SHARED——这是框架为本地文件系统
留的官方入口。

**当前形态**：
- 定义文件里不存在 `${WORKSPACE_ROOT}` / `${SESSION_ID}` 占位符，平台不做占位符展开、
  也不注入会话标记；
- 会话清理按血缘查库（见 §三），不做文件内容扫描；
- 子智能体汇报协议统一为 `【TASK_RESULT】` 的 `artifacts` 登记。

**为什么跨会话读取不需要占位符**：运行态根的绝对路径由框架写进**每个**会话的
system prompt（`## Workspace → Project (…)`），产出方会话目录由产出方自己在 `artifacts` 里登记，
父编排只做原样转发。

## 五、框架运行行为备忘

给维护者的框架行为速记：哪些是**框架默认且平台已调优/接受**的，不再重复排查。

### 已调优的配置（AgentAssemblyService）

| 配置 | 框架默认 | 平台现值 | 理由 |
|---|---|---|---|
| Compaction 触发（`COMPACTION_TRIGGER_MESSAGES`） | 50 条消息自动摘要 | 150 | 教材一轮 30~60 轮，默认值会在流程中途缩掉 `【TASK_RESULT】`/artifacts 登记；动态 token 阈值仍兜底防上下文溢出 |
| Compaction 保留条数（`COMPACTION_KEEP_MESSAGES`） | 20 | 40 | 摘要保真度与保留轮次成正比 |
| Compaction 摘要提示词（`COMPACTION_SUMMARY_PROMPT`） | 框架通用 | 要求逐字保留契约字段 | 摘要必须保全 status/completed/artifacts 路径/闸门结论/agent_key |
| 大工具结果转存（`TOOL_RESULT_EVICTION_MAX_CHARS`） | 80K | 200K + 排除 spawn/task/枚举类工具 | 子智能体汇报/任务结果被搬走后模型只见 2K 预览且不知道去读转存文件；`execute` 故意不排除（输出可达 MB） |
| execute 默认超时（`SHELL_EXECUTE_TIMEOUT_SECONDS`） | 框架 ShellExecuteTool **写死 30 秒**（模型不传 timeout 即 30s 杀命令） | 平台自注册 `PlatformShellExecuteTool` 替代，默认 600s，显式 timeout 优先 | 模型可能同轮并行多次 execute，长任务需要充足默认超时（60s 后台化 → 完成 → wakeup 闭环依赖命令跑完） |
| spawn 同步超时引导 | 默认 30s | 定义层 AGENTS.md 已引导 `timeout_seconds=300`；DB 型 agent 平台注入同款引导 | 30s 默认会把长任务提前转入后台、编排轮次膨胀 |

### 平台接受/依赖的框架行为（不必再排查）

- **工具默认并行**：`ToolkitConfig` 默认 `parallel=true`，同一工具是否并发自身取决于
  `isConcurrencySafe`（`ToolExecutor`）。**同轮并行写同一文件 = 竞态**——AGENTS.md 应约束「同一轮
  只写一个文件、不同产物写不同文件」，平台不强制。
- **状态冲突策略默认 OVERWRITE**（last-writer-wins，`ReActAgent.persistAgentStateCas`）：
  单实例安全；若未来多节点并发调同一会话，需切 `FAIL` 或 `APPEND_MERGE`。
- **孤儿任务回收**：后台子任务 10 分钟无心跳 → FAILED（`WorkspaceTaskRepository`），失败结果会
  作为 FAILED delivery 推给父会话——重启后父会话看到错误属预期，不是丢任务。
- **wakeup 延迟**：单进程 `WorkspaceMessageBus` 的订阅是 3 秒轮询，wakeup 最多延迟 3 秒，正常现象。
- **重启重发去重**：`shutdownManager` 会丢弃重启后重投的同一条用户消息，不是 bug。
- **子智能体配置继承**：声明的 subagent 自动继承父的 compaction/eviction/execution 配置
  （`HarnessAgentBuilderSupport` 捕获传播），改父一处即全局生效。

### 框架缺陷的平台侧规避

- **Overlay 下层对越权路径抛异常**：框架 `OverlayFilesystem` 的 ls/read 合并上下层时会对两层
  同时求值，而下层 `LocalFilesystem` 对「不在自己 allowed roots 内的绝对路径」**抛
  SecurityException**（`LocalFilesystem.java:675`）而非返回 not-found，该异常直接冒泡成文件
  工具失败。平台文件系统是 upper=runtimes/{agentId} + lower=定义区只读的 Overlay，父编排
  按契约读子会话产物（runtimes/{id}/sub-*/… 绝对路径，上层合法）会命中此问题：报错形如
  "not within an allowed root. Filesystem root: workspace/agents/…"。
  **规避**：`RuntimeLocalFilesystemSpec.toFilesystem` 把 runtimeRoot 一并加入 lower 的
  PathPolicy 允许根（`PathPolicy.of(List.of(definitionDir, runtimeRoot))`）——下层对运行态
  路径静默返回不存在、由上层命中，契约恢复；定义区仍受 ROOTED 约束（只允许两个根）。
- **意向表单可能在会话中再次弹出（前端渲染 artifact，非后端重复）**：agent 消息流里的
  表单卡片组件在会话重连/新消息推送触发列表重渲染时，内部“已提交”状态丢失、重置为第一页
  （刷新页面即消失，formGone）。后端 transcript 无第二次表单提交（唯一提交在会话首轮），
  不产生重复数据；风险仅是用户误以为要再填一次。修复方向在前端表单卡片组件（按消息状态
  恢复已提交视图），尚未实施。

### 待办：HITL 残留的两个看护盲区

1. **后台 wakeup run 撞上 ASKING 残留 → 前端不可见**：权限 ASK 挂起会持久化 ASKING
   tool_call 到会话状态；重启后 wakeup 投递任务丢失通知时，恢复 run 因
   "no confirmation supplied" 抛 IllegalStateException（ReActAgent.doCallInner 优先于
   pendingToolRecovery），唤醒失败且**错误只在服务端日志**。前端"恢复会话"按钮仅在
   前端自己捕获到 HITL 错误时显示（staleHitlError 匹配 chat.error.message），后台
   失败对前端不可见 → 用户无入口。改进：wakeup run    失败且原因为 HITL 残留时，经
   session 事件/progress 标记 needsRecovery，通知流/转录同步读到即显示恢复按钮。
2. **HitlRecoveryGuardMiddleware 未覆盖 ASKING 残留**：日志 pendingHitl=false 放行后
   撞框架异常。应在唤醒路径检测到 ASKING 残留时主动合成取消型 ConfirmResult 并注入
   恢复指示（服务端自愈），而非依赖前端应答。临时解法：POST /sessions/{id}/recover
   （平台已有端点，清除 ASKING/interrupt 残留，保留会话与产物）。

### 重复 spawn 模式（已有提示词约束）

**风险**：父会话收到主编的「继续 / 已就绪 / 重试」类续跑消息时，若不先 `task_list`
查重就直接重新 `agent_spawn` 资料准备智能体，会产生多个并行任务——多个子智能体
共享同一子会话空间并发写文件、互相污染上下文。

**现行约束**：AGENTS.md 硬约束 8 含子项「spawn 前必须 task_list 查重，同目标
RUNNING 禁止再调度」；`task_cancel` 属框架内部工具、实际放行（清理重复任务时可用），
AGENTS.md 仅以提示词约束不主动调用。

**平台层可下沉的兜底**（未做）：AgentSpawnTool 调用时查 TaskRepository 同
session 同 subAgentId 的 RUNNING 任务即拒绝并返回既有 task_id——比提示词可靠，
若提示词约束再失效可按此实现。
