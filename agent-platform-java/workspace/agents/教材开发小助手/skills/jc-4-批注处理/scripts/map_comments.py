"""
文档章节结构提取与批注映射工具。

功能：
1. 从 DOCX 中提取完整的段落/标题结构索引
2. 将批注 JSON 中的 context 文本匹配到对应段落和章节
3. 基于 Word 标题层级确定组件归属（component 字段）
4. 输出结构化的"批注→章节→组件→段落"映射表
"""

import json
import re
from docx import Document


# 固定标题关键词 → 组件名（按优先级排序）
FIXED_COMPONENT_KEYWORDS = [
    (["引言", "导入", "情景"], "引言"),
    (["学习目标", "教学目标", "知识目标", "技能目标", "素养目标"], "教学目标"),
    (["思维导图", "导图"], "思维导图"),
    (["小结", "总结"], "项目小结"),
    (["实训"], "实训"),
    (["课后习题", "习题", "选择题", "填空题", "操作题"], "课后习题"),
    (["阅读拓展", "拓展"], "阅读拓展"),
]

# 正文字段：这些固定组件标题之间的 H2，均归入正文（知识准备/项目实现）
BODY_COMPONENT = "知识准备"


def _heading_level(style_name: str):
    """从样式名提取标题级别，如 'Heading 2' → 2。"""
    if not style_name:
        return None
    for ch in style_name:
        if ch.isdigit():
            return int(ch)
    return None


def _match_fixed_component(text: str):
    """按固定标题关键词匹配组件名，未命中返回 None。"""
    for keywords, comp in FIXED_COMPONENT_KEYWORDS:
        for kw in keywords:
            if kw in text:
                return comp
    return None


def _component_for_heading(h_text: str, prev_component: str):
    """根据 H2 标题文本 + 前一个 H2 的组件，判定当前 H2 的组件归属。

    规则：
    - 标题含固定关键词（引言/目标/导图/小结/习题等）→ 直接映射
    - 否则 → 正文组件（知识准备/项目实现），由"所处区间"决定
    """
    fixed = _match_fixed_component(h_text)
    if fixed:
        return fixed
    return BODY_COMPONENT


def build_heading_components(docx_path: str):
    """遍历 Word，为每个 H2 标题判定组件归属。

    Returns:
        list[dict]: 每个 H2 的 {index, level, text, component}
    """
    doc = Document(docx_path)
    h2_list = []
    for idx, para in enumerate(doc.paragraphs):
        style_name = para.style.name if para.style else ""
        if "Heading" not in style_name:
            continue
        level = _heading_level(style_name)
        # 只关注 H1/H2（章节标题 + 组件级标题）
        if level not in (1, 2):
            continue
        text = para.text.strip()
        if not text:
            continue
        h2_list.append({"index": idx, "level": level, "text": text, "component": None})

    # 为 H2 判定组件（H1 跳过，仅作章节边界）
    for h in h2_list:
        if h["level"] == 1:
            continue
        h["component"] = _component_for_heading(h["text"], None)

    return h2_list


def component_for_section(h2_list, section_text: str, match_idx):
    """给定批注的 section 标题文本和段落索引，确定组件归属。

    策略：优先用 match_idx 找到最近的 H2（含 section 标题本身），取其 component。
    """
    if match_idx is None:
        return None
    # 向上找最近的 H2（index <= match_idx）
    candidate = None
    for h in h2_list:
        if h["level"] != 2:
            continue
        if h["index"] <= match_idx:
            candidate = h
        else:
            break
    if candidate and candidate.get("component"):
        return candidate["component"]
    return None


def extract_structure(docx_path: str) -> list[dict]:
    """
    提取 DOCX 文档的完整段落结构。

    Returns:
        list[dict]: 每个元素包含 index, style, text, is_heading, heading_level
    """
    doc = Document(docx_path)
    structure = []
    for idx, para in enumerate(doc.paragraphs):
        text = para.text.strip()
        if not text:
            continue
        style_name = para.style.name if para.style else "Normal"
        is_heading = "Heading" in style_name or "heading" in style_name.lower()
        # 提取标题级别（如 Heading 2 → 2）
        heading_level = None
        if is_heading:
            for ch in style_name:
                if ch.isdigit():
                    heading_level = int(ch)
                    break
        structure.append({
            "index": idx,
            "style": style_name,
            "text": text[:200],  # 截取前200字符用于匹配
            "is_heading": is_heading,
            "heading_level": heading_level,
        })
    return structure


