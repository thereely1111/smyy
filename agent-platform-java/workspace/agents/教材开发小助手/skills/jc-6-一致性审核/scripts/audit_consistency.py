#!/usr/bin/env python3
"""jc-6-一致性审核 — 基于 chapter_summary.json 的跨章对比审核"""
import sys, json, os
from pathlib import Path
def __sb_find_shared_root():
    __sb_me = Path(__file__).resolve()
    __sb_cur = __sb_me.parent
    for __sb_i in range(8):
        __sb_cand = __sb_cur / "_shared"
        if (__sb_cand / "_audit_base.py").is_file():
            return __sb_cand
        __sb_up = __sb_cur.parent
        if __sb_up == __sb_cur:
            break
        __sb_cur = __sb_up
    __sb_cur = __sb_me
    while __sb_cur.parent != __sb_cur:
        __sb_cur = __sb_cur.parent
        try:
            for __sb_src in __sb_cur.glob("agents/*/skills/_shared"):
                if (__sb_src / "_audit_base.py").is_file():
                    return __sb_src
        except OSError:
            pass
    raise RuntimeError("cannot locate _shared: _audit_base.py not found")

sys.path.insert(0, str(__sb_find_shared_root()))
from _audit_base import Runner

def load_json(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)

def find_concept(name, concepts):
    for c in concepts:
        if c["name"] == name:
            return c
    return None

def find_term(name, terms):
    for t in terms:
        if t["term"] == name:
            return t
    return None

def find_symbol(sym, symbols):
    for s in symbols:
        if s["symbol"] == sym:
            return s
    return None


def check_concepts(curr, prev):
    """对比同名概念的定义一致性"""
    curr_concepts = {c["name"]: c for c in curr.get("concepts", [])}
    prev_concepts = {c["name"]: c for c in prev.get("concepts", [])}

    diffs = []
    for name, cc in curr_concepts.items():
        if name in prev_concepts:
            pc = prev_concepts[name]
            if cc.get("definition", "") != pc.get("definition", ""):
                diffs.append({
                    "concept": name,
                    "current": cc["definition"][:80],
                    "previous": pc["definition"][:80]
                })

    return {
        "ok": len(diffs) == 0,
        "shared_count": len(curr_concepts & prev_concepts),
        "diff_count": len(diffs),
        "diffs": diffs[:10]
    }


def check_terms(curr, prev):
    """对比同名术语的命名一致性"""
    curr_terms = {t["term"]: t for t in curr.get("terms", [])}
    prev_terms = {t["term"]: t for t in prev.get("terms", [])}

    diffs = []
    for name, ct in curr_terms.items():
        if name in prev_terms:
            pt = prev_terms[name]
            if ct.get("abbreviation") != pt.get("abbreviation") or ct.get("full_en") != pt.get("full_en"):
                diffs.append({
                    "term": name,
                    "current": f"{ct.get('abbreviation','')} / {ct.get('full_en','')}",
                    "previous": f"{pt.get('abbreviation','')} / {pt.get('full_en','')}"
                })

    return {
        "ok": len(diffs) == 0,
        "shared_count": len(curr_terms & prev_terms),
        "diff_count": len(diffs),
        "diffs": diffs[:10]
    }


def check_symbols(curr, prev):
    """对比同名符号的含义一致性"""
    curr_syms = {s["symbol"]: s for s in curr.get("symbols", [])}
    prev_syms = {s["symbol"]: s for s in prev.get("symbols", [])}

    diffs = []
    for sym, cs in curr_syms.items():
        if sym in prev_syms:
            ps = prev_syms[sym]
            if cs.get("meaning") != ps.get("meaning") or cs.get("type") != ps.get("type"):
                diffs.append({
                    "symbol": sym,
                    "current": f"{cs.get('meaning')} ({cs.get('type')})",
                    "previous": f"{ps.get('meaning')} ({ps.get('type')})"
                })

    return {
        "ok": len(diffs) == 0,
        "shared_count": len(curr_syms & prev_syms),
        "diff_count": len(diffs),
        "diffs": diffs[:10]
    }


CHECKS = [
    {"id": "consist_concept", "desc": "跨章概念定义一致性", "severity": "error",
     "check": lambda _content, ctx: check_concepts(load_json(ctx.get("target_path", "")), load_json(ctx.get("prev_path", ""))),
     "pass_msg": "跨章概念定义一致", "fail_msg": "同名概念定义存在差异"},

    {"id": "consist_term", "desc": "跨章术语命名一致性", "severity": "error",
     "check": lambda _content, ctx: check_terms(load_json(ctx.get("target_path", "")), load_json(ctx.get("prev_path", ""))),
     "pass_msg": "跨章术语命名一致", "fail_msg": "同名术语拼写/缩写存在差异"},

    {"id": "consist_symbol", "desc": "跨章符号含义一致性", "severity": "error",
     "check": lambda _content, ctx: check_symbols(load_json(ctx.get("target_path", "")), load_json(ctx.get("prev_path", ""))),
     "pass_msg": "跨章符号含义一致", "fail_msg": "同名符号含义存在差异"},
]


def main():
    import argparse
    parser = argparse.ArgumentParser(description="jc-6-一致性审核 跨章对比")
    parser.add_argument("--target", required=True, help="当前章 chapter_summary.json")
    parser.add_argument("--prev", required=True, help="已入库章 chapter_summary.json")
    parser.add_argument("--output", default="", help="输出报告路径")
    args = parser.parse_args()

    if not os.path.exists(args.target):
        print(f"[错误] 文件不存在: {args.target}", file=sys.stderr)
        sys.exit(1)

    content = Path(args.target).read_text(encoding="utf-8", errors="replace")
    ctx = {"target_path": args.target, "prev_path": args.prev} if os.path.exists(args.prev) else {"target_path": args.target, "prev_path": ""}

    runner = Runner("jc-6-一致性审核", CHECKS)
    report = runner.run(content, ctx)
    if args.output:
        Path(args.output).write_text(report, encoding="utf-8")
        print(f"[jc-6-一致性审核] 报告: {args.output}")
    else:
        if sys.platform == "win32":
            try:
                sys.stdout.reconfigure(encoding="utf-8")
            except Exception:
                pass
        print(report)
    sys.exit(1 if runner.fails else 0)


if __name__ == "__main__":
    main()
