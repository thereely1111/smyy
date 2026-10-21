# 冗余 / 废代码排查报告（2026-09-21）

- **基线**：`e430a53`（develop，前端菜单细粒度权限码）。工作区有少量未提交改动（`ImageTools.java` 已删除、`AgentAssemblyService` / `application.yml` / README 等），不影响本轮结论。
- **规模**：后端 199 个 Java 文件 ≈ 28,800 行；前端 245 个 TS/TSX ≈ 42,300 行。
- **方式**：8 路并行子审查（后端按包切 6 组：service×3、harness、web、domain+repository；前端 2 组）+ knip 全量静态检测（未用文件/导出/依赖）+ 主审回源核对。全部结论均经全仓库 grep 复核（含 `backend/src/test`、`frontend/src`、`workspace/agents`、`application.yml`）。
- **判定口径**：web 层死端点 = 前端零调用 + 后端零内部调用；前端 `components/ui`（shadcn 组件库）的未用文件/导出与未用 UI 子导出**不计入**问题，仅附录参考。

## 总览

| 分级 | 数量 | 说明 |
|---|---|---|
| A 死功能整链（可直接删） | 5 组 | 前端无任何调用、后端链路自闭环 |
| B 死方法 / 死文件 / 死依赖（可直接删） | 后端 10 项 + 前端 34 文件 / 6 依赖 | 已逐一核为零引用 |
| C 冗余 / 重复实现（建议收敛） | 6 项 | 需小幅重构，不是纯删除 |
| D 观察项 / 待产品决策 | 4 项 | 不急，删前需确认 |
| 误报剔除 | 3 组 | 子审查上报、经回源核对不成立 |

---

## A. 死功能整链（前端零调用，整链可删）

均经 `frontend/src` 全量 grep 零命中 + 后端唯一引用链确认。

### A1. 分组管理（groups）
- 端点：`GroupController.java:26`（`/api/groups` 全部 6 个端点）
- 服务：`GroupService.java`（185 行）——仅被死控制器注入；**例外**：`summariesForGranting` 被 `KnowledgeBaseService.java:522` 用于知识库授权，`GroupDtos.GroupSummary` 也被 `ListGrantsResponse` 复用 → 保留授权路径，其余删。
- DTO：`GroupDtos.java` 的 `CreateGroupRequest/AddMemberRequest/GroupView/GroupMemberView/ListGroupsResponse`（:16/:21/:25/:30/:45）。

### A2. 会话扩展（extensions）
- 端点：`SessionExtensionController.java:42`（6 个端点）
- 服务：`SessionExtensionService` 除 `getOrNull/putEntry/upsertEntries/searchByEntries` 等仍被 `SessionKnowledgeController/SessionKnowledgeService` 使用的之外，`get()`（:50）与 `deleteByAgent()`（:164）零调用（`deleteByAgent` 仅自测引用）。
- DTO：`SessionExtensionDtos` 的 4 个对外 record（:20/:28/:41/:48）。
- ⚠️ `getOrNull` 链是活的（知识库会话引用功能在用），勿整类删。

### A3. 子任务汇报（subagents）
- 端点：`SessionSubagentController.java:30`（`GET .../subagents`）。记忆口径一致：前端「子任务汇报」节已隐藏，接口保留至今无人调。
- 连带：`SubagentReportService`（177 行）与 `SubagentReportParser` 的 `privates` 解析链（`SubagentReportParser.java:61/104/120/186` 收集后无任何消费）、`RuntimeDtos.SubagentReport/SubagentArtifact`（:71/:95）、`PostgresSubagentStateReader`（仅被 SubagentReportService 注入）。
- ⚠️ `docs/workspace-layout.md:66` 仍记载该端点为「子任务汇报视图」——文档需同步更新。

### A4. 事件查询（agui-events）
- 端点：`AguiEventController.java:33`（`GET /api/agui-events`）。注意：**落库链（AguiEventPersistenceService 异步批量写）是活的，勿动**；仅查询端点无消费方。

### A5. 遗留 chat DTO
- `AgentDtos.ChatRequest/ChatResponse`（`AgentDtos.java:133/136`）：chat 已迁 AG-UI，全仓零引用。

---

## B. 死方法 / 死文件 / 死依赖（已核零引用，可直接删）

