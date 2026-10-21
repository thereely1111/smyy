# -*- coding: utf-8 -*-
"""从 scene_map.json 渲染分档动画 HTML：清洗 → 结构化闸门 → 真实数据注入 → 模板填充。

**不调用任何 LLM**：scene_map 由「分析动画智能体」自己产出，本脚本只做确定性渲染，
因此同一份 scene_map 永远得到同一份 HTML。

用法：
    python render_animation.py --scene-map <scene_map.json> --problem <题目目录或PDF> \
        --out-dir <产物目录> [--facts <facts.json>] [--title <标题>]
    python render_animation.py --problem <题目目录或PDF> --out-dir <产物目录> --deterministic

输出（均在 --out-dir 下）：
    01_<档名>.html …        分档 HTML（每档一份，顶栏可切换）
    quality_report.json     结构闸门与质量统计（幕/块/图表点数/警告）
    _generation_manifest.json  输入哈希 + 附件清单 + 输出清单

退出码：0 成功；1 结构闸门不通过（stderr 会列出每条 structure_errors，据此修 scene_map 重跑）
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List

sys.path.insert(0, str(Path(__file__).resolve().parent / "modules"))

from data_facts import compute_facts, resolve_chart_data  # noqa: E402
from deck_enricher import deck_quality_report, enrich_parts  # noqa: E402
from deck_render import (  # noqa: E402
    TutorialGenerationError,
    _clean_scene,
    _validate_reference_structure,
    render_deck,
)
from doc_parser import _scan_one_problem, build_problem_input  # noqa: E402

MIN_HTML_BYTES = 1000


def _fix_stdout_encoding() -> None:
    for stream in (sys.stdout, sys.stderr):
        if stream and hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8", errors="replace")
            except Exception:  # noqa: BLE001
                pass


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _load_scene_map(path: Path) -> Dict[str, Any]:
    """读取模型产出的 scene_map.json；容忍 ```json 包裹与结尾的注释/标记。"""
    text = path.read_text(encoding="utf-8").strip()
    fence = re.match(r"^```(?:json)?\s*(.*?)```\s*$", text, flags=re.DOTALL)
    if fence:
        text = fence.group(1).strip()
    text = text.replace("<!--STEPS_END-->", "").strip()
    try:
        payload = json.loads(text)
    except json.JSONDecodeError as exc:
        raise SystemExit(f"scene_map.json 不是合法 JSON：{exc}\n提示：不要写注释、不要留尾逗号。") from exc
    if not isinstance(payload, dict):
        raise SystemExit("scene_map.json 顶层必须是 JSON 对象（含 title / summary / parts）。")
    return payload


def _normalize_parts(payload: Dict[str, Any]) -> List[dict]:
    """与上游 _generate_analysis 相同的清洗口径：逐幕 _clean_scene，丢掉空档。"""
    parts: List[dict] = []
    raw_parts = payload.get("parts")
    if not isinstance(raw_parts, list):
        return parts
    for pi, part in enumerate(raw_parts):
        if not isinstance(part, dict):
            continue
        name = str(part.get("name") or "").strip() or f"档{pi + 1}"
        scenes = []
        for si, scene in enumerate(part.get("scenes") or []):
            cleaned = _clean_scene(scene, si)
            if cleaned:
                scenes.append(cleaned)
        if scenes:
            parts.append({"name": name, "scenes": scenes})
    return parts


def _resolve_problem_question(problem_arg: str) -> tuple[str, dict]:
    target = Path(problem_arg).resolve()
    if target.is_file():
        problem = _scan_one_problem(target.parent, problem_pdf=target)
    else:
        problem = _scan_one_problem(target)
    if problem is None:
        raise SystemExit(f"无法解析题目：{target}")
    return build_problem_input(problem), problem


def _write_outputs(out_dir: Path, htmls: List[Dict[str, str]]) -> List[str]:
    """写出分档 HTML；任何一份过小都视为渲染失败（上游同口径）。"""
    written: List[str] = []
    for item in htmls:
        filename = Path(str(item.get("file") or "page")).name
        if not filename.endswith(".html"):
            filename += ".html"
        html = str(item.get("html") or "")
        if len(html.encode("utf-8")) < MIN_HTML_BYTES:
            raise SystemExit(f"拒绝写出异常偏小的 HTML：{filename}（{len(html)} 字符）")
        (out_dir / filename).write_text(html, encoding="utf-8")
        written.append(filename)
    return written


def main() -> int:
    _fix_stdout_encoding()
    parser = argparse.ArgumentParser(description="Render split animation HTML from a scene map (no LLM)")
    parser.add_argument("--scene-map", default=None, help="scene_map.json 路径（模型产出）")
    parser.add_argument("--problem", required=True, help="题目目录或题目 PDF 路径")
    parser.add_argument("--out-dir", required=True, help="HTML 产物目录（如 animation/）")
    parser.add_argument("--facts", default=None, help="facts.json 路径；缺省时从题目附件现算")
    parser.add_argument("--title", default=None, help="覆盖标题；缺省用 scene_map.title")
    parser.add_argument(
        "--deterministic",
        action="store_true",
        help="不用 scene_map，直接由题面与事实生成可审计版本（链路自测用）",
    )
    args = parser.parse_args()

    if not args.deterministic and not args.scene_map:
        raise SystemExit("缺少 --scene-map（或用 --deterministic 做链路自测）")

    question, problem = _resolve_problem_question(args.problem)

    if args.facts and Path(args.facts).is_file():
        facts = json.loads(Path(args.facts).read_text(encoding="utf-8"))
    else:
        facts = compute_facts(problem)

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    if args.deterministic:
        payload: Dict[str, Any] = {
            "title": args.title or problem.get("title") or "数模演易",
            "summary": "Audited deterministic deck built from the problem statement and parsed attachment facts.",
            "parts": [],
        }
    else:
        payload = _load_scene_map(Path(args.scene_map))

    title = str(args.title or payload.get("title") or problem.get("title") or "数模演易")
    summary = str(payload.get("summary") or "")

    parts = _normalize_parts(payload) if not args.deterministic else []
    parts, question_count = enrich_parts(parts, question, facts)

    deck: Dict[str, Any] = {
        "title": title,
        "summary": summary,
        "parts": parts,
        "question_count": question_count,
    }
    resolve_chart_data(facts, deck)

    try:
        _validate_reference_structure(parts)
    except TutorialGenerationError as exc:
        errors = (exc.debug_info or {}).get("structure_errors") or [str(exc)]
        print("结构闸门未通过，按下列条目修 scene_map.json 后重跑：", file=sys.stderr)
        for item in errors:
            print(f"  - {item}", file=sys.stderr)
        return 1

    quality = deck_quality_report(deck, facts)
    htmls = render_deck(deck)
    written = _write_outputs(out_dir, htmls)

    quality_out = dict(quality)
    quality_out["html_files"] = written
    quality_out["generation_mode"] = "deterministic-facts" if args.deterministic else "scene-map+deterministic-render"
    (out_dir / "quality_report.json").write_text(
        json.dumps(quality_out, ensure_ascii=False, indent=2, default=str), encoding="utf-8"
    )

    pdf_path = Path(str(problem.get("problem_pdf") or ""))
    manifest = {
        "generated_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "generator": "mm-2-render-animation",
        "generation_mode": quality_out["generation_mode"],
        "title": title,
        "question_count": question_count,
        "source_pdf": str(pdf_path),
        "source_pdf_sha256": _sha256(pdf_path) if pdf_path.is_file() else None,
        "scene_map": str(Path(args.scene_map).resolve()) if args.scene_map else None,
        "scene_map_sha256": _sha256(Path(args.scene_map)) if args.scene_map and Path(args.scene_map).is_file() else None,
        "attachments": [
            {"name": item.get("name"), "size": item.get("size"), "suffix": item.get("suffix")}
            for item in (facts.get("_catalog") or [])
        ],
        "facts": {
            "series": len(facts.get("_series") or {}),
            "stats": len(facts.get("_stats") or {}),
            "tables": len(facts.get("_table_meta") or {}),
            "notes": list(facts.get("_notes") or []),
        },
        "output": {"html_files": written, "quality": quality_out},
    }
    (out_dir / "_generation_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2, default=str), encoding="utf-8"
    )

    n_scenes = sum(len(p.get("scenes", [])) for p in parts)
    n_blocks = sum(len(sc.get("blocks", [])) for p in parts for sc in p.get("scenes", []))
    print(f"标题：{title}")
    print(f"分档：{len(parts)}；幕：{n_scenes}；块：{n_blocks}；图表点数：{quality.get('chart_points', 0)}")
    for name in written:
        print(f"  写出 {name}")
    for warning in quality.get("warnings") or []:
        print(f"! 内容完整性：{warning}")
    print(f"\n产物目录：{out_dir.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
