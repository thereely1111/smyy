#!/usr/bin/env python3
"""
jc-3-导图生成 — XMind 格式生成器
生成 .xmind 思维导图文件，可被 XMind 8/2020 打开编辑。
含完整的配色样式表，多层节点彩色圆角渲染。

用法:
  python generate_xmind.py --input 目录.md --output 思维导图.xmind --title "章标题" --direction LR

XMind 文件格式:
  .xmind = ZIP 压缩包，内含 content.xml + styles.xml + META-INF/manifest.xml
"""

import argparse
import hashlib
import re
import sys
import uuid
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

# ── XMind XML 命名空间 ──
NS_CONTENT = "urn:xmind:xmap:xmlns:content:2.0"
NS_FO = "http://www.w3.org/1999/XSL/Format"
NS_STYLE = "urn:xmind:xmap:xmlns:style:2.0"

# ── 需要从标题中去除的学习目标动词 ──
STRIP_VERBS = [
    "了解", "熟悉", "掌握", "认识", "能够", "理解",
    "知道", "学会", "运用", "应用", "实现", "搭建",
    "构建", "完成", "进行",
]

STRIP_SUFFIX_PUNCT = "。，、；：,.!?;:…"


def clean_title(title: str) -> str:
    """清理标题：去除章节编号 + 学习目标动词 + 末尾标点"""
    title = title.strip()
    # 去除 "第X章" 前缀
    title = re.sub(r"^第[一二三四五六七八九十\d]+章\s*", "", title)
    # 去除各种编号前缀：2.1 / 2.1.1 / 2.1.1. / (1) / 1、/ 1.
    title = re.sub(r"^\d+(\.\d+)*[.\s、]+", "", title)
    title = re.sub(r"^[\(（]\d+[\)）][.\s、]*", "", title)
    for verb in sorted(STRIP_VERBS, key=len, reverse=True):
        if title.startswith(verb):
            title = title[len(verb):].strip()
            break
    title = re.sub(r"\s{2,}", " ", title).strip()
    title = title.rstrip(STRIP_SUFFIX_PUNCT).strip()
    if len(title) > 40:
        title = title[:38] + "…"
    return title or title


def uid() -> str:
    """生成 XMind 节点唯一 ID"""
    return hashlib.md5(str(uuid.uuid4()).encode()).hexdigest()[:16]


def parse_markdown_toc(text: str) -> list[dict]:
    """解析 Markdown 标题为树结构"""
    lines = text.strip().split("\n")
    root = {"title": "ROOT", "children": [], "level": 0}
    stack = [root]
    for line in lines:
        line = line.strip()
        if not line:
            continue
        m = re.match(r"^(#{1,4})\s+(.+)", line)
        if not m:
            continue
        level = len(m.group(1))
        if level == 1:
            continue  # 跳过 H1，根节点由 --title 参数提供
        raw_title = m.group(2).strip()
        title = clean_title(raw_title)
        node = {"title": title, "children": [], "level": level}
        while stack and stack[-1]["level"] >= level:
            stack.pop()
        if stack:
            stack[-1]["children"].append(node)
        stack.append(node)
    return root["children"]


# ── 节点层级 → style-id 映射 ──
# H2 → style-h2, H3 → style-h3, H4+ → style-h4
def style_id_for_level(level: int) -> str:
    if level >= 4:
        return "style-h4"
    return f"style-h{level}"


def build_topic_element(title: str, children: list[dict] | None = None,
                        level: int = 2, topic_type: str = "attached") -> ET.Element:
    """构建 XMind topic XML 元素（含 style-id）"""
    t = ET.Element(f"{{{NS_CONTENT}}}topic", {
        "id": uid(),
        "type": topic_type,
        "timestamp": "",
        "style-id": style_id_for_level(level),
    })
    title_el = ET.SubElement(t, f"{{{NS_CONTENT}}}title")
    title_el.text = title

    if children:
        children_el = ET.SubElement(t, f"{{{NS_CONTENT}}}children")
        topics_el = ET.SubElement(children_el, f"{{{NS_CONTENT}}}topics", {
            "type": "attached",
        })
        for child in children:
            child_el = build_topic_element(
                child["title"], child.get("children", []) or None,
                level=child["level"],
            )
            topics_el.append(child_el)
    return t


def create_content_xml(root_title: str, tree: list[dict]) -> bytes:
    """生成 content.xml（含 style-id 引用）"""
    ET.register_namespace("", NS_CONTENT)
    ET.register_namespace("fo", NS_FO)
    xmap = ET.Element(f"{{{NS_CONTENT}}}xmap-content", {
        f"{{{NS_FO}}}content-height": "",
    })
    sheet = ET.SubElement(xmap, f"{{{NS_CONTENT}}}sheet", {
        "id": uid(),
        "timestamp": "",
        "style-id": "style-map",
    })
    ET.SubElement(sheet, f"{{{NS_CONTENT}}}title").text = "思维导图"
    root_topic = ET.SubElement(sheet, f"{{{NS_CONTENT}}}topic", {
        "id": uid(),
        "type": "root",
        "timestamp": "",
        "style-id": "style-root",
    })
    ET.SubElement(root_topic, f"{{{NS_CONTENT}}}title").text = root_title
    # 子节点（从 root 的 children 开始，对应 H2）
    if tree:
        children_el = ET.SubElement(root_topic, f"{{{NS_CONTENT}}}children")
        topics_el = ET.SubElement(children_el, f"{{{NS_CONTENT}}}topics", {
            "type": "attached",
        })
        for node in tree:
            topics_el.append(build_topic_element(
                node["title"], node.get("children", []) or None,
                level=node["level"],
            ))
    return ET.tostring(xmap, encoding="utf-8", xml_declaration=True)


