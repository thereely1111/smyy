#!/usr/bin/env python3
"""jc-6-引言审核 — 规则审核脚本"""
import sys
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

CHECKS = [
    {"id": "intro_lines", "desc": "引言篇幅4~10行", "severity": "error",
     "check": lambda c,ctx: {"ok": 4 <= count_lines(c) <= 10, "actual": count_lines(c), "expected": "4~10"},
     "pass_msg": "引言篇幅在4~10行内", "fail_msg": "引言篇幅不在4~10行范围"},

    {"id": "intro_3parts", "desc": "引言3部分结构（背景→全书关系→内容预览）", "severity": "error",
     "check": lambda c,ctx: check_sequence(c, ["背景|行业|领域|随着", "本书|全书|章节|关系|关联|承接", "本章将|本章介绍|本章主要|内容预览|主要包括"]),
     "pass_msg": "检测到引言3部分结构", "fail_msg": "缺少完整3部分结构"},

    {"id": "intro_book_rel", "desc": "体现本章与全书关系", "severity": "error",
     "check": lambda c,ctx: {"ok": has_any(c, ["本书", "全书", "前章", "后续章节", "承接"])},
     "pass_msg": "体现了全书关系", "fail_msg": "未体现全书关系"},

    {"id": "intro_sizheng", "desc": "思政融入自然", "severity": "review",
     "check": lambda c,ctx: {"ok": has_any(c, ["发展", "国家", "产业", "创新", "人才", "战略", "应用", "服务", "社会", "时代"])},
     "pass_msg": "检测到思政关键词", "fail_msg": "请人工确认思政融入"},
]

if __name__ == "__main__":
    cli("jc-6-引言审核", CHECKS)
