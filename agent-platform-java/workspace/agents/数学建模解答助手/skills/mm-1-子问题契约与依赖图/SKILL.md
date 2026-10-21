---
name: mm-1-子问题契约与依赖图
description: 将竞赛题拆分为稳定 ID 的逐问契约，明确每问的动词、输入、输出、约束、判据及问间依赖和复杂度增量。
---
# 子问题契约与依赖图

## 步骤
1. 依据题面原问编号建立 `q01`、`q02`…；不合并题面要求不同的任务。
2. 每问记录 `task_verb`、`objective`、`inputs`、`outputs`、`constraints`、`evaluation_criteria`、`deliverables`、`source_refs`。
3. 说明问题类型（预测、优化、评价、分类、仿真、判定、解释或组合），允许标记待确认。
4. 对每条先后依赖记录 `from_question_id`、`to_question_id`、`dependency_type` 和依据；不得仅因题号先后假定依赖。
5. 写出问间共享状态/数据/符号，以及后问增加的参数、边界、时间范围、随机性或优化目标。
6. 说明依赖图拓扑顺序；发现循环依赖或不可判定关系时如实标注。

## 输出与自检
形成 `problem_contract.tasks` 与 `dependencies`。逐项对照题面，确认题目交付要求无遗漏、引用 ID 均存在。图形绘制不是本 Skill 产物；仅输出结构化边列表。

## 逐问模型选择契约（2026-10-14 加）

逐问闭环（S1b）里，每一问的模型**不由 S1a 拍定**，而是每问单独由用户在闸门 1'B 选。以下字段名是**站点前端与下游共同读取的契约，不得改名**：

| 字段 | 含义 |
|---|---|
| `solution_map.routes[qNN].candidate_models[]` | 候选模型数组，每项 `{ name, method_id, reason, risk }`；`method_id` 必须真实存在于方法库 `索引.md`；2~3 项 |
| `solution_map.routes[qNN].selected_model_id` | **用户最终选定的 `method_id`**。**S2／S3 只认这个字段**；站点前端也按它渲染「已选」 |
| `solution_map.routes[qNN].selected_model_name` / `selected_at` / `selection_source` | 名称、时间、来源（`gate`＝闸门里选的，`dialogue`＝对话里说的） |
| `solution_map.routes[qNN].recommended_route` | S1a 的**推荐**路线，保留不动；用户跳过该问时下游回落到它 |

自检：`selected_model_id` 一旦存在，必须能在 `candidate_models` 里找到同 `method_id` 的项，否则须标 `user_specified` 并记用户原话。
