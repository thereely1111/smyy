#!/usr/bin/env python3
"""jc-6-前置后置审核 — 规则审核脚本"""
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
    {"id": "preface_industry", "desc": "前言包含行业背景", "severity": "error",
     "check": lambda c,ctx: {"ok": has_any(c, ["行业", "领域", "发展", "背景", "趋势", "应用"])},
     "pass_msg": "包含行业背景引入", "fail_msg": "缺少行业背景引入"},

    {"id": "preface_feature", "desc": "本书特色描述", "severity": "error",
     "check": lambda c,ctx: {"ok": has_any(c, ["特色", "特点", "设计", "编排", "结构", "案例"])},
     "pass_msg": "本书特色描述存在", "fail_msg": "缺少本书特色描述"},

    {"id": "preface_no_20th", "desc": "前言不含\"二十大\"三字（正文中不得出现）", "severity": "error",
     "check": lambda c,ctx: {"ok": not has_any(c, ["二十大", "党的二十大"]), "found": has_none(c, ["二十大", "党的二十大"])},
     "pass_msg": "未出现\"二十大\"三字", "fail_msg": "出现\"二十大\"三字，违反规范"},

    {"id": "preface_audience", "desc": "前言包含适用对象", "severity": "error",
     "check": lambda c,ctx: {"ok": has_any(c, ["适用", "对象", "读者", "适合", "面向"])},
     "pass_msg": "包含适用对象说明", "fail_msg": "缺少适用对象说明"},

    {"id": "preface_download", "desc": "前言包含代码下载及问题反馈", "severity": "warning",
     "check": lambda c,ctx: {"ok": has_any(c, ["下载", "代码", "反馈", "问题", "配套"])},
     "pass_msg": "包含代码下载及反馈信息", "fail_msg": "缺少代码下载及反馈信息"},

    {"id": "abstract_structure", "desc": "内容提要（两段式：特色+主要内容 / 适用对象）", "severity": "error",
     "check": lambda c,ctx: {"ok": has_any(c, ["内容提要"]) and has_any(c, ["适用", "对象", "读者", "适合"])},
     "pass_msg": "检测到内容提要结构", "fail_msg": "缺少内容提要或适用对象说明"},

    {"id": "appendix_structure", "desc": "附录结构（补充知识点/查询表/名词解释）", "severity": "review",
     "check": lambda c,ctx: {"ok": has_any(c, ["附录"])},
     "pass_msg": "检测到附录", "fail_msg": "未检测到附录（视图书内容而定，非强制）"},

    {"id": "ref_blog", "desc": "参考文献无博客来源", "severity": "error",
     "check": lambda c,ctx: {"ok": len(check_blog(c)) == 0, "found": check_blog(c)},
     "pass_msg": "未引用非权威来源", "fail_msg": "引用了非权威来源"},

    {"id": "ref_note", "desc": "文中无脚注尾注", "severity": "warning",
     "check": lambda c,ctx: {"ok": len(has_none(c, ["脚注", "尾注", "Footnote"])) == 0},
     "pass_msg": "未使用脚注尾注", "fail_msg": "不应使用脚注尾注"},
]

if __name__ == "__main__":
    cli("jc-6-前置后置审核", CHECKS)
