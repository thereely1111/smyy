#!/usr/bin/env python3
"""jc-6-项目实现审核 — 规则审核脚本"""
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


def check_import_structure(content, ctx):
    """检查导入结构：任务描述4部分 / 项目描述（背景+目标+分析）"""
    # 项目任务式：任务描述应含 理论背景/实际背景/原因/目标 线索
    # 项目式：项目描述应含 项目背景/项目目标/项目分析 线索
    has_task = has_any(content, ["任务描述", "任务实现"])
    has_project = has_any(content, ["项目描述", "项目实施"])

    result = {"task_style": has_task, "project_style": has_project}
    if has_project and not has_task:
        # 项目式：检测背景/目标/分析
        has_bg = has_any(content, ["项目背景", "背景"])
        has_goal = has_any(content, ["项目目标", "目标"])
        has_analysis = has_any(content, ["项目分析", "分析"])
        result["project_parts"] = {"背景": has_bg, "目标": has_goal, "分析": has_analysis}
        # 仅项目背景模式允许无目标/分析
        result["ok"] = has_bg
    elif has_task and not has_project:
        # 项目任务式：检测任务描述
        result["ok"] = has_any(content, ["任务描述"])
    else:
        result["ok"] = True  # 结构由 AI 深度判断

    return result


def check_project_bg_structure(content, ctx):
    """检查项目式「项目背景」结构（背景/数据字段/目标/技术/内容预览/全书关系）。"""
    has_project = has_any(content, ["项目描述", "项目实施"]) and not has_any(content, ["任务描述", "任务实现"])
    if not has_project:
        return {"ok": True, "note": "非项目式，跳过项目背景结构检查"}
    seq = check_sequence(content, [
        "背景|行业|场景|发展|现状",
        "数据|字段|数据集|记录",
        "目标|目的|挑战",
        "技术|方法|工具",
        "内容|本章|主要包括|知识点",
        "关系|关联|全书|本书",
    ])
    return {
        "ok": seq.get("ok", False),
        "missing": seq.get("missing", ""),
    }


def check_verb_title(content, ctx):
    """检查任务实现/项目实施下的三级标题是否动宾结构（AI辅助，脚本仅检测标题存在）"""
    headers = re.findall(r'^###\s+(.+)$', content, re.MULTILINE)
    return {
        "ok": True,
        "h3_count": len(headers),
        "note": "动宾结构由 AI 语义判断",
    }


def check_code_pairing(content, ctx):
    """检查代码块与结果段落配对。

    依据 _shared/references/代码规范.md：代码 → "运行代码X-N得到的结果如下。" → 结果。
    「代码X-N」为题注，「运行代码X-N」为结果引导句，故 code_pattern 须排除「运行代码」前缀。
    """
    code_pattern = re.compile(r'(?<!运行)代码(\d+-\d+)', re.MULTILINE)
    result_pattern = re.compile(r'运行代码(\d+-\d+)', re.MULTILINE)
    code_nums = set(code_pattern.findall(content))
    result_nums = set(result_pattern.findall(content))
    missing = code_nums - result_nums
    return {
        "ok": len(missing) == 0,
        "total_code": len(code_nums),
        "total_result": len(result_nums),
        "missing_results": list(missing)[:10],
    }


def check_caption_refs(content, ctx):
    """检查图/表/代码是否有前置引用句式"""
    orphan = []
    for kind, cap_pat, ref_pat in [
        ("图", r'图\s*\d+-\d+', r'如图\s*\d+-\d+\s*所示'),
        ("表", r'表\s*\d+-\d+', r'如表\s*\d+-\d+\s*所示'),
        ("代码", r'代码\s*\d+-\d+', r'如代码\s*\d+-\d+\s*所示'),
    ]:
        caps = set(re.findall(cap_pat, content))
        refs = set(re.findall(ref_pat, content))
        cap_nums = set(re.findall(r'(\d+-\d+)', ' '.join(caps)))
        ref_nums = set(re.findall(r'(\d+-\d+)', ' '.join(refs)))
        missing = cap_nums - ref_nums
        if missing:
            orphan.append(f"{kind}: {sorted(missing)[:10]}")
    return {"ok": len(orphan) == 0, "orphan": orphan}


def check_code_numbering(content, ctx):
    """检查代码编号连续性（按二级标题分组，简单检查全章连续）"""
    nums = [int(m.split('-')[-1]) for m in re.findall(r'代码\s*(\d+-\d+)', content)]
    if not nums:
        return {"ok": True, "count": 0}
    nums_sorted = sorted(nums)
    gaps = [i for i in range(1, len(nums_sorted)) if nums_sorted[i] != nums_sorted[i-1] + 1]
    return {"ok": len(gaps) == 0, "count": len(nums), "gaps": gaps}


CHECKS = [
    {"id": "impl_import", "desc": "导入结构完整性", "severity": "review",
     "check": check_import_structure,
     "pass_msg": "检测到导入结构线索", "fail_msg": "请确认导入结构（任务描述4部分 / 项目描述3部分）"},

    {"id": "impl_project_bg", "desc": "项目背景结构（背景→数据字段→目标→技术→内容预览→全书关系）", "severity": "review",
     "check": check_project_bg_structure,
     "pass_msg": "检测到项目背景结构线索", "fail_msg": "项目背景结构不完整，请确认（背景/数据字段/目标/技术/内容预览/全书关系）"},

    {"id": "impl_verb_title", "desc": "实现部分动宾标题", "severity": "review",
     "check": check_verb_title,
     "pass_msg": "实现部分有三级标题", "fail_msg": "请确认实现部分标题为动宾结构"},

    {"id": "impl_code_pair", "desc": "代码-结果配对", "severity": "error",
     "check": check_code_pairing,
     "pass_msg": "代码与结果一一配对", "fail_msg": "代码与结果段落不配对"},

    {"id": "impl_caption_ref", "desc": "题注前置引用", "severity": "error",
     "check": check_caption_refs,
     "pass_msg": "图/表/代码均有前置引用", "fail_msg": "存在无前置引用的图/表/代码"},

    {"id": "impl_code_num", "desc": "代码编号连续性", "severity": "warning",
     "check": check_code_numbering,
     "pass_msg": "代码编号连续", "fail_msg": "代码编号存在缺号"},

    {"id": "impl_sensitive", "desc": "无敏感词", "severity": "error",
     "check": lambda c, ctx: {"ok": len(check_sensitive(c)) == 0, "found": check_sensitive(c)},
     "pass_msg": "无敏感词", "fail_msg": "出现敏感词"},

    {"id": "impl_pronoun", "desc": "不使用人称代词", "severity": "warning",
     "check": lambda c, ctx: {"ok": len(check_personal(c)) == 0, "found": check_personal(c)},
     "pass_msg": "未使用人称代词", "fail_msg": "出现人称代词"},

    {"id": "impl_vague", "desc": "无模糊时间词", "severity": "error",
     "check": lambda c, ctx: {"ok": len(check_vague_time(c)) == 0, "found": check_vague_time(c)},
     "pass_msg": "无模糊时间词", "fail_msg": "出现模糊时间词"},
]


if __name__ == "__main__":
    cli("jc-6-项目实现审核", CHECKS)
