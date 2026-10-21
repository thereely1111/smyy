#!/usr/bin/env python3
"""
jc-3-视觉素材 — 流程图/原理图生成器（graphviz）

用法：
  python generate_diagram.py --input <diagram.json> --output <图X-N_描述.png>
  python generate_diagram.py --text "A->B->C" --output <图X-N.png>

输入格式（JSON）：
{
  "direction": "TB",
  "nodes": [
    {"id": "data", "label": "数据输入", "shape": "box", "type": "start"},
    {"id": "feat", "label": "特征选择", "shape": "box", "type": "process"},
    ...
  ],
  "edges": [
    {"from": "data", "to": "feat", "label": ""},
    ...
  ]
}

设计规范：
- 不输出题注名称（题注由 Markdown 外部标注）
- 节点文字清晰可读（9pt），边标签 8pt
- 节点内边距充足，不会遮挡或截断文字
- 节点间距和行间距宽裕，连线不重叠
- LR 方向作为默认布局（横向阅读更自然）
"""

import argparse
import json
import os
import sys
from pathlib import Path

# ── 自动检测 scripts/ 内 graphviz 便携版 ──
_graphviz_bin = Path(__file__).resolve().parent / "graphviz" / "bin"
if _graphviz_bin.exists():
    os.environ["PATH"] = str(_graphviz_bin) + os.pathsep + os.environ.get("PATH", "")

try:
    import graphviz
except ImportError:
    print("错误: 请先运行 install.py 安装环境", file=sys.stderr)
    sys.exit(1)


# ── 形状映射 ──────────────────────
# ── 形状映射（shape → Graphviz shape 名） ──
SHAPE_MAP = {
    "box": "box",
    "rounded": "box",
    "diamond": "diamond",
    "ellipse": "ellipse",
    "cylinder": "cylinder",
}

# ── 形状对应的默认 style（叠加到 NODE_STYLES 之上） ──
SHAPE_STYLE = {
    "box": "",
    "rounded": "rounded",
    "diamond": "",
    "ellipse": "",
    "cylinder": "",
}

# ── 节点样式预设（type 字段对应，不含 shape） ──
NODE_STYLES = {
    "start":   {"fillcolor": "#4A90E2", "fontcolor": "#FFFFFF", "penwidth": "1"},
    "process": {"fillcolor": "#EBF0FA", "fontcolor": "#1A1A1A", "penwidth": "1"},
    "decision":{"fillcolor": "#FFF8E1", "fontcolor": "#1A1A1A", "penwidth": "1.5"},
    "end":     {"fillcolor": "#D4EDDA", "fontcolor": "#1A1A1A", "penwidth": "1.5"},
    "data":    {"fillcolor": "#EEEEEE", "fontcolor": "#1A1A1A", "penwidth": "1"},
}

# ── 默认值 ────────────────────────
FONT_NAME = "SimSun"
NODE_FONT_SIZE = "10"
EDGE_FONT_SIZE = "6"
DPI = 200
RANK_SEP = "0.25"
NODE_SEP = "0.25"
MARGIN = "0.05,0.02"
# 最大画布尺寸（英寸），超过则按 ratio=compress 缩放；适合教材插图（A4 半幅约 3.5~4.5 英寸宽）
MAX_WIDTH_INCH = "4.5"
MAX_HEIGHT_INCH = "3.5"
RATIO = "compress"   # compress=按比例缩小至不超过 size；fill=填充至填满


def _merge_style(shape_str: str, node_type: str) -> dict:
    """合并 shape 和 type 对应的 Graphviz 属性"""
    attrs = {"shape": SHAPE_MAP.get(shape_str, "box")}
    type_style = NODE_STYLES.get(node_type, NODE_STYLES["process"]).copy()
    # 构建 style 字段: filled 为底, shape 的 rounded 叠加
    parts = ["filled"]
    if SHAPE_STYLE.get(shape_str, "") == "rounded":
        parts.append("rounded")
    type_style["style"] = ",".join(parts)
    attrs.update(type_style)
    return attrs


