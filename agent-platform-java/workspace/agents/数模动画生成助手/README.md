# 数模动画生成助手（文件型 Agent 定义包）

把一道数学建模竞赛题（题目 PDF + 附件）转成**一套可离线打开的分档分析动画 HTML**（青绿玻璃拟态、逐幕翻页、SVG 示意图 + 图表交互）。

本目录是 AgentHub / Agent Platform 的**文件型 Agent 定义**（markdown + 少量 Python 脚本），**不是可独立运行的程序**：运行时由平台 `FileSystemAgentLoader` 扫描 `workspace/agents/<名称>/` 注册，模型由 harness 装配。

## 目录结构

```text
数模动画生成助手/
├── AGENTS.md                                  # 父编排：硬约束、状态机、两个闸门、输出协议
├── subagents/动画生成智能体.md                  # 子智能体：盘点 → scene_map → 渲染 → 验收
└── skills/
    ├── mm-0-路径与会话隔离/SKILL.md              # 路径/脚本调用口径的唯一权威
    ├── mm-0-产物交接协议/SKILL.md                # 【TASK_RESULT】与 artifacts 登记口径
    ├── mm-2-分析到场景映射/
    │   ├── SKILL.md
    │   └── references/scene_schema.md          # scene_map JSON 契约（上游 prompt 逐字保留）
    ├── mm-2-Web动画实现规范/
    │   ├── SKILL.md
    │   ├── requirements.txt
    │   ├── scripts/{install_deps,probe_inputs,render_animation}.py
    │   ├── scripts/modules/                    # 上游模块，逐字保留：deck_render/deck_enricher/data_facts/doc_parser
    │   └── templates/teaching_template.html
    └── mm-2-动画一致性与可用性验收/
        ├── SKILL.md
        └── scripts/check_animation.py
```

## 数据流

```text
题目 PDF + 附件
  └─ probe_inputs.py        → analysis/input_digest.md、truth/facts.json、analysis/pages/*.png
       └─ 模型（子智能体）   → analysis/scene_map.json        ← 唯一由模型产出的环节
            └─ render_animation.py → animation/NN_*.html + quality_report.json + _generation_manifest.json
                 └─ check_animation.py → qa/qa_animation.json（静态验收；视觉部分人工）
```

**脚本不调用任何 LLM**：模型只产出 `scene_map.json`，其余全是确定性代码。这样同一份场景映射永远渲染出同一份产物，也避免了在 Skill 目录里放 API 密钥。

## 部署

1. 把本目录整体放进平台工作区：`<workspace-root>/agents/数模动画生成助手/`（**目录名就是 Agent 名**，不要重名）。
2. 起平台（`docker compose up -d postgres redis` → 后端 → 前端），日志里应出现该 Agent 的注册记录。**改定义后必须重启后端**才重新扫描。
3. 首次使用前确认依赖与脚本可执行：见 `skills/mm-2-Web动画实现规范/SKILL.md` 第六节「链路自测」。
4. 平台里选该 Agent，丢一道题（题目 PDF + `附件/` 目录，或直接上传 PDF），按闸门提示走完两轮确认。

## 与上游项目的关系

`modules/*.py` 与 `templates/teaching_template.html` 来自上游脚本项目 `实训书动画生成`，逐字保留；平台侧只加了包装脚本与三处导入适配。同步规则与适配点见 `skills/mm-2-Web动画实现规范/SKILL.md` 第五节——**覆盖上游文件后必须重做那三处适配**。

## 当前待验证项

以下两点只有在平台里真跑一次才能确认，第一次联调时优先验证并据实修正文档：

1. **Skill 脚本的运行期路径**：定义区对文件工具是只读下层，脚本是否被物化到 `<运行态根>/.harness/…` 需实测（`mm-0-路径与会话隔离` 第四节给了探测命令与上报兜底）。
2. **平台主机 Python 依赖**：`pymupdf` 等需装在平台进程可用的 Python 环境里（`execute` 用的是平台版 shell）。

## 硬边界（贯穿全部提示词）

- 不编造数值：图表只引用 `facts.json` 的真实键，缺数据处标"示意/待运行"。
- 视觉与交互验收**只能人工**；没有浏览器能力就标 `NOT_TESTED`。
- 产物只写自身会话空间，定义区只读；闸门只由父编排发起（子智能体返回 `[HITL_REQUIRED]`）。
