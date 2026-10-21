# Coding Agent Harness 使用手册

版本：v0.1  
适用对象：使用该规范的人类开发者，以及 Codex、Claude Code、Cursor 等 coding agent。

这份手册回答三个问题：

1. 什么时候可以直接 vibe coding？
2. 什么时候必须写 Spec、Plan、Tasks？
3. 怎么避免 agent 把“看起来做完了”当成“真的做完了”？

---

## 1. 这套 Harness 管什么

本目录定义开发者/coding agent 如何理解、拆分、实现、验证和交付任意仓库的变更。

---

## 2. 30 秒判断：该用哪种流程

按顺序回答：

1. 这个任务是否只读、探索、不写代码？  
   → `Investigation`。

2. 是否只改一处，且验证方式显然？  
   → `Tiny`。

3. 是否只影响一个模块或小范围接口/组件？  
   → `Bounded`。

4. 是否新增能力、跨模块、影响 API/UI/数据/权限？  
   → `Feature`。

5. 是否涉及部署、迁移、协议、安全、权限模型或多会话交付？  
   → `Release`。

规则：**判断不确定时升一档，不要降档。**

---

## 3. 快速开始

### 3.1 Agent 的最小动作

每个 coding agent 开始前应：

1. 阅读 `AGENTS.md`。
2. 判断任务类型。
3. 如果是 `Bounded / Feature / Release`，先看：
   - `docs/agent-harness/CONTEXT.md`
   - `docs/agent-harness/GATES.md`
   - 相关的 `spec.md / plan.md / tasks.md / progress.md`
4. 不清楚就先澄清，不要直接改代码。
5. 完成时按 Completion Contract 汇报证据。

### 3.2 人类开发者的最小动作

作为任务负责人，你需要重点确认：

- 任务是否分类正确；
- In Scope / Out of Scope 是否合理；
- 验收标准是否可测；
- 是否批准进入实现；
- 最终是否批准 Ship。

你不需要写完整 Spec，但需要阻止 agent 把未确认假设写成事实。

---

## 4. 任务分级

| 类型 | 典型例子 | 最低要求 | 文档 |
|---|---|---|---|
| `Investigation` | 读代码、做方案比较、定位问题原因 | 输出结论、证据、建议下一步 | 通常不需要 |
| `Tiny` | 改文案、小样式、局部重命名、补一个小测试 | 实现加最窄验证 | 不需要 charter/spec |
| `Bounded` | 修一个明确 bug、调一个端点校验、改一个组件行为 | 轻量任务契约加验证 | `TEMPLATE_TASK.md` |
| `Feature` | 新增 API/UI 能力、跨模块功能、新权限点 | 澄清、Spec、Plan、Tasks、Verify、Review | task charter + spec + plan + tasks + progress |
| `Release` | 数据迁移、权限模型变化、部署链路变化、运行时协议变化 | 完整 Harness 加明确 Ship 授权 | 完整工件，必要时 ADR |

### 示例

- “把这个按钮文案改成‘保存’” → `Tiny`
- “列表为空时显示空状态” → `Bounded`
- “新增一个列表中心，包含筛选、批量操作和偏好设置” → `Feature`
- “调整权限模型，并同步 API 契约、服务端校验和客户端守卫” → `Feature` 或 `Release`
- “新增数据库迁移并灰度发布” → `Release`

---

## 5. 标准流程

推荐流程：

```text
Clarify → Recon → Charter → Spec Freeze → Plan → Execute
        → Verify → Review → Integrate → Ship → Retro
```

