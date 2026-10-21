---
name: jc-0-知识库检索
description: 知识库 API 封装层。为 jc-1-目录生成、jc-2-资料查找、jc-2-来源核查 和 jc-2-数据准备 提供 KB 检索、原文下载与数据拉取能力。AI搜索优先、传统搜索兜底。不直接产出章节文件。
---
## 职责

底层通用封装 Skill，被 `jc-1-目录生成`、`jc-2-资料查找`、`jc-2-来源核查` 和 `jc-2-数据准备` 使用。本 Skill 是唯一直接操作知识库 API 的环节，所有 KB 检索、原文下载和数据集拉取均由此处封装的脚本完成。

## 输入

由调用方传入搜索词和 scope（知识库空间）：

| scope         | 知识库     | 用途                   | 调用方        |
| ------------- | ---------- | ---------------------- | ------------- |
| `knowledge` | 学科知识库 | 技术知识点检索（默认） | jc-2-资料查找 |
| `case`      | 案例库     | 案例/示例检索          | jc-1-目录生成 |
| `dataset`   | 数据库     | 数据集拉取             | jc-2-数据准备 |
| （不限）    | 三空间     | KB 原文下载（fetch_entry.py） | jc-2-来源核查 |

返回命中条目列表（含 URL、entry_id、摘要等），配套下载由调用方按需处理。

> **路径（本 Skill 的输入/产出）**：**输入**文件路径由父编排在 task 中注入，是**绝对路径**（运行态根 + 产出方会话目录 + 相对路径），**原样使用**，**禁止**用相对路径去猜；**产出**一律用**相对路径**写在自己的会话空间（会话之间不共享目录），并在 `【TASK_RESULT】artifacts` 里逐文件登记（`<自身会话目录>/<相对路径>`）。完整规则见 `jc-0-路径工具`。

## 输出

仅返回检索结果，不直接产出章节文件。配套下载由调用方（来源核查/数据准备）执行。

## 执行方式

> ⛔ 所有 KB 检索必须通过 `execute_command` 执行 Python 脚本。

### 技术知识点检索

```bash
cd "{Skill目录}/scripts" && python search_kb.py "知识点1" "知识点2" ...
# 默认 scope=knowledge（学科知识库）
```

### 案例检索

```bash
cd "{Skill目录}/scripts" && python search_kb.py "案例名" --scope case
```

### 数据集拉取

```bash
cd "{Skill目录}/scripts" && python pull_datasets.py "数据集名"
```

### KB 原文件下载（来源核查用）

```bash
# 下载知识库条目原文件（保留原始格式），默认顺带提取原文图片
cd "{Skill目录}/scripts" && python fetch_entry.py --fulltext <entry_id1> <entry_id2> ... --out <输出目录>

# 只下载原文，不提取图片
cd "{Skill目录}/scripts" && python fetch_entry.py --fulltext <entry_id1> ... --out <输出目录> --no-extract-images

# 获取案例条目原文件 + 元数据
cd "{Skill目录}/scripts" && python fetch_entry.py --case <entry_id> --out <输出目录>

# 获取案例 + 下载配套文件
cd "{Skill目录}/scripts" && python fetch_entry.py --case <entry_id> --out <输出目录> --download-assets
```

#### 图片提取与索引（默认开启）

`fetch_entry.py --fulltext` 下载原文后，**默认顺带提取原文中的图片**：

| 原文类型 | 图片提取方式 | 说明 |
|---------|-------------|------|
| page (.md) | `extract_markdown_images()` 提取 `/kb_files/{file_id}` → `download_image()` 下载 | 图片是独立 file_id |
| file (.docx) | 解 zip 提取 `word/media/*` | 图片内嵌在文档里 |
| file (.pptx/.ppt) | 不提取图片 | — |
| file (.pdf) | pdfplumber / PyPDF2 提取内嵌图 | 依赖 pdfplumber，缺失时降级 |

产出（在 `--out` 目录下）：

