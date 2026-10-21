---
name: mm-3-输出契约图表代码与用户运行包
description: 定义数学建模代码的结果、图表、指标和单位契约，生成用户运行说明及可核验回传清单。
---

# 输出契约、图表代码与用户运行包

## 输出契约

`spec.json` 和 `expected_outputs.json` 必须逐项一致，登记：

- 结果文件、图表文件和相对路径
- `question_id`、`model_id`、`algorithm_id`
- 字段、主键、行数/覆盖检查和单位
- 指标定义、方向、计算窗口和精度要求
- `acceptance_checks`
- `external_run_verified: false`

路径禁止绝对路径和 `..`。不同问题、模型和算法使用独立命名空间，结果 JSON/CSV 与 PNG/SVG 不得覆盖。

推荐结果目录：

```text
results/
├── q01_m01_a01_summary.json
├── q01_m01_a01_table.csv
└── validation.json
figures/
└── q01_m01_a01_<figure-name>.png
```

图表代码必须从运行生成的数据读取，明确标题、坐标轴、单位、图例、时间/样本索引和数据来源；不得把示例数字或静态截图当作正式图表。

## 用户运行包

必须提供：

- `README.md`：兼容入口
- `README_RUN.md`：隔离环境、依赖安装、输入放置、运行命令、输出位置、验收检查和回传方式
- `requirements.txt`：仅列直接依赖和兼容版本/范围，不执行安装
- `spec.json`
- `expected_outputs.json`
- `run_metadata.json`
- `data/`、`results/`、`figures/`、`logs/`

运行命令由用户在自己的隔离环境执行。代码 Agent 不代跑，不读取自己试跑生成的结果，不写“已运行成功”。

## 回传清单

用户运行后至少回传：

```text
return_manifest.json
run_log.txt
results/
figures/
source_snapshot/       # 运行前修改过源码时必须提供
```

`return_manifest.json` 至少包含：

- `case_id`、`code_revision`
- `source_code_sha256`、`input_data_sha256`
- `run_timestamp`
- Python/依赖/求解器环境
- 执行命令、退出码、seed
- 每个文件的相对路径、hash、`question_id`、`kind`

只有版本、hash、结构、单位和范围均核验通过，才能登记 `user_executed_verified`；用户口述数字或截图只能标记 `user_reported_unverified`。用户明确确认前不得进入论文阶段。

## 静态检查与交接

运行 `validate_code_package.py <package>` 进行非执行型检查，核对必需文件/目录、Python AST、JSON/schema、路径安全、输出一致性、hash 和未实现占位。静态检查通过也不证明数学正确性、依赖可用或结果可信。

交接必须明确：已写文件、revision/hash、静态检查状态、未测试项、缺失产物、`executed_problem_code: false` 和下一步用户动作。

## 失败与重生成

- 环境失败：先修依赖或运行说明。
- 输入失败：修字段、单位、清洗规则或要求用户修数据。
- 实现失败：升 revision 修源码。
- 模型失败：保留证据，修约束/路线并重新确认模型。
- 版本、输入或模型变化：保留旧包和旧回传，生成新版本，旧结果标记 `STALE`。
