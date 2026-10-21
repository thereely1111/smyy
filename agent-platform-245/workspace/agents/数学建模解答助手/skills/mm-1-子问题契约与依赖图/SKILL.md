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
