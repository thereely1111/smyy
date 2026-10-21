# Phase 0 契约与现状盘点

> 项目：`E:\数模演易\smyy`  
> 方案：`设计方案/smyy-LLM-MM-Agent-AG-UI-RD-Agent-升级方案.md` v1.2  
> 盘点日期：2026-10-15  
> 盘点范围：静态源码、Agent 定义、前端测试、Java 后端接口与数据库迁移

## 结论边界

本文件是 Phase 0 的静态盘点，不代表后端运行、认证端到端、真实赛题实验或数学正确性已经验证。当前仓库工作区在盘点前无未提交改动，基线提交为 `e0a0aff067f3bbf5633ddd0ffd87bc13165c8c5f`。

本轮没有把未能核实的开源仓库、提交 SHA、许可证或效果写成事实。LLM-MM-Agent 的仓库地址仍未锁定；AG-UI 与 Microsoft RD-Agent 已通过 GitHub 官方 API 核实。所有外部参考只作为方法来源，不作为本项目效果证据：

| 参考 | 计划借鉴 | 核验状态 | 采用边界 |
|---|---|---|---|
| LLM-MM-Agent | 问题拆解、候选路线 | `UNVERIFIED`：方案未锁定仓库地址，当前未确认同名仓库 | 只保留为待核实方法参考 |
| AG-UI | Agent 与界面事件传输 | 已核实仓库 `https://github.com/ag-ui-protocol/ag-ui`；main SHA `72e8ad1802ce9a0525af1398d0de288d9b496317`；MIT | 仅借鉴事件/状态传输；以项目实际安装的 Java AG-UI starter 和本地接口为准 |
| Microsoft RD-Agent | 实验登记、反馈、指标记录 | 已核实仓库 `https://github.com/microsoft/RD-Agent`；main SHA `484776c211e4fbbeef03e0ec00d6bbee7362a4f4`；MIT | 仅参考轻量实验闭环，不引入运行栈 |

## 已确认的本地能力

| 领域 | 证据 | 当前能力 | 边界 |
|---|---|---|---|
| 前端事件恢复 | `src/api/aguiEvents.ts` | 支持水位、`afterSequence` 分页和 SSE 收尾后的事件跟随 | 需认证后端运行验证；静态接口存在不等于恢复闭环通过 |
| 中断登记 | `src/api/formInterrupt.ts` | 有登记表、框架活中断枚举、清理接口，并兼容老后端 404 降级 | 需验证重复登记、并发取消和重启后的权威一致性 |
| 产物读取 | `src/api/analysisRun.ts`、`src/stores/analysis.ts` | 以 `analysis_result.json.primary_artifacts` 作为当前产物指针 | 旧根级产物与新逐问目录尚未统一为新 revision 结构 |
| 前端类型 | `src/types/analysis.ts` | 已有题目契约、候选模型、路线和分析结果类型 | 尚无 `question state/revision/validity/verification` 的完整类型 |
| Agent 交接 | `workspace/agents/数学建模解答助手/skills/mm-0-产物交接协议` | 已有 `selected_model_id`、`primary_artifacts` 等交接字段 | 还需补齐 revision、依赖版本和契约检查结果 |
| 方法库 | `workspace/agents/数学建模解答助手/skills/mm-1-机理识别与模型路线比较` | 已有稳定 `M-领域-编号` 方法 ID 和方法卡片 | `model_id` 与 `method_id` 的服务端持久化闸门尚未确认 |
| 用户运行约束 | 总控 `AGENTS.md` | 解题代码由用户运行，结果回传后才可进入论文 | Phase 1 不得引入默认自动执行 |
| AG-UI 事件查询 | `backend/.../web/AguiEventController.java` | `/api/agui-events` 查询事件水位和事件页 | 需验证会话归属、快照与事件水位的一致读取 |
| 会话恢复 | `backend/.../web/AgentManagementController.java` | 有 transcript、turns、progress、run-status、recover、interrupt | 尚无逐问业务状态的权威接口 |
| 中断接口 | 两份 `AgentManagementController.java` 对照 | 仓库内只有 form-interrupts；外置源码另有 `/interrupts` 活中断枚举 | 前端发送 `runId`，两份 DTO 和内存登记表均未接收，恢复身份链有缺口 |
| 运行持久化 | Flyway `V26`, `V27`, `V37`, `V38`, `V60`, `V62` | 已有会话归属、进度、AG-UI 事件、run lease、停止和人工闸门扩展 | 尚未看到逐问 revision、依赖失效和验证报告专用表 |
| 回传核验 | 既有 `mm-4-用户回传确定性核验/scripts/verify_return.py` | 已有路径、hash、大小、字段、单位声明、非有限值等确定性检查 | 不能证明真实物理单位、数值来源或数学正确性 |

