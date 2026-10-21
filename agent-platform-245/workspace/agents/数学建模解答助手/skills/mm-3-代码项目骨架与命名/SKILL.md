---
name: mm-3-代码项目骨架与命名
description: 为数学建模代码阶段建立可审计的 Python 源码包骨架、规格文件、版本信息和命名隔离规则。
---

# 代码项目骨架与命名

## 目标

把已审核的题目契约和模型路线转化为结构清晰、可静态检查、可交给用户本地运行的源码包。此阶段只生成源码与运行契约，不执行题目程序，不生成正式数值结果。

## 输入前提

必须已有并版本一致：

- 原题与附件真实路径、hash、来源说明
- `problem_contract.json`
- `solution_map.json`
- 已审核分析与模型/算法选择
- 用户确认的关键假设、单位、输入字段、验收标准

缺任一关键项或版本冲突时停止，返回 `BLOCKED` 和缺口清单。

## 标准包结构

路径均为案例产物目录内相对路径，禁止 `..`、绝对磁盘路径和覆盖原始输入。

```text
code/
├── main.py              # 兼容入口；多问项目可转发到 src/run_all.py
├── spec.json             # case/revision/hash/问题规格
├── requirements.txt      # 直接依赖，不执行安装
├── README.md             # 兼容入口
├── README_RUN.md         # 用户安装、运行和回传说明
├── expected_outputs.json # 结果/图表/指标/单位契约
├── data/                 # 用户放入或复制的输入数据，不放秘密
├── results/              # 用户运行生成，初始为空
├── figures/              # 用户运行生成，初始为空
├── logs/                 # 用户运行日志
└── run_metadata.json     # 版本、hash、状态和未验证边界
```

可增加 `src/`、`tests/`、`configs/`，但根目录必须保留清晰入口和运行说明。

## 规格文件要求

`spec.json` 至少包含：

- `case_id`、`problem_id`、`title`、`code_revision`
- `language: python`
- `model.family`、`model.name`、`model.selection`
- `data.input_files`、字段、时间范围/步长、单位、缺失值策略
- 决策变量、目标、约束、求解器或算法库
- 输出文件、指标、验收检查
- `input_manifest`、`depends_on`、`stale_when_changed`

不确定信息写入 `assumptions` 或待确认清单，不静默猜测。

## 命名规则

- 单问单模型：建议 `q01-v001/`、`q01-v002/`。
- 多问/多模型/多算法：使用 `qXX_mYY_aZZ` 命名源文件、结果文件和图表文件。
- 结果 JSON/CSV 与图 PNG/SVG 均应带问题、模型和算法标识，避免覆盖。
- 每次重生成使用新目录或新 revision，不覆盖已经交给用户或用于回传的旧包。
- 共用逻辑集中到 `src/common/` 或等价模块；问题专属逻辑隔离。

## 版本与失效

`run_metadata.json` 或总控清单至少登记：

- `case_id`、`question_id`、`model_id`、`algorithm_id`
- `code_revision`
- `source_code_sha256`
- `input_manifest` 或输入来源 hash
- `spec_sha256`
- 生成时间、状态、依赖关系
- `external_run_verified: false`
- `stale_when_changed`

源码、输入、模型路线、单位或输出契约变化后，旧回传和旧论文引用必须失效，生成新版本。

## 脚手架边界

可以用通用脚手架生成结构，但骨架中的 `NotImplementedError` 表示待实现，不是可交付解题代码。带未实现占位的包只能是 `CODE_DRAFT`，不能标记为 `CODE_READY`。
