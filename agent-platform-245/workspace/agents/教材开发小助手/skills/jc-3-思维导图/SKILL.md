---
name: jc-3-思维导图
description: 为项目式、项目任务式体例生成章节思维导图。产出 .xmind（可编辑）和 .png（插入 Word）。
---

## 输入

| 参数 | 路径 |
|------|------|
| 目录结构.json | `artifacts/{教材名称}/章节目录/目录结构.json` |
| chapter_id | 目标章节在 `目录结构.json` 中 `chapters[].id` 的值（如 `ch03`） |

> **路径（本 Skill 的输入/产出）**：**输入**文件路径由父编排在 task 中注入，是**绝对路径**（运行态根 + 产出方会话目录 + 相对路径），**原样使用**，**禁止**用相对路径去猜；**产出**一律用**相对路径**写在自己的会话空间（会话之间不共享目录），并在 `【TASK_RESULT】artifacts` 里逐文件登记（`<自身会话目录>/<相对路径>`）。完整规则见 `jc-0-路径工具`。

## 体例输出格式

| 体例 | 输出组件 | 路径 | 结构 |
|------|----------|------|------|
| 项目式 | 【思维导图】 | `artifacts/{教材名称}/第X章_{标题}/思维导图_{章节}.xmind` + `.png` | 根节点=项目标题 → children2（知识准备+项目实施）→ children3 → children4（如有） |
| 项目任务式 | 【思维导图】 | `artifacts/{教材名称}/第X章_{标题}/思维导图_{章节}.xmind` + `.png` | 根节点=章标题 → children2（任务节点）→ children3 → children4（如有） |

## 工作流程

### Step 1：解析目录结构.json，提取目录

从 `目录结构.json` 中定位 `chapter_id` 对应的章节，提取 `children2`、`children3`、`children4` 节点，生成 `组件/目录_{章节}.md`。

#### 提取规则

1. 遍历目标章节的 `children2[]`，每个 `children2` 节点的 `name` 输出为 `##` 级标题
2. 遍历 `children2` 下的 `children3[]`，每个 `children3` 节点的 `name` 输出为 `###` 级标题
3. 遍历 `children3` 下的 `children4[]`（如有），每个 `children4` 节点的 `name` 输出为 `####` 级标题
4. 根节点（`#`）使用章节 `title`

#### 输出示例

```markdown
# 第3章 数据清洗

## 认识缺失值
### 缺失值类型
### 缺失值产生原因

## 用pandas处理缺失值
### isnull与notnull检测缺失值
### dropna删除缺失值
#### 按行删除
#### 按列删除
### fillna填充缺失值
```

#### 标题清洗

1. **去除"了解/熟悉/掌握"等动词**前缀，仅保留知识点名称
2. 不保留"引言/学习目标/小结/实训"等模块，只保留正文部分标题
3. 根节点为章节名称，第1、2、3级与目录2、3、4级标题对应，层级与目录严格一致
4. 顺序全书统一（逆时针或顺时针），不可混用

### Step 2：生成 XMind

环境依赖：

```bash
python scripts/install.py    # 先验证，不存在才安装（playwright + chromium ~180MB → scripts/browsers/）
```

```bash
python scripts/generate_xmind.py \
  --input 目录_{章节}.md \
  --output 思维导图_{章节}.xmind \
  --title "第X章 xxx"
```

产出标准 `.xmind`，兼容 XMind 8+，白底黑字黑框。

### Step 3：生成 PNG

```bash
python scripts/render_mindmap.py \
  --input 思维导图_{章节}.xmind \
  --output 图片/思维导图_{章节}.png
```

从 `.xmind` 解析标题树，SVG 左右对称布局 + Playwright 截图。引用：`![图X-1 第X章思维导图](图片/思维导图_{章节}.png)`

字号在 `scripts/render_mindmap.py` 顶部常量区统一配置（`FONT_ROOT=30` / `FONT_H2=26` / `FONT_H3=26` / `FONT_H4=26`），调整该常量即可全图生效，节点宽高、折行、横向间距均自适应，无需改其他位置。

### Step 4：持久化

```
artifacts/{教材}/第X章_{标题}/
  ├── 思维导图_{章节}.xmind
  ├── 图片/思维导图_{章节}.png
  └── 组件/目录_{章节}.md
```

---

## 核心约束

1. `.xmind` + `.png` 双产出，缺一不可
2. 层级与目录严格一致，标题清洗全部执行
3. 白底/浅灰底黑字黑框，无彩色样式
4. 标题必须去除"了解/熟悉/掌握"等动词，仅保留纯知识词
5. 输出图片仅 jpg/png，禁止 svg
6. 根节点和一级分支用浅灰底色(#F0F0F0)，其余白底

## 修改模式

收到 `[修改指令]` 时：

1. 修改 `目录_{章节}.md`
2. 重新运行 Step 2 → `.xmind`
3. 重新运行 Step 3 → `.png`
4. 输出完整内容，不做解释
