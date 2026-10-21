---
name: preview-0-产物交接协议
description: 定义预览图生成 Agent 的统一状态、交付文件、进度行和 TASK_RESULT 交接格式。
---

# 预览图产物交接协议

## 一、交接块

每轮结束只输出一个 `【TASK_RESULT】` YAML 块：

```yaml
【TASK_RESULT】
status: COMPLETED|PARTIAL|BLOCKED|FAILED
stage: INTEGRATION_READY|IMAGE_QA_PASSED|IMAGE_GENERATED|PROMPT_READY|WAIT_USER
case_id: <父任务注入的案例 ID>
revision: v1.0
completed: [视觉提炼, 提示词生成, 图像生成, 图像验收]
in_progress: []
remaining: [人工首页预览]
artifacts:
  - <自身会话目录>/visual_brief.json
  - <自身会话目录>/preview_prompt.txt
  - <自身会话目录>/image_qa.json
  - <自身会话目录>/integration.json
  - <项目资产目录相对路径>/case-cover-v1.png
missing_artifacts: []
cover_image_url: /hero-assets/case-cover-v1.png
executed_problem_code: false
next_action: 打开首页检查横向卡片裁切
summary: 已生成并通过静态验收；未生成数学模型结果
resumable: true
```

`artifacts` 只登记本轮真实生成的文件。路径应使用运行态根相对路径并带自身会话目录前缀；项目资产文件也要登记其实际运行态路径。禁止写绝对磁盘路径、`..` 或不存在的文件。

## 二、进度行

长流程每轮结束给出一行：

```text
[进度] 阶段=IMAGE_QA 完成=[视觉提炼,提示词,生成] 进行中=[验收] 剩余=[前端预览] 下步=检查图片尺寸和安全区 状态=执行中
```

`状态` 只能是：`执行中`、`部分完成`、`等待人工`、`已完成`、`已阻塞`。

## 三、状态语义

- `COMPLETED`：全部文件真实存在，验收通过；
- `PARTIAL`：图片已生成但缺少非关键接入信息或需要人工确认；
- `BLOCKED`：主题信息、资产目录或必要生成输入缺失；
- `FAILED`：生成或写出失败且无法继续；
- `WAIT_USER`：需要用户选择风格、补充案例事实或确认覆盖策略。

禁止把计划文件写成已完成，禁止把 `NOT_TESTED` 写成 `PASS`。