### B1. 后端 repository 方法（生产零调用）
| 位置 | 方法 | 备注 |
|---|---|---|
| `UserRepository.java:11` | `findByUsername` | 登录链走 `findByOauthUserIdAndOauthProvider`/`existsByUsername` |
| `PlatformNotificationRepository.java:31` | `findByUserIdAndId` | 功能被 `markRead`(:72) 覆盖 |
| `PlatformNotificationRepository.java:89` | `deleteOlderThan` | 无任何 @Scheduled 清理任务接线 |
| `PlatformNotificationRepository.java:38` | `existsByUserIdAndAlertKeyAndReadAtIsNull` | 重构绕开残留；仅 `AlertNotifierTest` mock（测试该删） |
| `TagRepository.java:20` | `findByNameInIgnoreCase` | 实际用单名版 |

### B2. 后端 service 方法
- `SessionOwnerService.isOwner`(:80)、`sessionsOf`(:108)、`deleteByAgent`(:177)——后两个连带注释「删除智能体时连带清理归属记录」是**失效承诺**：`AgentConfigService.delete`(:220-239) 只清目录与配置，从不清 `agent_session_owner` 表。
- `KnowledgeBaseService.resolveStorageDir`(:469)——全仓仅 Javadoc 提及，检索链直接走 `storageService.kbDir`。
- `SessionAgentRegistry.registerAll`(:86)、`agentFor`(:52)——仅测试引用；`InboxWatchdogWakeupService.java:36` 注释明确「不用 agentFor()」。
- `HitlRecoveryGuardMiddleware.hasPendingHitl` 三参重载(:192)——仅测试引用。
- `RunSilenceDetector.recoverThreshold()`(:203)——「供测试/运维」但零调用。
- `AguiEventPersistenceService.pendingCount()`(:132)——「运维观测用」但无任何端点消费（可留着接 Actuator，也可删）。

### B3. 后端 config
- `FileSystemAgentLoader.buildConfigFromDir`(:189)——private 零调用，与 `loadAll()` 内联构造(:100-124)重复，且**漏了 `setFileBased(true)`**，真被调反而有 bug。删。
- `AguiIntegrationConfiguration.onApplicationReady`(:94)——纯启动日志监听器，无副作用（低价值，可留）。

### B4. 前端文件（knip 检出 34 个未用文件，剔误报后如下）
**整目录删（旧 Dock 面板整代遗留，6 文件）**：`components/panel/{PanelDock,McpPanel,SkillPanel,TaskPanel,TeamPanel,PermissionPanel}.tsx`
**对话框 7 个**：`AgentDialog`、`EditAgentDialog`、`PublishAgentDialog`、`RenameSessionDialog`、`VersionHistoryDialog`、`AddMCPDialog`（含 `MCPConfigForm`）、`AddSkillDialog`
**hooks 6 个**：`useAgents`、`useWorkspace`、`useAgentSchema`、`useSkills`、`useSkillHubs`、`useSkillHubCards`、`useAvailableTTSModels`
**其他 5 个**：`pages/form-builder.tsx`（demo 页，无路由）、`pages/profile/index.tsx`（无路由无引用）、`components/auth/RoleGuard.tsx`、`lib/next-navigation-shim.ts`、`components/badge/InputTypeBadges.tsx`、`components/popover/ModelParametersPopover.tsx`、`components/select/DimensionSelect.tsx`、`components/form/AgentFormFields.tsx`、`utils/platform.ts`（≈17 文件，约 4600 行）
**shadcn ui 未接入（组件库，删留均可）**：`sonner.tsx`、`button-group.tsx`、`toggle.tsx`、`progress.tsx`

### B5. 前端依赖（package.json，零 import）
`@xyflow/react`、`lucide`（裸包；实际用的是 `lucide-react`）、`onborda`、`shadcn-prose`、`@fontsource-variable/geist`、`next-themes`（仅被死的 `ui/sonner.tsx` 引用，随其删除）。

### B6. 前端 i18n 死命名空间
`tour`（16 key，含 next-themes 关联的引导tour功能残留）、`agent-menu`（2 key）。其余 264 个「未静态引用」key 绝大多数为动态模板键（`` t(`channel.fields.${key}...`) `` 等），已核实非死，勿批量清理。

---

## C. 冗余 / 重复实现（建议收敛，非纯删除）

