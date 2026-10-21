# -*- coding: utf-8 -*-
"""Deterministic deck normalization and enrichment.

The LLM supplies the narrative; this module guarantees the reference-example
structure, real attachment charts, and usable interactions on every run.
"""
from __future__ import annotations

import html
import math
import re
from copy import deepcopy
from typing import Any, Dict, Iterable, List, Optional, Tuple

_CN_DIGITS = "零一二三四五六七八九十"
_CN_MAP = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}


def chinese_number(value: int) -> str:
    if value <= 0:
        return str(value)
    if value <= 10:
        return _CN_DIGITS[value]
    if value < 20:
        return "十" + (_CN_DIGITS[value - 10] if value > 10 else "")
    tens, ones = divmod(value, 10)
    return _CN_DIGITS[tens] + "十" + (_CN_DIGITS[ones] if ones else "")


def _parse_number(token: str) -> Optional[int]:
    token = (token or "").strip()
    if token.isdigit():
        return int(token)
    if token in _CN_MAP:
        return _CN_MAP[token]
    if token.startswith("十"):
        return 10 + _CN_MAP.get(token[1:], 0)
    if "十" in token:
        left, right = token.split("十", 1)
        return _CN_MAP.get(left, 1) * 10 + _CN_MAP.get(right, 0)
    return None


def infer_question_count(question: str, parts: Optional[List[dict]] = None) -> int:
    nums = []
    for token in re.findall(r"问题\s*([一二三四五六七八九十百0-9]+)", question or ""):
        value = _parse_number(token)
        if value:
            nums.append(value)
    if nums:
        return max(1, min(12, max(nums)))
    return max(1, min(12, max(0, len(parts or []) - 1) or 1))


def _question_segments(question: str, count: int) -> List[str]:
    text = question or ""
    matches = list(re.finditer(r"问题\s*([一二三四五六七八九十百0-9]+)", text))
    by_index: Dict[int, str] = {}
    for pos, match in enumerate(matches):
        idx = _parse_number(match.group(1))
        if not idx or idx in by_index:
            continue
        end = matches[pos + 1].start() if pos + 1 < len(matches) else len(text)
        by_index[idx] = text[match.start():end]
    return [by_index.get(i, "") for i in range(1, count + 1)]


def _blank_global_scene(index: int) -> dict:
    defaults = [
        ("一  对象与关系", "全局底图", "先把系统中的角色、能量流与决策流连起来", "点击节点查看每个对象的职责与约束。"),
        ("二  附件与任务", "数据地图", "把每个附件准确送到对应子问题", "点击矩阵列或切换标签，核对输入、输出模板和数据粒度。"),
        ("三  共同底座", "统一口径", "把时间尺度、状态递推与评价指标统一", "拖动时段游标并悬停曲线，观察所有子问题共享的数据底座。"),
    ]
    title, kicker, h1, lead = defaults[max(0, min(2, index))]
    return {"title": title, "kicker": kicker, "h1": h1, "small": "", "lead": lead, "blocks": []}


def _blank_question_scene(index: int) -> dict:
    cn = chinese_number(index)
    return {"title": f"问题{cn}  建模分析", "kicker": f"QUESTION {index}",
            "h1": f"问题{cn}：从新增条件到可交付策略",
            "small": "同一幕完成数据、模型、交互图表与结果口径",
            "lead": "只讲相对前一问新增的条件，并把决策变量、约束、费用和输出文件逐项对齐。", "blocks": []}


def _fill_scene_meta(scene: dict, fallback: dict) -> dict:
    out = deepcopy(scene) if isinstance(scene, dict) else {}
    for key in ("title", "kicker", "h1", "small", "lead"):
        if not str(out.get(key) or "").strip():
            out[key] = fallback.get(key, "")
    if not isinstance(out.get("blocks"), list):
        out["blocks"] = []
    return out


MAX_QUESTION_SCENES = 3


def _merge_question_scenes(scenes: List[dict], fallback: dict) -> List[dict]:
    """每个子问题允许最多 MAX_QUESTION_SCENES 幕（默认 1 幕）。

    旧实现把所有幕压成一幕，导致子问题档篇幅被硬性截断、四问排版雷同。
    现在保留作者写出的每一幕，仅在完全没给场景时回退到一幕空壳。
    """
    usable = [scene for scene in scenes if isinstance(scene, dict)]
    if not usable:
        return [deepcopy(fallback)]
    out = []
    for index, scene in enumerate(usable[:MAX_QUESTION_SCENES]):
        base = deepcopy(fallback) if index == 0 else _blank_question_scene(index + 1)
        out.append(_fill_scene_meta(scene, base))
    # 超出上限的幕并入最后一幕，避免内容被静默丢弃
    if len(usable) > MAX_QUESTION_SCENES:
        tail = usable[MAX_QUESTION_SCENES:]
        out[-1]["blocks"].extend(
            b for scene in tail for b in (scene.get("blocks") or []) if isinstance(b, dict)
        )
    return out


def normalize_parts(parts: List[dict], question: str) -> Tuple[List[dict], int]:
    raw = [deepcopy(p) for p in (parts or []) if isinstance(p, dict)]
    count = infer_question_count(question, raw)
    global_part = next((p for p in raw if "全局" in str(p.get("name") or "")), None)
    if global_part is None and len(raw) >= count + 1:
        global_part = raw[0]
    remaining = [p for p in raw if p is not global_part]
    mapped: Dict[int, dict] = {}
    leftovers: List[dict] = []
    for part in remaining:
        match = re.search(r"问题\s*([一二三四五六七八九十百0-9]+)", str(part.get("name") or ""))
        idx = _parse_number(match.group(1)) if match else None
        if idx and 1 <= idx <= count and idx not in mapped:
            mapped[idx] = part
        else:
            leftovers.append(part)

    global_scenes = list((global_part or {}).get("scenes") or [])
    scenes: List[dict] = []
    for index in range(3):
        base = _blank_global_scene(index)
        scene = global_scenes[index] if index < len(global_scenes) else base
        scenes.append(_fill_scene_meta(scene, base))
    for extra in global_scenes[3:]:
        if isinstance(extra, dict):
            scenes[2]["blocks"].extend(extra.get("blocks") or [])
    normalized = [{"name": "全局问题分析", "scenes": scenes}]
    for index in range(1, count + 1):
        part = mapped.get(index) or (leftovers.pop(0) if leftovers else None)
        qscenes = _merge_question_scenes(list((part or {}).get("scenes") or []), _blank_question_scene(index))
        normalized.append({"name": f"问题{chinese_number(index)}分析", "scenes": qscenes})
    return normalized, count


def _numbers(values: Any) -> List[float]:
    if not isinstance(values, list):
        return []
    out: List[float] = []
    for value in values:
        if isinstance(value, bool):
            continue
        try:
            number = float(value)
        except (TypeError, ValueError):
            continue
        if math.isfinite(number):
            out.append(number)
    return out


def _series(facts: Dict[str, Any], key: str) -> List[float]:
    return _numbers((facts.get("_series") or {}).get(key))


def _fmt(value: Any, digits: int = 2) -> str:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return ""
    return f"{number:,.0f}" if abs(number) >= 1000 else f"{number:.{digits}f}"


def _axis(values: Iterable[float], include_zero: bool = True) -> Tuple[float, float]:
    nums = [float(v) for v in values if isinstance(v, (int, float)) and math.isfinite(float(v))]
    if not nums:
        return 0.0, 1.0
    lo, hi = min(nums), max(nums)
    if include_zero and lo >= 0:
        lo = 0.0
    span = max(hi - lo, abs(hi), 1.0)
    pad = span * 0.08
    return (max(0.0, lo - pad) if include_zero else lo - pad, hi + pad)