| 阶段 | 目标 | 关键产出 |
|---|---|---|
| Clarify | 把模糊意图变成边界 | 目标、范围、非目标、风险 |
| Recon | 理解现有实现 | 相关模块、现有测试、约束 |
| Charter | 固定本次任务契约 | `TEMPLATE_TASK.md` |
| Spec Freeze | 冻结行为和验收标准 | `TEMPLATE_SPEC.md` |
| Plan | 决定怎么改 | `TEMPLATE_PLAN.md` |
| Tasks | 拆成纵向切片 | `TEMPLATE_TASKS.md` |
| Execute | 按切片实现 | 代码、测试、提交 |
| Verify | 用证据证明完成 | 测试、构建、人工路径 |
| Review | 检查质量和需求覆盖 | 两遍 Review |
| Integrate/Ship | 合入或发布 | 最终授权 |
| Retro | 沉淀经验 | `DECISIONS.md` / `LESSONS.md` |

`Investigation` 和 `Tiny` 不需要走完整流程。  
`Feature` 和 `Release` 不应该跳过 Spec 和 Verify。

---

## 6. 工件放在哪里

推荐目录：

```text
docs/agent-harness/
├── AGENTS.md               # 仓库根目录
├── CONSTITUTION.md
├── CONTEXT.md
├── GATES.md
├── TASK_KINDS.md
├── DECISIONS.md
├── LESSONS.md
├── adr/
└── specs/
    └── <feature-name>/
        ├── task.md
        ├── spec.md
        ├── plan.md
        ├── tasks.md
        └── progress.md
```

建议：

- `Investigation`：可以只留在对话或临时报告里。
- `Tiny`：不建目录，不写 Spec。
- `Bounded`：如果需要留档，只写一个轻量 `task.md`。
- `Feature` / `Release`：创建 `specs/<feature-name>/`。
- 长期架构决策：写 `adr/`。
- 重复踩坑：写 `LESSONS.md`。
- 稳定约束：优先写 `CONTEXT.md` 或 ADR，不要堆进每个 Spec。

本仓库（smyy）目录边界：

