# -*- coding: utf-8 -*-
"""对已渲染的分档 HTML 做**静态验收**（不启动浏览器、不重跑渲染）。

这是「动画一致性与可用性验收」的机械部分：能自动判的自动判，判不了的明确标
NOT_TESTED，交给人工预览（平台没有浏览器工具时，视觉验收只能人工做，不得写成通过）。

用法：
    python check_animation.py --animation <HTML 产物目录> [--quality <quality_report.json>] [--out <qa.json>]

检查项：
    F1 至少一份分档 HTML；每份体积 ≥ --min-bytes（默认 1000）
    F2 模板占位符已全部替换（__TITLE__ / __STEPS_JSON__ 不得残留）
    F3 内嵌 <script id="DATA"> 的 JSON 可解析（LaTeX 转义 / </script> 逃逸的兜底）
    F4 每份含分档切换锚点 splitnav，且 DATA.deck 的档名/文件清单自洽
    F5 每档至少一幕、每幕至少一块；块类型限定 html/pills/matrix/svgclick/slider/chart
    F6 质量报告阈值：parts ≥ 2、scenes ≥ 4、blocks ≥ 12、chart_points > 0
    F7 禁用开场词（题目原文 / 题面原文 / 赛题原文 / 原题全文 / 封面 / 内容概览）

退出码：0 全通过（可含 NOT_TESTED）；1 存在 FAIL
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List

BLOCK_TYPES = {"html", "pills", "matrix", "svgclick", "slider", "chart"}
FORBIDDEN = ("题目原文", "题面原文", "赛题原文", "原题全文", "封面", "内容概览")
DATA_PATTERN = re.compile(
    r'<script[^>]*id="DATA"[^>]*>(.*?)</script>', flags=re.DOTALL | re.IGNORECASE
)
DEFAULT_MIN_PARTS = 2
DEFAULT_MIN_SCENES = 4
DEFAULT_MIN_BLOCKS = 12


def _fix_stdout_encoding() -> None:
    for stream in (sys.stdout, sys.stderr):
        if stream and hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8", errors="replace")
            except Exception:  # noqa: BLE001
                pass


def _item(item_id: str, status: str, detail: str, severity: str = "P0") -> Dict[str, str]:
    return {"item_id": item_id, "status": status, "severity": severity, "detail": detail}


def _scene_text(scene: Dict[str, Any]) -> str:
    text = " ".join(str(scene.get(k) or "") for k in ("title", "kicker", "h1", "small", "lead"))
    for block in scene.get("blocks") or []:
        text += " " + " ".join(
            str(block.get(key) or "") for key in ("html", "title", "label", "sideTitle", "note")
        )
    return text


def check(animation_dir: Path, quality_path: Path | None, min_bytes: int) -> List[Dict[str, str]]:
    results: List[Dict[str, str]] = []

    html_files = sorted(p for p in animation_dir.glob("*.html"))
    if not html_files:
        results.append(_item("F1-分档文件", "FAIL", f"{animation_dir} 下没有 .html"))
        return results

    small = [p.name for p in html_files if p.stat().st_size < min_bytes]
    results.append(
        _item(
            "F1-分档文件",
            "FAIL" if small else "PASS",
            f"{len(html_files)} 份 HTML；偏小：{small}" if small else f"{len(html_files)} 份 HTML，体积均 ≥ {min_bytes} 字节",
        )
    )

    decks: Dict[str, Any] = {}
    for path in html_files:
        html = path.read_text(encoding="utf-8", errors="replace")

        leftovers = [token for token in ("__TITLE__", "__STEPS_JSON__") if token in html]
        results.append(
            _item(
                f"F2-占位符[{path.name}]",
                "FAIL" if leftovers else "PASS",
                f"残留 {leftovers}" if leftovers else "占位符已全部替换",
            )
        )

        match = DATA_PATTERN.search(html)
        if not match:
            results.append(_item(f"F3-DATA[{path.name}]", "FAIL", "找不到 <script id=\"DATA\">"))
            continue
        try:
            data = json.loads(match.group(1))
        except json.JSONDecodeError as exc:
            results.append(_item(f"F3-DATA[{path.name}]", "FAIL", f"内嵌 JSON 解析失败：{exc}"))
            continue
        results.append(_item(f"F3-DATA[{path.name}]", "PASS", "内嵌 JSON 可解析"))

        if "splitnav" not in html:
            results.append(_item(f"F4-分档切换[{path.name}]", "FAIL", "缺少 splitnav 顶栏切换锚点"))
        else:
            results.append(_item(f"F4-分档切换[{path.name}]", "PASS", "顶栏分档切换存在"))

        scenes = data.get("scenes") or []
        if not scenes:
            results.append(_item(f"F5-结构[{path.name}]", "FAIL", "该档没有幕"))
            continue
        problems: List[str] = []
        for si, scene in enumerate(scenes):
            blocks = scene.get("blocks") or []
            if not blocks:
                problems.append(f"第{si + 1}幕没有块")
            for block in blocks:
                btype = str(block.get("type") or "")
                if btype not in BLOCK_TYPES:
                    problems.append(f"第{si + 1}幕出现非法块类型 {btype!r}")
            hit = [term for term in FORBIDDEN if term in _scene_text(scene)]
            if hit:
                problems.append(f"第{si + 1}幕含禁用开场词 {hit}")
        results.append(
            _item(
                f"F5-结构[{path.name}]",
                "FAIL" if problems else "PASS",
                "; ".join(problems) if problems else f"{len(scenes)} 幕，块类型全部合法，无禁用开场词",
            )
        )
        decks[path.name] = data

    # F4 补强：DATA.deck 与目录内实际文件自洽
    if decks:
        sample_name, sample = next(iter(decks.items()))
        deck_meta = sample.get("deck") or {}
        named = list(deck_meta.get("files") or [])
        actual = [p.name for p in html_files]
        missing = [name for name in actual if name not in named]
        results.append(
            _item(
                "F4b-档清单自洽",
                "FAIL" if missing else "PASS",
                f"实际文件未登记在 deck.files：{missing}" if missing else f"deck 登记 {len(named)} 档，与目录一致（样本 {sample_name}）",
            )
        )

    # F6 质量报告阈值
    if quality_path and quality_path.is_file():
        quality = json.loads(quality_path.read_text(encoding="utf-8"))
        checks = [
            ("parts", DEFAULT_MIN_PARTS),
            ("scenes", DEFAULT_MIN_SCENES),
            ("blocks", DEFAULT_MIN_BLOCKS),
            ("chart_points", 1),
        ]
        shortfall = [
            f"{key}={quality.get(key)} < {floor}"
            for key, floor in checks
            if int(quality.get(key) or 0) < floor
        ]
        results.append(
            _item(
                "F6-质量阈值",
                "FAIL" if shortfall else "PASS",
                "; ".join(shortfall) if shortfall else "分档/幕/块/图表点数均达标",
            )
        )
        for warning in quality.get("warnings") or []:
            results.append(_item("F6b-内容完整性", "NOT_TESTED", str(warning), severity="P1"))
    else:
        results.append(
            _item("F6-质量阈值", "NOT_TESTED", "未提供 quality_report.json，阈值未复核", severity="P1")
        )

    # 视觉与交互验收：平台无浏览器工具，只能人工
    results.append(
        _item(
            "F7-视觉与交互",
            "NOT_TESTED",
            "鼠标/触屏交互、桌面与移动端无裁切、逐幕翻页与自动讲解需人工打开页面确认",
            severity="P1",
        )
    )
    return results


def main() -> int:
    _fix_stdout_encoding()
    parser = argparse.ArgumentParser(description="Static acceptance checks for rendered animation HTML")
    parser.add_argument("--animation", required=True, help="HTML 产物目录")
    parser.add_argument("--quality", default=None, help="quality_report.json 路径（默认取产物目录内同名文件）")
    parser.add_argument("--out", default=None, help="qa JSON 输出路径（默认写到产物目录上级 qa/）")
    parser.add_argument("--min-bytes", type=int, default=1000, help="单份 HTML 最小体积（默认 1000）")
    args = parser.parse_args()

    animation_dir = Path(args.animation)
    if not animation_dir.is_dir():
        raise SystemExit(f"产物目录不存在：{animation_dir}")

    quality_path = Path(args.quality) if args.quality else animation_dir / "quality_report.json"
    results = check(animation_dir, quality_path if quality_path.is_file() else None, args.min_bytes)

    failures = [item for item in results if item["status"] == "FAIL"]
    not_tested = [item for item in results if item["status"] == "NOT_TESTED"]

    for item in results:
        mark = {"PASS": "PASS", "FAIL": "FAIL", "NOT_TESTED": "NOT_TESTED"}[item["status"]]
        print(f"[{mark}] {item['item_id']}: {item['detail']}")
    print(
        f"\n汇总：{len(results) - len(failures) - len(not_tested)} 项通过，"
        f"{len(failures)} 项失败，{len(not_tested)} 项待人工确认"
    )

    payload = {
        "checked_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "animation_dir": str(animation_dir),
        "items": results,
        "summary": {
            "pass": len(results) - len(failures) - len(not_tested),
            "fail": len(failures),
            "not_tested": len(not_tested),
        },
    }
    out_path = Path(args.out) if args.out else animation_dir.parent / "qa" / "qa_animation.json"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"报告：{out_path.resolve()}")

    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