def render_diagram(data: dict, output_path: str) -> str:
    """根据 JSON 描述渲染流程图，不输出题注名称"""
    direction = data.get("direction", "LR")
    nodes = data.get("nodes", [])
    edges = data.get("edges", [])

    dot = graphviz.Digraph(
        name="diagram",
        format="png",
        engine="dot",
    )

    # ── 全局样式 ──
    dot.attr(
        rankdir=direction,
        bgcolor="#FFFFFF",
        pad="0.4",
        nodesep=NODE_SEP,
        ranksep=RANK_SEP,
        dpi=str(DPI),
        size=f"{MAX_WIDTH_INCH},{MAX_HEIGHT_INCH}!",
        ratio=RATIO,
        # 不设 label（题注不由图内显示）
    )
    dot.attr("node",
        fontname=FONT_NAME,
        fontsize=NODE_FONT_SIZE,
        margin=MARGIN,
    )
    dot.attr("edge",
        fontname=FONT_NAME,
        fontsize=EDGE_FONT_SIZE,
        color="#555555",
        arrowsize="0.4",
        minlen="1",
        margin="0.02,0.02",
    )

    # ── 根据最长标签计算统一框尺寸 ──
    font_size = float(NODE_FONT_SIZE)
    char_w = font_size / 72       # 每字符宽度(英寸)
    line_h = (font_size + 3) / 72 # 每行高度(英寸)

    max_chars = 0
    max_lines = 1
    for node in nodes:
        label = node.get("label", "")
        for line in label.split("\n"):
            max_chars = max(max_chars, len(line))
        max_lines = max(max_lines, label.count("\n") + 1)

    margin_x, margin_y = map(float, MARGIN.split(","))
    calc_w = max_chars * char_w + margin_x * 2 + 0.06
    calc_h = max_lines * line_h + margin_y * 2 + 0.02

    # ── 添加节点 ──
    for node in nodes:
        nid = node.get("id", "")
        label = node.get("label", nid)
        shape = node.get("shape", "box")
        node_type = node.get("type", "process")

        attrs = _merge_style(shape, node_type)
        attrs["fixedsize"] = "true"
        attrs["width"] = str(round(calc_w, 2))
        attrs["height"] = str(round(calc_h, 2))
        dot.node(nid, label, **attrs)

    # ── 添加边 ──
    for edge in edges:
        src = edge.get("from", "")
        dst = edge.get("to", "")
        label = edge.get("label", "")
        dot.edge(src, dst, label)

    out = Path(output_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    dot.render(filename=out.stem, directory=str(out.parent), cleanup=True, format="png")

    png = out.parent / f"{out.stem}.png"
    print(f"[jc-3] 已生成流程图: {png}")
    return str(png)


def render_text(text: str, output_path: str) -> str:
    """从 A->B->C 文本生成纵向流程图"""
    if "->" in text:
        steps = [s.strip() for s in text.split("->")]
    elif "→" in text:
        steps = [s.strip() for s in text.split("→")]
    elif "|" in text:
        steps = [s.strip() for s in text.split("|")]
    else:
        steps = [text.strip()]

    data = {"direction": "TB", "nodes": [], "edges": []}
    for i, step in enumerate(steps):
        nid = f"step{i}"
        ntype = "start" if i == 0 else ("end" if i == len(steps)-1 else "process")
        data["nodes"].append({"id": nid, "label": step, "shape": "rounded", "type": ntype})
    for i in range(len(steps)-1):
        data["edges"].append({"from": f"step{i}", "to": f"step{i+1}"})

    return render_diagram(data, output_path)


# ── CLI ───────────────────────────
def main():
    parser = argparse.ArgumentParser(description="渲染流程图/原理图为 PNG（不含题注）")
    parser.add_argument("--input", help="JSON 描述文件路径")
    parser.add_argument("--text", help="简单文本描述，如 A->B->C")
    parser.add_argument("--output", required=True, help="输出 PNG 文件路径")
    args = parser.parse_args()

    try:
        if args.input:
            with open(args.input, "r", encoding="utf-8") as f:
                data = json.load(f)
            render_diagram(data, args.output)
        elif args.text:
            render_text(args.text, args.output)
        else:
            print("错误: 需要 --input 或 --text 参数", file=sys.stderr)
            sys.exit(1)
    except Exception as e:
        print(f"[ERROR] {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
