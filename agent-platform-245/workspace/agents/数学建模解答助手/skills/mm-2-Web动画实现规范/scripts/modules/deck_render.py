# -*- coding: utf-8 -*-
"""赛题分析生成核心（数模演易 · 竞赛题 → 分档分析动画 HTML）。

职责：把竞赛题目 + 附件交给 LLM，一次请求生成 {title, summary, parts} 的分档分析教程
（全局 + 各子问题，每档含若干幕，每幕由 typed blocks：html/pills/matrix/svgclick/slider/chart 组成），
再注入自包含的「赛题分析动画」HTML 模板，按档拆成多份 HTML。
只保留「流式调用 + JSON 解析校验 + 模板填充」。
"""
from __future__ import annotations

import json
import os
import re
from html import escape as html_escape
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

# --- 平台适配层：以下三处之外，本文件与上游 animation_generator.py 逐字一致 ---
# 上游用 openai 客户端自行调用 LLM；在 Agent 平台里模型由 harness 提供，
# 本模块只承担「清洗 → 结构化闸门 → 模板填充」的确定性渲染，故 LLM 依赖改为可选导入。
try:
    from openai import OpenAI
except ImportError:  # 纯渲染链路不需要 openai
    OpenAI = None  # type: ignore[assignment]

try:
    import config
except ImportError:  # 无 .env / 无 python-dotenv 时不影响渲染
    config = None  # type: ignore[assignment]

# 模板位于 skills/mm-2-Web动画实现规范/templates/
TEMPLATE_DIR = Path(__file__).resolve().parent.parent.parent / "templates"

TEACHING_TEMPLATE_PATH = TEMPLATE_DIR / "teaching_template.html"
# 上游的两个 prompt 文件在平台内由 Skill 承担（见 mm-2-分析到场景映射/references/scene_schema.md），
# 以下常量只被本文件里未使用的 LLM 分支引用，正常运行不会读取。
STEPS_SYSTEM_PROMPT_PATH = TEMPLATE_DIR / "steps_system_prompt.txt"
STEPS_USER_PROMPT_PATH = TEMPLATE_DIR / "steps_user_prompt.txt"

STEPS_END_MARKER = "<!--STEPS_END-->"

BLOCK_TYPES = {"html", "pills", "matrix", "svgclick", "slider", "chart"}

INTERACTIVE_BLOCK_TYPES = {'pills', 'matrix', 'svgclick', 'slider', 'chart'}

_FORBIDDEN_INTRO_PHRASES = (
    '题目原文',
    '题面原文',
    '赛题原文',
    '原题全文',
    '封面',
    '内容概览',
)

_kimi_client: Optional[OpenAI] = None
_backup_client: Optional[OpenAI] = None


class TutorialGenerationError(RuntimeError):
    """分步教程数据生成失败时携带诊断信息。"""

    def __init__(self, message: str, debug_info: Optional[Dict] = None):
        super().__init__(message)
        self.debug_info = debug_info or {}


def log(message: str, level: str = "INFO") -> None:
    line = f"[{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}] [{level}] {message}"
    try:
        print(line, flush=True)
    except UnicodeEncodeError:
        buf = getattr(sys.stdout, "buffer", None)
        if buf is not None:
            buf.write((line + "\n").encode("utf-8", errors="replace"))
            buf.flush()


# ---------------------------------------------------------------------------
# 客户端
# ---------------------------------------------------------------------------

def _get_kimi_client() -> OpenAI:
    global _kimi_client
    if _kimi_client is None:
        if not config.API_KEY:
            raise RuntimeError("缺少 KIMI_API_KEY，请在 .env 中配置")
        _kimi_client = OpenAI(
            api_key=config.API_KEY,
            base_url=config.BASE_URL,
            timeout=600.0,
        )
        log(f"Kimi Client 初始化完成，模型: {config.GENERAL_MODEL}, base_url: {config.BASE_URL}")
    return _kimi_client


def _get_backup_client() -> OpenAI:
    global _backup_client
    if _backup_client is None:
        if not config.BACKUP_API_KEY:
            raise RuntimeError("备用模型未配置 BACKUP_MODEL_API_KEY")
        _backup_client = OpenAI(
            api_key=config.BACKUP_API_KEY,
            base_url=config.BACKUP_BASE_URL,
            timeout=600.0,
        )
        log(f"备用模型 Client 初始化完成，模型: {config.BACKUP_MODEL}, base_url: {config.BACKUP_BASE_URL}")
    return _backup_client


