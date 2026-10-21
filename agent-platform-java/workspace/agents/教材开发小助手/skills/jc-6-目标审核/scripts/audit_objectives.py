#!/usr/bin/env python3
"""jc-6-目标审核 — 规则审核脚本"""
import sys, re
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
from _audit_base import *


def _check_obj_counts(text: str) -> dict:
    """按三块（知识目标/技能目标/素养目标）分别统计条目数并校验范围。
    知识 3~7、技能 3~7、素养 2~4。"""
    blocks = {
        "知识目标": (3, 7),
        "技能目标": (3, 7),
        "素养目标": (2, 4),
    }
    problems = []
    detail = {}
    for name, (lo, hi) in blocks.items():
        # 匹配该块标题到下一个块标题之间的内容
        pat = rf'{name}[^\n]*\n(.*?)(?=知识目标|技能目标|素养目标|\Z)'
        m = re.search(pat, text, re.DOTALL)
        if not m:
            problems.append(f"未找到「{name}」块")
            continue
        body = m.group(1)
        cnt = len(re.findall(r'^(?:[-•\d.、]+|[（(]\d+[）)])\s*\S', body, re.MULTILINE))
        detail[name] = cnt
        if not (lo <= cnt <= hi):
            problems.append(f"{name}数量 {cnt} 不在 {lo}~{hi} 范围")
    return {"ok": len(problems) == 0, "detail": detail, "problems": problems}

CHECKS = [
    {"id": "obj_count", "desc": "教学目标三块数量（知识3~7/技能3~7/素养2~4）", "severity": "error",
     "check": lambda c,ctx: _check_obj_counts(c),
     "pass_msg": "知识/技能/素养目标数量均在规定范围", "fail_msg": "目标数量不在规定范围"},

    {"id": "obj_levels", "desc": "了解/熟悉/掌握层次使用", "severity": "review",
     "check": lambda c,ctx: {"ok": has_any(c, ["了解", "熟悉", "掌握", "理解", "能够"])},
     "pass_msg": "检测到层次关键词", "fail_msg": "请人工确认层次分配"},

    {"id": "obj_period", "desc": "目标条目以句号结尾", "severity": "warning",
     "check": lambda c,ctx: {"ok": has_pattern(c, r'^(?:[-•\d.]+|[（(]\d+[）)])\s*.*。$', 2)},
     "pass_msg": "目标条目以句号结尾", "fail_msg": "部分目标未以句号结尾"},

    {"id": "obj_specific", "desc": "目标描述具体不虚写", "severity": "warning",
     "check": lambda c,ctx: {"ok": len(has_none(c, ["了解相关知识", "掌握相关技能", "熟悉基本概念"])) == 0, "found": has_none(c, ["了解相关知识", "掌握相关技能", "熟悉基本概念"])},
     "pass_msg": "目标描述具体", "fail_msg": "存在过于笼统的目标描述"},
]

if __name__ == "__main__":
    cli("jc-6-目标审核", CHECKS)
