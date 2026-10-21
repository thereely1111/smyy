#!/usr/bin/env python3
"""jc-6-知识准备审核 — 规则审核脚本"""
import sys, re, os
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


# ═══ 来源可追溯检查 ════════════════════════════════════
def check_code_pairing(content, _ctx):
    """检查代码块与结果段落的配对：每个 代码X-N 都应有对应的 运行代码X-N 结果。

    依据 _shared/references/代码规范.md：
    代码 → "运行代码X-N得到的结果如下。" → 结果展示 → 结果解释。
    「代码X-N」为题注（如"代码3-1 描述"），「运行代码X-N」为结果引导句。
    因此 code_pattern 须排除「运行代码」前缀，避免同一编号同时落入两集合导致误判通过。
    """
    code_pattern = re.compile(r'(?<!运行)代码(\d+-\d+)', re.MULTILINE)
    result_pattern = re.compile(r'运行代码(\d+-\d+)', re.MULTILINE)
    code_nums = set(code_pattern.findall(content))
    result_nums = set(result_pattern.findall(content))
    missing_results = code_nums - result_nums
    orphan_results = result_nums - code_nums
    ok = len(missing_results) == 0 and len(orphan_results) == 0
    return {
        "ok": ok,
        "total_code_blocks": len(code_nums),
        "total_result_blocks": len(result_nums),
        "missing_results": list(missing_results)[:10],
        "orphan_results": list(orphan_results)[:10]
    }


def check_source_traceability(content, ctx):
    """验证 资料来源.md 存在且内容可读，并检测正文中的数值/专有名词与来源的对齐度。

    原文不标注 [来源] 块，不做标注存在性检查。
    逐句语义比对由 AI 在第二步完成。
    """
    source_path = ctx.get("source_md_path", "")
    if not source_path or not os.path.exists(source_path):
        return {"ok": False, "error": f"资料来源文件不存在: {source_path}"}

    with open(source_path, "r", encoding="utf-8") as f:
        source_text = f.read()
    source_lines = source_text.splitlines()

    # 孤立的数值检测（来源中找不到对应数字的，标记供 AI 复查）
    orphan_numbers = []
    numbers = re.findall(r'(\d+(?:\.\d+)?\s*[%％倍秒毫秒天月年次个]|约\s*\d+|超过\s*\d+)', content)
    for n in numbers[:30]:
        if n not in source_text:
            orphan_numbers.append(n)

    # 专有名词提取（供 AI 比对）
    proper_nouns = set()
    for m in re.finditer(r'([A-Z][a-zA-Z]+(?:\s[A-Z][a-zA-Z]+)*|Trae|Copilot|Cursor|WorkBuddy|GitHub|Claude|GPT|DeepSeek|Kimi|MCP)', content):
        proper_nouns.add(m.group().strip())
    missing_in_source = [pn for pn in sorted(proper_nouns) if pn not in source_text]

    ok = len(orphan_numbers) == 0
    return {
        "ok": ok,
        "source_file": source_path,
        "source_size": len(source_text),
        "suspicious_numbers": orphan_numbers[:20],
        "missing_terms": missing_in_source[:20],
        "note": "逐句语义比对由 AI 在第二步完成（原文不标注 [来源] 块）"
    }


def check_ui_fabrication(content, ctx):
    """检测正文中的 UI 界面元素是否在来源中存在。

    提取正文中【】包裹的界面元素（菜单名、按钮名、选项卡名），
    以及 "点击/进入/选择 + 路径" 模式的操作描述，与来源全文交叉比对。
    来源中不存在的标记为 [捏造风险]。
    """
    # 读取来源全文（含 KB 全文 + AI 搜索片段 + 外部摘要）
    source_text = ctx.get("source_full_text", "")
    if not source_text:
        # fallback to source_md
        sp = ctx.get("source_md_path", "")
        if sp and os.path.exists(sp):
            with open(sp, "r", encoding="utf-8") as f:
                source_text = f.read()

    if not source_text:
        return {"ok": True, "note": "无来源全文，跳过 UI 捏造检测"}

    # 1. 提取【】包裹的 UI 元素
    ui_elements = re.findall(r'【([^】]+)】', content)

    # 2. 提取操作路径模式："点击/进入/选择 + XXX + →/进入 + YYY"
    action_patterns = re.findall(
        r'(?:点击|单击|进入|选择|打开)\s*[^，。,.\)）]*?(?:【[^】]+】|设置|配置|管理|面板|菜单|页面|对话框|图标|按钮)',
        content
    )

    fabricated_ui = []
    for elem in ui_elements:
        if elem not in source_text:
            fabricated_ui.append({"element": f"【{elem}】", "type": "UI元素"})

    for action in action_patterns:
        # 检查操作路径中的关键名词是否在来源中
        nouns = re.findall(r'【([^】]+)】|(?:设置|配置|管理|面板|菜单|页面|对话框|图标|按钮)', action)
        all_in_source = True
        for n in nouns:
            if n and n not in source_text:
                all_in_source = False
                break
        if not all_in_source:
            fabricated_ui.append({"element": action.strip()[:60], "type": "操作路径"})

    ok = len(fabricated_ui) == 0
    return {
        "ok": ok,
        "total_ui_elements": len(ui_elements),
        "total_action_patterns": len(action_patterns),
        "fabricated_count": len(fabricated_ui),
        "fabricated_items": fabricated_ui[:15],
    }


