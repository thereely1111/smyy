#!/usr/bin/env python3
"""jc-6-实训审核 — 规则审核脚本

依据 jc-3-实训生成 + 评审文档「4.2.7 实训」：
- 操作题/实训 ≥ 2 题
- 难度递进（第1题基础单知识点，第2题综合多知识点）
- 实训结构：训练要点 + 需求说明 + 实现思路及步骤
- 知识点覆盖：尽量覆盖每个二级标题
- 思政：实训选题尽量与思政相关
"""
import sys, os, json
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


def _extract_h2_names(data):
    """从目录结构.json提取当前章所有正文 H2 名称（去编号）。"""
    names = []
    for ch in data.get("chapters", []):
        for h2 in ch.get("children2", []):
            name = h2.get("name", "")
            if name:
                names.append(name)
    return names


def check_kp_coverage(content, ctx):
    """检查实训是否覆盖二级标题知识点（尽量覆盖，非强制全部）。"""
    toc = ctx.get("toc_data")
    if not toc:
        return {"ok": True, "info": "未提供目录结构.json，跳过知识点覆盖检查"}
    h2_names = _extract_h2_names(toc)
    fixed = {"引言", "教学目标", "思维导图", "项目小结", "小结", "实训", "课后习题", "阅读拓展", "项目描述", "知识准备", "项目实施"}
    content_h2 = [n for n in h2_names if not any(f in n for f in fixed)]
    if not content_h2:
        return {"ok": True, "info": "无正文 H2，跳过"}
    covered = []
    for n in content_h2:
        core = re.sub(r'^(任务)?\d+(\.\d+)*\s*', '', n).strip()
        if core and core in content:
            covered.append(n)
    ratio = len(covered) / len(content_h2)
    # 覆盖 >= 50% 通过（评审文档要求"尽量覆盖"，非全量强制）
    return {
        "ok": ratio >= 0.5,
        "h2_total": len(content_h2),
        "h2_covered": len(covered),
        "covered_names": covered,
        "ratio": ratio,
    }


CHECKS = [
    {"id": "op_count", "desc": "实训>=2", "severity": "error",
     "check": lambda c,ctx: {"ok": count_elements(c, r'实训\s*\d+') >= 2, "actual": count_elements(c, r'实训\s*\d+'), "expected": ">=2"},
     "pass_msg": "实训数量>=2", "fail_msg": "实训数量不足"},

    {"id": "op_difficulty", "desc": "由简单到难排列", "severity": "review",
     "check": lambda c,ctx: {"ok": has_any(c, ["基础", "简单", "基本", "初步", "综合"])},
     "pass_msg": "检测到难度递进标识", "fail_msg": "请确认难度递进"},

    {"id": "op_training", "desc": "实训结构完整", "severity": "error",
     "check": lambda c,ctx: check_sequence(c, ["训练要点|要点|目标", "需求说明|要求|需求", "实现思路|实现|步骤|操作|方法|代码"]),
     "pass_msg": "实训结构完整", "fail_msg": "实训结构不完整"},

    {"id": "op_kp_coverage", "desc": "知识点覆盖二级标题", "severity": "review",
     "check": check_kp_coverage,
     "pass_msg": "实训覆盖足够二级标题", "fail_msg": "实训覆盖二级标题不足"},

    {"id": "op_sizheng", "desc": "实训选题与思政相关", "severity": "review",
     "check": lambda c,ctx: {"ok": has_any(c, ["发展", "创新", "应用", "服务", "社会责任", "安全", "规范"])},
     "pass_msg": "检测到思政关键词", "fail_msg": "请确认思政融入"},
]

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="jc-6-实训审核 规则审核")
    parser.add_argument("--target", required=True, help="待审核实训.md")
    parser.add_argument("--toc", default="", help="目录结构.json（知识点覆盖检查用）")
    parser.add_argument("--output", default="", help="输出报告路径")
    args = parser.parse_args()
    if not os.path.exists(args.target):
        print(f"[错误] 文件不存在: {args.target}", file=sys.stderr)
        sys.exit(1)
    content = Path(args.target).read_text(encoding="utf-8", errors="replace")
    ctx = {"toc_data": None}
    if args.toc and os.path.exists(args.toc):
        try:
            ctx["toc_data"] = json.loads(Path(args.toc).read_text(encoding="utf-8"))
        except Exception as e:
            print(f"[警告] 目录结构.json解析失败: {e}", file=sys.stderr)
    runner = Runner("jc-6-实训审核", CHECKS)
    report = runner.run(content, ctx)
    if args.output:
        Path(args.output).write_text(report, encoding="utf-8")
        print(f"[jc-6-实训审核] 报告: {args.output}")
    else:
        if sys.platform == "win32":
            try:
                sys.stdout.reconfigure(encoding="utf-8")
            except Exception:
                pass
        print(report)
    sys.exit(1 if runner.fails else 0)