```
{输出目录}/图片/KB图片/
  ├── kb_{entry_id短id}_01.png     # 提取出的图片
  ├── kb_{entry_id短id}_02.jpg
  └── 索引.json                      # 图片索引（entry_id → 图片列表 + context）
```

索引.json 结构：

```json
[
  {
    "entry_id": "xxx",
    "title": "条目标题",
    "source_type": "docx",
    "images": [
      {"file": "KB图片/kb_xxx_01.png", "context": "图片附近的原文文字"}
    ]
  }
]
```

> `context` 字段记录图片在原文中附近的文字（page 类型取 markdown 图片 alt 文本；file 类型取原文件名），供 jc-3 阶段判断"这张图是否满足当前正文需求"。

### 检索结果解析

脚本输出格式：

```
✅ KB命中 [知识点名] entry_id=xxx
- 来源：[知识库] 标题，https://lexiangla.com/pages/{entry_id}
  entry_id: {entry_id}
  摘要：{内容摘要}

❌ 待外部来源 [知识点名] 来源=ai/keyword/none
```

> **调用方**（jc-2-资料查找、jc-2-来源核查）必须将 `✅ KB命中` 的结果完整写入资料来源文档，不得遗漏。`❌ 待外部来源` 的条目：默认（仅知识库模式）**禁止 web_search 补充**，标注 `⚠️ KB未覆盖，需人工补充`；仅当父任务注入「资料来源模式=知识库+互联网」时才可通过 web_search 补充。

## 脚本

| 脚本                         | 用途                                                                |
| ---------------------------- | ------------------------------------------------------------------- |
| `scripts/search_kb.py`     | 知识点/案例检索（AI搜索优先，关键词兜底），支持`--scope` 限定空间 |
| `scripts/pull_datasets.py` | 数据集下载                                                          |
| `scripts/fetch_entry.py`   | KB 条目原文件下载：page→.md / file→原格式 / smartsheet→.csv + 案例配套下载 + 图片提取与索引（来源核查用）        |
| `scripts/lexiang_api.py`   | KB API 核心封装（内部调用）                                         |
| `scripts/git_download.py`  | KB 条目关联的 git 仓库 clone（内部调用）                            |
| `scripts/config.json`      | API 密钥、三空间配置                                                |

### API 方法选择指南

> ⛔ `get_entry_content()` 仅支持 `page` 类型条目（走 blocks/children API）。对于 `file` 类型（.docx/.pdf/.xlsx 等）会返回空。
>
> ✅ **原文件下载请统一使用 `download_file()`**，它会自动根据 `entry_type` 路由：
> - `page` → 获取 markdown 内容 → 保存为 `.md`
> - `file` → 通过预签名 URL 下载原文件（保留 .docx/.pdf/.xlsx 等原始格式）
> - `smartsheet` → CSV 导出
> - `folder` → 不处理（请用 `download_folder`）
>
> `fetch_entry.py` 的 `fetch_case()` 和 `fetch_fulltext()` 已统一使用 `download_file()`。
>
> ✅ **如需提取文本**（知识准备阶段按需使用），用 `get_entry_text()`：
> - `page` → content API（markdown）
> - `file` → 下载 + 按扩展名解析文本（支持 docx/pdf/pptx/xlsx/csv/txt 等）
> - `smartsheet` → CSV 导出
> - `folder` → 返回空

## 约束

- **必须通过 `execute_command` 执行脚本**，不得绕过
- Token、异常、限频等均由脚本内部处理
- 检索按 scope 自动路由到对应知识库空间（knowledge / case / dataset）
- `download_kp_assets` 自动判断条目类型：关联 git 仓库（父文件夹含 `git_info`）→ clone 仓库；KB 内置文件 → 直接下载子文件
- `--scope case` 时搜索词必须用案例清单中的 `case_name` 完整值，不准截断或泛化
- `fetch_entry.py --case` 默认只拉全文，需加 `--download-assets` 才会下载配套代码/数据
