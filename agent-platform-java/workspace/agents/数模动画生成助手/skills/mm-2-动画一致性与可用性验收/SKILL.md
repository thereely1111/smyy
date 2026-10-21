---
name: mm-2-动画一致性与可用性验收
description: 对已渲染的分档动画 HTML 做静态验收（占位符、内嵌 JSON、分档自洽、结构密度、质量阈值），并给出必须由人工完成的视觉与交互确认清单。没有浏览器能力时一律标 NOT_TESTED，不得声称已通过视觉验收。
---

# mm-2-动画一致性与可用性验收

验收分两半：**能自动判的**交给脚本，**判不了的**明确标 `NOT_TESTED` 并交人工。这条边界不能含糊——把没看过的页面说成"已验收"是本环节最严重的错误。

## 一、静态验收（自动）

```
<RUN> "<MM2_ACCEPT>/check_animation.py" --animation "<运行态根>/<自身会话目录>/animation" --out "<运行态根>/<自身会话目录>/qa/qa_animation.json"
```

- 两个值的探测方法见 `mm-0-路径与会话隔离` 第四节（用 `glob_files`／`list_files`，不要依赖 shell 命令）。
- `check_animation.py` 自身无第三方依赖，用 `<RUN>` 借它的解释器挑选即可；包装脚本接受绝对路径的脚本参数。
- `--quality` 缺省自动取产物目录内的 `quality_report.json`。

| 检查项 | 判据 | FAIL 时怎么修 |
|---|---|---|
| F1 分档文件 | 至少 1 份，每份 ≥ 1000 字节 | 回渲染：产物异常小说明模板未填充 |
| F2 占位符 | 无 `__TITLE__` / `__STEPS_JSON__` 残留 | 回渲染：模板未替换，检查模板是否被改坏 |
| F3 内嵌 JSON | `<script id="DATA">` 可 `json.loads` | 回场景映射：多为 LaTeX 转义或 `</script>` 逃逸问题 |
| F4 分档切换 | 每份含 `splitnav`；`deck.files` 与目录实际文件一致 | 回渲染：档名与文件名不同步 |
| F5 结构 | 每档有幕、每幕有块、块类型合法、无禁用开场词 | 回场景映射改内容 |
| F6 质量阈值 | `parts ≥ 2`、`scenes ≥ 4`、`blocks ≥ 12`、`chart_points > 0` | 回场景映射补幕/补可视化 |
| F6b 内容完整性 | `quality_report.warnings` 逐条带出 | 回场景映射补公式/补数据，**不要删 warning** |
| F7 视觉与交互 | 永远 `NOT_TESTED` | 交人工，见第二节 |

退出码：0 = 无 `FAIL`（可含 `NOT_TESTED`）；1 = 存在 `FAIL`。**有 FAIL 不得交付**，回对应阶段修，同类错误最多 3 轮，之后 `[HITL_REQUIRED]`。

## 二、人工确认清单（转给用户，不要代答）

在交付前把这份清单原样给用户，请其打开 `animation/01_*.html` 逐项确认：

1. 逐幕翻页是否正常，`自动讲解` 是否按拍露出、`重播` 是否复位；
2. 顶栏分档切换能否在各问之间跳转且当前档高亮正确；
3. 图表交互：拖 `slider` 数值是否联动曲线、图上拖动是否反向更新数值、悬停是否读出曲线值；
4. `pills` 切换是否在同 canvas 内换图、`matrix` 点格子是否高亮对应数据、`svgclick` 点节点是否联动右侧说明；
5. 手机宽度（约 375px）下是否无裁切、无横向滚动条；
6. 内容层面：有没有把示意数字当成实测结论、缺数据处是否已标注。

用户确认后把结论写进 `qa/qa_animation.json`（追加 `human_review` 段），未确认前状态只能是 `ANIMATION_REVIEW_PENDING`。

## 三、状态与汇报

- 验收结果按 `mm-0-产物交接协议` 回报：`status=COMPLETED` 仅当静态 0 FAIL **且**人工确认完成；否则 `PARTIAL`（静态过了、人工待确认）或 `BLOCKED`（有 FAIL 且 3 轮未修好）。
- `artifacts` 必须含 `qa/qa_animation.json`；交付页 `animation/01_*.html` 一并登记。
- 禁止在汇报里写"视觉验收通过""交互正常"之类未经确认的表述；`NOT_TESTED` 就是 `NOT_TESTED`。
