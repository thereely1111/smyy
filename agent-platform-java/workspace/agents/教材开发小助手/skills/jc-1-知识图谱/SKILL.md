---
name: jc-1-知识图谱
description: 分析教材定位，识别全书知识点及依赖关系，输出结构化知识图谱（Markdown + JSON）。
---

## 输入

| 参数 | 路径/来源 |
|------|-----------|
| 面向对象 | `定位与需求.md`（本科/高职/中职） |
| 核心知识点 | `定位与需求.md` |
| 前置课程 | `定位与需求.md`（可能为空） |
| 后置课程 | `定位与需求.md`（可能为空） |
| 操作系统 | `定位与需求.md` |
| 实现工具/框架 | `定位与需求.md` |
| 融入思政 | `定位与需求.md`（true/false） |

> **路径（本 Skill 的输入/产出）**：**输入**文件路径由父编排在 task 中注入，是**绝对路径**（运行态根 + 产出方会话目录 + 相对路径），**原样使用**，**禁止**用相对路径去猜；**产出**一律用**相对路径**写在自己的会话空间（会话之间不共享目录），并在 `【TASK_RESULT】artifacts` 里逐文件登记（`<自身会话目录>/<相对路径>`）。完整规则见 `jc-0-路径工具`。

## 输出

| 文件 | 路径 |
|------|------|
| 知识图谱.md | `artifacts/{教材名称}/章节目录/知识图谱.md` |
| 知识图谱.json | `artifacts/{教材名称}/章节目录/知识图谱.json` |

知识图谱 = 学科知识的**有向无环图（DAG）**。节点是知识点，边是依赖关系，分组是主题域。

```json
{
  "version": "1.0", "generated_at": "YYYY-MM-DD",
  "meta": { "面向对象": "高职", "os": "win11", "tools": ["trae"], "思政_required": false },
  "themes": [
    {
      "id": "theme-A", "name": "AI产品设计基础", "order": 1,
      "knowledge_points": [
        {
          "id": "A-01", "name": "提示词工程基础", "difficulty": "基础",
          "prerequisites": [], "dependents": ["A-02"],
          "tools": ["trae"], "children": []
        }
      ]
    }
  ],
  "思政_points": []
}
```

| 字段 | 说明 |
|------|------|
| `meta.面向对象` | 本科 / 高职 / 中职 |
| `meta.os` | 操作系统 |
| `meta.tools` | 全书工具库存清单（数组） |
| `meta.思政_required` | true / false |
| `themes[].id` | 主题域编号（theme-A） |
| `knowledge_points[].id` | 知识点编号（A-01） |
| `knowledge_points[].difficulty` | 基础 / 进阶 / 高级 |
| `knowledge_points[].prerequisites` | 前置依赖 ID 列表 |
| `knowledge_points[].dependents` | 后置依赖 ID 列表 |
| `knowledge_points[].tools` | 从 `meta.tools` 选取 |
| `思政_points[].target_knowledge` | 关联的知识点编号 |

## 工作流程

### Step 0：路径规范（前置确认）

本 Skill **无需建目录**——`write_file` 自动创建多级父目录。

- **输入**（如定位文档、已有目录）：由父编排在 task 中注入**绝对路径**，原样读取，**禁止**用相对路径。
- **产出**：全部产物用**相对路径**写在自己的会话空间（`artifacts/{教材名称}/章节目录/...`），并在 `【TASK_RESULT】artifacts` 里逐文件登记（`<自身会话目录>/<相对路径>`）。主编在**产物面板**查看（面板已聚合本会话 + 子会话产物），无需写父目录。规则见 jc-0 路径工具 §1/§4。

### Step 1：解析教材定位

解析输入参数：面向对象 → 难度边界；核心知识点 → 展开范围；操作系统 → `meta.os`；工具框架 → `meta.tools`；思政要求 → `meta.思政_required`；前置课程 → 已覆盖知识点集合（不重复展开）；后置课程 → 衔接方向（不越界铺陈）。

### Step 2：识别知识点

**主题域划分**：知识点按学科逻辑的概念分组，命名使用领域术语。域内知识点 3~8 个。

**知识点粒度**：一个知识点 = 一个可独立教学的概念单元。涉及代码/API/工具操作的从 `meta.tools` 选取写入 `tools`；纯理论填 `[]`。

**知识点结构**：编号（A-01）、名称、难度（基础/进阶/高级）、前置依赖列表、后置依赖列表、工具列表。

**子知识点**：父知识点含 2+ 个共享同一核心前提的子概念时触发。编号 = 父编号-序号（A-01-1），依赖继承父节点，数量 2~6 个。

### Step 3：建立依赖关系

对每对知识点 (A, B) 四点判定：知识前提、数据前提、工具前提、逻辑顺序。依赖关系必须构成 DAG（无循环），非入口节点至少 1 个前置依赖。

### Step 4：思政融入

若 `meta.思政_required = true`，推导融入点写入 `思政_points`。若 false，空数组。

### Step 5：持久化（**分两步 + 中间 CHECK，禁止一次性 LLM 流输出整张大 JSON**）

> **重要**：经实测，dashscope 流式生成大 JSON 时会在中途 token 静默停流，整张图谱卡死。必须**先把 markdown 落地、再分批写 JSON**，避免 LLM 在一次流里输出过多 token 触发流式阈值。

