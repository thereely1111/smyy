#!/usr/bin/env python3
"""jc-6-阅读拓展审核 — 规则审核脚本"""
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
    {"id": "rd_structure", "desc": "导读+思考题结构", "severity": "error",
     "check": lambda c, ctx: {"ok": has_any(c, ["导读", "阅读拓展"]) and has_any(c, ["思考", "想一想", "问题"])},
     "pass_msg": "检测到导读+思考题结构", "fail_msg": "缺少导读或思考题"},

    {"id": "rd_sizheng", "desc": "思政元素融入", "severity": "error",
     # 是否要求融入思政由目录声明（目录结构.json 的 思政_required）决定，不在本脚本自行假定；
     # 父编排按其值传 --sizheng on/off，off 时本判据不参与判定（见 SKILL.md）。
     "requires": {"flag": "sizheng", "value": "on", "reason": "sizheng=off（思政_required=false，本章不写作思政内容）"},
     "check": lambda c, ctx: {"ok": has_any(c, ["党的二十大", "二十大", "十五五", "文化", "数字中国", "创新", "强国", "发展", "工匠", "复兴", "科技", "民族"])},
     "pass_msg": "检测到思政关键词", "fail_msg": "未检测到思政元素，请确认"},

    {"id": "rd_question", "desc": "思考题与导读结合", "severity": "review",
     "check": lambda c, ctx: {"ok": has_any(c, ["？", "?"])},
     "pass_msg": "检测到思考题", "fail_msg": "请确认思考题是否与导读结合"},

    {"id": "rd_sensitive", "desc": "无敏感词", "severity": "error",
     "check": lambda c, ctx: {"ok": len(check_sensitive(c)) == 0, "found": check_sensitive(c)},
     "pass_msg": "无敏感词", "fail_msg": "出现敏感词"},

    {"id": "rd_pronoun", "desc": "不使用人称代词", "severity": "warning",
     "check": lambda c, ctx: {"ok": len(check_personal(c)) == 0, "found": check_personal(c)},
     "pass_msg": "未使用人称代词", "fail_msg": "出现人称代词"},

    {"id": "rd_vague", "desc": "无模糊时间词", "severity": "error",
     "check": lambda c, ctx: {"ok": len(check_vague_time(c)) == 0, "found": check_vague_time(c)},
     "pass_msg": "无模糊时间词", "fail_msg": "出现模糊时间词"},
]

if __name__ == "__main__":
    cli("jc-6-阅读拓展审核", CHECKS, extra_flags={
        "sizheng": {
            "choices": ["on", "off"], "default": "on",
            "help": "是否要求融入思政；取目录结构.json 的 思政_required（true→on / false→off）。off 时跳过思政判据",
        },
    })