def _load_prompt_template(file_path: Path) -> str:
    try:
        if file_path.exists():
            return file_path.read_text(encoding="utf-8").strip()
        log(f"Prompt 文件不存在: {file_path}", "WARN")
        return ""
    except Exception as exc:  # noqa: BLE001
        log(f"读取 Prompt 文件失败 {file_path}: {exc}", "ERROR")
        return ""


# ---------------------------------------------------------------------------
# 流式调用
# ---------------------------------------------------------------------------

def _stream_once(
    client: OpenAI,
    model_name: str,
    system_prompt: str,
    user_prompt: str,
    max_completion_tokens: int,
) -> Tuple[str, str]:
    kwargs = {
        "model": model_name,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        "max_completion_tokens": max_completion_tokens,
        "stream": True,
        "timeout": 900.0,
    }
    params = config.get_params_for_model(model_name)
    if params:
        kwargs.update(params)
    try:
        stream = client.chat.completions.create(**kwargs)
    except TypeError as exc:
        if "max_completion_tokens" not in str(exc):
            raise
        kwargs["max_tokens"] = kwargs.pop("max_completion_tokens")
        stream = client.chat.completions.create(**kwargs)

    text = ""
    finish_reason = "stop"
    for ev in stream:
        if not ev.choices:
            continue
        delta = ev.choices[0].delta.content or ""
        if delta:
            text += delta
        fr = getattr(ev.choices[0], "finish_reason", None)
        if fr:
            finish_reason = fr
    return text, finish_reason


# ---------------------------------------------------------------------------
# JSON 提取与修复
# ---------------------------------------------------------------------------

def _extract_first_json_object(text: str) -> str:
    if not text:
        return ""
    start = text.find("{")
    if start == -1:
        return ""
    depth = 0
    in_string = False
    escape_next = False
    for i in range(start, len(text)):
        ch = text[i]
        if escape_next:
            escape_next = False
            continue
        if ch == "\\":
            escape_next = True
            continue
        if ch == '"':
            in_string = not in_string
            continue
        if not in_string:
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return text[start:i + 1]
    return ""


def _escape_json_control_chars_in_strings(text: str) -> str:
    if not text:
        return text
    out: List[str] = []
    in_string = False
    escape_next = False
    for ch in text:
        if in_string:
            if escape_next:
                out.append(ch)
                escape_next = False
                continue
            if ch == "\\":
                out.append(ch)
                escape_next = True
                continue
            if ch == '"':
                out.append(ch)
                in_string = False
                continue
            if ch == "\n":
                out.append("\\n")
                continue
            if ch == "\r":
                out.append("\\r")
                continue
            if ch == "\t":
                out.append("\\t")
                continue
            if ord(ch) < 0x20:
                out.append(f"\\u{ord(ch):04x}")
                continue
            out.append(ch)
            continue
        out.append(ch)
        if ch == '"':
            in_string = True
    return "".join(out)


def _sanitize_diagram(svg: str) -> str:
    """剔除 SVG 里的 <script>、事件属性与 javascript: 链接，保证注入安全。"""
    if not svg:
        return ""
    svg = re.sub(r"<script\b[^>]*>.*?</script>", "", svg, flags=re.IGNORECASE | re.DOTALL)
    svg = re.sub(r"\son[a-z]+\s*=\s*(\"[^\"]*\"|'[^']*'|[^\s>]+)", "", svg, flags=re.IGNORECASE)
    svg = re.sub(r"\shref\s*=\s*[\"']\s*javascript:[^\"']*[\"']", "", svg, flags=re.IGNORECASE)
    return svg


# ---------------------------------------------------------------------------
# 流式收集 + JSON 解析
# ---------------------------------------------------------------------------