## 当前契约映射

### 输入与问题拆解

- 题目数量必须来自题面，不能硬编码为 q01–q04。
- 全题产物目标为 `truth/problem_contract.json`、`truth/problem_profile.json`、`truth/data_inventory.json`、`truth/assumptions.json`、`truth/solution_map.json`。
- `solution_map` 需要稳定 `question_id`、必做标记、输入/输出、依赖边和初始路线。
- 关键附件不可读时应为 `NEEDS_INPUT`，禁止使用示意数据冒充输入。

### 两次分析调用

1. 初析调用只处理指定问题，传入 `only=qNN`、`phase=初析` 和当前全题版本。
2. 用户选择模型或自定义路线后，保存选择记录，再以 `only=qNN`、`phase=完整` 发起第二次独立调用。
3. 第二次调用必须携带选定的 `model_id`、对应 `method_id`、选择记录版本和必要备注。
4. 选择不适用时必须回到人工决策，不得静默换模。

### 三个独立状态维度

目标状态字段如下，当前主要存在于方案和 Agent 约定中，尚未形成后端权威逐问状态：

- `workflow_status`：流程执行阶段
- `validity`：当前 revision 是否仍有效
- `verification_status`：结构、运行来源、数值和解释验证状态

用户确认只能改变确认状态，不能把 `NOT_RUN` 改成 `PASS`。`QUESTION_COMPLETED` 只能表示分析契约和用户确认完成，不表示实验验证完成。

### 依赖与版本

正式 revision 应写入 `artifacts/<case_id>/questions/qNN/revisions/r0001/`，旧 revision 只读。上游模型、输入、输出、单位、参数或数值变化时，应按依赖图传播 `INVALIDATED_BY_DEPENDENCY`；旧 run 晚到时不能覆盖当前指针。

现有 `analysis_result.json.primary_artifacts`、`analysis_v*.md` 和 `selection.json` 仍是兼容读取来源。Phase 1 应先双读旧结构、单写新结构，并通过功能开关控制逐问流程。

## 缺口与优先级

| 优先级 | 缺口 | 影响 | 建议落点 | Phase 1 验收证据 |
|---|---|---|---|---|
| P0 | 服务端逐问状态、revision、幂等命令 | 重复提交和晚到结果可能无法由后端权威阻止 | Java domain/service/controller + Flyway | 重复提交只产生一次派发；状态与事件一致 |
| P0 | 初析→选模→完整分析交接 | 无法证明两次调用和选择版本闭环 | Agent 总控、请求载荷、会话业务状态 | 真实逐问分析记录两次 run 和选择记录 |
| P0 | 模型契约检查 | 选模可能与公式、输入、下游接口不一致 | Agent 产物 schema + 服务端门控 | 失败契约阻止用户确认 |
| P1 | 依赖图与下游失效 | 换模或单位变化可能继续消费旧结果 | solution map 解析 + revision service | q01 变更使实际依赖 q02 失效 |
| P1 | 新逐问目录双读单写 | 新旧会话兼容 | artifact adapter | 旧会话可读，新会话写 revision 目录 |
| P1 | 三阶段验证报告查询 | 无法追踪证据覆盖和不确定结论 | verification manifest/API | NOT_RUN/INCONCLUSIVE 不被聚合为 PASS |
| P2 | 评测登记与对照统计 | 无法支持质量提升结论 | 实验登记产物和离线报告 | 固定集、基线、失败样本、成本均有分母 |
| P2 | 受控自动实验 | 增加执行与安全边界 | 后续独立阶段 | Phase 1 明确不启用 |

## Phase 0 冻结输入与验收清单

- [x] 项目路径和适用方案版本已确认。
- [x] 现有前端、Agent、Java 后端和数据库迁移位置已定位。
- [x] 现有能力与静态证据已记录。
- [x] 标准事件与业务语义的边界已记录：业务事件只能放在明确扩展载荷中，不冒充 AG-UI 标准事件。
- [x] 外部开源参考的核验记录已更新；LLM-MM-Agent 仍为 `UNVERIFIED`。
- [x] MVP 用户运行并回传约束已冻结。
- [ ] 认证后端运行验证：`NOT_RUN`。
- [ ] 认证端到端恢复、幂等和依赖失效：`NOT_RUN`。
- [ ] 真实赛题对照实验：`NOT_RUN`。
- [ ] 论文结论证据覆盖：`NOT_RUN`。

## Phase 1 进入条件

Phase 1 可以从 P0 开始，但必须先以 feature flag 隔离新流程，并保持旧读取器可用。首个实现批次建议只覆盖一个新 case 的 qNN：创建初析记录、登记候选和人工选择、发起完整分析、保存模型契约检查结果、写入三维状态和幂等事件。完成后再扩展依赖失效和历史会话迁移。