def _time_ticks(n: int, step_minutes: int = 10) -> List[dict]:
    if n <= 1:
        return [{"i": 0, "t": "起点"}]
    positions = sorted(set([0, (n - 1) // 4, (n - 1) // 2, 3 * (n - 1) // 4, n - 1]))
    ticks = []
    for index in positions:
        minute = index * step_minutes
        hour, mins = divmod(minute, 60)
        if index == n - 1 and n * step_minutes % (24 * 60) == 0:
            hour, mins = 24, 0
        ticks.append({"i": index, "t": f"{hour:02d}:{mins:02d}"})
    return ticks


def _chart_block(chart_id: str, title: str, spec: dict, legend: str = "", em: str = "") -> dict:
    return {"type": "chart", "id": chart_id, "title": title, "em": em, "legend": legend, "spec": spec}


def _slider_block(slider_id: str, chart_id: str, n: int, label: str, unit: str = "") -> dict:
    return {"type": "slider", "id": slider_id, "label": label, "min": 1, "max": max(1, n),
            "step": 1, "value": 1, "unit": unit, "valueFmt": "%v", "chartIds": [chart_id],
            "cursorOffset": -1, "labelW": 190}


def _is_c_microgrid(question: str, facts: Dict[str, Any]) -> bool:
    keys = set((facts.get("_series") or {}).keys())
    required = {
        "附件1/电价:series",
        "附件1/小区负载:series",
        "附件1/光伏发电预测功率:series",
    }
    text = question or ""
    return required.issubset(keys) or ("储能" in text and "微网" in text and "紧急购电" in text)


def _trim(*values: List[float]) -> List[List[float]]:
    valid = [v for v in values if v]
    if not valid:
        return [[] for _ in values]
    n = min(len(v) for v in valid)
    return [list(v[:n]) if v else [] for v in values]


def _mean_chunks(values: List[float], chunks: int) -> List[float]:
    if not values or chunks <= 0:
        return []
    out: List[float] = []
    for i in range(chunks):
        start = round(i * len(values) / chunks)
        end = round((i + 1) * len(values) / chunks)
        bucket = values[start:max(start + 1, end)]
        out.append(sum(bucket) / len(bucket))
    return out


_INDEX_COLUMN_RE = re.compile(
    r"^(?:时间|时刻|日期时间|日期|序号|编号|索引)"          # 中文的时间/序号列
    r"|^(?:time|timestamp|datetime|date|index|idx|no|id|day|hour|month|year)\b",
    re.IGNORECASE,
)

def _column_of(key: str) -> str:
    return str(key).split("/")[-1].split(":")[0]


def _is_index_column(column: str) -> bool:
    """时间/序号列只适合当横轴，不该被当成"被测量的数据"画成曲线。"""
    return bool(_INDEX_COLUMN_RE.match((column or "").strip()))


def _time_unit_seconds(column: str, step: float) -> float:
    """按列名或采样步长推断时间列的单位，返回 1 个刻度值等于多少秒。"""
    name = (column or "").lower()
    for token, scale in (("小时", 3600.0), ("hour", 3600.0), ("(h)", 3600.0), ("（h）", 3600.0),
                         ("分钟", 60.0), ("min", 60.0), ("分", 60.0),
                         ("秒", 1.0), ("second", 1.0), ("(s)", 1.0), ("（s）", 1.0)):
        if token in name:
            return scale
    # 没有单位线索时按步长猜：整分钟的步长多半是"秒"。
    return 1.0 if step >= 60 and step % 60 == 0 else 60.0


def _clock_label(value: float, unit_seconds: float) -> str:
    hours, mins = divmod(int(round(value * unit_seconds / 60.0)), 60)
    return f"{hours}:{mins:02d}"


def _tick_positions(n: int) -> List[int]:
    if n <= 1:
        return [0]
    return sorted({0, (n - 1) // 4, (n - 1) // 2, 3 * (n - 1) // 4, n - 1})


def _x_axis_for(facts: Dict[str, Any], key: str, n: int) -> Tuple[List[dict], str]:
    """横轴优先用同一附件的时间列标真实时刻，没有时间列就退回观测点序号。"""
    if n <= 1:
        return [{"i": 0, "t": "起点"}], "第 %i 个观测点"
    namespace = key.split("/", 1)[0] if "/" in key else ""
    for column in ("时间", "时刻", "日期时间", "time", "Time"):
        times = _series(facts, f"{namespace}/{column}:series")
        if len(times) != n:
            continue
        scale = _time_unit_seconds(column, times[1] - times[0])
        return [{"i": index, "t": _clock_label(times[index], scale)} for index in _tick_positions(n)], "第 %i 个观测点"
    return [{"i": index, "t": str(index + 1)} for index in _tick_positions(n)], "第 %i 个观测点"


def _cited_attachments(hint: str) -> set:
    """题面开头点名的附件（如「根据附件2」）是本问的数据来源，作图优先用它。"""
    return set(re.findall(r"附件\s*(\d+)", (hint or "")[:200]))


def _first_real_series(facts: Dict[str, Any], min_points: int = 8,
                       hint: str = "") -> Tuple[str, List[float]]:
    """挑一条最值得画的真实序列。

    排序依据（越靠前越优先）：不是时间/序号列 → 属于本问引用的附件 → 该列在题面里
    被提到的次数 → 序列长度。不同物理量的"波动幅度"单位不可比，只作为最后的兜底，
    否则温度这种量纲大的列会永远压掉水分浓度。
    """
    candidates = []
    text = hint or ""
    cited = _cited_attachments(text)
    for key, raw in (facts.get("_series") or {}).items():
        values = _numbers(raw)
        if len(values) < min_points:
            continue
        spread = max(values) - min(values) if values else 0.0
        column = _column_of(str(key))
        namespace = str(key).split("/", 1)[0]
        cited_hit = any(digits in cited for digits in re.findall(r"\d+", namespace))
        hits = text.count(column) if (text and column) else 0
        candidates.append((
            1 if _is_index_column(column) else 0,
            0 if cited_hit else 1,
            0 if "slot_mean" in key else 1 if "daily_mean" in key else 2,
            -hits, -len(values), -spread, str(key), values))
    if not candidates:
        return "", []
    candidates.sort(key=lambda row: row[:7])
    _, _, _, _, _, _, key, values = candidates[0]
    return key, values


def _legend(items: List[Tuple[str, str]]) -> str:
    return "".join(
        f'<span><i class="ln" style="background:{html.escape(color)}"></i>{html.escape(name)}</span>'
        for name, color in items
    )



def _chart_view_pills(chart: dict) -> dict:
    """Build buttons that switch one canvas between useful chart views."""
    chart_id = str(chart.get("id") or "")
    title = str(chart.get("title") or "")
    original = deepcopy(chart.get("spec") or {})
    options: List[dict] = []

    def add(label: str, spec: dict, suffix: str, explanation: str) -> None:
        series = list(spec.get("series") or [])
        if not series:
            for panel in spec.get("panels") or []:
                series.extend(panel.get("series") or [])
        legend = _legend([(str(item.get("name") or suffix), str(item.get("c") or "#146c87"))
                          for item in series if isinstance(item, dict)])
        options.append({
            "label": label,
            "html": f"<div class='note'><strong>{html.escape(label)}:</strong>{html.escape(explanation)}</div>",
            "chartTitle": f"{title} \u00b7 {suffix}",
            "legend": legend,
            "spec": spec,
        })

    if original.get("type") == "stack" and original.get("panels"):
        add("\u8054\u5408\u89c6\u56fe", deepcopy(original), "\u540c\u65f6\u5bf9\u9f50", "\u540c\u4e00\u6e38\u6807\u4e0b\u5bf9\u9f50\u4ef7\u683c\u3001\u8d1f\u8f7d\u4e0e\u5149\u4f0f\u3002")
        for panel in original.get("panels") or []:
            variant = deepcopy(original)
            variant["type"] = "line"
            variant.pop("panels", None)
            variant.pop("gap", None)
            variant["h"] = 280
            variant["yMin"] = panel.get("yMin", 0)
            variant["yMax"] = panel.get("yMax", 1)
            variant["yFmt"] = panel.get("fmt", "x")
            variant["series"] = deepcopy(panel.get("series") or [])
            for item in variant["series"]:
                item.pop("ax", None)
            cap = str(panel.get("cap") or "\u5b50\u56fe")
            add(cap.split("  ")[0][:10], variant, cap, "\u9694\u79bb\u8be5\u7c7b\u91cf\u7eb2\uff0c\u4fbf\u4e8e\u8bfb\u53d6\u5c40\u90e8\u6ce2\u52a8\u3002")
    else:
        series = [item for item in (original.get("series") or []) if isinstance(item, dict)]
        if len(series) > 1:
            add("\u5168\u90e8\u5e8f\u5217", deepcopy(original), "\u5bf9\u6bd4", "\u4fdd\u7559\u6240\u6709\u5e8f\u5217\uff0c\u7528\u4e8e\u6bd4\u8f83\u76f8\u4f4d\u3001\u5cf0\u503c\u4e0e\u8d8b\u52bf\u3002")
            for item in series[:4]:
                variant = deepcopy(original)
                one = deepcopy(item)
                if one.get("ax") == "y2" and isinstance(original.get("y2"), dict):
                    y2 = original["y2"]
                    variant["yMin"], variant["yMax"] = y2.get("min", 0), y2.get("max", 1)
                    variant["yFmt"] = y2.get("fmt", variant.get("yFmt", "x"))
                    one.pop("ax", None)
                    variant.pop("y2", None)
                variant["series"] = [one]
                name = str(one.get("name") or "\u5355\u5e8f\u5217")
                add(name[:10], variant, name, "\u53ea\u663e\u793a\u8be5\u5e8f\u5217\uff0c\u907f\u514d\u591a\u66f2\u7ebf\u91cd\u53e0\u5e72\u6270\u3002")
        elif series:
            add("\u539f\u59cb\u66f2\u7ebf", deepcopy(original), "\u539f\u59cb\u503c", "\u4fdd\u7559\u9644\u4ef6\u4e2d\u6bcf\u4e2a\u89c2\u6d4b\u70b9\uff0c\u7528\u4e8e\u7cbe\u786e\u60ac\u505c\u8bfb\u503c\u3002")
            values = _numbers(series[0].get("d"))
            if len(values) >= 3:
                window = max(3, min(15, len(values) // 10 or 3))
                smooth = []
                for index in range(len(values)):
                    start = max(0, index - window + 1)
                    bucket = values[start:index + 1]
                    smooth.append(sum(bucket) / len(bucket))
                smooth_spec = deepcopy(original)
                smooth_spec["series"] = [deepcopy(series[0])]
                smooth_spec["series"][0]["name"] = f"{series[0].get('name') or ''} \u6ed1\u52a8\u5747\u503c"
                smooth_spec["series"][0]["d"] = smooth
                smooth_spec["series"][0].pop("fill", None)
                add("\u5e73\u6ed1\u8d8b\u52bf", smooth_spec, f"{window} \u70b9\u6ed1\u52a8\u5747\u503c", "\u538b\u4f4e\u9ad8\u9891\u6ce2\u52a8\uff0c\u66f4\u6e05\u695a\u5730\u770b\u8d8b\u52bf\u4e0e\u8f6c\u6298\u3002")
                mean_value = sum(values) / len(values)
                relative = [value - mean_value for value in values]
                rel_spec = deepcopy(original)
                rel_spec["series"] = [deepcopy(series[0])]
                rel_spec["series"][0]["name"] = "\u76f8\u5bf9\u5747\u503c"
                rel_spec["series"][0]["d"] = relative
                rel_spec["series"][0].pop("fill", None)
                rel_spec["yMin"], rel_spec["yMax"] = _axis(relative, include_zero=False)
                rel_spec["yFmt"] = "x"
                rel_spec["hlines"] = [{"v": 0, "c": "#8aa3aa", "label": "\u5747\u503c\u57fa\u7ebf"}]
                add("\u76f8\u5bf9\u504f\u5dee", rel_spec, "\u76f8\u5bf9\u5747\u503c", "\u4ee5\u96f6\u7ebf\u4e3a\u57fa\u51c6\uff0c\u76f4\u63a5\u89c2\u5bdf\u9ad8\u4e8e\u6216\u4f4e\u4e8e\u5747\u503c\u7684\u533a\u95f4\u3002")

    if not options:
        add("\u4ea4\u4e92\u89c6\u56fe", deepcopy(original), "\u6570\u636e", "\u62d6\u52a8\u6ed1\u6746\u6216\u56fe\u4e2d\u6e38\u6807\uff0c\u5e76\u60ac\u505c\u8bfb\u503c\u3002")
    return {
        "type": "pills",
        "chartId": chart_id,
        "defaultIndex": 0,
        "options": options,
        "note": "<span class='badge q'>\u56fe\u8868\u5207\u6362</span>\u70b9\u51fb\u4e0d\u540c\u6309\u94ae\u53ef\u5728\u540c\u4e00 canvas \u4e2d\u5207\u6362\u53ef\u89c6\u5316\u89c6\u56fe\u3002",
    }

def _system_svg() -> str:
    return """<svg viewBox="0 0 760 310" xmlns="http://www.w3.org/2000/svg" aria-label="微网能量流关系图">
<defs><marker id="arr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" fill="#12a594"/></marker></defs>
<path id="pvFlow" class="flowdot" d="M155 72 C255 72 258 128 330 142" fill="none" stroke="#12a594" stroke-width="4" stroke-dasharray="9 5" marker-end="url(#arr)"/>
<path id="loadFlow" d="M430 146 C510 130 520 88 607 82" fill="none" stroke="#146c87" stroke-width="4" marker-end="url(#arr)"/>
<path id="battFlow" d="M382 180 L382 239" fill="none" stroke="#e0a04a" stroke-width="4" marker-end="url(#arr)"/>
<path id="gridFlow" d="M435 161 C520 180 525 232 607 236" fill="none" stroke="#b8543a" stroke-width="4" marker-end="url(#arr)"/>
<g class="nd" data-k="pv"><rect x="34" y="35" width="122" height="75" rx="14" fill="#eef8f6" stroke="#12a594" stroke-width="2"/><text x="95" y="68" text-anchor="middle" fill="#0b2e36" font-size="16" font-weight="700">光伏</text><text x="95" y="91" text-anchor="middle" fill="#57727c" font-size="12">预测 / 实际功率</text></g>
<g class="nd" data-k="model"><rect x="302" y="111" width="160" height="78" rx="16" fill="#f3fafa" stroke="#0b6e68" stroke-width="2.5"/><text x="382" y="145" text-anchor="middle" fill="#0b2e36" font-size="16" font-weight="700">调控模型</text><text x="382" y="168" text-anchor="middle" fill="#57727c" font-size="12">购电  充放电  SOC</text></g>
<g class="nd" data-k="load"><rect x="605" y="43" width="122" height="75" rx="14" fill="#eef4f8" stroke="#146c87" stroke-width="2"/><text x="666" y="76" text-anchor="middle" fill="#0b2e36" font-size="16" font-weight="700">小区负载</text><text x="666" y="99" text-anchor="middle" fill="#57727c" font-size="12">必须满足的需求</text></g>
<g class="nd" data-k="battery"><rect x="321" y="235" width="122" height="60" rx="14" fill="#fdf4e8" stroke="#e0a04a" stroke-width="2"/><text x="382" y="261" text-anchor="middle" fill="#0b2e36" font-size="16" font-weight="700">储能</text><text x="382" y="282" text-anchor="middle" fill="#57727c" font-size="12">1200&ndash;10800 kWh</text></g>
<g class="nd" data-k="grid"><rect x="605" y="203" width="122" height="75" rx="14" fill="#fbf0ec" stroke="#b8543a" stroke-width="2"/><text x="666" y="236" text-anchor="middle" fill="#0b2e36" font-size="16" font-weight="700">外部电网</text><text x="666" y="259" text-anchor="middle" fill="#57727c" font-size="12">计划 / 调整 / 紧急购电</text></g>
</svg>"""


def _system_block() -> dict:
    return {
        "type": "svgclick",
        "sideTitle": "点击节点，查看它在优化中的角色",
        "sideP": "高亮的连线表示该对象直接影响的能量流或决策流。",
        "svg": _system_svg(),
        "nodes": [
            {"key": "pv", "title": "光伏：不确定的本地供给", "p": "问题一使用日前预测；问题二改用全年实际值；问题三再加入 0/6/12/18 时滚动预报。", "q": "先明确预测值与实际值不能混作同一口径。", "src": "C题正文、附件13", "links": ["pvFlow"]},
            {"key": "model", "title": "调控模型：每 10 分钟做能量平衡", "p": "共同决策包括外购电、充电、放电和储能量，目标是满足负载并最小化购电与偏差成本。", "q": "144 个时段把功率、能量和价格统一到同一网格。", "src": "C题储能参数", "links": ["pvFlow", "loadFlow", "battFlow", "gridFlow"]},
            {"key": "load", "title": "负载：每个时段必须被覆盖", "p": "光伏、放电与外购电共同供给负载；多余能量可进入储能，但不得越过容量和功率边界。", "q": "供需平衡是每一问都不能破坏的硬约束。", "src": "附件1、附件2", "links": ["loadFlow"]},
            {"key": "battery", "title": "储能：跨时段搬移能量", "p": "容量 12000 kWh，安全区间 1200&ndash;10800 kWh，最大充放电功率 5000 kW，效率 90%。", "q": "问题一还要求日初与日末储能量相同。", "src": "C题第1页", "links": ["battFlow"]},
            {"key": "grid", "title": "外部电网：成本与风险的来源", "p": "正常计划购电按电价计费；短缺紧急购电按 5 倍计价；滚动调整又分别承担 1.5 倍和 0.5 倍偏差成本。", "q": "不同购电通道必须拆成不同变量。", "src": "问题二、问题三", "links": ["gridFlow"]},
        ],
    }


def _catalog_pills(facts: Dict[str, Any]) -> dict:
    options = []
    for item in facts.get("_catalog") or []:
        sheets = item.get("sheets") or []
        rows = []
        for sheet in sheets:
            shape = f"{int(sheet.get('rows') or 0)}  {int(sheet.get('columns') or 0)}"
            kind = "数值数据" if sheet.get("numeric") else "结果模板"
            rows.append(f"<tr><td>{html.escape(str(sheet.get('name') or 'Sheet'))}</td><td class='num'>{shape}</td><td>{kind}</td></tr>")
        table = "".join(rows) or "<tr><td colspan='3'>未发现可解析工作表</td></tr>"
        body = (
            f"<div class='card'><h3>{html.escape(str(item.get('name') or item.get('label') or '附件'))}</h3>"
            f"<table class='tb'><thead><tr><th>工作表</th><th>行  数值列</th><th>读取类型</th></tr></thead><tbody>{table}</tbody></table>"
            "<p class='small'>这里显示的是程序实际打开并检查到的工作表，不是模型猜测的附件说明。</p></div>"
        )
        options.append({"label": str(item.get("label") or item.get("name") or "附件"), "html": body})
    if not options:
        options = [
            {"label": "\u9898\u9762\u8f93\u5165", "html": "<div class='card'><h3>\u9898\u9762\u53c2\u6570</h3><p>\u672c\u9898\u6ca1\u6709\u53ef\u89e3\u6790\u7684\u672c\u5730\u9644\u4ef6\uff0c\u6240\u6709\u6570\u503c\u5fc5\u987b\u56de\u6eaf\u5230\u9898\u9762\u5e76\u6807\u660e\u5355\u4f4d\u3002</p></div>"},
            {"label": "\u6570\u636e\u53e3\u5f84", "html": "<div class='card'><h3>\u6700\u5c0f\u6570\u636e\u8868</h3><p>\u5148\u5efa\u7acb\u5b57\u6bb5\u3001\u65f6\u95f4\u7c92\u5ea6\u3001\u5355\u4f4d\u3001\u7f3a\u5931\u503c\u548c\u8f93\u51fa\u5217\u6e05\u5355\uff0c\u518d\u8fdb\u5165\u5efa\u6a21\u3002</p></div>"},
        ]
    return {"type": "pills", "defaultIndex": 0, "options": options,
            "note": f"<span class='badge q'>已读取</span>共核验 {len(facts.get('_catalog') or [])} 个本地附件文件；点击标签查看真实工作表结构。"}

def _attachment_matrix(facts: Dict[str, Any], question_count: int, is_c: bool) -> dict:
    cols = [f"问题{chinese_number(i)}" for i in range(1, question_count + 1)]
    rows = []
    for item in facts.get("_catalog") or []:
        label = str(item.get("label") or item.get("name") or "附件")
        if is_c and question_count >= 4:
            if label == "附件1":
                cells = ["固定日电价、负载与光伏预测", "基准电价", "基准电价", "固定价格对照"]
            elif label == "附件2":
                cells = ["不用", "全年实际负载与光伏", "实际值用于偏差核验", "年度实际功率"]
            elif label == "附件3":
                cells = ["不用", "不用", "滚动光伏预报", "沿用滚动预报"]
            elif label == "附件4":
                cells = ["不用", "不用", "不用", "实时波动电价"]
            elif label == "result1":
                cells = ["输出模板", "不用", "不用", "不用"]
            elif label == "result2":
                cells = ["不用", "输出模板", "不用", "不用"]
            elif label == "result3":
                cells = ["不用", "不用", "输出模板", "不用"]
            elif label == "result4-2":
                cells = ["不用", "不用", "不用", "问题二重算模板"]
            elif label == "result4-3":
                cells = ["不用", "不用", "不用", "问题三重算模板"]
            else:
                cells = ["核对字段"] * question_count
        else:
            is_result = label.lower().startswith("result")
            cells = [("结果模板" if is_result else "输入数据") for _ in cols]
        if len(cells) < question_count:
            cells += ["不用"] * (question_count - len(cells))
        rows.append({"label": label, "cells": cells[:question_count]})
    if not rows:
        rows = [{"label": "题面输入", "cells": ["建立数据口径"] * question_count}]
    notes = []
    for i in range(1, question_count + 1):
        if is_c and i == 1:
            notes.append("问题一只用固定日数据，先建立可复用的能量平衡和储能状态递推。")
        elif is_c and i == 2:
            notes.append("问题二把输入扩展到全年实际数据，并单独记录 5 倍价的紧急购电。")
        elif is_c and i == 3:
            notes.append("问题三引入滚动预报与调整购电，偏差方向对应不同计价倍数。")
        elif is_c and i == 4:
            notes.append("问题四只替换价格机制，分别重算问题二与问题三并写入两个模板。")
        else:
            notes.append(f"点亮问题{chinese_number(i)}这一列，核对它真正需要的输入和交付物。")
    return {"type": "matrix", "title": "附件  子问题数据血缘", "em": "点任意有效格子，高亮同一问题列",
            "cols": cols, "rows": rows,
            "style": f"grid-template-columns:142px repeat({max(1, question_count)},1fr)",
            "noteFor": notes,
            "cellNote": "灰色格表示该附件不直接参与此问；结果模板必须与问题编号一一对应。"}


def _global_stack_chart(facts: Dict[str, Any], chart_id: str, slider_id: str) -> Tuple[dict, dict]:
    price = _series(facts, "附件1/电价:series")
    load = _series(facts, "附件1/小区负载:series")
    pv = _series(facts, "附件1/光伏发电预测功率:series")
    price, load, pv = _trim(price, load, pv)
    n = len(price or load or pv)
    if not n:
        key, fallback = _first_real_series(facts)
        fallback = fallback or [0, 1, 2, 3, 2, 4, 3, 5]
        lo, hi = _axis(fallback)
        x_ticks, x_name = _x_axis_for(facts, key, len(fallback))
        spec = {"type": "line", "n": len(fallback), "h": 260, "yMin": lo, "yMax": hi, "yFmt": "x",
                "xTicks": x_ticks, "xName": x_name, "cursor": {"i": 0},
                "drag": slider_id, "series": [{"name": key or "可用序列", "c": "#146c87", "w": 2.2,
                                                  "fill": "rgba(20,108,135,.10)", "d": fallback}]}
        axis_name = _column_of(str(key) or "") or "样本"
        return _slider_block(slider_id, chart_id, len(fallback), f"{axis_name}序列 · 第 %v 点"), _chart_block(
            chart_id, "附件真实序列交互预览", spec, _legend([(key or "可用序列", "#146c87")]), "悬停读值  拖动游标")
    p_lo, p_hi = _axis(price)
    power_lo, power_hi = _axis(load + pv)
    spec = {
        "type": "stack", "n": n, "h": 360, "L": 58, "R": 58, "T": 8, "B": 26, "gap": 18,
        "xTicks": _time_ticks(n), "xName": "第 %i 格（10 分钟）", "cursor": {"i": 0}, "drag": slider_id,
        "panels": [
            {"cap": "分时电价  元/kWh", "capc": "#b8543a", "h": 92, "yMin": p_lo, "yMax": p_hi, "fmt": "x", "grid": 3,
             "series": [{"name": "电价", "c": "#b8543a", "w": 2.2, "step": True, "fill": "rgba(184,84,58,.08)", "d": price}]},
            {"cap": "负载与光伏  kW", "capc": "#0b6e68", "h": 166, "yMin": power_lo, "yMax": power_hi, "fmt": "k", "grid": 4,
             "series": [{"name": "小区负载", "c": "#146c87", "w": 2.1, "d": load},
                        {"name": "光伏预测", "c": "#12a594", "w": 2.2, "fill": "rgba(18,165,148,.10)", "d": pv}]},
        ],
    }
    slider = _slider_block(slider_id, chart_id, n, "当前时段：第 %v / 144 格")
    chart = _chart_block(chart_id, "附件1：固定日价格、负载与光伏预测", spec,
                         _legend([("电价", "#b8543a"), ("小区负载", "#146c87"), ("光伏预测", "#12a594")]),
                         "鼠标悬停读数  可在图上左右拖动")
    return slider, chart


def _q2_chart(facts: Dict[str, Any]) -> Tuple[dict, dict]:
    load = _series(facts, "附件2/小区负载:daily_mean")
    pv = _series(facts, "附件2/光伏发电实际功率:daily_mean")
    load, pv = _trim(load, pv)
    n = len(load or pv)
    if not n:
        return _global_stack_chart(facts, "q2Chart", "q2Day")
    net = [max(a - b, 0.0) for a, b in zip(load, pv)]
    lo, hi = _axis(load + pv + net)
    ticks = [{"i": 0, "t": "第1天"}, {"i": min(n - 1, 90), "t": "约第91天"},
             {"i": min(n - 1, 181), "t": "约第182天"}, {"i": min(n - 1, 273), "t": "约第274天"},
             {"i": n - 1, "t": f"第{n}天"}]
    spec = {"type": "line", "n": n, "h": 285, "L": 58, "R": 22, "yMin": lo, "yMax": hi, "yFmt": "k",
            "xTicks": ticks, "xName": "第 %i 天", "cursor": {"i": 0}, "drag": "q2Day",
            "series": [{"name": "日均负载", "c": "#146c87", "w": 2.0, "d": load},
                       {"name": "日均实际光伏", "c": "#12a594", "w": 2.0, "fill": "rgba(18,165,148,.08)", "d": pv},
                       {"name": "日均正净负荷", "c": "#b8543a", "w": 1.8, "dash": [6, 4], "d": net}]}
    return _slider_block("q2Day", "q2Chart", n, "查看全年：第 %v 天"), _chart_block(
        "q2Chart", "附件2：全年实际负载、光伏与正净负荷", spec,
        _legend([("日均负载", "#146c87"), ("日均实际光伏", "#12a594"), ("正净负荷", "#b8543a")]),
        "365 天真实附件数据")


def _q3_chart(facts: Dict[str, Any]) -> Tuple[dict, dict]:
    forecast = _series(facts, "附件3:slot_mean")
    if not forecast:
        return _global_stack_chart(facts, "q3Chart", "q3Lead")
    n = len(forecast)
    lo, hi = _axis(forecast, include_zero=False)
    mean_value = sum(forecast) / len(forecast)
    ticks = []
    for pos in [0, 5, 11, 17, n - 1]:
        if 0 <= pos < n and pos not in [x[0] for x in [(t["i"], t["t"]) for t in ticks]]:
            ticks.append({"i": pos, "t": f"+{pos + 1}h"})
    spec = {"type": "line", "n": n, "h": 275, "L": 60, "R": 22, "yMin": lo, "yMax": hi, "yFmt": "k",
            "xTicks": ticks, "xName": "预报提前量 +%i 小时", "cursor": {"i": 0}, "drag": "q3Lead",
            "hlines": [{"v": mean_value, "c": "#8aa3aa", "label": f"24 h 均值 {_fmt(mean_value, 0)} kW"}],
            "vlines": [{"i": i, "c": "#d9e9e9"} for i in (5, 11, 17) if i < n],
            "series": [{"name": "滚动光伏预报均值", "c": "#12a594", "w": 2.4,
                        "fill": "rgba(18,165,148,.12)", "d": forecast}]}
    return _slider_block("q3Lead", "q3Chart", n, "预报提前量：第 %v 小时"), _chart_block(
        "q3Chart", "附件3：滚动预报随提前量的平均功率", spec,
        _legend([("滚动光伏预报", "#12a594")]), "0/6/12/18 时滚动更新")


def _q4_chart(facts: Dict[str, Any]) -> Tuple[dict, dict]:
    realtime = _series(facts, "附件4/日均电价:series") or _series(facts, "附件4:daily_mean")
    fixed = _series(facts, "附件1/电价:series")
    if not realtime:
        return _global_stack_chart(facts, "q4Chart", "q4Day")
    baseline = sum(fixed) / len(fixed) if fixed else sum(realtime) / len(realtime)
    compare = [baseline] * len(realtime)
    lo, hi = _axis(realtime + compare, include_zero=False)
    n = len(realtime)
    ticks = [{"i": 0, "t": "第1天"}, {"i": min(n - 1, 90), "t": "约第91天"},
             {"i": min(n - 1, 181), "t": "约第182天"}, {"i": min(n - 1, 273), "t": "约第274天"},
             {"i": n - 1, "t": f"第{n}天"}]
    spec = {"type": "line", "n": n, "h": 275, "L": 58, "R": 22, "yMin": lo, "yMax": hi, "yFmt": "x",
            "xTicks": ticks, "xName": "第 %i 天", "cursor": {"i": 0}, "drag": "q4Day",
            "series": [{"name": "实时电价日均值", "c": "#b8543a", "w": 2.2, "fill": "rgba(184,84,58,.08)", "d": realtime},
                       {"name": "固定日电价均值", "c": "#146c87", "w": 1.8, "dash": [7, 4], "d": compare}]}
    return _slider_block("q4Day", "q4Chart", n, "查看实时价格：第 %v 天"), _chart_block(
        "q4Chart", "附件4：全年实时电价日均波动与固定价基准", spec,
        _legend([("实时电价日均值", "#b8543a"), ("固定价格均值", "#146c87")]),
        "真实附件价格  悬停查看")

def _c_overview_html() -> str:
    return """<div class="grid4">
<div class="kpi"><div class="k">时间离散</div><div class="v">144 <u>格/日</u></div></div>
<div class="kpi g"><div class="k">储能容量</div><div class="v">12000 <u>kWh</u></div></div>
<div class="kpi b"><div class="k">安全能量区间</div><div class="v">10%&ndash;90%</div></div>
<div class="kpi w"><div class="k">紧急购电价格</div><div class="v">5 <u>正常电价</u></div></div>
</div><div class="card"><h3>一条主线贯穿四问 <em>供需平衡  储能搬移  电网兜底  成本计价</em></h3>
<p>光伏与小区负载先形成净负荷；储能在 5000 kW 功率上限和 1200&ndash;10800 kWh 安全区间内跨时段搬移能量；仍不足的部分由外部电网补齐。四问真正变化的是<strong>信息到达方式</strong>与<strong>购电计价方式</strong>，而不是重新发明一套能量平衡。</p></div>"""


def _c_foundation_html() -> str:
    return """<div class="grid2"><div class="card"><h3>每 10 分钟的能量平衡</h3>
<div class="quote num">P<sub>grid,t</sub> + P<sub>pv,t</sub> + P<sub>dis,t</sub> = P<sub>load,t</sub> + P<sub>ch,t</sub></div>
<p>把计划购电、调整购电、紧急购电拆成独立变量后，仍应回到同一条时段平衡式，避免重复计费或漏计短缺。</p></div>
<div class="card"><h3>储能状态递推</h3><div class="quote num">E<sub>t+1</sub> = E<sub>t</sub> + 0.9 P<sub>ch,t</sub>&Delta;t &minus; P<sub>dis,t</sub>Δt / 0.9</div>
<p><span class="badge">Δt = 1/6 h</span> <span class="badge g">1200 &le; E &le; 10800</span> <span class="badge b">0 &le; P<sub>ch</sub>,P<sub>dis</sub> &le; 5000</span></p></div></div>
<div class="note"><strong>统一核验：</strong>每一问都要检查时段供需平衡、SOC 边界、充放电功率边界、期初/期末条件以及输出模板字段；曲线只用于解释，约束核验才决定结果是否可交付。</div>"""


def _c_question_pills(index: int) -> dict:
    content = {
        1: [
            ("新增条件", "<div class='card'><h3>固定日场景</h3><p>电价、负载和光伏预测都来自附件1；日初储能量为 6000 kWh，并要求日末回到同一水平。该问是后续所有年度与滚动策略的基准。</p></div>"),
            ("模型与变量", "<div class='card'><h3>日内线性调度</h3><p>按 144 个时段设置计划购电、充电、放电和储能量；目标是最小化全天购电费用，同时满足能量平衡、效率和安全边界。</p></div>"),
            ("输出核验", "<div class='card'><h3>result1.xlsx</h3><p>逐格写入计划购电量与充放电量；重点核对单位换算、144 格完整性、日末 E 与 6000 kWh 的闭合误差。</p></div>"),
        ],
        2: [
            ("新增条件", "<div class='card'><h3>从一天扩展到全年</h3><p>附件2提供 365 天实际负载和实际光伏；若计划不足，需要启用按正常电价 5 倍计费的紧急购电。</p></div>"),
            ("模型与变量", "<div class='card'><h3>计划购电与紧急购电分开记账</h3><p>紧急购电是高惩罚兜底变量，不应混入普通购电。全年求解还要保持储能状态在日期边界处连续。</p></div>"),
            ("输出核验", "<div class='card'><h3>result2.xlsx</h3><p>输出计划购电、充放电和紧急购电；除总成本外，还应报告紧急购电出现的天数、时段数与峰值。</p></div>"),
        ],
        3: [
            ("新增条件", "<div class='card'><h3>滚动信息到达</h3><p>每天 0、6、12、18 时获得新的光伏预报。每次更新只修正尚未发生的时段，已执行决策必须冻结。</p></div>"),
            ("模型与变量", "<div class='card'><h3>方向不对称的偏差成本</h3><p>少买后的追加购电按 1.5 倍偏差计价，多买后的回退按 0.5 倍偏差计价；需要显式记录原计划与每次调整量。</p></div>"),
            ("输出核验", "<div class='card'><h3>result3.xlsx</h3><p>写入计划购电、调整购电、充放电和紧急购电；按四个滚动时点回放，确认没有使用未来信息。</p></div>"),
        ],
        4: [
            ("新增条件", "<div class='card'><h3>价格机制替换</h3><p>附件4给出实时波动电价。负载、光伏与储能物理约束保持不变，只替换目标函数中各时段的价格输入。</p></div>"),
            ("模型与变量", "<div class='card'><h3>做两组可比重算</h3><p>分别沿用问题二的年度策略与问题三的滚动策略，统一使用附件4价格，才能把价格变化与预报更新的影响分离。</p></div>"),
            ("输出核验", "<div class='card'><h3>result4-2.xlsx / result4-3.xlsx</h3><p>两个模板分别对应问题二、问题三的实时电价版本；除总成本外，还要比较购电峰值、紧急购电和储能循环变化。</p></div>"),
        ],
    }
    options = [{"label": label, "html": body} for label, body in content.get(index, content[1])]
    return {"type": "pills", "defaultIndex": 0, "options": options,
            "note": "<span class='badge q'>交互</span>依次切换新增条件  模型与变量  输出核验，避免把本问写成题面复述。"}


def _c_question_summary(index: int) -> str:
    summaries = {
        1: "<div class='note'><strong>本问结论：</strong>先用附件1建立可复用的日内基准模型；最关键的验收点是日末储能量回到 6000 kWh，而不是只看费用最小。</div>",
        2: "<div class='note'><strong>本问结论：</strong>年度变化把偶发短缺放大成风险问题；紧急购电必须作为 5 倍价的独立兜底变量，并在结果中单独统计。</div>",
        3: "<div class='note'><strong>本问结论：</strong>这是滚动优化而非一次性重算；每次更新冻结历史决策，只调整未来区间，并按少买 1.5 倍、多买 0.5 倍分别计价。</div>",
        4: "<div class='note'><strong>本问结论：</strong>保持物理模型不变、仅替换价格序列并分别重算问题二和问题三，才能得到可解释、可比较的实时电价影响。</div>",
    }
    return summaries.get(index, "<div class='note'><strong>本问结论：</strong>数据、变量、约束和输出模板必须形成闭环。</div>")


def _generic_system_block() -> dict:
    svg = """<svg viewBox="0 0 660 250" xmlns="http://www.w3.org/2000/svg">
<path id="g1" d="M165 125H285" stroke="#12a594" stroke-width="4"/><path id="g2" d="M375 125H495" stroke="#146c87" stroke-width="4"/>
<g class="nd" data-k="input"><rect x="35" y="82" width="130" height="86" rx="14" fill="#eef8f6" stroke="#12a594" stroke-width="2"/><text x="100" y="132" text-anchor="middle" font-size="16">题面与附件</text></g>
<g class="nd" data-k="model"><rect x="285" y="82" width="90" height="86" rx="14" fill="#fdf4e8" stroke="#e0a04a" stroke-width="2"/><text x="330" y="132" text-anchor="middle" font-size="16">模型</text></g>
<g class="nd" data-k="output"><rect x="495" y="82" width="130" height="86" rx="14" fill="#eef4f8" stroke="#146c87" stroke-width="2"/><text x="560" y="132" text-anchor="middle" font-size="16">结果与核验</text></g></svg>"""
    return {"type": "svgclick", "sideTitle": "点击节点查看分析链路", "sideP": "先确定输入，再定义模型，最后对齐交付与验证。", "svg": svg,
            "nodes": [{"key": "input", "title": "输入", "p": "只登记题面与已读取附件中的事实。", "q": "未知量与假设分开标注。", "src": "题面/附件", "links": ["g1"]},
                      {"key": "model", "title": "模型", "p": "变量、约束、目标与算法必须对应任务。", "q": "先做最小可行路线。", "src": "逐问分析", "links": ["g1", "g2"]},
                      {"key": "output", "title": "输出", "p": "结果格式、图表和误差核验构成交付闭环。", "q": "没有验证就不宣称完成。", "src": "交付要求", "links": ["g2"]}]}


def _generic_chart(facts: Dict[str, Any], index: int, hint: str = "") -> Tuple[dict, dict]:
    key, values = _first_real_series(facts, hint=hint)
    values = values or [1, 2, 1.5, 3, 2.8, 4, 3.6, 4.5]
    lo, hi = _axis(values)
    x_ticks, x_name = _x_axis_for(facts, key, len(values))
    chart_id, slider_id = f"q{index}Chart", f"q{index}Cursor"
    # 游标必须带题目语义：用真实字段名/轴名命名，禁止"第 %v 点"这类无意义措辞
    axis_name = _column_of(str(key)) or "样本"
    unit_name = str(x_name or "").strip() or "个采样"
    spec = {"type": "line", "n": len(values), "h": 260, "yMin": lo, "yMax": hi, "yFmt": "x",
            "xTicks": x_ticks, "xName": x_name, "cursor": {"i": 0}, "drag": slider_id,
            "series": [{"name": key or "数据序列", "c": "#146c87", "w": 2.2,
                        "fill": "rgba(20,108,135,.10)", "d": values}]}
    return _slider_block(slider_id, chart_id, len(values), f"{axis_name} · {unit_name}：第 %v 点"), _chart_block(
        chart_id, f"问题{chinese_number(index)}：{axis_name}沿{unit_name}的真实取值", spec,
        _legend([(key or "数据序列", "#146c87")]), "悬停读值  图上拖动")


def _generic_question_pills(index: int, segment: str) -> dict:
    excerpt = re.sub(r"\s+", " ", segment or "").strip()[:220]
    excerpt_html = html.escape(excerpt) if excerpt else "先从题面识别本问新增的输入、限制和输出。"
    return {"type": "pills", "defaultIndex": 0, "options": [
        {"label": "新增条件", "html": f"<div class='card'><h3>本问边界</h3><p>{excerpt_html}</p></div>"},
        {"label": "模型与变量", "html": "<div class='card'><h3>建模闭环</h3><p>把输入映射为决策变量、状态变量、约束与目标函数；所有单位和时间粒度保持一致。</p></div>"},
        {"label": "输出核验", "html": "<div class='card'><h3>交付检查</h3><p>结果字段与题目要求逐项对应，并通过边界、守恒、量纲和敏感性检查。</p></div>"}],
            "note": "<span class='badge q'>交互</span>切换查看本问的条件、模型与交付。"}

def _density_backfill(facts: Dict[str, Any], need: int) -> List[dict]:
    """稀疏页兜底：只用附件里真实读到的统计与清单补内容，不编造数值。"""
    out: List[dict] = []
    stats = facts.get("_stats") or {}
    if stats and need > 0:
        rows = []
        for key, item in list(stats.items())[:6]:
            if not isinstance(item, dict):
                continue
            column = _column_of(str(key)) or str(key)
            rows.append(
                f"<tr><td>{html.escape(column)}</td><td>{html.escape(_fmt(item.get('min')))}</td>"
                f"<td>{html.escape(_fmt(item.get('max')))}</td><td>{html.escape(_fmt(item.get('mean')))}</td>"
                f"<td>{html.escape(_fmt(item.get('n'), 0))}</td></tr>"
            )
        if rows:
            out.append({"type": "html", "html":
                        "<div class='card'><h3>附件字段实测统计</h3><table class='tbl'><thead><tr>"
                        "<th>字段</th><th>最小</th><th>最大</th><th>均值</th><th>样本数</th></tr></thead><tbody>"
                        + "".join(rows) + "</tbody></table></div>"})
            need -= 1
    catalog = facts.get("_catalog") or []
    if catalog and need > 0:
        items = []
        for entry in catalog[:6]:
            if not isinstance(entry, dict):
                continue
            sheet = (entry.get("sheets") or [{}])[0] or {}
            rows_cnt = sheet.get("rows") or 0
            flag = "空模板（待运行求解）" if not rows_cnt else f"{rows_cnt} 行 × {sheet.get('columns') or 0} 列"
            items.append(f"<div class='card'><h3>{html.escape(str(entry.get('label') or entry.get('name') or '附件'))}</h3>"
                         f"<p>{html.escape(str(entry.get('name') or ''))}：{flag}。</p></div>")
        if items:
            out.append({"type": "html", "html": "<div class='grid3'>" + "".join(items) + "</div>"})
    return out


def _c_question_intro(index: int) -> str:
    blocks = {
        1: """<div class='grid4'><div class='kpi'><div class='k'>输入跨度</div><div class='v'>1 <u>天</u></div></div><div class='kpi b'><div class='k'>时段数</div><div class='v'>144</div></div><div class='kpi g'><div class='k'>初始/终止储能</div><div class='v'>6000 <u>kWh</u></div></div><div class='kpi'><div class='k'>结果文件</div><div class='v'>result1</div></div></div>""",
        2: """<div class='grid4'><div class='kpi'><div class='k'>输入跨度</div><div class='v'>365 <u>天</u></div></div><div class='kpi b'><div class='k'>实际功率网格</div><div class='v'>365&times;144</div></div><div class='kpi w'><div class='k'>紧急购电</div><div class='v'>5</div></div><div class='kpi'><div class='k'>结果文件</div><div class='v'>result2</div></div></div>""",
        3: """<div class='grid4'><div class='kpi'><div class='k'>滚动时点</div><div class='v'>0/6/12/18 <u>时</u></div></div><div class='kpi b'><div class='k'>预报提前量</div><div class='v'>24 <u>小时</u></div></div><div class='kpi w'><div class='k'>少买/多买</div><div class='v'>1.5 / 0.5</div></div><div class='kpi'><div class='k'>结果文件</div><div class='v'>result3</div></div></div>""",
        4: """<div class='grid4'><div class='kpi'><div class='k'>新增输入</div><div class='v'>实时电价</div></div><div class='kpi b'><div class='k'>重算对象</div><div class='v'>问题二 + 三</div></div><div class='kpi g'><div class='k'>输出模板</div><div class='v'>2 <u>个</u></div></div><div class='kpi'><div class='k'>核心比较</div><div class='v'>成本与风险</div></div></div>""",
    }
    return blocks.get(index, "<div class='note'>围绕新增条件、模型变化、数据图与交付结果建立闭环。</div>")


def _append_if_missing(scene: dict, block: dict, btype: str) -> None:
    blocks = scene.setdefault("blocks", [])
    if not any(isinstance(item, dict) and item.get("type") == btype for item in blocks):
        blocks.append(block)



def _chart_has_source(spec: Any) -> bool:
    if not isinstance(spec, dict):
        return False
    pools = [spec.get("series") or []]
    pools.extend((panel.get("series") or []) for panel in spec.get("panels") or [] if isinstance(panel, dict))
    for pool in pools:
        for item in pool:
            if not isinstance(item, dict):
                continue
            data = item.get("d")
            if isinstance(data, list) and len(data) >= 2:
                return True
            if isinstance(data, dict) and str(data.get("from") or "").startswith("facts:"):
                return True
    return False


def _drop_broken_blocks(scene: dict) -> None:
    kept = []
    for block in scene.get("blocks") or []:
        if not isinstance(block, dict):
            continue
        kind = block.get("type")
        if kind == "pills" and len(block.get("options") or []) < 2:
            continue
        if kind == "matrix" and (not block.get("cols") or not block.get("rows")):
            continue
        if kind == "svgclick" and (not block.get("svg") or len(block.get("nodes") or []) < 2):
            continue
        if kind == "chart" and not _chart_has_source(block.get("spec")):
            continue
        kept.append(block)
    scene["blocks"] = kept

def enrich_parts(parts: List[dict], question: str, facts: Optional[Dict[str, Any]] = None) -> Tuple[List[dict], int]:
    """Normalize the model output and deterministically backfill a rich interactive deck."""
    facts = facts or {}
    normalized, count = normalize_parts(parts, question)
    segments = _question_segments(question, count)
    is_c = _is_c_microgrid(question, facts)

    if is_c:
        global_meta = [
            ("一  对象与关系", "全局底图", "一张图看懂微网里的能量流与决策流", "先点选光伏、负载、储能和外部电网，明确四问共用的物理关系。"),
            ("二  附件与任务", "数据血缘", "附件确实读到了什么，又分别送进哪一问", "矩阵负责问题映射，标签负责展示程序实际打开的工作表与尺寸。"),
            ("三  共同底座", "统一口径", "用同一套状态递推和交互曲线托住四个问题", "拖动滑杆或直接在图上拖动游标；鼠标悬停可读取附件1每个 10 分钟时段的真实值。"),
        ]
        for scene, meta in zip(normalized[0]["scenes"], global_meta):
            scene["title"], scene["kicker"], scene["h1"], scene["lead"] = meta
        normalized[0]["scenes"][0]["small"] = "点击 SVG 节点，右栏说明与高亮通路同步变化"
        normalized[0]["scenes"][0]["blocks"] = [
            {"type": "html", "html": _c_overview_html()},
            _system_block(),
            {"type": "html", "html": "<div class='note'><strong>结构判断：</strong>四问共享微网物理模型；从问题一到问题四，依次增加全年实际值、滚动预报和实时价格。因此实现上应复用约束，只替换数据与计价模块。</div>"},
        ]
        normalized[0]["scenes"][1]["small"] = "本页即附件读取证据：文件数、工作表名和行列规模均来自本地解析"
        normalized[0]["scenes"][1]["blocks"] = [
            _attachment_matrix(facts, count, True),
            _catalog_pills(facts),
            {"type": "html", "html": "<div class='note'><strong>附件闭环：</strong>附件1&ndash;4提供输入数据，附件5中的 result1、result2、result3、result4-2、result4-3 是空白交付模板；数据附件与结果模板都已进入清单。</div>"},
        ]
        g_slider, g_chart = _global_stack_chart(facts, "globalBaseChart", "globalTime")
        normalized[0]["scenes"][2]["small"] = "滑杆与图中竖向游标双向联动，悬停显示同一时段三条真实序列"
        normalized[0]["scenes"][2]["blocks"] = [
            {"type": "html", "html": _c_foundation_html()}, g_slider, _chart_view_pills(g_chart), g_chart,
        ]

        q_meta = {
            1: ("问题一  固定日最优调度", "BASELINE", "把附件1变成 144 格可执行购电与储能计划", "固定数据先建立基准；核心是费用最小、约束可行且日初日末储能闭合。"),
            2: ("问题二  全年实际运行", "YEARLY ACTUAL", "把年度波动与 5 倍紧急购电风险纳入同一模型", "从一天扩展到 365 天，重点观察净负荷季节性与紧急购电触发风险。"),
            3: ("问题三  滚动预报修正", "ROLLING FORECAST", "每 6 小时用新信息修正未来，而不是重写过去", "附件3提供滚动光伏预报；方向不对称的偏差价格决定调整策略。"),
            4: ("问题四  实时电价重算", "REAL-TIME PRICE", "保持物理约束不变，只替换价格并做两组可比重算", "分别复用问题二与问题三策略，比较实时价格如何改变成本、峰值与储能动作。"),
        }
        for index in range(1, count + 1):
            scene = normalized[index]["scenes"][0]
            title, kicker, h1, lead = q_meta.get(index, (f"问题{chinese_number(index)}  递进建模", f"QUESTION {index}", f"问题{chinese_number(index)}的新增条件与交付闭环", "只讨论相对前一问新增的数据、约束、计价和输出。"))
            scene.update({"title": title, "kicker": kicker, "h1": h1, "lead": lead,
                          "small": "切换标签看模型结构；拖动图表游标看真实附件数据"})
            if index == 1:
                slider, chart = _global_stack_chart(facts, "q1Chart", "q1Time")
            elif index == 2:
                slider, chart = _q2_chart(facts)
            elif index == 3:
                slider, chart = _q3_chart(facts)
            elif index == 4:
                slider, chart = _q4_chart(facts)
            else:
                slider, chart = _generic_chart(facts, index)
            scene["blocks"] = [
                {"type": "html", "html": _c_question_intro(index)},
                _c_question_pills(index) if index <= 4 else _generic_question_pills(index, segments[index - 1] if index - 1 < len(segments) else ""),
                slider, _chart_view_pills(chart), chart,
                {"type": "html", "html": _c_question_summary(index)},
            ]
        return normalized, count

    # Generic deterministic backfill: preserve useful model narrative, but guarantee the reference interactions.
    for part in normalized:
        for scene in part.get("scenes") or []:
            _drop_broken_blocks(scene)
    g0, g1, g2 = normalized[0]["scenes"]
    _append_if_missing(g0, _generic_system_block(), "svgclick")
    if not any(b.get("type") == "html" for b in g0.get("blocks") or [] if isinstance(b, dict)):
        g0["blocks"].insert(0, {"type": "html", "html": "<div class='grid3'><div class='card'><h3>输入</h3><p>题面参数与已读取附件。</p></div><div class='card'><h3>模型</h3><p>变量、约束与目标。</p></div><div class='card'><h3>输出</h3><p>结果、图表与验证。</p></div></div>"})
    if len(g0.get("blocks") or []) < 3:
        g0["blocks"].append({"type": "html", "html": "<div class='note'><strong>结构判断：</strong>每项输出都要沿模型约束回溯到已经核验的输入。</div>"})
    _append_if_missing(g1, _attachment_matrix(facts, count, False), "matrix")
    _append_if_missing(g1, _catalog_pills(facts), "pills")
    if not any(b.get("type") == "html" for b in g1.get("blocks") or [] if isinstance(b, dict)):
        g1["blocks"].append({"type": "html", "html": "<div class='note'><strong>数据血缘：</strong>每个附件字段都要映射到明确的问题列和可核验输出。</div>"})
    g1["blocks"].append({"type": "html", "html": "<div class='card'><h3>附件核验清单</h3><p>逐项确认本地路径、工作表名、行列规模、单位、缺失值、时间粒度，以及该表是输入数据还是空白结果模板；再由矩阵把每个已核验字段映射到实际使用它的问题和交付物。</p></div>"})
    g2_kept = [block for block in (g2.get("blocks") or []) if isinstance(block, dict)]
    g2_has = any(b.get("type") == "slider" for b in g2_kept) and any(b.get("type") == "chart" for b in g2_kept)
    if g2_has:
        # 作者已给出题目专属的游标与图表，保留原样，不被通用堆叠图覆盖
        g2["blocks"] = g2_kept
    else:
        slider, chart = _global_stack_chart(facts, "globalBaseChart", "globalCursor")
        g2["blocks"] = [
            block for block in g2_kept
            if block.get("type") not in {"slider", "chart"}
            and not (block.get("type") == "pills" and block.get("chartId"))
        ]
        g2["blocks"].extend([slider, _chart_view_pills(chart), chart])
    if not any(b.get("type") == "html" for b in g2.get("blocks") or [] if isinstance(b, dict)):
        g2["blocks"].insert(0, {"type": "html", "html": "<div class='card'><h3>共同底座</h3><p>统一时间粒度、变量单位、状态递推、边界条件和评价指标，再逐问替换新增模块。</p></div>"})
    for index in range(1, count + 1):
        part = normalized[index]
        segment = segments[index - 1] if index - 1 < len(segments) else ""
        all_blocks = [b for scene in (part.get("scenes") or []) for b in (scene.get("blocks") or []) if isinstance(b, dict)]
        part_has = any(b.get("type") == "slider" for b in all_blocks) and any(b.get("type") == "chart" for b in all_blocks)
        for order, scene in enumerate(part.get("scenes") or []):
            kept = [block for block in (scene.get("blocks") or []) if isinstance(block, dict)]
            scene["blocks"] = kept
            _append_if_missing(scene, _generic_question_pills(index, segment), "pills")
            if not part_has:
                # 整档完全没有专属图表时才回填一次通用图表；有则一个都不加，
                # 避免与作者图表撞 ID、也避免四问排版雷同
                kept = [
                    block for block in kept
                    if not (block.get("type") == "pills" and block.get("chartId"))
                ]
                scene["blocks"] = kept
                slider, chart = _generic_chart(facts, index, segment)
                scene["blocks"].extend([slider, _chart_view_pills(chart), chart])
                part_has = True
            # 稀疏页兜底：块数不足时用附件真实统计补齐，避免整页只有两三个方块
            if len(scene["blocks"]) < 4:
                scene["blocks"].extend(_density_backfill(facts, 4 - len(scene["blocks"])))
            if not any(block.get("type") == "html" for block in scene["blocks"]):
                scene["blocks"].append({"type": "html", "html": "<div class='note'><strong>交付判断：</strong>本问必须做到输入可追溯、约束可核验、结果字段与题面逐项对应。</div>"})
    return normalized, count


def _chart_point_count(spec: Any) -> int:
    if not isinstance(spec, dict):
        return 0
    total = 0
    for series in spec.get("series") or []:
        if isinstance(series, dict):
            total += len(_numbers(series.get("d")))
    for panel in spec.get("panels") or []:
        if isinstance(panel, dict):
            for series in panel.get("series") or []:
                if isinstance(series, dict):
                    total += len(_numbers(series.get("d")))
    return total


_APPENDIX_REF_RE = re.compile(r"附录\s*\d+")
_MATH_MARKERS = ("math-display", "math-inline", "math-fallback")
_QUESTION_PART_RE = re.compile(r"问题\s*[一二三四五六七八九十百0-9]+")


def _scene_strings(value: Any) -> List[str]:
    if isinstance(value, str):
        return [value]
    if isinstance(value, list):
        return [text for item in value for text in _scene_strings(item)]
    if isinstance(value, dict):
        return [text for item in value.values() for text in _scene_strings(item)]
    return []


def _formula_warnings(part_name: str, part: dict) -> List[str]:
    """问题档引用了附录却整档没有公式——这种页面读者单独打开时会缺掉所依赖的公式。"""
    if not _QUESTION_PART_RE.search(part_name or ""):
        return []
    strings = _scene_strings(part.get("scenes") or [])
    if any(marker in text for marker in _MATH_MARKERS for text in strings):
        return []
    if any("$$" in text for text in strings):
        return []
    refs = sorted({match.group(0) for text in strings for match in _APPENDIX_REF_RE.finditer(text)})
    if not refs:
        return []
    return [f"{part_name} 提到 {'/'.join(refs)} 却没有任何公式，请在 prompt 要求的本档公式重述处补上 $$…$$"]


def deck_quality_report(deck: Dict[str, Any], facts: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    facts = facts or {}
    parts = deck.get("parts") or []
    type_counts: Dict[str, int] = {}
    scenes = 0
    blocks = 0
    chart_points = 0
    scene_interactions = []
    warnings: List[str] = []
    for part in parts:
        for scene in part.get("scenes") or []:
            scenes += 1
            types = []
            for block in scene.get("blocks") or []:
                if not isinstance(block, dict):
                    continue
                blocks += 1
                btype = str(block.get("type") or "html")
                types.append(btype)
                type_counts[btype] = type_counts.get(btype, 0) + 1
                if btype == "chart":
                    chart_points += _chart_point_count(block.get("spec"))
            scene_interactions.append(sum(1 for t in types if t in {"pills", "matrix", "svgclick", "slider", "chart"}))
        warnings.extend(_formula_warnings(str(part.get("name") or ""), part))
    return {
        "parts": len(parts),
        "scenes": scenes,
        "blocks": blocks,
        "block_types": dict(sorted(type_counts.items())),
        "chart_points": chart_points,
        "scene_interactions": scene_interactions,
        "attachment_files": len(facts.get("_catalog") or []),
        "fact_series": len(facts.get("_series") or {}),
        "fact_stats": len(facts.get("_stats") or {}),
        "fact_notes": list(facts.get("_notes") or []),
        "warnings": warnings,
    }
