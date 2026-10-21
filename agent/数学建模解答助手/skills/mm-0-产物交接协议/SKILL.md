---
name: mm-0-产物交接协议
description: 数学建模解答助手汇报与交接的唯一权威来源。定义【TASK_RESULT】YAML 块的字段与状态取值、artifacts 运行态根相对路径的登记口径、缺件与可续跑语义、[HITL_REQUIRED] 与 [进度] 行的机器契约、以及逐问级台账（`questions/qNN/state.json`）与重做（版本递增 + STALE 打标）口径。所有子智能体（题目解析／分析动画／解题代码／结果接收／论文生成／质检）在 return 前必须遵循本规范。
---

# mm-0-产物交接协议

交接口径的**唯一权威来源**。平台有代码在解析这些字段，**措辞即契约**：改字面值前必须先改平台代码。

## 一、【TASK_RESULT】（return 前必须输出，且只输出一个）

```yaml
【TASK_RESULT】
status: COMPLETED            # COMPLETED | PARTIAL | BLOCKED | FAILED
stage: ANIMATION_READY       # 当前阶段标识：INPUT_PARSED | SCENE_MAPPED | ANIMATION_READY | QA_CHECKED
case_id: <父任务注入的 case_id>
completed: ["输入盘点", "场景映射", "渲染"]
in_progress: []
remaining: ["人工预览确认"]
artifacts:                   # 逐文件登记运行态根相对路径，见第二节
  - sub-1a2b3c4d/artifacts/<case_id>/animation/01_全局问题分析.html
  - sub-1a2b3c4d/artifacts/<case_id>/animation/quality_report.json
missing_artifacts: []        # 应有而未产出的文件（含原因）
next_action: 用户打开 artifacts/<case_id>/animation/ 首档页面确认交互与移动端显示
summary: 已产出 N 档分档动画，静态验收 0 FAIL，视觉待人工确认
resumable: true
```

- `status` 语义：`COMPLETED` 全部要求达成；`PARTIAL` 有非关键缺项但仍可用（如某档没有真实图表数据，已标示意）；`BLOCKED` 缺关键输入或脚本路径探测失败，无法继续；`FAILED` 试过并放弃（附 `next_action` 说明下一步找谁）。
- **父编排只信 `artifacts`**：平台 `SubagentReportService` 会逐条核对文件是否真实存在（见第二节写法），写错路径等于没交付。
- 禁止把「计划要做」写成 `completed`，禁止把 `NOT_TESTED` 写成通过。

## 二、artifacts 的写法（最易错的一条）

每条写成**运行态根相对路径** = **`<自身会话目录>/<会话内相对路径>`**：

```yaml
artifacts:
  - sub-1a2b3c4d/artifacts/<case_id>/animation/01_全局问题分析.html  ✅ 自身会话目录打头
  - sub-1a2b3c4d/artifacts/<case_id>/qa/qa_animation.json           ✅
  - animation/01_全局问题分析.html                   ❌ 缺会话目录前缀，平台核不到
  - E:/somewhere/01_全局问题分析.html                 ❌ 绝对路径、且不在运行态根内
```

- 自身会话目录的取法见 `mm-0-路径与会话隔离`（system prompt 的 `AgentStateStore ID:` 行）。
- `<case_id>` 是占位符，回报前必须替换为父任务注入的真实值；写入文件时只用会话内相对路径 `artifacts/<case_id>/...`。历史会话根目录下的旧文件只能按原路径读取，不要在汇报中把它们虚报成新路径。
- 只登记**本次真实产出**；只供自己排查的临时件放 `private`（可省略），不登记交付件。
- 下游/父编排要读这些文件时，会用**运行态根绝对路径 + 你登记的这条相对路径**，原样拼接即可读到。

## 三、进度行（每轮结束输出一行）

```
[进度] 阶段=S3 完成=[输入盘点,场景映射] 进行中=[渲染] 剩余=[验收] 下步=跑 render_animation.py 状态=执行中
```

- 六个字段名固定：`阶段`、`完成`、`进行中`、`剩余`、`下步`、`状态`。
- `状态` 只能取这五个字面值：`执行中` / `部分完成` / `等待人工` / `已完成` / `已阻塞`。平台的停滞看护按这些值判断该不该补醒；改了措辞会导致「该补醒时静默不补醒」。

**`等待人工` 只在「已经渲染出闸门/表单、球真的在用户手里」时才用**（2026-09-29 实测踩坑）：平台把 `状态=等待人工` 字面映射为 `WAIT_HITL`，语义是「等主编闸门」，**永不自动补醒**，并会向用户推送「智能体在等你输入（表单/确认待处理）」。

| 你实际的处境 | 该写 |
|---|---|
| 已输出 `[HITL_REQUIRED]`／已渲染闸门，正在等用户拍板 | `等待人工` |
| 正在等子智能体回报、等脚本跑完、等下一轮模型输出 | **`执行中`**（球在你这，不在用户手里） |
| 阶段性收尾但有非关键缺项 | `部分完成` |

写错成 `等待人工` 的后果：用户会收到一条**指向不存在的表单**的提示（他去会话里找不到任何表单），而平台又判定"球在用户手里"、不再补醒，会话可能就此停住。等子智能体时的正确写法：`下步=等题目解析智能体回报 状态=执行中`。
- 长流程每轮都要给，不要只在最后给一次。

## 四、需要用户决策：`[HITL_REQUIRED]`

子智能体没有 `ui_gate`，遇到需要用户拍板的事（问数无法判定、附件缺失影响图表、脚本路径探测失败、同类错误 3 轮不过）时：

```
[HITL_REQUIRED] 问题=附件3 缺失，问题三的图表没有真实数据 选项=[用示意值并标注, 等你补附件] 影响=问题三整档的图表可信度
```

输出后**结束本轮**，由父编排渲染闸门。不要自己替用户选，也不要在同一条消息里既上报又开始按某个选项推进。

## 五、逐问级台账与重做口径（S1b，2026-10-14 加）

逐问闭环里，**每一问除交付自己的分析产物外，还要写一份问题级台账** `artifacts/<case_id>/questions/qNN/state.json`：

| 字段 | 含义 |
|---|---|
| `question_id` | `q01`、`q02`… |
| `stage` | `QB`（逐问阶段） |
| `status` | `ANALYSIS_INITIAL` / `MODEL_PICKED` / `ANALYSIS_DONE` / `STALE` |
| `question_status` | `PENDING` / `SATISFIED` / `SKIPPED` |
| `revision` | 从 1 起，**每次重做 +1** |
| `selected_model_id` / `selected_model_name` / `selection_source` / `selected_at` | 用户选定模型（P0-3 契约，**下游与站点前端只认这里与 `solution_map` 的对应字段**） |
| `primary_artifacts` | 本问当前版本文件（数组） |
| `supersedes` | 被本版本替代的旧文件（重做时填） |
| `stale_at` / `stale_reason` | 被标 `STALE` 的时间与原因（**记用户原话**） |
| `updated_at` | ISO 时间戳 |

**重做口径（硬）**：平台**没有撤销 API**，所以重做 ＝ **追加新版本 + 旧版在台账里标 `STALE`**。旧版本**保留在盘上**，**不要删文件、不要原地覆盖**——原地覆盖会让"当前版本"失去可追溯性。

## 六、禁止事项

- 不伪造结果、不把 `NOT_TESTED` 说成已验证、不把示意数字写成实测结论。
- 不在交接文本里粘贴大段产物内容（HTML/JSON 正文）；只给路径与摘要。
- 不修改已交付的他人产物；重做时生成新版本并把受影响旧产物标 `STALE`。
