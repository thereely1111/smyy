---
name: mm-2-Web动画实现规范
description: 把 scene_map.json 渲染成分档分析动画 HTML 的唯一实现规范。说明 render_animation.py 的调用方式、结构闸门与退出码语义、真实附件数组的注入路径、依赖安装，以及本目录脚本与上游项目的同步规则。脚本不调用任何 LLM。
---

# mm-2-Web动画实现规范

渲染是**确定性**的：同一份 `scene_map.json` + 同一份 `facts.json` 永远得到同一份 HTML。模型只负责场景内容，渲染由脚本完成——不要在提示词里手写 HTML 顶替这一步。

## 一、脚本清单（均在 `MM2_SCRIPTS` 下，探测方法见 `mm-0-路径与会话隔离` 第四节）

| 脚本 | 用途 | 是否调用 LLM |
|---|---|---|
| `install_deps.py` | 安装/检查依赖（`--check-only` 只检查） | 否 |
| `prepare_input.py` | 把平台上传的平铺文件整理成「题目目录 + `附件/`」结构（只复制归类，不改内容） | 否 |
| `probe_inputs.py` | 盘点题目与附件、算事实、渲染页码图（见 `mm-2-分析到场景映射`） | 否 |
| `render_animation.py` | 清洗 → 结构闸门 → 注入真实数组 → 模板填充 → 写分档 HTML | 否 |
| `modules/` | 上游模块（`deck_render` / `deck_enricher` / `data_facts` / `doc_parser`） | 否 |
| `templates/teaching_template.html` | 分档动画模板（`__TITLE__` / `__STEPS_JSON__` 两个占位符） | — |

## 二、渲染

```
<RUN> render_animation.py --scene-map "<运行态根>/<自身会话目录>/analysis/scene_map.json" --problem "<题目目录或题目 PDF 的绝对路径>" --facts "<probe_inputs.py 的 --out-dir 下的 facts.json 绝对路径>" --out-dir "<运行态根>/<自身会话目录>/animation"
```

- `--facts` **必须显式传入** `probe_inputs.py` 产出的那份，保证与场景映射用的是同一批事实。它的位置**由 `probe_inputs.py --out-dir` 决定，不固定在 `truth/` 下**——实测落在 `analysis/facts.json`。路径取父任务注入或上游 `artifacts` 登记的实际值，别按文件名猜目录。
- `--facts` 缺省时脚本会从题目附件现算。**注意这条回退不会报错**：路径写错时会静默走"现算"，得到的事实与 scene_map 引用的键名可能对不上——所以写完命令先确认该文件真实存在（`glob_files` 或 `list_files`）。
- `--title` 可覆盖标题；缺省用 `scene_map.title`。
- `--deterministic` 不用 scene_map，直接由题面与事实生成一版可审计产物（**链路自测**用，不是交付物）。

产出（均在 `--out-dir`）：

| 文件 | 内容 |
|---|---|
| `NN_<档名>.html` | 分档 HTML，每档一份，顶栏可切换；`01_` 必须是全局档 |
| `quality_report.json` | 分档/幕/块/图表点数与内容完整性 `warnings` |
| `_generation_manifest.json` | 输入哈希（题面 PDF、scene_map）+ 附件清单 + 输出清单 |

## 三、退出码与修错

| 退出码 | 含义 | 动作 |
|---|---|---|
| 0 | 渲染成功（可能有 `warnings`） | 进验收；`warnings` 如实带回，不要隐藏 |
| 1 | **结构闸门**未通过 | stderr 会逐条列出 `structure_errors`，按条目改 `scene_map.json` 后重跑 |

闸门会拦这些（比 `references/scene_schema.md` 的契约更严）：

- 首档不是 `全局问题分析`、或只有一档；
- 某一幕 `blocks` 少于 3 个、缺可视化或小切换；
- `chart` 没有可用数据系列（`spec` 为空或点数 < 2）、`slider` 没有 `id`、`svgclick` 少于两个节点；
- 出现禁用开场词（题目原文 / 题面原文 / 赛题原文 / 原题全文 / 封面 / 内容概览）。

闸门报错时**改 scene_map，不要改闸门、不要改模板、不要手写 HTML**。注意：结构类的缺失（少幕、少块、档名不对）会被 `deck_enricher` 自动补齐，能落到闸门上的基本是**内容问题**，必须回场景映射修。

## 四、依赖与环境

