#!/usr/bin/env python3
"""jc-6-小结审核 — 规则审核脚本

依据 jc-3-小结生成 + 评审文档「3.2 小结」：
- 至少 3 行
- 每个二级标题的内容须在正文中体现（小结需覆盖所有二级标题）
- 体现二级标题之间的联系
- 思政融入（知识点总结 + 思政正面引导）
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
    """从目录结构.json提取当前章所有 H2 名称（去编号）。"""
    names = []
    for ch in data.get("chapters", []):
        for h2 in ch.get("children2", []):
            name = h2.get("name", "")
            if name:
                names.append(name)
    return names


def check_h2_coverage(content, ctx):
    """检查小结是否提及目录中的二级标题内容。"""
    toc = ctx.get("toc_data")
    if not toc:
        return {"ok": True, "info": "未提供目录结构.json，跳过H2覆盖检查"}
    h2_names = _extract_h2_names(toc)
    if not h2_names:
        return {"ok": True, "info": "目录无 H2，跳过"}
    # 过滤体例固定组件（引言/教学目标/思维导图/项目小结/实训/课后习题/阅读拓展/项目描述/知识准备/项目实施）
    fixed = {"引言", "教学目标", "思维导图", "项目小结", "小结", "实训", "课后习题", "阅读拓展", "项目描述", "知识准备", "项目实施"}
    content_h2 = [n for n in h2_names if not any(f in n for f in fixed)]
    if not content_h2:
        return {"ok": True, "info": "无正文 H2，跳过"}
    # 检查每个 H2 的关键词是否在小结中出现（H2 名去编号后取核心词）
    missing = []
    for n in content_h2:
        # 去除编号前缀如 "任务1.1 "、"1.1 "
        core = re.sub(r'^(任务)?\d+(\.\d+)*\s*', '', n).strip()
        if core and core not in content:
            missing.append(n)
    return {
        "ok": len(missing) == 0,
        "h2_total": len(content_h2),
        "h2_missing": missing,
    }


CHECKS = [
    {"id": "sum_len", "desc": "篇幅至少3行", "severity": "error",
     "check": lambda c,ctx: {"ok": count_lines(c) >= 3, "actual": count_lines(c), "expected": ">=3行"},
     "pass_msg": "总结篇幅>=3行", "fail_msg": "总结篇幅不足"},

    {"id": "sum_h2", "desc": "覆盖每个二级标题内容", "severity": "error",
     "check": check_h2_coverage,
     "pass_msg": "小结覆盖所有二级标题", "fail_msg": "小结未覆盖部分二级标题"},

    {"id": "sum_sizheng", "desc": "思政融入自然", "severity": "review",
     "check": lambda c,ctx: {"ok": has_any(c, ["发展", "创新", "应用", "意义", "价值", "贡献"])},
     "pass_msg": "检测到思政关键词", "fail_msg": "请确认思政融入"},
]

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="jc-6-小结审核 规则审核")
    parser.add_argument("--target", required=True, help="待审核小结.md")
    parser.add_argument("--toc", default="", help="目录结构.json（H2覆盖检查用）")
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
    runner = Runner("jc-6-小结审核", CHECKS)
    report = runner.run(content, ctx)
    if args.output:
        Path(args.output).write_text(report, encoding="utf-8")
        print(f"[jc-6-小结审核] 报告: {args.output}")
    else:
        if sys.platform == "win32":
            try:
                sys.stdout.reconfigure(encoding="utf-8")
            except Exception:
                pass
        print(report)
    sys.exit(1 if runner.fails else 0)
