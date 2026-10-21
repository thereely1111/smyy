# -*- coding: utf-8 -*-
"""解析赛题输入：题目 PDF + 附件 → 供模型阅读的摘要 + 附件事实 + 页码图。

本脚本只读输入，不修改题目目录；所有输出路径由调用方显式给出。
不调用任何 LLM —— 模型推理发生在「题目解析智能体」自己身上。

用法：
    python probe_inputs.py --problem <题目目录或PDF> --out-dir <产物目录> [--max-pages 30] [--no-pages]

输出（均在 --out-dir 下）：
    input_digest.md    题目正文 + 附件结构说明 + 已计算事实摘要（模型读这个）
    facts.json         附件事实（渲染脚本用它把真实数组注入 chart）
    pages/page_NN.png  题目 PDF 页码图（默认渲染，供 read_image 读公式）

stdout：审计摘要（附件数、series/stats/table 计数、注意事项）
退出码：0 成功；1 输入不可读或解析失败
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "modules"))

from data_facts import compute_facts, summarize  # noqa: E402
from doc_parser import _scan_one_problem, build_problem_input  # noqa: E402


def _fix_stdout_encoding() -> None:
    for stream in (sys.stdout, sys.stderr):
        if stream and hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8", errors="replace")
            except Exception:  # noqa: BLE001
                pass


def _resolve_problem(target: Path) -> dict:
    """把「题目目录」或「PDF 路径」统一解析为 problem dict。"""
    target = target.resolve()
    if target.is_file():
        problem = _scan_one_problem(target.parent, problem_pdf=target)
        if problem is None:
            # PDF 文本层为空（扫描件）时仍保留页码图，让 read_image 兜底
            from doc_parser import extract_problem_pdf

            extracted = extract_problem_pdf(target)
            problem = {
                "title": target.stem,
                "problem_text": extracted.get("text", ""),
                "attachments": [],
                "page_images": extracted.get("page_images", []),
                "problem_pdf": str(target),
            }
        return problem
    problem = _scan_one_problem(target)
    if problem is None:
        raise SystemExit(f"无法解析题目：{target}（目录下没有可读的 PDF，或 PDF 文本层为空）")
    return problem


def _render_pages(pdf: Path, pages_dir: Path, max_pages: int) -> list[str]:
    """把 PDF 每页渲染成 PNG 落盘，返回相对 pages_dir 的文件名列表。"""
    try:
        import pymupdf as fitz
    except ImportError:
        print("! 未安装 pymupdf，跳过页码图渲染（公式将以文本层为准）")
        return []

    pages_dir.mkdir(parents=True, exist_ok=True)
    written: list[str] = []
    doc = fitz.open(str(pdf))
    try:
        for index, page in enumerate(doc):
            if index >= max_pages:
                print(f"! 页数超过 {max_pages}，其余页面未渲染")
                break
            pix = page.get_pixmap(matrix=fitz.Matrix(2.0, 2.0))
            name = f"page_{index + 1:02d}.png"
            (pages_dir / name).write_bytes(pix.tobytes("png"))
            written.append(name)
    finally:
        doc.close()
    return written


def main() -> int:
    _fix_stdout_encoding()
    parser = argparse.ArgumentParser(description="Parse competition problem + attachments for the animation agent")
    parser.add_argument("--problem", required=True, help="题目目录或题目 PDF 路径")
    parser.add_argument("--out-dir", required=True, help="产物目录（analysis/ 或 truth/ 的父目录）")
    parser.add_argument("--max-pages", type=int, default=30, help="最多渲染多少页 PNG（默认 30）")
    parser.add_argument("--no-pages", action="store_true", help="不渲染页码图")
    args = parser.parse_args()

    problem = _resolve_problem(Path(args.problem))
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    facts = compute_facts(problem)

    # 1) 附件事实：渲染脚本据此把真实数组注入 chart，杜绝示意数字冒充真值
    (out_dir / "facts.json").write_text(
        json.dumps(facts, ensure_ascii=False, indent=2, default=str), encoding="utf-8"
    )

    # 2) 模型输入摘要：题面正文 + 附件结构 + 已计算事实
    digest = [build_problem_input(problem)]
    facts_summary = ""
    try:
        facts_summary = summarize(facts)
    except Exception as exc:  # noqa: BLE001
        print(f"! 事实摘要生成失败（不影响 facts.json）: {exc}")
    if facts_summary:
        digest += ["", "## 已计算附件事实（可直接引用 facts: 键）", "", facts_summary]
    (out_dir / "input_digest.md").write_text("\n".join(digest), encoding="utf-8")

    # 3) 页码图：文本层公式会被拉平，视觉模型读图更准
    page_files: list[str] = []
    pdf_path = Path(str(problem.get("problem_pdf") or ""))
    if not args.no_pages and pdf_path.is_file():
        page_files = _render_pages(pdf_path, out_dir / "pages", max(1, args.max_pages))

    catalog = facts.get("_catalog") or []
    print(f"题目：{problem.get('title')}")
    print(f"PDF：{pdf_path if pdf_path.is_file() else '-'}；页码图 {len(page_files)} 张")
    print(f"正文 {len(problem.get('problem_text') or '')} 字符；附件 {len(catalog)} 份")
    print(
        f"事实：series={len(facts.get('_series') or {})} "
        f"stats={len(facts.get('_stats') or {})} tables={len(facts.get('_table_meta') or {})}"
    )
    for item in catalog:
        sheets = item.get("sheets") or []
        print(f"- {item.get('name')} | {item.get('size', 0)} bytes")
        if not sheets:
            print("    （无数值表：可能是空结果模板）")
        for sheet in sheets:
            print(
                f"    sheet {sheet.get('name')}: {sheet.get('rows')} 行 x "
                f"{sheet.get('columns')} 列；numeric={sheet.get('numeric')}"
            )
    for note in facts.get("_notes") or []:
        print(f"! {note}")
    print(f"\n写出：{out_dir / 'input_digest.md'}")
    print(f"写出：{out_dir / 'facts.json'}")
    if not (problem.get("problem_text") or "").strip() and not page_files:
        print("! 题目正文为空且没有页码图：无法继续分析")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