Phase 1 仍然不自动运行生成代码，不把前端状态或 LLM 文本作为服务端权威状态，不把结构校验当作数学正确性证明。


## 后端来源差异与运行版本边界

`.env.local` 仅配置 `http://localhost:8080`，不能单凭这个 URL 判定当前监听进程来自哪份源码。`src/config/index.ts` 注释指向 `E:\象数演易\agent-platform-java`，本轮对外置源码只读比对，未编辑它。外置仓库基线 HEAD 为 `053cf6711412f012446fea98f8a351a25cb761c0`，存在大量已有未提交修改，不能直接用该 HEAD 代表运行构建。

外置源码真实 Maven 根：`E:\象数演易\agent-platform-java\agent-platform-java\backend`。仓库内副本 Maven 根：`smyy/agent-platform-java/backend`。两份的 Agent 总控与题目解析定义 SHA256 一致；控制器不同。外置版本有 `/interrupts`，内置没有。两份 `PendingFormInterruptStore` SHA256 一致，都是进程内 `ConcurrentHashMap`；DTO 未包含 `runId`。后端重启恢复人工决策目前只能作为待验收能力，不能称为已实现的持久化。

`pom.xml` 声明 Java 21、Spring Boot 3.5.16、AgentScope `2.0.3-tipdm`（含 `agentscope-agui-spring-boot-starter`）。这是源码声明版本，实际运行 JAR、配置、模型、物化定义 hash 均为 `NOT_RUN`。前端未直接安装官方 AG-UI JS SDK，不能把上游仓库版本当作当前协议运行版本。

### 已发现的定义冲突

- 题目解析子智能体初析硬性要求 2–3 个候选；v1.2 允许仅一个可行候选并说明证据，完全不可行时返回 `NEEDS_INPUT`。
- 题目解析逐问模式禁止重写全题 truth，但其后通用“完整分析先落四个骨架”段落可能被应用到逐问完整阶段；Phase 1 需要明确仅全题阶段适用。
- 旧逐问台账已经有 revision、STALE 和选择字段，不能称为“从零缺失”；缺口是服务端权威、三维状态和不可变 revision 的一致落地。

## 事件与业务语义映射草案

| 现有传输/事件 | 已有用途 | 逐问业务语义的适配规则 |
|---|---|---|
| `RUN_STARTED` | 运行开始 | 关联服务端已创建的 question/revision/run，不能单凭开始事件推进业务完成 |
| `TEXT_MESSAGE_*`、`TOOL_CALL_*` | 文本和工具过程 | 展示过程；不从自然语言推断 PASS 或用户批准 |
| `RUN_FINISHED` 及现有 `outcome` 扩展 | 成功、错误、中断 | 这是本项目 starter 的既有载荷；成功仅表示该 run 收尾，不表示问题有效或数值验证通过 |
| `RUN_ERROR`、既有 `RAW` 错误 | 运行失败/拒绝 | 保留原始错误，HTTP 200 不能掩盖流内失败 |
| `/api/agui-events` | 当前用户事件分页与水位 | 复用事件序号；当前最大序号与最后 RUN_STARTED 为独立查询，尚无逐问快照原子读保证 |
| 未来业务扩展载荷 | 决策、契约、状态变化 | 必须先核实 starter 扩展支持；建议命名空间 `smyy.question.*`，携带 schema/case/question/revision/run/command ID，服务端提交后发出；不得把 `QUESTION_COMPLETED` 命名成 AG-UI 标准事件 |

## 可执行的冻结工程输入

`tests/upgrade-contracts.test.mjs` 的固定样例用于工程契约，不是真实题目质量评测。覆盖非连续编号、单候选、重复 ID、未知依赖、环、数值依赖进入点、缺输出、确认与验证分离、跳过/取消、旧版拒绝。`scripts/upgrade/contract-checks.mjs` 是无副作用的离线检查器，尚未接入生产接口；不验证数学语义、不验证真实身份或来源，不能替代服务端状态机。

目标 `solution_map` 暂用 `questions[]` 规范化结构，旧版为 `routes[]`。本轮不切换生产读取器；双读适配必须在 Phase 1 显式实现。目标状态检查另外要求 `contract_check_status`，这是本轮明确登记的门控字段，不能默认为旧状态已有。

真实评测输入尚未冻结：当前没有用户确认的真实赛题集、专家基线、LLM 配置、预算和验收阈值，均 `NOT_RUN`。三类题目覆盖需要后续登记；本轮工程样例不能替代它。Phase 0 的工程基线可用，运行基线和质量评测部分仍未完成。