- **`python` 不在 PATH 上**：平台给 shell 的 `PATH` 被固定成 `/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin` 且 `inheritEnv=false`，所以**两种宿主**上 `python` / `py` 都解析不到（Linux 上 `/usr/bin/python3` 能解析到，但那是系统解释器、通常没装渲染依赖）。因此**不要直接敲 `python xxx.py`**，一律走 `<RUN>`（定义见 `mm-0-路径与会话隔离` §4.2）：它按候选清单探测解释器，优先挑已装齐依赖的那个。
- **shell 视宿主而变**：Windows 上是受限 cmd.exe（`find`/`ls`/`where`/`grep`/`bash` 不可用，列目录用 `dir /b`）；Linux/macOS 上是 `sh -c`（都可用）。**所以查路径一律用文件工具 `glob_files`／`list_files`**，不要写依赖某一侧 shell 的探测命令。
- 依赖：`python-docx`、`openpyxl`、`pymupdf`、`latex2mathml`（`pypdf` 仅在缺 `pymupdf` 时才需要，见 `requirements.txt`）。
- 补齐依赖：`<RUN> install_deps.py`；只想看缺什么就加 `--check-only`。
- Python 3.8+；脚本自行把 stdout/stderr 重设为 UTF-8（`reconfigure`），**不依赖宿主 locale**。
- `execute` 的工作目录是**运行态根**（不是会话目录），所以路径参数一律写全。

## 五、与上游项目的同步规则

本目录的 `modules/*.py` 与 `templates/teaching_template.html` 来自上游项目（`实训书动画生成`），**逐字保留**，不得就地改写；`scripts/*.py` 是平台包装层，可以改。

同步上游新版时：

1. 覆盖 `modules/doc_parser.py`、`modules/data_facts.py`、`modules/deck_enricher.py` 与 `templates/teaching_template.html`（这三个模块无项目内依赖，可直接覆盖）；
2. `modules/deck_render.py` 来自上游 `animation_generator.py`，覆盖后**必须重做三处平台适配**（文件头部有注释标明）：
   - `from openai import OpenAI` 改为可选导入（平台内模型由 harness 提供，渲染链路不依赖 openai）；
   - `import config` 改为可选导入（无 `.env` 也能渲染）；
   - `TEMPLATE_DIR` 指向本 Skill 的 `templates/`（上游是 `prompt/`）。
3. 覆盖后自测（见下），通过再交付。

## 六、链路自测（首次部署或同步上游后必做）

三步依次跑：**先**检查解释器与依赖，**再**用真实题目跑一遍确定性渲染（确认模板与模块能跑通），**最后**过验收脚本（`check_animation.py` 在验收 Skill 下，把 `<MM2_ACCEPT>` 的绝对路径传给 `<RUN>` 即可）：

```
<RUN> install_deps.py --check-only

<RUN> render_animation.py --problem "<某道真实题目目录的绝对路径>" --out-dir "<运行态根>/<自身会话目录>/animation/_selftest" --deterministic

<RUN> "<MM2_ACCEPT>/check_animation.py" --animation "<上一步的产物目录>"
```

> 上面三行**故意不加行内注释**：注释得用 `rem`（cmd）或 `#`（sh），两者互不通用——写进代码块会让另一侧执行失败。步骤说明一律写在代码块外面。

自测判定：渲染退出码 0、产出 ≥2 份 HTML、验收无 `FAIL`。自测产物放 `_selftest/`，**不登记为交付物**。

## 七、禁止事项

- 不用 `--deterministic` 的产物冒充正式交付（它没有模型写的公式与讲解）。
- 不修改 `templates/teaching_template.html` 来绕过闸门（模板是渲染出口，改它会破坏所有档）。
- 不把 `warnings` 当噪声丢掉：它们指向"该有公式/数据的地方缺了内容"，属于要回场景映射修的问题。

---

## 附：维护者须知（同步上游 / 改脚本前必读，与运行时无关）

- 本目录的**上游同步规则**见第五节；`modules/` 与 `templates/` 是上游逐字保留物，平台侧只做那三处适配。
- `run.cmd` 必须保持**纯 ASCII + CRLF**、`run.sh` 必须保持**纯 ASCII + LF**（理由与 `core.autocrlf` 的坑见 `mm-0-路径与会话隔离` 末尾的「维护者须知」）。
- 两个 Agent 下的 `scripts/` 是**各自独立的副本**，改一处必须同步另一处。**必须逐字节相同**的是：`run.sh`、`run.cmd`、`install_deps.py`、`prepare_input.py`、`modules/*`、`requirements.txt`、`templates/teaching_template.html`（另一 Agent 的 `check_animation.py` 在验收 Skill 下，同理）。
  **唯二允许不同**的是 `probe_inputs.py` 与 `render_animation.py` 的**头部 docstring 各一行**——那里写着「模型推理发生在『XX 智能体』自己身上」，两个 Agent 的子智能体名本来就不一样。除这一行外也必须相同。
