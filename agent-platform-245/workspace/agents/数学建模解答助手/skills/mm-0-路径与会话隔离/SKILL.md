---
name: mm-0-路径与会话隔离
description: 数学建模解答助手路径与脚本调用的唯一权威来源。定义运行态根与自身会话目录的读取位置、产物相对路径写法、跨会话绝对路径读法、execute 工作目录与 Skill 脚本路径的探测方法。所有子智能体（题目解析／分析动画／解题代码）在读写文件、调用脚本、登记产物前必须先遵循本规范。
---

# mm-0-路径与会话隔离

路径约定的**唯一权威来源**。其它文件（AGENTS.md、子智能体定义、其它 Skill）一律引用本文，不得复制或改写规则。

> **先读 `AGENTS.md` 的「本机环境事实」一节**：shell 的 `PATH`／`HOME` 缺失是平台**刻意隔离**，属预期行为，不要试图"修复"，也不要据此判定"环境损坏／被污染／是上一台机器的残留"。文档／记忆里出现过的历史盘符或用户名一律忽略（那只是**举例**，不是本机事实）。

## 一、两个值从哪来（不要猜、不要 echo）

| 值 | 读哪里 | 形如 |
|---|---|---|
| **运行态根** | 自己 system prompt 的 `## Workspace` 段，`Project (…)` 行给出的绝对路径 | `…/workspace/runtimes/<agentId>` |
| **自身会话目录** | 自己 system prompt 的 `## AgentStateStore Context` 段，`AgentStateStore ID:` 行 | 主编 `main-<uuid>` / 子智能体 `sub-<uuid>` |

这两个值框架在每个会话的 system prompt 里都给出。**不要**执行 `echo`，不要从别处抄示例值，不要用 `${WORKSPACE_ROOT}` / `${SESSION_ID}` —— 平台**不存在**这两个占位符，写了只会得到字面量目录名，谁都找不到。

## 二、两类写法（只有两种）

| 用途 | 写法 | 例子 |
|---|---|---|
| **读**（含跨会话输入） | **绝对路径** = 运行态根 + 产出方会话目录 + 相对路径，**原样使用** | `<运行态根>/sub-1a2b3c4d/analysis/input_digest.md` |
| **写**（自己的产物） | **相对路径** | `animation/01_全局问题分析.html` |

- 相对路径由文件工具自动拼到**本会话空间**，即 `<运行态根>/<自身会话目录>/<相对路径>`；`write_file` 自动建多级父目录，**不需要 mkdir**。
- **禁止**把产物写进父会话或其它会话目录；**禁止**用相对路径去读别人的产物（会落到自己空间，误判为不存在）。
- 跨会话路径里的「产出方会话目录」段来自**产出方自己的汇报**（`【TASK_RESULT】artifacts`）。父编排**原样转发**，不要自己拼、不要缩写、不要改成相对路径。

## 三、execute 的工作目录（最容易踩）

`execute` 的工作目录**就是你的会话目录**（`SMYY_SESSION_DIR` 给出的绝对路径），与文件工具里相对路径的基准一致。因此：

- 脚本里的**相对路径**直接落在自己的会话里（`python solve.py`、`savefig('figs/a.png')` 都是对的）；
- 要读另一会话的文件（父编排转发来的子会话产物）时，用产出方给出的**绝对路径**；
- 脚本写出的文件等同于你写的产物，**必须**按第五节登记进 `artifacts`；
- `cd` 只在自己的会话内用，**不要**去碰运行态根或别的会话目录（越界会被文件系统直接拒绝）。

## 四、Skill 脚本路径与解释器（首次使用必做）

### 4.1 找脚本：用文件工具，不要依赖 shell 命令

**执行环境一句话**：平台的 `execute` 跑在**平台所在的那台机器**上，环境被剥到只剩一个固定的 `PATH`（`/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin`，`inheritEnv=false`）。宿主是 **Windows** 时 shell 是 cmd.exe（`ls`/`find`/`grep`/`where` 不可用）；宿主是 **Linux/macOS** 时 shell 是 `sh -c`（这些命令可用，但 `python` 仍不在 `PATH` 上）。

两种宿主的**共同结论只有一条**，记住它就够了：**查路径一律用文件工具，跑脚本一律经 `<RUN>`**。不要为差异写两套流程。

查路径要用**文件工具**——它们走 overlay 文件系统，不受 shell 限制：

- 首选 `glob_files(pattern="**/render_animation.py")`，命中即得脚本目录；
- 或 `list_files("skills")`（定义区的脚本目录，文件工具按相对路径即可取到）。

