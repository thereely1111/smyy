# -*- coding: utf-8 -*-
"""输入盘点用的**只读**数据画像脚本（不是解题代码，不做任何求解/优化）。

用途：为 S1 题目分析提供附件1 的字段覆盖率、取值范围、类别分布、区间解析可行性，
以及由数据推得的时间/频率占用跨度，供 problem_contract 与数据质量评估引用。

用法：
    run.cmd <本脚本绝对路径> --xlsx <附件1绝对路径> --out <data_profile.json 绝对路径>
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

INTERVAL = re.compile(r"^\s*[\[(]\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*[\])]\s*$")


def _fix(stream):
    if stream and hasattr(stream, "reconfigure"):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass


def parse_interval(raw):
    if raw is None:
        return None
    m = INTERVAL.match(str(raw))
    if not m:
        return None
    return float(m.group(1)), float(m.group(2))


def main() -> int:
    _fix(sys.stdout)
    _fix(sys.stderr)
    ap = argparse.ArgumentParser()
    ap.add_argument("--xlsx", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    try:
        import openpyxl
    except ImportError as exc:
        print("! 缺少 openpyxl：", exc)
        return 1

    wb = openpyxl.load_workbook(args.xlsx, data_only=True, read_only=True)
    ws = wb[wb.sheetnames[0]]
    rows = list(ws.iter_rows(values_only=True))
    wb.close()
    if not rows:
        print("! 空表")
        return 1

    headers = [str(c).strip() if c is not None else "" for c in rows[0]]
    body = [r for r in rows[1:] if any(v is not None and str(v).strip() != "" for v in r)]

    idx = {h: i for i, h in enumerate(headers)}
    col_id = idx.get("用频装备编号", 0)

    ids = [str(r[col_id]).strip() for r in body if len(r) > col_id]
    dup = [k for k, v in Counter(ids).items() if v > 1]
    prefix = Counter(re.match(r"[A-Za-z]+", s).group(0) if re.match(r"[A-Za-z]+", s) else "?" for s in ids)

    band_raw_bad, time_raw_bad = [], []
    bands, times = [], []
    freq_band_min = freq_band_max = None
    t_min, t_max = None, None
    dur_band, dur_time = Counter(), Counter()
    gap_counter, use_counter = Counter(), Counter()
    band_time_pairs = []
    recs = []
    for pos, r in enumerate(body):
        rid = str(r[col_id]).strip() if len(r) > col_id and r[col_id] is not None else ""
        b = parse_interval(r[idx["频段区间"]]) if "频段区间" in idx and len(r) > idx["频段区间"] else None
        t = parse_interval(r[idx["时间区间"]]) if "时间区间" in idx and len(r) > idx["时间区间"] else None
        if b is None:
            band_raw_bad.append((rid, r[idx.get("频段区间", 1)] if len(r) > idx.get("频段区间", 1) else None))
        if t is None:
            time_raw_bad.append((rid, r[idx.get("时间区间", 2)] if len(r) > idx.get("时间区间", 2) else None))
        if b and t:
            bands.append(b)
            times.append(t)
            band_time_pairs.append((rid, b, t))
            freq_band_min = min(freq_band_min, b[0]) if freq_band_min is not None else b[0]
            freq_band_max = max(freq_band_max, b[1]) if freq_band_max is not None else b[1]
            t_min = min(t_min, t[0]) if t_min is not None else t[0]
            t_max = max(t_max, t[1]) if t_max is not None else t[1]
            dur_band[b[1] - b[0]] += 1
            dur_time[t[1] - t[0]] += 1
        gap = r[idx["间隔时长"]] if "间隔时长" in idx and len(r) > idx["间隔时长"] else None
        use = r[idx["使用次数"]] if "使用次数" in idx and len(r) > idx["使用次数"] else None
        try:
            gapv = float(gap)
            usev = float(use)
            gap_counter[gapv] += 1
            use_counter[usev] += 1
        except (TypeError, ValueError):
            gapv = usev = None
        if t and gapv is not None and usev is not None:
            last_start = t[0] + (usev - 1) * gapv
            last_end = t[1] + (usev - 1) * gapv
            recs.append((rid, last_end, t[1] - t[0], gapv, usev))

    horizon = max((e for _, e, _, _, _ in recs), default=None)
    occ_slots = sum(int((e - (e - d))) for _, e, d, _, _ in recs)  # placeholder, replaced below
    total_occ = sum(int(d) * int(u) for _, _, d, _, u in recs if float(d).is_integer() and float(u).is_integer())
    tf_cells = sum(int(d) * int(u) * int(b[1] - b[0]) for (_, _rid, _e, _d, _g, _u) in [] )  # noqa: unused

    band_cells = 0
    for (rid, b, t) in band_time_pairs:
        nb = int(b[1] - b[0])
        nt = int(t[1] - t[0])
        u = None
        for cand in recs:
            if cand[0] == rid:
                u = cand[4]
                break
        if u is not None and nb > 0:
            band_cells += nb * int(u)

    profile = {
        "sheet_names": list(ws.title for _ in [0]) or None,
        "headers": headers,
        "n_data_rows": len(body),
        "equipment_ids_first": ids[:6],
        "equipment_ids_last": ids[-6:],
        "duplicate_ids": dup,
        "class_prefix_counts": dict(prefix),
        "band_interval_unparsed": band_raw_bad[:10],
        "band_interval_unparsed_n": len(band_raw_bad),
        "time_interval_unparsed": time_raw_bad[:10],
        "time_interval_unparsed_n": len(time_raw_bad),
        "band_axis": {"min": freq_band_min, "max": freq_band_max},
        "first_time_axis": {"min": t_min, "max": t_max},
        "band_width_distribution": {str(k): v for k, v in sorted(dur_band.items())},
        "first_time_duration_distribution": {str(k): v for k, v in sorted(dur_time.items())},
        "gap_values": {str(k): v for k, v in sorted(gap_counter.items())},
        "use_count_values": {str(k): v for k, v in sorted(use_counter.items())},
        "derived_last_occupancy_end_max": horizon,
        "derived_total_time_slots_sum_over_plans": total_occ,
        "derived_time_freq_cells_sum_over_plans": band_cells,
        "plans_by_class_last_end": {},
    }
    by_class = {}
    for rid, last_end, d, g, u in recs:
        c = re.match(r"[A-Za-z]+", rid).group(0) if re.match(r"[A-Za-z]+", rid) else "?"
        by_class.setdefault(c, {"n": 0, "max_last_end": None, "durations": Counter(), "gaps": Counter(), "uses": Counter()})
        by_class[c]["n"] += 1
        by_class[c]["max_last_end"] = max(by_class[c]["max_last_end"] or 0, last_end)
        by_class[c]["durations"][d] += 1
        by_class[c]["gaps"][g] += 1
        by_class[c]["uses"][u] += 1
    profile["plans_by_class_last_end"] = {
        c: {
            "n": v["n"],
            "max_last_occupancy_end": v["max_last_end"],
            "first_duration": {str(k): n for k, n in sorted(v["durations"].items())},
            "gap": {str(k): n for k, n in sorted(v["gaps"].items())},
            "use_count": {str(k): n for k, n in sorted(v["uses"].items())},
        }
        for c, v in sorted(by_class.items())
    }

    Path(args.out).write_text(json.dumps(profile, ensure_ascii=False, indent=2), encoding="utf-8")
    for k in ("headers", "n_data_rows", "class_prefix_counts", "band_axis", "first_time_axis",
              "band_width_distribution", "first_time_duration_distribution", "gap_values",
              "use_count_values", "derived_last_occupancy_end_max", "plans_by_class_last_end",
              "band_interval_unparsed_n", "time_interval_unparsed_n", "duplicate_ids"):
        print(f"{k}: {json.dumps(profile[k], ensure_ascii=False)}")
    print(f"写出：{Path(args.out)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