def map_comments_to_sections(
    comments_json_path: str, docx_path: str
) -> list[dict]:
    """
    将批注映射到文档章节。

    匹配策略：
    1. 按批注 context 文本在段落中精确查找
    2. 找到后，向上追溯最近的 Heading 作为所在章节
    3. 返回增强的批注列表（增加 section 字段）

    Args:
        comments_json_path: docx-comments-extractor 输出的 JSON 文件路径
        docx_path: 原始 DOCX 文件路径

    Returns:
        list[dict]: 增强后的批注列表
    """
    with open(comments_json_path, "r", encoding="utf-8") as f:
        comments = json.load(f)

    doc = Document(docx_path)
    paragraphs = [p.text.strip() for p in doc.paragraphs]

    headings = []  # (para_index, heading_text, level)
    for idx, para in enumerate(doc.paragraphs):
        style_name = para.style.name if para.style else ""
        if "Heading" in style_name:
            heading_level = 1
            for ch in style_name:
                if ch.isdigit():
                    heading_level = int(ch)
                    break
            headings.append((idx, para.text.strip(), heading_level))

    # 构建 H2 组件归属表（基于 Word 标题层级）
    h2_components = build_heading_components(docx_path)

    for comment in comments:
        ctx = comment.get("context", "")
        match_idx = None

        # 图片锚点：优先使用 extract 脚本记录的段落索引直接定位（跳过文本匹配）
        anchor_idx = comment.get("anchor_para_index")
        if anchor_idx is not None:
            match_idx = anchor_idx

        # 文本匹配：context 前60字符精确匹配
        if match_idx is None:
            for i, p_text in enumerate(paragraphs):
                if ctx and ctx[:60] in p_text:
                    match_idx = i
                    break

        # 模糊匹配（取前30字符）
        if match_idx is None and ctx:
            ctx_short = ctx[:30]
            for i, p_text in enumerate(paragraphs):
                if ctx_short and ctx_short in p_text:
                    match_idx = i
                    break

        # 找到匹配后，追溯最近标题
        section = "未知"
        if match_idx is not None:
            for h_idx, h_text, h_level in reversed(headings):
                if h_idx < match_idx:
                    section = h_text
                    break

        # 确定组件归属（基于 H2 层级结构，而非标题关键词猜）
        component = component_for_section(h2_components, section, match_idx)

        comment["mapped_para_index"] = match_idx
        comment["section"] = section
        comment["component"] = component

    return comments


def filter_by_section_range(
    comments: list[dict], section_keywords: list[str]
) -> list[dict]:
    """
    按章节关键字筛选批注。

    Args:
        comments: map_comments_to_sections 的输出
        section_keywords: 章节标题关键字列表（如 ["表结构设计", "集群部署", "AI驱动"]）

    Returns:
        筛选后的批注列表
    """
    result = []
    for c in comments:
        section = c.get("section", "")
        for kw in section_keywords:
            if kw in section:
                result.append(c)
                break
    return result


if __name__ == "__main__":
    import sys

    # Windows 下强制 stdout 使用 UTF-8，避免中文输出走 GBK 污染下游 JSON
    if sys.platform == "win32":
        try:
            sys.stdout.reconfigure(encoding="utf-8")
        except Exception:
            pass

    if len(sys.argv) < 3:
        print("Usage: python map_comments.py <comments.json> <doc.docx> [keyword1 keyword2 ...]")
        sys.exit(1)

    comments_json = sys.argv[1]
    docx = sys.argv[2]
    keywords = sys.argv[3:] if len(sys.argv) > 3 else []

    result = map_comments_to_sections(comments_json, docx)

    if keywords:
        result = filter_by_section_range(result, keywords)

    print(json.dumps(result, ensure_ascii=False, indent=2))
    print(f"\n共 {len(result)} 条批注", file=sys.stderr)