- `E:\数模演易\计划\`＝历史方案归档，**只读**，不迁入 `specs/`，新任务不回写。
- `specs/` 只放新任务的工件，按 `<feature-name>` 建目录。
- `docs/工程范式参考.md`、`docs/smyy配置清单.md`＝既有事实源，`CONTEXT.md` 只做索引与指向，不复制正文。

---

## 7. Spec 怎么写

Spec 的目的不是写长文档，而是提前固定最容易返工的决定。

### 7.1 写行为，不写文件清单

不要写：

```text
创建 Controller
创建 Service
创建 Repository
创建客户端页面
```

这是 Plan，不是 Spec。

应写：

```text
用户打开记录列表时，默认看到最近 30 天记录。
用户只能读取属于当前账号的数据。
标记单条已处理后，未处理数立即减少。
更新失败时保留原状态，并显示重试提示。
```

### 7.2 用 EARS 或 Given / When / Then

EARS：

```text
WHEN 用户点击“全部已处理”
THE SYSTEM SHALL 只更新当前账号的未处理记录
```

Given / When / Then：

```text
Given 当前账号有 3 条未处理记录
When 用户将其中 1 条标记为已处理
Then 未处理数量显示为 2
And 该条记录不再显示高亮标记
```

### 7.3 必须写“不做什么”

`Out of Scope` 是保护范围的关键。

例如：

```text
## Out of Scope
- 外部推送
- 管理员批量创建后台
- 自定义数据保留周期
- 跨设备实时同步
```

### 7.4 未确认问题不能伪装成结论

如果有未决问题，放进：

```md
## Open Questions
- [ ] 是否需要支持管理员批量标记已读？
```

不要让 agent 自己猜成默认规则。

---

## 8. Tasks 怎么拆

优先使用纵向切片，不按技术层横向拆。

不推荐：

```text
1. 做所有表结构
2. 做所有接口
3. 做所有页面
4. 最后补测试
```

推荐：

```text
1. 查询当前账号最近 30 天记录
2. 标记单条已处理并更新计数
3. 全部已处理，且只影响当前账号
4. 偏好设置：仅活动记录可关闭
5. 空状态、失败态、权限边界
6. 端到端验证和回归测试
```

每个任务应有：

- Goal；
- Acceptance；
- Verification；
- Dependencies；
- Status；
- Evidence。

---

## 9. Verify 与完成声明

没有证据的完成声明无效。

### 9.1 至少验证什么

根据任务影响选择：

| 变更类型 | 常见验证 |
|---|---|
| 服务端逻辑 | 相关单测；必要时全量单测 |
| API 契约 | 更新并核对契约文件 |
| 数据迁移 | 迁移检查命令；确认 rollback/兼容性 |
| 客户端/UI | 类型检查 + 构建；必要时 lint |
| 权限 | 后端校验 + 前端守卫 + 越权路径验证 |
| 运行时模块 | 相关单测 + 启动/回归路径 |

不要照抄命令。使用当前仓库和任务实际可执行的命令。

### 9.1.1 本仓库（smyy）实际命令

| 用途 | 命令 | 说明 |
|---|---|---|
| 全量单测 | `pnpm test` | `node --test tests/*.test.mjs`，11 个文件 |
| 单文件单测 | `node --test tests/<name>.test.mjs` | 按任务相关性挑，禁止只跑无关测试来"证明"完成 |
| 类型检查 + 构建 | `pnpm build` | `check-python.mjs && vue-tsc -b && vite build`，必须先过 |
| 开发服务 | `pnpm dev` | 端口 5181（strictPort） |
| 浏览器探针 | `node scripts/port/probe-<名>.mjs` | 需先起 5181；base 显式写 `http://localhost:5181` |

探针与用途对应：

- `probe-gate.mjs` / `probe-gate-submit.mjs`：闸门 SSE 路径 / 重建+提交路径回归
- `probe-flicker.mjs`：原形重绘闪烁
- `probe-live.mjs` / `probe-events.mjs`：SSE 流与事件
- `probe-site.mjs` / `probe-home-jump.mjs`：站点路由与首页
- 其余 `probe-*.mjs` 按文件名对应功能点

本仓库现状约束：

- **无 lint 脚本**，`vue-tsc -b` 即类型门禁，不要凭空编造 lint 命令。
- 产物层改动（`src/legacy/prototype/core.ts`、`src/views/PrototypeShell.vue`）须跑对应探针，单测覆盖不到。
- `scripts/port/build-port.mjs` 已与产物失同步，运行必 ABORT，**不得**以"重新生成"作为验证手段。

### 9.2 Evidence 表格式

```md
| Check | Command / Method | Result |
|---|---|---|
| 单元测试 | `<unit-test-command>` | pass |
| 客户端构建 | `<build-command>` | pass |
| 权限边界 | 手工验证无权限用户返回 404 | pass |
```

### 9.3 最终汇报模板

```md
## Summary
- Changed:
- Not changed:

## Acceptance Trace
- AC1: ... → verified by ...
- AC2: ... → verified by ...

## Verification
- Command:
- Result:

## Known Limitations
- ...

## Follow-ups
- ...
```

---

## 10. Scope Change Gate

执行中发现新需求时，agent 不能顺手做。

必须先分成三类：

1. 新 Follow-up task；
2. 修改当前 Spec，经确认后再做；
3. 只记录，不在本任务实现。

示例输出：

```md
## Scope Change
- 发现点：当前列表失败时可能需要外部兜底。
- 影响：新增外部依赖、配置和重试逻辑。
- 建议：不放入当前任务，创建 follow-up。
- 当前动作：继续完成当前列表的失败态和重试。
```

---

## 11. Circuit Breaker

以下情况必须停止盲改：

- 同一个问题连续失败 3 次；
- 修 A 坏 B，开始出现连锁回归；
- 核心假设被证伪；
- 缺少必要权限、环境、配置或信息。

此时：

```text
STOP PATCHING
→ 保留当前最好状态
→ 记录失败假设和证据
→ 状态改为 REPLAN_REQUIRED
→ 新会话或新计划继续
```

不要把“再试 5 次”当成计划。

---

## 12. Review 怎么做

Feature / Release 至少两遍 Review。

### 12.1 Code Quality Review

检查：

- 是否符合现有架构；
- 是否重复造轮子；
- 是否修改无关文件；
- 命名和抽象是否清晰；
- 是否引入不必要依赖；
- 是否容易维护。

### 12.2 Requirements Coverage Review

重新打开 `spec.md`，逐条检查：

- 每条验收标准是否完成；
- Out of Scope 是否被擅自实现；
- 空态、失败态、权限边界是否覆盖；
- 是否有遗漏行为；
- 证据是否真实。

测试全绿只能证明“已写入测试的行为”没坏，不能证明原始需求没有漏。

---

## 13. Ship 的规则

```text
VERIFYING 通过 → READY_TO_SHIP
用户/负责人明确授权 → SHIPPED
```

不要把“测试通过”自动等同为“可以发布”。

以下操作必须有人类明确授权：

- 部署；
- 数据迁移；
- 删除数据；
- 修改权限；
- 修改生产配置；
- 访问敏感数据。

---

## 14. 常用提示词

### 启动一个 Feature

```text
我想做【功能名称】。
请先判断任务类型，不要直接改代码。
请基于 docs/agent-harness/TASK_KINDS.md 和 GATES.md，
先澄清范围、非目标、风险和验收标准。
每次最多问 4 个关键问题，并给出你的推荐。
```

### 冻结 Spec

```text
根据我们确认的讨论生成 docs/agent-harness/specs/<feature>/spec.md。
不要把未确认假设写成结论。
先给我检查，确认后再进入 Plan 和实现。
```

### 拆任务

```text
阅读 spec.md 和相关代码，生成 plan.md 与 tasks.md。
请使用纵向切片，每个任务都要能独立验证。
先展示任务依赖和验收方式，我确认后再开始实现。
```

### 执行中遇到范围变化

```text
发现范围变化：...
请按 Scope Change Gate 输出：
1. 是否影响当前任务；
2. 建议 follow-up / 修改 Spec / 仅记录；
3. 当前任务如何继续。
在确认前不要实现新范围。
```

### 最终验证

```text
不要根据任务勾选状态宣布完成。
请重新阅读 spec.md，逐条核对验收标准。
运行相关测试、构建和检查，并输出每条验收标准对应的证据。
```

---

## 15. 常见反模式

| 反模式 | 纠正 |
|---|---|
| 所有任务都写长 Spec | Tiny 不需要 |
| 所有任务都不写 Spec | Feature/Release 必须写 |
| Spec 写成文件清单 | Spec 写行为，Plan 才写文件 |
| 任务打勾就算完成 | 必须回到 Spec 逐条验证 |
| 测试通过就说完成 | 还需要需求覆盖检查 |
| 聊天里决定架构 | 写入 DECISIONS.md 或 ADR |
| 同一失败反复改 | 触发 Circuit Breaker |
| 顺手做新需求 | 先走 Scope Change Gate |

---

## 16. FAQ

### Q1：这是不是要求每次都用重流程？

不是。  
目标是“刚好够用”：`Tiny` 尽量轻，`Feature/Release` 必须稳。

### Q2：Spec 以后会过期怎么办？

三种生命周期都可以，但必须明确：

- `Spec-first`：交付后归档；
- `Spec-anchored`：长期维护；
- `Spec-as-source`：先改 Spec，再改实现。

默认倾向：任务 Spec 用于当前变更，稳定约束进入 `CONTEXT.md`、`DECISIONS.md` 或 ADR。

### Q3：Agent 可以自己决定需求吗？

可以提出选项和推荐，但不能独自决定权限、隐私、付费、数据保留、安全边界和部署策略。

### Q4：多个 agent 可以并行工作吗？

可以，但前提是：

- 任务边界清楚；
- 文件影响范围不冲突；
- 每个任务有独立验收；
- 结果由人类统一集成和审查。

不要让多个 agent 同时修改同一批文件。

---

## 17. 一句话总结

> 用 vibe coding 探索方向，用 Spec 固化共识，用 Tasks 控制执行，用 Evidence 证明完成，用 ADR/Lessons 沉淀经验。
