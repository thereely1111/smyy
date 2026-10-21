#!/usr/bin/env python3
"""
XMind → SVG (左右对称布局) → Playwright 截图 → PNG
动态计算分支 Y 位置，确保不重叠。

用法：
  python render_mindmap.py --input 思维导图.xmind --output 思维导图.png
"""

import argparse
import math
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

BC = "#000000"
BF = "#FFFFFF"      # H3/H4 白底
BF_TOP = "#F0F0F0"  # 根/H2 浅灰底色
FC = "#000000"
BG = "#FFFFFF"

# 各层级字号（像素），根 > H2 > H3 > H4，统一在此调大即可全图生效
FONT_ROOT = 30   # 根节点字号
FONT_H2 = 26     # 二级标题字号
FONT_H3 = 26     # 三级标题字号
FONT_H4 = 26     # 四级标题字号


def clean_title(raw):
    t = raw.strip()
    t = re.sub(r"^第[一二三四五六七八九十\d]+章\s*", "", t)
    t = re.sub(r"^\d+(\.\d+)*[.\s、]+", "", t)
    t = re.sub(r"^[\(（]\d+[\)）][.\s、]*", "", t)
    for v in ["掌握", "熟悉", "了解", "认识", "能够", "理解",
              "知道", "学会", "运用", "应用", "实现", "搭建", "构建", "完成", "进行"]:
        if t.startswith(v):
            t = t[len(v):].strip()
            break
    return t.rstrip("。，、；：,.!?;:…").strip() or raw.strip()


def parse_xmind(xmind_path):
    with zipfile.ZipFile(xmind_path, "r") as zf:
        content = zf.read("content.xml")
    NS = "urn:xmind:xmap:xmlns:content:2.0"
    def parse_topic(elem):
        title_el = elem.find(f"{{{NS}}}title")
        title = title_el.text.strip() if title_el is not None and title_el.text else ""
        children = []
        children_el = elem.find(f"{{{NS}}}children")
        if children_el is not None:
            topics_el = children_el.find(f"{{{NS}}}topics")
            if topics_el is not None:
                for child in topics_el.findall(f"{{{NS}}}topic"):
                    p = parse_topic(child)
                    if p["title"]:
                        children.append(p)
        return {"title": clean_title(title), "children": children}
    root = ET.fromstring(content)
    sheet = root.find(f"{{{NS}}}sheet")
    if sheet is None:
        return None, []
    root_topic = sheet.find(f"{{{NS}}}topic")
    if root_topic is None:
        return None, []
    tree = parse_topic(root_topic)
    return tree["title"], tree["children"]


