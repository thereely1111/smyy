# -*- coding: utf-8 -*-
"""
批注 → 源组件映射 + 生成标准修改指令。

功能：
1. 读取 map_comments.py 输出的增强批注 JSON（含 section 字段）
2. 将批注的 section（Word 标题）映射到源组件 md 文件
3. 生成标准 [批注修改指令]，供 jc-3 修改模式消费

Usage:
  python map_to_components.py <增强批注.json> <组件目录> [--output <路径>]
"""
import json
import os
import sys
import re

# Word 标题 → 组件名 → 文件名映射
SECTION_TO_COMPONENT = [
    (["引言"], "引言", "引言.md"),
    (["教学目标", "学习目标", "知识目标", "技能目标", "素养目标"], "教学目标", "教学目标.md"),
    (["思维导图", "导图"], "思维导图", None),  # 导图是 .xmind/.png，非 md
    (["知识准备", "知识讲解"], "知识准备", "知识准备.md"),
    (["项目实现", "项目实施", "任务实现", "任务描述"], "项目实现", "项目实现.md"),
    (["项目小结", "小结", "总结"], "项目小结", "项目小结.md"),
    (["实训"], "实训", "实训.md"),
    (["课后习题", "习题", "选择题", "填空题", "操作题"], "课后习题", "课后习题.md"),
    (["阅读拓展", "拓展"], "阅读拓展", "阅读拓展.md"),
]


def map_section_to_component(section: str):
    """根据 section 标题文本，映射到组件名和文件名。"""
    if not section or section == "未知":
        return ("未定位", None)
    for keywords, comp_name, filename in SECTION_TO_COMPONENT:
        for kw in keywords:
            if kw in section:
                return (comp_name, filename)
    return ("未定位", None)


def component_to_filename(comp_name: str):
    """根据组件名反查文件名（map_comments.py 已判定组件归属时使用）。"""
    for keywords, cname, filename in SECTION_TO_COMPONENT:
        if cname == comp_name:
            return filename
    return None


def build_comment_instruction(comment, comp_name, filename, component_dir):
    """生成单条 [批注修改指令]。"""
    anchor = comment.get("context", "")[:100] or comment.get("section", "")
    text = comment.get("text", "")

    # 尝试从 context 提取题注编号（图X-N / 表X-N / 代码X-N）
    caption_match = re.search(r'(图|表|代码)\s*\d+-\d+', anchor)
    if caption_match:
        anchor = caption_match.group(0)

    lines = []
    lines.append("[批注修改指令]")
    lines.append(f"目标组件: {comp_name}")
    lines.append(f"批注ID: {comment.get('id', '')}")
    lines.append(f"定位锚点: {anchor}")
    lines.append(f"修改方式: 待定（替换/插入/删除，由 jc-3 依据批注判断）")
    lines.append(f"修改要求: （依据批注翻译，见 AI 分析结果）")
    lines.append(f"修改原因: {text}")
    lines.append(f"优先级: P2")
    lines.append("[/批注修改指令]")
    return "\n".join(lines)


def main():
    import argparse
    parser = argparse.ArgumentParser(description="批注→源组件映射 + 生成修改指令")
    parser.add_argument("comments_json", help="增强批注 JSON 路径（map_comments.py 输出）")
    parser.add_argument("component_dir", help="源组件目录（组件/）")
    parser.add_argument("--output", "-o", help="输出文件路径（默认打印 stdout）")
    args = parser.parse_args()

    with open(args.comments_json, "r", encoding="utf-8") as f:
        comments = json.load(f)

    # 按组件分组
    grouped = {}
    instructions = []
    for c in comments:
        section = c.get("section", "未知")
        # 优先使用 map_comments.py 已判定的组件归属
        comp_name = c.get("component")
        if not comp_name or comp_name == "未定位":
            comp_name, filename = map_section_to_component(section)
        else:
            filename = component_to_filename(comp_name)
        c["component"] = comp_name
        c["component_file"] = filename

        if comp_name == "思维导图":
            c["note"] = "思维导图为 .xmind/.png，返回 jc-3-思维导图依据批注内容重新生成"

        grouped.setdefault(comp_name, []).append(c)
        if comp_name != "未定位":
            instructions.append(build_comment_instruction(c, comp_name, filename, args.component_dir))

    result = {
        "total": len(comments),
        "grouped_by_component": grouped,
        "instructions": instructions,
    }

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
        print(f"输出: {args.output}（共 {len(comments)} 条批注，映射到 {len(grouped)} 个组件）")
    else:
        if sys.platform == "win32":
            try:
                sys.stdout.reconfigure(encoding="utf-8")
            except Exception:
                pass
        print(f"共 {len(comments)} 条批注，映射到 {len(grouped)} 个组件：")
        for comp_name, items in grouped.items():
            print(f"\n=== {comp_name}（{len(items)} 条）===")
            for c in items:
                print(f"  [{c.get('id')}] {c.get('text','')[:60]}")
        print("\n\n## 修改指令清单\n")
        print("\n\n".join(instructions))


if __name__ == "__main__":
    main()