def _build_image_parts(page_images: Optional[List[str]]) -> List[dict]:
    if not page_images:
        return []
    return [
        {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{b}"}}
        for b in page_images
    ]


def _stream_until_marker(
    client: OpenAI,
    model_name: str,
    system_prompt: str,
    user_prompt: str,
    marker: str,
    marker_label: str,
    max_output_tokens: int,
    max_rounds: int,
    continue_tokens: int,
    image_parts: Optional[List[dict]] = None,
) -> Tuple[str, List[str]]:
    combined = ""
    current_prompt = user_prompt
    finish_reasons: List[str] = []

    for round_idx in range(max_rounds + 1):
        round_max_tokens = max_output_tokens if round_idx == 0 else max(1024, min(max_output_tokens, continue_tokens))
        if round_idx == 0 and image_parts:
            user_content = [{"type": "text", "text": current_prompt}] + image_parts
        else:
            user_content = current_prompt
        text, finish_reason = _stream_once(client, model_name, system_prompt, user_content, round_max_tokens)
        finish_reasons.append(finish_reason)
        if text:
            if combined and not combined.endswith("\n"):
                combined += "\n"
            combined += text
        if marker in combined:
            break
        if round_idx < max_rounds:
            tail = combined[-800:] if combined else ""
            current_prompt = (
                "继续输出剩余内容，仅输出新增部分，不要重复。"
                "最后一行必须追加结束标记 " + marker + "。\n\n"
                "末尾片段（仅定位用，禁止复述）：\n" + tail
            )

    if marker not in combined:
        raise TutorialGenerationError(
            f"{marker_label}生成失败：未检测到结束标记 {marker}（finish_reasons: {finish_reasons}）",
            {"combined_preview": combined[-2000:]},
        )
    return combined, finish_reasons


def _parse_json_payload(combined: str, marker: str, label: str) -> dict:
    cleaned = combined.replace(marker, "").strip()
    json_text = _extract_first_json_object(cleaned)
    if not json_text:
        raise TutorialGenerationError(f"{label}生成失败：未提取到有效 JSON", {"combined_preview": combined[-2000:]})

    json_text = re.sub(r'(?<!\\)\\(?![nu])([a-zA-Z])', r'\\\\\1', json_text)

    try:
        return json.loads(json_text)
    except json.JSONDecodeError as exc:
        repaired = _escape_json_control_chars_in_strings(json_text)
        try:
            payload = json.loads(repaired)
            log(f"{label} JSON 首次解析失败，已自动修复控制字符并重试成功: {exc}", "WARN")
            return payload
        except json.JSONDecodeError:
            raise TutorialGenerationError(
                f"{label}生成失败：JSON 解析失败: {exc}",
                {"json_text_preview": json_text[:4000]},
            ) from exc


# ---------------------------------------------------------------------------
# block / scene 清洗
# ---------------------------------------------------------------------------

def _clean_block(b, idx: int) -> Optional[dict]:
    if not isinstance(b, dict):
        return None
    btype = str(b.get("type") or "html").strip().lower()
    if btype not in BLOCK_TYPES:
        btype = "html"
    out: Dict[str, Any] = {"type": btype}
    if btype == "html":
        html = str(b.get("html") or "").strip()
        if not html:
            return None
        out["html"] = html
    elif btype == "pills":
        options = b.get("options")
        if not isinstance(options, list) or not options:
            return None
        out["options"] = []
        for option in options:
            if not isinstance(option, dict):
                continue
            cleaned = {
                "label": str(option.get("label") or "").strip(),
                "html": str(option.get("html") or ""),
            }
            if isinstance(option.get("spec"), dict):
                cleaned["spec"] = option["spec"]
            if option.get("chartTitle") is not None:
                cleaned["chartTitle"] = str(option.get("chartTitle") or "")
            if option.get("legend") is not None:
                cleaned["legend"] = str(option.get("legend") or "")
            out["options"].append(cleaned)
        if not out["options"]:
            return None
        out["defaultIndex"] = int(b.get("defaultIndex") or 0)
        out["note"] = _sanitize_diagram(str(b.get("note") or ""))
        if b.get("chartId") is not None:
            out["chartId"] = str(b.get("chartId") or "")
    elif btype == "matrix":
        cols = b.get("cols")
        rows = b.get("rows")
        if not isinstance(cols, list) or not isinstance(rows, list) or not cols or not rows:
            return None
        out["cols"] = [str(c) for c in cols]
        out["rows"] = []
        for r in rows:
            if not isinstance(r, dict):
                continue
            out["rows"].append({"label": str(r.get("label") or ""),
                                "cells": [str(c) if c is not None else "" for c in (r.get("cells") or [])]})
        out["title"] = str(b.get("title") or "")
        out["em"] = str(b.get("em") or "")
        out["style"] = str(b.get("style") or "")
        out["note"] = _sanitize_diagram(str(b.get("note") or ""))
        if isinstance(b.get("noteFor"), list):
            out["noteFor"] = [_sanitize_diagram(str(x)) for x in b["noteFor"]]
        out["cellNote"] = str(b.get("cellNote") or "")
    elif btype == "svgclick":
        svg = _sanitize_diagram(str(b.get("svg") or ""))
        if not svg:
            return None
        out["svg"] = svg
        nodes = b.get("nodes")
        if isinstance(nodes, list):
            out["nodes"] = []
            for n in nodes:
                if not isinstance(n, dict):
                    continue
                out["nodes"].append({
                    "key": str(n.get("key") or ""),
                    "title": str(n.get("title") or ""),
                    "p": str(n.get("p") or ""),
                    "q": str(n.get("q") or ""),
                    "src": str(n.get("src") or ""),
                    "lv": str(n.get("lv") or ""),
                    "links": [str(x) for x in (n.get("links") or [])],
                })
        else:
            out["nodes"] = []
        out["sideTitle"] = str(b.get("sideTitle") or "")
        out["sideP"] = str(b.get("sideP") or "")
        out["side"] = bool(b.get("side", True))
    elif btype == "slider":
        out["id"] = str(b.get("id") or f"slider{idx}")
        out["label"] = str(b.get("label") or "")
        out["min"] = float(b.get("min", 0))
        out["max"] = float(b.get("max", 100) if b.get("max") is not None else 100)
        out["step"] = float(b.get("step", 1))
        out["value"] = float(b.get("value") if b.get("value") is not None else b.get("min", 0))
        out["unit"] = str(b.get("unit") or "")
        out["valueFmt"] = str(b.get("valueFmt") or "%v")
        out["chartIds"] = [str(x) for x in (b.get("chartIds") or []) if str(x).strip()]
        try:
            out["cursorOffset"] = int(b.get("cursorOffset", 0))
        except (TypeError, ValueError):
            out["cursorOffset"] = 0
        if b.get("labelW") is not None:
            try:
                out["labelW"] = int(b["labelW"])
            except (TypeError, ValueError):
                out["labelW"] = 130
    elif btype == "chart":
        cid = str(b.get("id") or f"cv{idx}")
        spec = b.get("spec")
        if not isinstance(spec, dict):
            spec = {}
        out["id"] = cid
        out["title"] = str(b.get("title") or "")
        out["em"] = str(b.get("em") or "")
        out["legend"] = str(b.get("legend") or "")
        out["spec"] = spec
    return out


def _clean_scene(sc, idx: int) -> Optional[dict]:
    if not isinstance(sc, dict):
        return None
    blocks: List[dict] = []
    for bi, b in enumerate(sc.get("blocks") or []):
        cb = _clean_block(b, bi)
        if cb:
            blocks.append(cb)
    kicker = str(sc.get("kicker") or "").strip()
    h1 = str(sc.get("h1") or "").strip()
    lead = str(sc.get("lead") or "").strip()
    small = str(sc.get("small") or "").strip()
    title = str(sc.get("title") or "").strip()
    if not blocks and not (kicker or h1 or lead):
        return None
    return {
        "title": title,
        "kicker": kicker,
        "h1": h1,
        "small": small,
        "lead": lead,
        "blocks": blocks,
    }


def _validate_reference_structure(parts: List[dict]) -> None:
    """Final render gate: enforce reference-level density and interactions."""
    errors: List[str] = []
    if not parts:
        raise TutorialGenerationError("No renderable content was generated")

    first_name = str(parts[0].get("name") or "").strip()
    if first_name != "\u5168\u5c40\u95ee\u9898\u5206\u6790":
        errors.append("The first part must be the global analysis")
    if len(parts) < 2:
        errors.append("A global part and at least one question part are required")

    def chart_points(spec: Any) -> int:
        if not isinstance(spec, dict):
            return 0
        total = 0
        for series in spec.get("series") or []:
            if isinstance(series, dict) and isinstance(series.get("d"), list):
                total += len(series["d"])
        for panel in spec.get("panels") or []:
            if not isinstance(panel, dict):
                continue
            for series in panel.get("series") or []:
                if isinstance(series, dict) and isinstance(series.get("d"), list):
                    total += len(series["d"])
        return total

    for pi, part in enumerate(parts):
        name = str(part.get("name") or f"part-{pi + 1}")
        if pi > 0 and not re.fullmatch(r"\u95ee\u9898[\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d\u53410-9]+\u5206\u6790", name):
            errors.append(f"{name}: invalid question-part name")
        scenes = part.get("scenes") or []
        expected = 3 if pi == 0 else 1
        if len(scenes) != expected:
            errors.append(f"{name}: expected {expected} scene(s), got {len(scenes)}")

        for si, scene in enumerate(scenes):
            label = f"{name} scene {si + 1}"
            blocks = [b for b in (scene.get("blocks") or []) if isinstance(b, dict)]
            types = {str(b.get("type") or "") for b in blocks}
            if not str(scene.get("h1") or "").strip():
                errors.append(f"{label}: missing heading")
            if not str(scene.get("lead") or "").strip():
                errors.append(f"{label}: missing lead")
            if len(blocks) < 3:
                errors.append(f"{label}: fewer than three content blocks")
            if not types.intersection(INTERACTIVE_BLOCK_TYPES):
                errors.append(f"{label}: no interactive visualization")
            if len(json.dumps(scene, ensure_ascii=False)) < 900:
                errors.append(f"{label}: content payload is too sparse")

            if pi == 0 and si == 0 and "svgclick" not in types:
                errors.append(f"{label}: missing clickable SVG")
            if pi == 0 and si == 1 and not {"matrix", "pills"}.issubset(types):
                errors.append(f"{label}: attachment matrix and pills are required")
            if pi == 0 and si == 2 and not {"chart", "slider"}.issubset(types):
                errors.append(f"{label}: chart and slider are required")
            if pi > 0 and not {"pills", "chart", "slider"}.issubset(types):
                errors.append(f"{label}: pills, chart and slider are required")

            chart_ids = {str(b.get("id") or "") for b in blocks if b.get("type") == "chart"}
            sliders = {str(b.get("id") or ""): b for b in blocks if b.get("type") == "slider"}
            view_pills = [b for b in blocks if b.get("type") == "pills" and str(b.get("chartId") or "")]
            needs_chart_loop = (pi == 0 and si == 2) or pi > 0
            linked_pairs = 0
            for chart_block in (b for b in blocks if b.get("type") == "chart"):
                spec = chart_block.get("spec") or {}
                drag = spec.get("drag") if isinstance(spec, dict) else None
                drag_id = drag if isinstance(drag, str) else (drag or {}).get("sliderId") or (drag or {}).get("slider") if isinstance(drag, dict) else ""
                chart_id = str(chart_block.get("id") or "")
                slider = sliders.get(str(drag_id or ""))
                if slider and chart_id in [str(x) for x in (slider.get("chartIds") or [])]:
                    linked_pairs += 1
                elif needs_chart_loop:
                    errors.append(f"{label}: chart {chart_id or '?'} is not bidirectionally linked to a slider")
            if needs_chart_loop and linked_pairs < 1:
                errors.append(f"{label}: no valid slider/chart linkage pair")
            if needs_chart_loop and not view_pills:
                errors.append(f"{label}: missing chart-view pills")
            for pills_block in view_pills:
                target_id = str(pills_block.get("chartId") or "")
                if target_id not in chart_ids:
                    errors.append(f"{label}: chart-view pills target missing chart {target_id or '?'}")
                for option in pills_block.get("options") or []:
                    if not isinstance(option, dict) or chart_points(option.get("spec")) < 2:
                        errors.append(f"{label}: chart-view option has no usable series")
                        break

            for block in blocks:
                btype = block.get("type")
                if btype == "pills":
                    if len(block.get("options") or []) < 2:
                        errors.append(f"{label}: pills need at least two options")
                elif btype == "matrix":
                    if len(block.get("cols") or []) < 1 or len(block.get("rows") or []) < 1:
                        errors.append(f"{label}: matrix is missing columns or rows")
                elif btype == "svgclick":
                    if len(block.get("nodes") or []) < 2:
                        errors.append(f"{label}: clickable SVG needs at least two nodes")
                elif btype == "slider":
                    if not str(block.get("id") or "").strip():
                        errors.append(f"{label}: slider is missing an id")
                elif btype == "chart":
                    if not str(block.get("id") or "").strip() or chart_points(block.get("spec")) < 2:
                        errors.append(f"{label}: chart has no usable data series")

            visible_text = " ".join(
                str(scene.get(k) or "") for k in ("title", "kicker", "h1", "small", "lead")
            )
            visible_text += " " + " ".join(
                str(b.get("html") or b.get("title") or b.get("label") or "") for b in blocks
            )
            if any(term in visible_text for term in _FORBIDDEN_INTRO_PHRASES):
                errors.append(f"{label}: contains a forbidden cover/overview phrase")

    if errors:
        raise TutorialGenerationError(
            "Reference structure validation failed: " + "; ".join(errors[:12]),
            {"structure_errors": errors},
        )


# ---------------------------------------------------------------------------
# Analysis request: one JSON payload -> title/summary + parts/scenes
# ---------------------------------------------------------------------------

def _generate_analysis(
    client: OpenAI,
    model_name: str,
    question: str,
    title: str,
    max_output_tokens: int,
    page_images: Optional[List[str]] = None,
    facts_summary: str = "",
) -> Tuple[str, str, List[dict]]:
    system_prompt = _load_prompt_template(STEPS_SYSTEM_PROMPT_PATH)
    user_prompt_template = _load_prompt_template(STEPS_USER_PROMPT_PATH)
    if not system_prompt or not user_prompt_template:
        raise RuntimeError("缺少 steps_system_prompt.txt / steps_user_prompt.txt")

    user_prompt = (
        user_prompt_template
        .replace("__QUESTION__", question)
        .replace("__TITLE__", title)
        .replace("__FACTS__", facts_summary or "（无可用数值附件）")
    )

    image_parts = _build_image_parts(page_images)
    if image_parts:
        user_prompt += (
            "\n\n【重要】题目 PDF 的全部页面已渲染成图片，附在本消息下方，"
            "请务必以图片中的公式为准；文本里的公式可能因排版被拉平/错位。"
        )

    max_rounds = int(os.getenv("HTML_PARTS_CONTINUE_MAX_ROUNDS", "2"))
    continue_tokens = int(os.getenv("HTML_PARTS_CONTINUE_MAX_TOKENS", "32768"))

    combined, _ = _stream_until_marker(
        client, model_name, system_prompt, user_prompt,
        STEPS_END_MARKER, "赛题分析",
        max_output_tokens, max_rounds, continue_tokens, image_parts,
    )

    payload = _parse_json_payload(combined, STEPS_END_MARKER, "赛题分析")
    if not isinstance(payload, dict):
        raise TutorialGenerationError("赛题分析生成失败：顶层必须是 JSON 对象")

    title_out = str(payload.get("title") or "").strip()
    summary = str(payload.get("summary") or "").strip()

    raw_parts = payload.get("parts")
    parts: List[dict] = []
    if isinstance(raw_parts, list):
        for pi, p in enumerate(raw_parts):
            if not isinstance(p, dict):
                continue
            name = str(p.get("name") or "").strip() or f"档{pi + 1}"
            scenes = []
            for si, sc in enumerate(p.get("scenes") or []):
                cs = _clean_scene(sc, si)
                if cs:
                    scenes.append(cs)
            if scenes:
                parts.append({"name": name, "scenes": scenes})
    # Incomplete model parts are allowed here. The deterministic enricher repairs
    # the fixed three-scene global part and one scene per question later.
    return title_out, summary, parts


# ---------------------------------------------------------------------------
# Retry and backup-model switching
# ---------------------------------------------------------------------------

def _run_with_retry(client, model_name, fn, label: str):
    dynamic_retry_max = int(os.getenv("HTML_TEMPLATE_DYNAMIC_RETRY_MAX", "2"))
    dynamic_retry_delay = float(os.getenv("HTML_TEMPLATE_DYNAMIC_RETRY_DELAY_SECONDS", "1.5"))

    backup_client = None
    backup_model_name = ""
    if config.is_backup_configured():
        try:
            backup_client = _get_backup_client()
            backup_model_name = config.BACKUP_MODEL
        except Exception as exc:  # noqa: BLE001
            log(f"备用模型初始化失败，跳过: {exc}", "WARN")

    last_error = None
    used_backup = False
    attempt = 0
    while attempt <= dynamic_retry_max:
        cur_client = backup_client if used_backup else client
        cur_model = backup_model_name if used_backup else model_name
        try:
            if attempt > 0:
                log(f"{label} 重试中（{'备用模型' if used_backup else '默认模型'}）：第 {attempt + 1}/{dynamic_retry_max + 1} 次", "WARN")
            return fn(cur_client, cur_model)
        except TutorialGenerationError as exc:
            last_error = exc
            log(f"{label} 失败（第 {attempt + 1} 次）: {exc}", "WARN")
            if attempt < dynamic_retry_max:
                attempt += 1
                time.sleep(dynamic_retry_delay)
                continue
            break
        except Exception as exc:  # noqa: BLE001
            if not used_backup and backup_client is not None and config.is_timeout_error(exc):
                used_backup = True
                attempt = 0
                log(f"{label} 默认模型超时/网络异常，切换备用模型 {backup_model_name} 重跑: {exc}", "WARN")
                time.sleep(dynamic_retry_delay)
                continue
            last_error = exc
            log(f"{label} 未知异常（第 {attempt + 1} 次）: {exc}", "WARN")
            if attempt < dynamic_retry_max:
                attempt += 1
                time.sleep(dynamic_retry_delay)
                continue
            break
    raise RuntimeError(f"{label} 失败，已重试 {dynamic_retry_max} 次，最终错误: {last_error}")


# ---------------------------------------------------------------------------
# 模板填充
# ---------------------------------------------------------------------------

_DISPLAY_MATH_PATTERNS = (
    re.compile(r"\$\$(.+?)\$\$", re.DOTALL),
    re.compile(r"\\\[(.+?)\\\]", re.DOTALL),
)
_INLINE_MATH_PATTERNS = (
    re.compile(r"\\\((.+?)\\\)", re.DOTALL),
)
# Only these fields are inserted with innerHTML by teaching_template.html.
# Converting formulas here keeps ordinary text fields escaped as before.
_MATH_HTML_SCALAR_KEYS = {"html", "note", "legend", "cellNote"}
_MATH_HTML_COLLECTION_KEYS = {"cells", "noteFor"}


def _render_latex_delimiters(value: str) -> str:
    """Pre-render delimited LaTeX as native MathML for offline HTML files."""
    if not value or not any(token in value for token in ("$$", r"\[", r"\(")):
        return value

    try:
        from latex2mathml.converter import convert
    except ImportError as exc:
        raise RuntimeError(
            "LaTeX formulas were found, but latex2mathml is not installed. "
            "Run: pip install -r requirements.txt"
        ) from exc

    def replace_formula(match: re.Match[str], display: str) -> str:
        tex = match.group(1).strip()
        if not tex:
            return ""
        try:
            mathml = convert(tex, display=display)
        except Exception as exc:  # noqa: BLE001
            log(f"LaTeX to MathML failed; using readable fallback: {tex[:120]} ({exc})", "WARN")
            return (
                '<code class="math-fallback" title="Math formula rendering failed">'
                + html_escape(tex)
                + "</code>"
            )
        css_class = "math-display" if display == "block" else "math-inline"
        return f'<span class="{css_class}">{mathml}</span>'

    rendered = value
    for pattern in _DISPLAY_MATH_PATTERNS:
        rendered = pattern.sub(lambda match: replace_formula(match, "block"), rendered)
    for pattern in _INLINE_MATH_PATTERNS:
        rendered = pattern.sub(lambda match: replace_formula(match, "inline"), rendered)
    return rendered


def _render_math_payload(value: Any, *, html_context: bool = False) -> Any:
    """Copy a deck payload and convert formulas only in raw-HTML fields."""
    if isinstance(value, str):
        return _render_latex_delimiters(value) if html_context else value
    if isinstance(value, list):
        return [_render_math_payload(item, html_context=html_context) for item in value]
    if isinstance(value, dict):
        rendered: Dict[str, Any] = {}
        for key, item in value.items():
            child_html_context = (
                key in _MATH_HTML_SCALAR_KEYS
                or key in _MATH_HTML_COLLECTION_KEYS
            )
            rendered[key] = _render_math_payload(item, html_context=child_html_context)
        return rendered
    return value


def render_tutorial_html(data: Dict[str, Any]) -> str:
    """把 {title, summary, deck, scenes} 数据注入模板，返回最终 HTML。"""
    template_html = _load_prompt_template(TEACHING_TEMPLATE_PATH)
    if not template_html:
        raise RuntimeError("生成失败：缺少 teaching_template.html")

    title = str(data.get("title") or "数模演易")
    summary = str(data.get("summary") or "")
    deck = data.get("deck") or {}
    scenes = data.get("scenes") or []

    payload: Dict[str, Any] = {
        "title": title,
        "summary": summary,
        "deck": deck,
        "scenes": scenes,
    }
    payload = _render_math_payload(payload)
    steps_json = json.dumps(payload, ensure_ascii=False, indent=2)
    steps_json_for_script = (
        steps_json
        .replace("</", "<\\/")
        .replace(" ", "\\u2028")
        .replace(" ", "\\u2029")
    )

    html = template_html
    html = html.replace("__TITLE__", title)
    html = html.replace("__STEPS_JSON__", steps_json_for_script)
    return html


# ---------------------------------------------------------------------------
# 拆分多档
# ---------------------------------------------------------------------------

def _safe_filename(name: str) -> str:
    """把档名变成文件名安全串（保留中文/英文/数字）。"""
    return re.sub(r"[^\w\-\s]+", "_", (name or "").strip()).strip("_- ") or "内容"


def _nav_label(name: str) -> str:
    name = (name or "").strip()
    if "全局" in name:
        return "全局"
    m = re.search(r"问题\s*([一二三四五六七八九十0-9]+)", name)
    if m:
        return "问题" + m.group(1)
    return name[:6]


def render_deck(deck: Dict[str, Any]) -> List[Dict[str, str]]:
    """根据 {title, summary, parts} 生成多档 HTML。

    parts = [{"name": 档名, "scenes": [...]}]；返回 [{file, html}]。
    """
    title = str(deck.get("title") or "数模演易")
    summary = str(deck.get("summary") or "")

    raw_parts = deck.get("parts") or []
    parts: List[dict] = []
    for p in raw_parts:
        if isinstance(p, dict) and isinstance(p.get("scenes"), list) and p["scenes"]:
            parts.append({"name": str(p.get("name") or "解析"), "scenes": p["scenes"]})

    _validate_reference_structure(parts)

    names = [p["name"] for p in parts]
    files = [f"{i + 1:02d}_{_safe_filename(n)}.html" for i, n in enumerate(names)]
    labels = [_nav_label(n) for n in names]

    out: List[Dict[str, str]] = []
    for idx, part in enumerate(parts):
        per = {
            "title": title,
            "summary": summary,
            "deck": {"name": part["name"], "idx": idx, "names": names, "files": files, "labels": labels},
            "scenes": part["scenes"],
        }
        out.append({"file": files[idx], "html": render_tutorial_html(per)})
    return out


# ---------------------------------------------------------------------------
# 顶层
# ---------------------------------------------------------------------------

def _generate_tutorial_data(
    client: OpenAI,
    model_name: str,
    question: str,
    title: str,
    max_output_tokens: int,
    page_images: Optional[List[str]] = None,
    facts_summary: str = "",
) -> Tuple[str, str, List[dict]]:
    title_out, summary, parts = _run_with_retry(
        client, model_name,
        lambda c, m: _generate_analysis(c, m, question, title, max_output_tokens, page_images, facts_summary),
        "赛题分析",
    )
    return title_out, summary, parts


def generate_teaching_html(
    question: str,
    title: str,
    max_output_tokens: Optional[int] = None,
    page_images: Optional[List[str]] = None,
    facts: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """根据题目 + 附件文本 + 附件事实生成分档赛题分析 HTML。

    facts: data_facts.compute_facts() 的结果（可选）；提供时，会先 resolve 图表数据引用，
    再把事实摘要注入 prompt、把真实数组注入 chart。
    返回 {"htmls": [{"file","html"}], "title", "summary", "parts"}。
    """
    max_output_tokens = max_output_tokens or int(os.getenv("MAX_OUTPUT_TOKENS", "32768"))

    facts = facts or {}
    facts_summary = ""
    if facts:
        try:
            from data_facts import summarize
            facts_summary = summarize(facts)
        except Exception as exc:  # noqa: BLE001
            log(f"生成 facts 摘要失败，跳过: {exc}", "WARN")

    client = _get_kimi_client()
    model_name = config.GENERAL_MODEL

    title_out, summary, parts = _generate_tutorial_data(
        client=client,
        model_name=model_name,
        question=question,
        title=title,
        max_output_tokens=max_output_tokens,
        page_images=page_images,
        facts_summary=facts_summary,
    )

    data_title = title_out or title or "数模演易"

    # Normalize/enrich before resolving facts and applying the strict final gate.
    # This prevents a sparse or truncated model payload from becoming a sparse page.
    from deck_enricher import deck_quality_report, enrich_parts
    parts, question_count = enrich_parts(parts, question, facts)
    deck = {"title": data_title, "summary": summary, "parts": parts, "question_count": question_count}

    if facts:
        try:
            from data_facts import resolve_chart_data
            resolve_chart_data(facts, deck)
        except Exception as exc:  # noqa: BLE001
            log(f"Failed to resolve chart fact references: {exc}", "WARN")

    _validate_reference_structure(parts)
    quality = deck_quality_report(deck, facts)
    htmls = render_deck(deck)
    n_scenes = sum(len(p.get("scenes", [])) for p in parts)
    n_blocks = sum(len(sc.get("blocks", [])) for p in parts for sc in p.get("scenes", []))
    log(
        f"Template render complete: {len(htmls)} parts, {n_scenes} scenes, "
        f"{n_blocks} blocks, {quality.get('chart_points', 0)} chart points"
    )
    return {
        "htmls": htmls,
        "title": data_title,
        "summary": summary,
        "parts": parts,
        "quality": quality,
        "generation_mode": "ai+deterministic-enrichment",
    }