def generate_html(root_title, h2_list):
    # 画布尺寸（像素）：按左右分支节点数量动态扩张，避免节点挤在一起
    num_h2 = len(h2_list)
    # 估算每侧需要的 Y 高度：每个 H2 分支约 3 个 H3 × Y_SPACING_H3 + 余量
    est_h_per_side = max(2, num_h2) * 300 + 300
    H = max(1600, est_h_per_side)
    W = max(3200, 2400 + num_h2 * 200)
    CX, CY = W // 2, H // 2

    # ── 自动计算同级节点统一尺寸 ──
    def calc_w(text, fs):
        """估算中文文本宽度：中文≈fs*0.95, ASCII≈fs*0.55"""
        w = 0
        for ch in text:
            if ord(ch) > 127:
                w += fs * 0.92
            else:
                w += fs * 0.55
        return int(w + 28)  # +padding

    def calc_h(fs, lines=1):
        return int(fs * 1.6 * lines + 8)

    def wrap_text(text, fs, max_chars):
        """按 max_chars 折行，优先在分隔符（/、-、与、和、 空格等）处断行，
        避免把英文单词或短语从中间切断（如 LSTM/GRU 整体保留）。"""
        if len(text) <= max_chars:
            return [text], text

        # 边界字符集（按优先级）
        BOUNDARIES = [' ', '/', '-', '、', '与', '和', '（', '）', '—', '·', ':', '；']
        STRIPPABLE = set(' 、，：；。')

        lines = []
        i = 0
        n = len(text)
        while i < n:
            end = min(i + max_chars, n)
            if end >= n:
                lines.append(text[i:end].rstrip())
                break
            seg = text[i:end]
            # 从右向左在 [max_chars-3, max_chars) 窗口内寻找边界
            cut = -1
            for b in BOUNDARIES:
                # 窗口从右数 3 个字符范围内查找
                idx = seg.rfind(b, max(0, len(seg) - 3), len(seg))
                if idx > 0:
                    cut = idx
                    break
            # 若右侧窗口没找到，再向右扩大到整段
            if cut < 0:
                for b in BOUNDARIES:
                    idx = seg.rfind(b)
                    if idx >= max(2, len(seg) - 4):
                        cut = idx
                        break
            if cut < 0:
                # 无边界则硬切
                lines.append(seg)
                i = end
            else:
                line = text[i:i + cut + 1]
                lines.append(line.rstrip())
                i = i + cut + 1
                while i < n and text[i] in STRIPPABLE:
                    i += 1
        return lines, max(lines, key=len)

    # 各层级每行最大字符数（超过则折行）
    MAX_CHARS_ROOT = 10
    MAX_CHARS_H2 = 12
    MAX_CHARS_H3 = 15
    MAX_CHARS_H4 = 15

    def calc_wrap_w(text, fs, max_chars):
        _, longest = wrap_text(text, fs, max_chars)
        return calc_w(longest, fs)

    def calc_wrap_h(text, fs, max_chars):
        lines, _ = wrap_text(text, fs, max_chars)
        return calc_h(fs, len(lines))

    # 扫描各层级最大宽度（按折行后的最长单行计算）
    root_lines = wrap_text(root_title, FONT_ROOT, MAX_CHARS_ROOT)[0]
    max_root_w = calc_w(max(root_lines, key=len), FONT_ROOT)
    max_h2_w = max((calc_wrap_w(n["title"], FONT_H2, MAX_CHARS_H2) for n in h2_list), default=120)
    max_h3_w, max_h4_w = 120, 100
    for h2 in h2_list:
        for h3 in h2.get("children", []):
            max_h3_w = max(max_h3_w, calc_wrap_w(h3["title"], FONT_H3, MAX_CHARS_H3))
            for h4 in h3.get("children", []):
                max_h4_w = max(max_h4_w, calc_wrap_w(h4["title"], FONT_H4, MAX_CHARS_H4))

    # 各层级节点高度：按最大行数统一（保证同级等高）
    root_line_count = len(root_lines)
    ROOT_W, ROOT_H = max(260, max_root_w), calc_h(FONT_ROOT, root_line_count)
    # 各层级最大行数（用于分支高度估算）
    max_h2_lines = max((len(wrap_text(n["title"], FONT_H2, MAX_CHARS_H2)[0]) for n in h2_list), default=1)
    max_h3_lines = 1
    max_h4_lines = 1
    for h2 in h2_list:
        for h3 in h2.get("children", []):
            max_h3_lines = max(max_h3_lines, len(wrap_text(h3["title"], FONT_H3, MAX_CHARS_H3)[0]))
            for h4 in h3.get("children", []):
                max_h4_lines = max(max_h4_lines, len(wrap_text(h4["title"], FONT_H4, MAX_CHARS_H4)[0]))

    H2_W, H2_H = max(160, max_h2_w), calc_h(FONT_H2, max_h2_lines)
    H3_W, H3_H = max(140, max_h3_w), calc_h(FONT_H3, max_h3_lines)
    H4_W, H4_H = max(120, max_h4_w), calc_h(FONT_H4, max_h4_lines)

    # 横向间距：层级间留足空隙（随字号放大，根与 H2 之间自适应节点宽度）
    X_H2 = int(ROOT_W / 2 + H2_W / 2) + 100
    X_H3 = X_H2 + int((H2_W + H3_W) / 2) + 80
    X_H4 = X_H3 + int((H3_W + H4_W) / 2) + 80
    Y_SPACING_H3 = 200
    Y_SPACING_H4 = 180
    Y_PAD = 140

    left_count = (num_h2 + 1) // 2
    right_count = num_h2 - left_count

    def h3_extent(h3_node):
        """单个 H3 节点（含其 H4 子节点）的总垂直跨度（高度）。"""
        h3_h = calc_wrap_h(h3_node["title"], FONT_H3, MAX_CHARS_H3)
        h4_list = h3_node.get("children", [])
        if not h4_list:
            return h3_h
        h4_heights = [calc_wrap_h(h4["title"], FONT_H4, MAX_CHARS_H4) for h4 in h4_list]
        h4_total = sum(h4_heights) + (len(h4_list) - 1) * Y_SPACING_H4
        # H4 以 H3 中心对称分布，故 H3 整体跨度由 H4 组决定
        return max(h3_h, h4_total)

    def branch_half(node):
        """H2 分支（含其全部 H3+H4）相对分支中心的总半高。
        各 H3 按真实跨度居中堆叠。"""
        h3_list = node.get("children", [])
        if not h3_list:
            return H2_H / 2
        spans = [h3_extent(h3) for h3 in h3_list]
        total = sum(spans) + (len(h3_list) - 1) * Y_SPACING_H3
        return total / 2

    left_halfs = [branch_half(h2_list[i]) for i in range(left_count)]
    right_halfs = [branch_half(h2_list[left_count + i]) for i in range(right_count)]

    def layout_y(halfs):
        """从上到下排列分支，间隙严格 Y_PAD，整体居中于 CY"""
        if not halfs:
            return []
        n = len(halfs)
        # 总高度 = 所有半高之和 × 2 + (n-1) 个间隙
        total_h = sum(h * 2 for h in halfs) + (n - 1) * Y_PAD
        # 从上往下逐个排列
        cur = CY - total_h / 2
        result = []
        for h in halfs:
            result.append(cur + h)           # 分支中心 = 顶部 + 半高
            cur += h * 2 + Y_PAD             # 移到下一个分支顶部
        return result

    left_y = layout_y(left_halfs)
    right_y = layout_y(right_halfs)

    curves = []
    nodes = []
    all_x, all_y = [], []

    def track(x, y, w, h):
        all_x.extend([x - w/2, x + w/2])
        all_y.extend([y - h/2, y + h/2])

    # 节点文本统一折行（存储为换行文本，渲染时按行绘制）
    root_text = "\n".join(root_lines)
    nodes.append((CX, CY, ROOT_W, ROOT_H, 14, BF_TOP, BC, 2,
                  root_text, FONT_ROOT, True, FC))
    track(CX, CY, ROOT_W, ROOT_H)

    def add_branch(idx, h2_node, is_left, y_pos):
        sign = -1 if is_left else 1
        h2x, h2y = CX + sign * X_H2, y_pos
        h2_text = "\n".join(wrap_text(h2_node["title"], FONT_H2, MAX_CHARS_H2)[0])
        h2_h = calc_wrap_h(h2_node["title"], FONT_H2, MAX_CHARS_H2)
        nodes.append((h2x, h2y, H2_W, h2_h, 8, BF_TOP, BC, 2,
                      h2_text, FONT_H2, False, FC))
        track(h2x, h2y, H2_W, h2_h)
        curves.append((CX + sign * ROOT_W/2, CY, h2x - sign * H2_W/2, h2y, BC, 2))

        h3_list = h2_node.get("children", [])
        if not h3_list:
            return
        # 逐个累加：先算每个 H3（含 H4）的上/下扩展（half-height）
        h3_spans = [h3_extent(h3) for h3 in h3_list]
        n3 = len(h3_list)
        total_h = sum(h3_spans) + (n3 - 1) * Y_SPACING_H3
        cum_y = h2y - total_h / 2
        h3_centers = []
        for j in range(n3):
            cum_y += h3_spans[j] / 2
            h3_centers.append(cum_y)
            cum_y += h3_spans[j] / 2 + Y_SPACING_H3

        for j, h3_node in enumerate(h3_list):
            h3_h = calc_wrap_h(h3_node["title"], FONT_H3, MAX_CHARS_H3)
            h3y = h3_centers[j]
            h3x = CX + sign * X_H3
            h3_text = "\n".join(wrap_text(h3_node["title"], FONT_H3, MAX_CHARS_H3)[0])
            nodes.append((h3x, h3y, H3_W, h3_h, 6, BF, BC, 1.5,
                          h3_text, FONT_H3, False, FC))
            track(h3x, h3y, H3_W, h3_h)
            curves.append((h2x + sign * H2_W/2, h2y, h3x - sign * H3_W/2, h3y, BC, 1.5))

            h4_list = h3_node.get("children", [])
            if h4_list:
                h4_heights = [calc_wrap_h(h4["title"], FONT_H4, MAX_CHARS_H4) for h4 in h4_list]
                h4_total = sum(h4_heights) + (len(h4_list) - 1) * Y_SPACING_H4
                # 以 h3y 为中心对称分布 H4
                cum_h4 = h3y - h4_total / 2
                for k, h4_node in enumerate(h4_list):
                    h4_h = h4_heights[k]
                    h4y = cum_h4 + h4_h / 2
                    cum_h4 += h4_h + Y_SPACING_H4
                    h4x = CX + sign * X_H4
                    h4_text = "\n".join(wrap_text(h4_node["title"], FONT_H4, MAX_CHARS_H4)[0])
                    nodes.append((h4x, h4y, H4_W, h4_h, 5, BF, BC, 1,
                                  h4_text, FONT_H4, False, FC))
                    track(h4x, h4y, H4_W, h4_h)
                    curves.append((h3x + sign * H3_W/2, h3y, h4x - sign * H4_W/2, h4y, BC, 1))

    for i in range(left_count):
        add_branch(i, h2_list[i], True, left_y[i])
    for i in range(right_count):
        add_branch(left_count + i, h2_list[left_count + i], False, right_y[i])

    # ── 紧凑边界 ──
    pad = 80
    vx_orig = CX - max(abs(min(all_x) - CX), abs(max(all_x) - CX)) - pad
    vy_orig = CY - max(abs(min(all_y) - CY), abs(max(all_y) - CY)) - pad
    vw = (CX + max(abs(min(all_x) - CX), abs(max(all_x) - CX)) + pad) - vx_orig + 1
    vh = (CY + max(abs(min(all_y) - CY), abs(max(all_y) - CY)) + pad) - vy_orig + 1

    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{int(vw)}" height="{int(vh)}" viewBox="{vx_orig:.0f} {vy_orig:.0f} {vw:.0f} {vh:.0f}">']
    svg.append(f'<rect x="{vx_orig:.0f}" y="{vy_orig:.0f}" width="{int(vw)}" height="{int(vh)}" fill="{BG}"/>')
    svg.append('''<defs>
    <filter id="shadow" x="-10%" y="-10%" width="130%" height="130%">
      <feDropShadow dx="1" dy="1" stdDeviation="2" flood-opacity="0.15"/>
    </filter>
  </defs>''')

    for x1, y1, x2, y2, color, w in curves:
        mid_x = (x1 + x2) / 2
        svg.append(
            f'<path d="M{x1:.1f},{y1:.1f} C{mid_x:.1f},{y1:.1f} {mid_x:.1f},{y2:.1f} {x2:.1f},{y2:.1f}" '
            f'stroke="{color}" stroke-width="{w}" fill="none" stroke-linecap="round"/>'
        )

    for cx, cy, w, h, rx, fill, stroke, sw, text, fs, bold, tc in nodes:
        x, y = cx - w/2, cy - h/2
        fw = "bold" if bold else "normal"
        svg.append(
            f'<rect x="{x:.1f}" y="{y:.1f}" width="{w}" height="{h}" rx="{rx}" '
            f'fill="{fill}" stroke="{stroke}" stroke-width="{sw}" filter="url(#shadow)"/>'
        )
        lines = text.split("\n")
        if len(lines) == 1:
            svg.append(
                f'<text x="{cx:.1f}" y="{cy:.1f}" text-anchor="middle" dominant-baseline="central" '
                f'font-family="Microsoft YaHei,SimHei,sans-serif" font-size="{fs}" '
                f'font-weight="{fw}" fill="{tc}">{text}</text>'
            )
        else:
            # 多行：用 tspan 逐行垂直居中
            line_h = fs * 1.6
            start_y = cy - (len(lines) - 1) * line_h / 2
            tspans = "".join(
                f'<tspan x="{cx:.1f}" y="{start_y + i * line_h:.1f}">{ln}</tspan>'
                for i, ln in enumerate(lines)
            )
            svg.append(
                f'<text text-anchor="middle" '
                f'font-family="Microsoft YaHei,SimHei,sans-serif" font-size="{fs}" '
                f'font-weight="{fw}" fill="{tc}">{tspans}</text>'
            )

    svg.append('</svg>')

    vw_int, vh_int = int(vw), int(vh)
    return f'''<!DOCTYPE html>
<html><head><meta charset="UTF-8">
<style>
  body{{margin:0;padding:0;background:{BG};}}
  #wrap{{width:{vw_int}px;height:{vh_int}px;overflow:hidden;}}
</style></head><body><div id="wrap">
{"".join(svg)}
</div></body></html>'''


