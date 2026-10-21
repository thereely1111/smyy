# 预览图生成 Agent

面向数模演易案例的通用预览图生成智能体。它将案例主题和建模关系提炼成可复制的图像提示词，生成适合首页轮播卡片的横向封面，并输出尺寸/构图验收结果与前端 `coverImage` 接入信息。

## 目录

```text
预览图生成agent/
├── AGENTS.md
├── README.md
├── subagents/
│   └── 案例预览图智能体.md
└── skills/
    ├── preview-0-产物交接协议/
    │   └── SKILL.md
    ├── preview-1-案例视觉提炼/
    │   └── SKILL.md
    ├── preview-2-提示词与生成规范/
    │   └── SKILL.md
    └── preview-3-图像验收与前端接入/
        └── SKILL.md
```

## 典型输入

```yaml
case_id: herb-drying
标题: 中药材热风烘干
案例摘要: 热风作用下药材内部传热、传质和表面蒸发过程
asset_root: public/hero-assets
project_root: D:/Desktop/smyy/smyy
```

## 典型输出

- `visual_brief.json`：案例视觉提炼和生成参数；
- `preview_prompt.txt`：可复制给图像模型的完整提示词；
- `preview_image.png`：保存到项目资产目录的预览图；
- `image_qa.json`：尺寸、格式、构图和禁用元素检查；
- `integration.json`：`coverImage` URL、对应案例 ID 和前端接入说明；
- 唯一的 `【TASK_RESULT】` 交接块。

## 使用边界

图像是案例封面视觉资产，不代表数学模型的正式计算结果。该 Agent 不求解题目、不生成代码、不生成动画，也不修改业务逻辑。生成图片后仍需人工打开首页检查裁切效果。

## 部署

将本目录放在：

```text
<AGENT_PLATFORM_WORKSPACE_ROOT>/agents/预览图生成agent/
```

后端重新扫描或重启后，平台才会发现新 Agent。当前项目的实际根目录配置为 `D:/Desktop/smyy/smyy/workspace`。