#### Step 5.0 — Schema 数量预申报

在写任何文件之前，先**输出以下三行 CHECK**（不调任何工具）：

```
[CHECK] themes_count = ?
[CHECK] knowledge_points_total = ?
[CHECK] 思政_points_count = ?（0 当 meta.思政_required=false）
```

数据源自 Step 2-4 的产出。这三行作为 LLM 内部"记账"，下游 Step 5.1 / 5.3 还要复读校验。

#### Step 5.1 — 写入 知识图谱.md（**先这一步**）

把知识图谱整张用 Markdown 表述，调一次 `write_file` 写到 `<输出根>/章节目录/知识图谱.md`：

```
# 知识图谱

**教材名称**：xxx
**面向对象**：xxx | **OS**：xxx | **工具**：xxx | **思政**：true/false
**生成日期**：YYYY-MM-DD

## theme-A · AI产品设计基础
- **A-01 提示词工程基础** [基础] — 工具：trae
  - 前置：—
  - 后置：A-02
- ...

## theme-B · ...

## 思政融入点（仅 meta.思政_required=true 时列）
- A-XX：...
```

完成后在 return 里按 jc-0 路径工具 §4 在 `【TASK_RESULT】artifacts` 里逐文件登记产物路径。

#### Step 5.2 — 复读 Step 5.0 的 [CHECK] 三行

在本步输出**三行复读**（不调工具，仅 echo）：

```
[CHECK] 知识图谱.md 已写入；themes_count=?（应与 5.0 一致）, knowledge_points_total=?（应与 5.0 一致）, 思政_points_count=?（应与 5.0 一致）
```

> **真值校验**：不一致 → 输出 `[INCONSISTENT] Step 5.0 声明 X，实际落 md 为 Y，回退 Step 5.1 重写**——不要直接继续**

#### Step 5.3 — 写入 知识图谱.json（**拆 schema 分层**）

为避免 LLM 长流死锁，把 json 写入拆成**独立的两次 `write_file`**：

**Step 5.3.1**：写"骨架"——`meta` + `themes[]` 数组每一项只含 `id`、`name`、`order`、`knowledge_points_count`（不展开具体知识点）

```json
{ "version": "1.0", "generated_at": "YYYY-MM-DD",
  "meta": { "面向对象": "...", "os": "...", "tools": [...], "思政_required": false },
  "themes": [
    { "id": "theme-A", "name": "...", "order": 1, "knowledge_points_count": 5 },
    { "id": "theme-B", "name": "...", "order": 2, "knowledge_points_count": 4 }
  ],
  "思政_points": []
}
```

完成后在 `【TASK_RESULT】artifacts` 登记 `<自身会话目录>/artifacts/{教材名称}/章节目录/知识图谱.json`（注意此时 json 还不完整，**这是预期的，不要在此步就交付**）。

**Step 5.3.2**：再用一次 `write_file` **覆盖**同一文件，**写完整 json**（含每个 knowledge_points 的全部字段：id/name/difficulty/prerequisites/dependents/tools/children）。同一文件多次 write_file 是允许的，最后一次为准。

完成后 return `【TASK_RESULT】artifacts<运行态根>/<自身会话目录>/artifacts/{教材名称}/章节目录/知识图谱.json (完整版)`。

> **为什么这样拆**：5.3.1 体积小（约 50 行内）不会触发流式阈值；5.3.2 看着大但 LLM 已在 5.3.1 写过一遍 schema，第二次生成时 schema 已"热缓存"，流式中断概率显著下降。如 5.3.2 真卡住，至少 5.3.1 骨架 + 5.1 md 已落库，可重启只补 5.3.2。

#### Step 5.4 — 终检

输出三行：

```
[CHECK] themes_count = ?（应与 5.0 一致）
[CHECK] knowledge_points_total = ?（应与 5.0 一致；含 children）
[CHECK] 思政_points_count = ?（应与 5.0 一致）

【TASK_RESULT】artifacts
shared:
  - <运行态根>/<自身会话目录>/artifacts/{教材名称}/章节目录/知识图谱.md
  - <运行态根>/<自身会话目录>/artifacts/{教材名称}/章节目录/知识图谱.json
summary: 共享知识图谱 2 个文件
```

> 父编排硬约束「产物回写与结果规范化」：上面这 5 行 `【TASK_RESULT】artifacts` 段是父编排定位产物的唯一合法锚点，**禁止父编排自行拼路径**。
## 核心约束

1. **职责边界**：只回答"学科知识结构是什么"，不预设目录层级、不设计案例
2. **DAG 无环**：依赖关系必须构成有向无环图
3. **域内一致**：同主题域知识点共享一个教学子目标
4. **工具来源**：知识点 `tools` 必须从 `meta.tools` 选取
5. **Schema 铁律**：禁止臆造字段
6. **前置课程不重复**：前置课程已覆盖的知识点不重复展开，仅保留"应用衔接"所需的最小认知点（如概述、在本书工具场景下的应用能力）
7. **后置课程不越界**：后置课程将承载的内容不在本书铺陈，仅保留后置课程所需的接口/概念衔接

## 修改模式

收到 `[修改指令]` 时：仅修改指出的问题点，逐条对照修正，同步更新知识图谱.md 和知识图谱.json。