1. **HITL 工具名单三处复制**：`SessionProgressService:227`(ANSWERABLE_HITL_TOOLS)、`:553`(HITL_GATE_TOOLS)、`HitlRecoveryGuardMiddleware.HITL_TOOLS`——注释自己承认「跨包复制」。改名单漏一处就是行为分叉。
2. **时间戳解析四份近拷贝**：`SessionProgressService.parseTs`(:727) 与 `AgentRuntimeService` 的 `parseEpochMillis`(:517)/`parseTimestamp`(:1074)/`normalizeTranscriptTimestamp`(:1057)——同一「Instant 失败→空格换 T→LocalDateTime」逻辑，抽 util。
3. **反射取字段逐字重复**：`ResumeCoordinatorDeadlockCleaner.java:161` 与 `WakeupInterruptRegistrar.java:359` 的 `readFieldByName` + 反射链（后者注释已自认同实现）。
4. **「平台留痕」超时文案双份**：`StartupHitlReconciler:311` 与 `HitlTimeoutReaper:105` 拼接逻辑一致，回退口径微差——差异本身可能是 bug。
5. **前端 ChatContent workdir 死分支**：唯一调用方 `PlatformReferenceChat:286-290` 恒传 `cwd=null,git=null,showWorkdir=false` → `ChatContent.tsx:429` 的 `showWorkdir||git` 分支不可达，连带 `WorkingDirectoryDialog`、`GitBranch`/`DiffStats` 局部引用、5 个 workdir props 全部死参数（约 80 行）。删分支+props，`_shared.tsx` 的 `DiffStats` 是活的勿动。
6. **知识库检索三实现是并列不是重复（误报剔除，见 E1）**，但 `KnowledgeChunker` 标了 `@Component` 却由 `KnowledgeIngestionService:79` `new` 出来——注解冗余，删注解即可。

---

## D. 观察项 / 待决策

1. `ResourceStatus.inactive`（`ResourceStatus.java:3-6`）：全仓零引用、无停用逻辑——是预留状态还是该删，问产品。
2. `StudioProperties` reconnect 三字段(:39-46)：传入 Studio SDK builder，是否真消费需查 `agentscope-extensions-studio` 依赖源码（未证实）。
3. `scheduleApi` 五端点全 404（已知遗留）：**不是死代码**（页面/路由/hooks 都活着），是缺后端实现，不在本轮清理范围。
4. `HomeController`（`GET /`）：前端不调，但属探针型 info 端点，建议保留。
5. `JpaSkillRepository` 的 `setWriteable/isWriteable/getRepositoryInfo`：AgentScope 框架接口契约方法，平台侧无调用但框架运行期可能回调——**勿删**（未证实为死）。

---

## E. 误报剔除（子审查上报、回源核对不成立）

1. **「KnowledgeRetrievalService / KnowledgeTextRetrievalService / ExternalRagClient / KnowledgeSearchTool 新旧叠层」**——不成立。三者是 rag/text/external 三种 KB 类型的并列实现，由 `KnowledgeBaseService.search`(:456/458/460) 按 `kb.getType()` 分发，均活。
2. **「AguiWakeupTarget:136 / InboxWatchdogWakeupService:152 注释称 registry 恒空 → SessionAgentRegistry 死」**——注释已过时失真：`SessionActivityMiddleware.java:54` 每轮都在 `registry.register(...)`。**该删的是注释，不是代码**（注释现会诱导误删回填逻辑）。
3. **「非 /api 前缀端点前端够不着 → 一堆端点死」**——不成立：`platform.ts:401` `BASE='/api'` 统一拼接，Vite 代理 /api 可达，相关端点均在用。

## F. 文档失真连带修正

- `AguiWakeupTarget.java:136`、`InboxWatchdogWakeupService.java:152`：过时注释（见 E2）。
- `docs/workspace-layout.md:66`：subagents 端点若按 A3 删除需同步删该行。
- `SessionOwnerService.java:175` 注释承诺的删除联动从未接线（见 B2）。

## G. 建议执行顺序

1. **第一批（纯删，零行为变化）**：A1-A5 + B 全部 + C5 死分支 + B4/B5/B6 —— 约 6,500 行。
2. **第二批（小重构）**：C1-C4、C6。
3. **第三批（需决策/查证）**：D1、D2；顺带修 E2 过时注释、F 文档。

## H. 边界与未证实项

- 未连库、未跑运行时；`inactive` 枚举与 Studio SDK 字段消费未证实。
- 删 `SessionOwnerService`/`SessionExtensionService` 的死方法前建议跑一次 `mvn -o compile` + 全量测试（个别测试引用死方法，测试需同步删）。
- i18n 动态键未逐 key 定死（已抽查确认模式），如需 100% 覆盖需按命名空间逐个比对。
- knip 对 `api/index.ts` 桶导出、`@agentscope-ai/agentscope` 包内符号的判定已人工复核，可信。