**实测命中路径**：`<运行态根>/.harness/skills/mm-2-Web动画实现规范/scripts`。平台把定义区的 skills 物化到 `<运行态根>/.harness/skills/`，并把该目录的绝对路径注入为环境变量 **`SMYY_SKILLS_DIR`**。`execute` 的 cwd 是自己的**会话目录**（不再是运行态根），所以调脚本一律用 `"$SMYY_SKILLS_DIR/…"`（POSIX）或 `"%SMYY_SKILLS_DIR%\…"`（Windows），或用 `glob_files` 命中的绝对路径；**不要**再写 `.harness/skills/...` 这种相对写法（它相对的是会话目录，不存在）。

- `glob_files` 可能同时命中定义区（`agents/<agent>/skills/…`）与物化副本（`.harness/skills/…`）两处：**shell 用物化副本那条**（`SMYY_SKILLS_DIR` 之下，可执行），定义区那条只读、给文件工具读内容用。两条指的是同一份内容，**不要因为一条读不到就停下或改写脚本**。
- 命中后记为 **`MM2_SCRIPTS`**（该目录含 `run.cmd`、`run.sh`、`render_animation.py`、`probe_inputs.py`、`prepare_input.py`、`install_deps.py`、`modules/`）；`MM2_ACCEPT` 同理用 `glob_files(pattern="**/check_animation.py")` 找。
- **路径模板一律用 `/`**：cmd.exe、Windows 上的 Python `pathlib`、Java 的 `Path.resolve` **都接受正斜杠**，所以 `"<运行态根>/<自身会话目录>/analysis/scene_map.json"` 这种写法两边通用。**唯一的例外是 `run.cmd` 这个可执行文件自身的调用路径**（`<MM2_SCRIPTS>\run.cmd`），那里保留 `\`。
- **`MM2_SCRIPTS` 是三个子智能体共用的工具目录**：题目解析智能体用它跑 `prepare_input.py` / `probe_inputs.py`（输入整备与盘点），分析动画智能体用它跑 `render_animation.py`（渲染），解题代码智能体用它借解释器跑自己的静态检查。谁都不需要各自复制一份脚本。
- 两个值**本轮内复用**，不要每一步重新探测。
- 一个都找不到 → `[HITL_REQUIRED]` 并把 `glob_files` 原始结果贴出来，**不要**改去重写脚本或猜路径。

### 4.2 跑脚本：一律经 `<RUN>`

宿主上往往有多个解释器且依赖各不相同，`python` 也不在 `PATH` 上。统一用包装脚本当入口，它会自动挑一个已装渲染依赖的解释器。

**`<RUN>` 是本仓库文档的宿主相关占位符，定义只在本文这一处**（其余文档只引用、不复述）。按宿主二选一，**其余参数一字不改**：

| 宿主 | `<RUN>` 展开为 | 例 |
|---|---|---|
| Windows | `<MM2_SCRIPTS>\run.cmd` | `<MM2_SCRIPTS>\run.cmd probe_inputs.py --problem "D:/A题" --out-dir "…"` |
| Linux / macOS | `sh <MM2_SCRIPTS>/run.sh` | `sh <MM2_SCRIPTS>/run.sh probe_inputs.py --problem "/data/A题" --out-dir "…"` |

**判宿主（一次就够，本轮内复用，别每步重判）**：跑 `ls "$SMYY_SKILLS_DIR"` 并**只看退出码**——退出码 0 是 POSIX 宿主（用 `sh …/run.sh`），非 0 且提示「不是内部或外部命令」/「command not found」是 Windows 宿主（用 `…\run.cmd`）。

> 判据为什么是 `ls` 而不是 `dir /b`：`dir` 在 **GNU coreutils 里也是真实命令**（≈ `ls -C -b`），Linux 上 `dir` 能跑、只有带 `/b` 时才报错——所以「能跑 `dir /b` 就是 Windows」必须**判退出码**才成立，这个前提太容易在传抄中丢掉。`ls` 在 Windows cmd 里没有任何同名命令，不会假阳性。判错的表现只是「命令找不到」，改过来即可，**不必重开会话、不必改写脚本**。

- 首次运行若提示缺依赖 → 跑 `<RUN> install_deps.py` 补齐；只想看缺什么就加 `--check-only`。
- **`python_path.txt`**（放在 `scripts/` 下，一行，优先级最高；不生效或路径无效时自动回退候选探测）在两平台语义不同：
  - **Windows**：必须是**已存在文件的绝对路径**（如 `D:\Python311\python.exe`）——`run.cmd` 用 `if exist` 校验，写命令名无效。
  - **Linux/macOS**：绝对路径与命令名都可以（`run.sh` 用 `-x` / `command -v` 校验）。
  - **推荐一律写绝对路径**：`execute` 的 `PATH` 是固定的那一串，venv 不在里面；写 `/usr/bin/python3` 会命中系统解释器（通常没装渲染依赖），表现是「跑起来了但结果不对」。
  - 它是**机器专属**文件（已在 `.gitignore`），**绝不要在两台机器之间拷**——换机器第一件事是重写它。
- 包装脚本也失败时，才回退到"手工指定解释器绝对路径"，并把 `--version` 与原始报错一并上报。

**占位符规则**：文档里出现的 `<运行态根>`、`<自身会话目录>`、`<MM2_SCRIPTS>`、`<MM2_ACCEPT>`、`<RUN>` 都表示**运行时填入的真实值**，禁止把尖括号原样传给工具。

### 4.3 禁止对二进制文件用 `read_file`（2026-09-29 实测，会毁掉整个会话）

`read_file` **只读文本、没有二进制分支**——对 PDF／xlsx／docx／png 调用它，返回的是**整个文件的 base64 原文**。

**后果比"读不出来"严重得多**：若该文件是后台异步读完的，结果会以「后台工具完成通知」的形式回来，消息类型是 **`hint` 而不是 `tool_result`** —— 于是两条兜底机制**同时失效**：

- `ToolResultEviction`（大结果转存 `large_tool_results/`）只处理 `tool_result`，**不管 `hint`**；
- 压缩摘要也覆盖不到它。

实测：对一份 **501 KB 的题目 PDF** 调 `read_file`，产生了一条 **737,970 字符**的 `hint` 消息，永久驻留 `context`，把会话状态从 7.8 万字符撑到 **81.7 万字符**（占 91%），随后状态持久化 CAS 失败、`Failed to get versioned state`、**SSE 流被掐断，前端从此冻结**——整轮 run 白跑。

**铁律**：

| 文件类型 | 正确做法 |
|---|---|
| PDF（题面） | `probe_inputs.py`（它抽正文文本 + 渲染页码图）；公式用 `read_image` 看页码图 |
| xlsx／docx（附件） | `probe_inputs.py` 盘点出 `facts.json` 与 `input_digest.md`，读那两个 |
| png（页码图／图表） | `read_image` |
| 其它二进制（`.mat`／`.npy`／`.zip`…） | 写 Python 脚本经 `<RUN>` 读，只输出需要的字段 |

`read_file` 只用于**确知是纯文本**的文件：自己刚写的 `.md`／`.json`／`.py`／`.log`。**拿不准是不是文本就不要读**——先 `glob_files`／`list_files` 看扩展名，或者直接走脚本。

## 五、产物与登记

- 建议的会话内目录（相对路径）：`input_manifest.json`、`input/`（整备后的题目目录）、`analysis/`（盘点摘要、`facts.json`、页码图与 scene_map）、`truth/`（问题契约与模型路线）、`animation/`（分档 HTML 与质量报告）、`qa/`、`logs/`。
- **`facts.json` 的位置由 `probe_inputs.py --out-dir` 决定，没有固定值**：实测落在 `analysis/`（不是 `truth/`）。`facts.json`／`input_digest.md`／`pages/` 与 `--out-dir` 同目录，消费方一律用父任务注入或产出方 `artifacts` 登记的实际路径，**不要按文件名假定目录**。
- 交付给用户看的东西一律放 `animation/`，它是唯一"可交付"目录；`qa/`、`logs/` 是审计用。
- 每个真实产出的文件都要逐条登记到 `【TASK_RESULT】artifacts`，写法见 `mm-0-产物交接协议`。

---

## 附：维护者须知（改这两个包装脚本前必读，与运行时无关）

- **`run.cmd` 必须保持纯 ASCII + CRLF 行尾**：cmd.exe 按系统 OEM 码页（中文 Windows 是 GBK）读批处理，UTF-8 中文注释会被解成乱码碎片并当命令执行；LF 行尾会让 `goto`/标签解析出错。
- **`run.sh` 必须保持纯 ASCII + LF 行尾**。⚠ 本仓库 `core.autocrlf=true`——Windows 工作树里的 `run.sh` 是 **CRLF**，而 git blob 是 LF。**Linux 侧必须用 `git clone`/`git archive` 取文件**；若走工作树拷贝（scp/rsync/zip），**先 `dos2unix`**，否则 `HERE=$(cd "$(dirname "$0")" && pwd)` 的赋值尾部会粘上 `\r`，导致 `python_path.txt` **被静默忽略**、候选解释器路径全部解析失败，最后只报一句含糊的退出码 3。
- **两个包装脚本在「数学建模解答助手」与「数模动画生成助手」下必须逐字节相同**；改一处必须同步另一处。
- git 里 `run.sh` 的 mode 是 **100644（没有执行位）**，所以文档与命令模板一律写 **`sh run.sh`**，**不要写 `./run.sh`**（会 permission denied）。要补执行位：`git update-index --chmod=+x <path>`（Windows 上 `core.fileMode=false`，本机 `chmod` 不会被记录）。
