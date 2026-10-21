#!/usr/bin/env python3
"""jc-6-课后习题审核 — 规则审核脚本

题目编号规范（与 jc-3-课后习题 生成端一致）：
- 题目编号用（1）（2）（3）全角括号
- 选择题题干：括号填空格式，如（1）（    ）不属于……。题干末尾句号
- 选项格式：A．B．C．D． 大写字母编号（全角点）
- 多选题在题目开头标注【多选题】
"""
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
    {"id": "cq_count", "desc": "选择题>=5题", "severity": "error",
     "check": lambda c,ctx: {"ok": count_elements(c, r'^（\d+）') >= 5, "actual": count_elements(c, r'^（\d+）'), "expected": ">=5"},
     "pass_msg": "选择题数量>=5", "fail_msg": "选择题数量不足"},

    {"id": "cq_option_fmt", "desc": "选项格式统一(A．B．C．D．)", "severity": "warning",
     "check": lambda c,ctx: {"ok": has_pattern(c, r'^[A-D][．.]', 4)},
     "pass_msg": "选项格式统一", "fail_msg": "选项格式可能不统一"},

    {"id": "cq_bracket", "desc": "括号空4格全角", "severity": "warning",
     "check": lambda c,ctx: {"ok": has_pattern(c, r'（\s{2,}）', 2)},
     "pass_msg": "括号空格符合规范", "fail_msg": "括号空格可能不符合规范"},

    {"id": "cq_multi", "desc": "多选题标注【多选题】", "severity": "warning",
     "check": lambda c,ctx: {"ok": no_pattern(c, r'(?:多选题|多项选择)(?!.*【多选题】)')},
     "pass_msg": "多选题标签正确", "fail_msg": "多选题可能缺少【多选题】标签"},

    {"id": "cq_period", "desc": "题干以句号结尾", "severity": "warning",
     "check": lambda c,ctx: {"ok": has_pattern(c, r'^（\d+）.*。\s*$', 3)},
     "pass_msg": "题干以句号结尾", "fail_msg": "部分题干未以句号结尾"},
]

if __name__ == "__main__":
    cli("jc-6-课后习题审核", CHECKS)