CHECKS = [
    # ── 来源可追溯（强制，第一步） ──
    {"id": "source_trace", "desc": "来源文件可读 + 数值/术语对登记", "severity": "info",
     "check": check_source_traceability,
     "pass_msg": "资料来源文件就绪，可疑项已列出供 AI 复查", "fail_msg": "资料来源文件不存在或无法读取"},

    {"id": "ui_fabrication", "desc": "UI界面元素捏造检测", "severity": "review",
     "check": check_ui_fabrication,
     "pass_msg": "UI元素/操作路径可在来源中找到", "fail_msg": "存在来源中不存在的UI元素或操作路径，需人工确认"},

    {"id": "body_code_pair", "desc": "代码与结果段落配对", "severity": "error",
     "check": check_code_pairing,
     "pass_msg": "代码与结果一一配对", "fail_msg": "代码与结果段落不配对"},

    {"id": "body_theory", "desc": "理论知识展开顺序", "severity": "review",
     "check": lambda c,ctx: check_sequence(c, ["背景|引入|概述", "概念|定义|什么是", "分类|类型|分为", "作用|功能|用途|应用"]),
     "pass_msg": "检测到知识展开顺序", "fail_msg": "请确认知识展开顺序"},

    {"id": "body_pronouns", "desc": "除体例允许的引导语外不使用人称代词", "severity": "warning",
     "check": lambda c,ctx: {"ok": len(check_personal(c)) == 0, "found": check_personal(c)},
     "pass_msg": "未使用人称代词", "fail_msg": "出现人称代词，请人工判断是否为体例允许的引导语"},

    {"id": "body_questions", "desc": "少用疑问句", "severity": "warning",
     "check": lambda c,ctx: {"ok": no_pattern(c, r'[^。！？\n]*\?[^。！？\n]*')},
     "pass_msg": "疑问句使用合理", "fail_msg": "存在疑问句"},

    {"id": "body_vague", "desc": "无模糊时间词", "severity": "error",
     "check": lambda c,ctx: {"ok": len(check_vague_time(c)) == 0, "found": check_vague_time(c)},
     "pass_msg": "无模糊时间词", "fail_msg": "出现模糊时间词"},

    {"id": "body_sensitive", "desc": "无敏感词", "severity": "error",
     "check": lambda c,ctx: {"ok": len(check_sensitive(c)) == 0, "found": check_sensitive(c)},
     "pass_msg": "无敏感词", "fail_msg": "出现敏感词"},

    {"id": "body_blog", "desc": "未引用非权威来源", "severity": "error",
     "check": lambda c,ctx: {"ok": len(check_blog(c)) == 0, "found": check_blog(c)},
     "pass_msg": "未引用非权威来源", "fail_msg": "引用了博客等非权威来源"},

    {"id": "body_sizheng", "desc": "思政融入自然", "severity": "review",
     "check": lambda c,ctx: {"ok": has_any(c, ["发展", "创新", "国家", "产业", "人才", "安全", "规范", "责任", "工匠"])},
     "pass_msg": "检测到思政关键词", "fail_msg": "请确认思政融入"},
]

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="jc-6-知识准备审核 规则审核")
    parser.add_argument("--target", required=True, help="待审核内容.md")
    parser.add_argument("--source-md", default="", help="资料全文/第X章_资料来源.md 路径（来源闭环检查用）")
    parser.add_argument("--output", default="", help="输出报告路径")
    args = parser.parse_args()
    if not os.path.exists(args.target):
        print(f"[错误] 文件不存在: {args.target}", file=sys.stderr)
        sys.exit(1)
    content = Path(args.target).read_text(encoding="utf-8", errors="replace")
    ctx = {"source_md_path": args.source_md}
    # 来源全文供 UI 捏造检测使用
    if args.source_md and os.path.exists(args.source_md):
        ctx["source_full_text"] = Path(args.source_md).read_text(encoding="utf-8", errors="replace")
    runner = Runner("jc-6-知识准备审核", CHECKS)
    report = runner.run(content, ctx)
    if args.output:
        Path(args.output).write_text(report, encoding="utf-8")
        print(f"[jc-6-知识准备审核] 报告: {args.output}")
    else:
        if sys.platform == "win32":
            try:
                sys.stdout.reconfigure(encoding="utf-8")
            except Exception:
                pass
        print(report)
    sys.exit(1 if runner.fails else 0)