def render(xmind_path, output_path):
    root_title, h2_list = parse_xmind(xmind_path)
    if not h2_list:
        print("错误: 未解析到标题树")
        sys.exit(1)

    html = generate_html(root_title, h2_list)
    out = Path(output_path)
    html_path = out.with_suffix(".html")
    html_path.write_text(html, encoding="utf-8")
    print(f"[svg] HTML: {html_path}")

    from playwright.sync_api import sync_playwright

    # 自动检测本地封装的 Chromium
    import os as _os
    _skill_dir = Path(__file__).resolve().parent
    _chrome_exe = None
    for _root, _dirs, _files in _os.walk(str(_skill_dir / "browsers")):
        for _f in _files:
            if _f in ("chrome.exe", "chrome", "chromium.exe", "chromium"):
                _chrome_exe = _os.path.join(_root, _f)
                break

    with sync_playwright() as p:
        if _chrome_exe:
            browser = p.chromium.launch(executable_path=_chrome_exe, headless=True)
        else:
            try:
                browser = p.chromium.launch(channel="chrome", headless=True)
            except Exception:
                browser = p.chromium.launch(headless=True)
        page = browser.new_page(device_scale_factor=3)
        page.goto(html_path.resolve().as_uri())
        page.wait_for_timeout(300)
        wrap = page.query_selector("#wrap")
        if wrap:
            wrap.screenshot(path=str(out))
        else:
            page.screenshot(path=str(out), full_page=True)
        browser.close()

    kb = out.stat().st_size / 1024
    max_h3 = max((len(n.get("children", [])) for n in h2_list), default=0)
    print(f"[render] 完成: {out}  ({kb:.0f} KB, {len(h2_list)} H2 * {max_h3} H3)")


def main():
    p = argparse.ArgumentParser(description="XMind → SVG 左右对称 → PNG")
    p.add_argument("--input", required=True)
    p.add_argument("--output", required=True)
    a = p.parse_args()
    render(a.input, a.output)


if __name__ == "__main__":
    main()