def create_styles_xml() -> bytes:
    """生成 styles.xml — 黑白学术风格"""
    ET.register_namespace("", NS_STYLE)
    ET.register_namespace("fo", NS_FO)

    styles = ET.Element(f"{{{NS_STYLE}}}styles")

    def add_style(style_id: str, style_type: str,
                  fill: str, line: str,
                  font_family: str, font_size: str,
                  font_color: str = "#000000",
                  font_weight: str = "normal",
                  shape: str = "org.xmind.topicShape.roundedRect"):
        el = ET.SubElement(styles, f"{{{NS_STYLE}}}style", {
            "id": style_id, "type": style_type,
        })
        ET.SubElement(el, f"{{{NS_STYLE}}}topic-properties", {
            "fill-color": fill,
            "line-color": line,
            "shape-class": shape,
            "line-width": "1px",
        })
        ET.SubElement(el, f"{{{NS_STYLE}}}font-properties", {
            f"{{{NS_FO}}}font-family": font_family,
            f"{{{NS_FO}}}font-size": font_size,
            f"{{{NS_FO}}}font-color": font_color,
            f"{{{NS_FO}}}font-weight": font_weight,
        })

    # 全局默认字体
    global_el = ET.SubElement(styles, f"{{{NS_STYLE}}}style", {
        "id": "default", "type": "global",
    })
    font_el = ET.SubElement(global_el, f"{{{NS_STYLE}}}font-family")
    font_el.set(f"{{{NS_STYLE}}}default", "Microsoft YaHei")

    # Map 级别默认
    add_style("style-map", "map",
              fill="#FFFFFF", line="#000000",
              font_family="Microsoft YaHei", font_size="14pt",
              font_color="#000000")

    # Root 节点（章标题）- 白底黑字加粗
    add_style("style-root", "topic",
              fill="#FFFFFF", line="#000000",
              font_family="Microsoft YaHei", font_size="18pt",
              font_color="#000000", font_weight="bold")

    # H2 节点 - 白底黑字加粗
    add_style("style-h2", "topic",
              fill="#FFFFFF", line="#000000",
              font_family="Microsoft YaHei", font_size="14pt",
              font_color="#000000", font_weight="bold")

    # H3 节点 - 白底黑字
    add_style("style-h3", "topic",
              fill="#FFFFFF", line="#000000",
              font_family="Microsoft YaHei", font_size="12pt",
              font_color="#000000")

    # H4+ 节点 - 白底黑字黑框
    add_style("style-h4", "topic",
              fill="#FFFFFF", line="#000000",
              font_family="Microsoft YaHei", font_size="11pt",
              font_color="#000000")

    return ET.tostring(styles, encoding="utf-8", xml_declaration=True)


def create_manifest_xml() -> bytes:
    """生成 META-INF/manifest.xml"""
    return (
        b'<?xml version="1.0" encoding="UTF-8"?>\n'
        b'<manifest xmlns="urn:xmind:xmap:xmlns:manifest:1.0">\n'
        b'  <file-entry full-path="content.xml" media-type="text/xml"/>\n'
        b'  <file-entry full-path="styles.xml" media-type="text/xml"/>\n'
        b'</manifest>\n'
    )


def generate_xmind(input_file: str, output_file: str, title: str, direction: str = "LR"):
    """生成 .xmind 思维导图文件"""
    text = Path(input_file).read_text(encoding="utf-8")
    tree = parse_markdown_toc(text)

    if not tree:
        print(f"警告: {input_file} 中未找到有效标题")
        return False

    root_title = clean_title(title)
    content_xml = create_content_xml(root_title, tree)
    styles_xml = create_styles_xml()
    manifest_xml = create_manifest_xml()

    out_path = Path(output_file)
    if out_path.suffix.lower() != ".xmind":
        out_path = out_path.with_suffix(".xmind")

    with zipfile.ZipFile(str(out_path), "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("content.xml", content_xml)
        zf.writestr("styles.xml", styles_xml)
        zf.writestr("META-INF/manifest.xml", manifest_xml)

    print(f"[jc-3-导图生成] [OK] XMind file generated: {out_path}")
    return True


def main():
    parser = argparse.ArgumentParser(description="生成 XMind 格式思维导图 (.xmind)")
    parser.add_argument("--input", required=True, help="Markdown 目录文件路径")
    parser.add_argument("--output", required=True, help="输出 .xmind 文件路径")
    parser.add_argument("--title", required=True, help="根节点标题（会自动去编号和动词）")
    parser.add_argument("--direction", default="LR", choices=["LR", "TB"],
                        help="导图方向（保留参数，XMind 可在桌面端调整）")
    args = parser.parse_args()

    ok = generate_xmind(args.input, args.output, args.title, args.direction)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
