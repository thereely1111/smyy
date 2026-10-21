#!/usr/bin/env python3
"""
审核共享工具 — 被各 jc-6 审核 Skill 的 audit_*.py 引用。

提供：
- 运行框架：Runner（分级判定 error/warning/review）+ cli（argparse 入口）
- 通用检查：count_lines / has_any / check_sequence / 敏感词 / 禁用词 / 口语词 / 人称 / 模糊时间 / 指代不明词

规则常量依据《图书编写与评审注意事项》及 _shared/references/用语与格式规范.md 整理。
"""

# ── 内容统计 ──────────────────────
def count_lines(text: str) -> int:
    return len([l for l in text.split("\n") if l.strip()])

import re

def count_sections(text: str, pattern: str) -> int:
    return len(re.findall(pattern, text, re.MULTILINE))

def count_elements(text: str, pattern: str) -> int:
    return len(re.findall(pattern, text, re.MULTILINE))

# ── 关键词检查 ────────────────────
def has_any(text: str, keywords: list) -> bool:
    return any(kw in text for kw in keywords)

def has_none(text: str, keywords: list) -> list:
    return [kw for kw in keywords if kw in text]

# ── 模式匹配 ──────────────────────
def has_pattern(text: str, pattern: str, min_count: int = 1) -> bool:
    return len(re.findall(pattern, text, re.MULTILINE)) >= min_count

def no_pattern(text: str, pattern: str) -> bool:
    return len(re.findall(pattern, text, re.MULTILINE)) == 0

# ── 结构检查 ──────────────────────
def check_sequence(text: str, patterns: list, ordered: bool = True) -> dict:
    remaining = text
    found = 0
    for pat in patterns:
        m = re.search(pat, remaining)
        if m:
            found += 1
            remaining = remaining[m.end():]
        else:
            return {"ok": False, "found": found, "missing": pat}
    return {"ok": True, "found": found}

# ── 术语提取 ──────────────────────
def extract_terms(text: str) -> set:
    terms = set()
    terms.update(re.findall(r'\*\*(.+?)\*\*', text))
    terms.update(re.findall(r'\u300C(.+?)\u300D', text))
    terms.update(re.findall(r'\u300A(.+?)\u300B', text))
    return {t.strip() for t in terms if len(t.strip()) > 1}

# ── 敏感词 / 用语 / 来源 ──────────
# 依据《图书编写与评审注意事项》敏感内容要求 + 用语与格式规范.md 敏感内容禁止清单
SENSITIVE = [
    # 政治/领土（绝对禁止）
    "地图", "领导人", "国旗", "国徽", "党旗", "党徽", "军装", "钱币",
    "港澳台", "新疆", "西藏", "中美", "中澳", "台湾", "香港", "澳门",
    # 不良社会现象
    "赌博", "暴力", "色情", "淫秽", "毒品", "恐怖主义", "非法", "违禁", "欺诈", "盗窃",
    "犯罪", "自杀", "社会不良现象", "崇洋媚外", "反华",
    # 烟酒等
    "酒", "槟榔", "烟", "彩票",
    # 宗教/迷信
    "宗教", "天堂", "庙宇", "灵魂", "迷信", "星座", "算命", "风水",
    # 歧视
    "歧视", "贫困县",
    # 其他敏感
    "西方节日", "房价", "加班", "996", "游戏", "明星", "奢侈品", "非政府组织",
]

VAGUE_TIME = ["当前", "去年", "今年", "前年", "最近", "近期", "不久前"]

# 人称代词（禁"你/我/他"等人称代词）
PERSONAL = ["我们", "你们", "他们", "她们", "咱们", "笔者", "你", "我", "他", "她", "它"]

# 禁用词（用语与格式规范.md 替换表）
FORBIDDEN_WORDS = {
    "其它": "其他", "比如": "如/例如", "等等": "等", "点击": "单击",
    "拷贝": "复制", "键入": "输入", "拖拽": "拖曳", "拖到": "拖至", "拖动": "拖至",
    "或者": "或", "就可以": "即可", "用来": "用于", "如下所示": "如下",
    "的时候": "时", "时候": "时", "把": "将",
}

# 口语词（语句必须书面化）
COLLOQUIAL_WORDS = ["来", "去", "说", "讲", "演示", "想", "当然", "觉得", "然后", "就"]

# 指代不明词（尽量少用）
VAGUE_REF_WORDS = ["上面", "下面", "以上", "以下", "上述", "接下来"]

BLOG_SOURCES = ["csdn.net", "博客园", "简书", "知乎专栏", "segmentfault", "blog"]

# ── 正文口径 ──────────────────────
# 教材正文里的代码与运行结果都写在 ``` 围栏内（jc-0-代码验证 会回填运行结果）。
# 围栏内的变量名、注释、print 文案、报错信息是程序产物，不是写作表述，不应按写作规范判。
_FENCED_BLOCK = re.compile(r'^[ \t]*```.*?^[ \t]*```', re.MULTILINE | re.DOTALL)
_INLINE_CODE = re.compile(r'`[^`\n]*`')

def prose_only(text: str) -> str:
    """剥离代码围栏、围栏化运行结果与行内代码，只留正文散文。

    仅在「命中 / 不命中」判定里使用；剥离后文本长度已不可比，**禁止**用于行数、篇幅类统计。
    """
    return _INLINE_CODE.sub("", _FENCED_BLOCK.sub("\n", text))

# 模糊时间词的技术语境豁免：`当前` 后紧接这些词时是术语用法（如「当前目录」），不判违规。
# 只收 ≥2 字的明确技术复合词——单字后缀（行/列/值）与普通词首同形（「当前行」vs「当前行情」），
# 收进来会把真违规放过。
VAGUE_TIME_TECH_TAIL = {
    "当前": ["目录", "路径", "工作目录", "文件夹", "文件", "元素", "节点",
             "对象", "列表", "字典", "变量", "单元", "任务", "状态", "位置", "步骤"],
}

# 单字敏感词（酒/烟）的「中性复合词」豁免：同形但语义中性，不判违规。
# 命中仍在任一中立复合词窗口内的出现一律放过，其余（饮酒、酒精、一支烟…）照判。
SINGLE_CHAR_SENSITIVE = {"酒", "烟"}
SINGLE_CHAR_NEUTRAL_COMPOUNDS = {
    "酒": ["酒店", "酒窝"],
    "烟": ["烟囱", "烟雾", "烟花", "烟火", "炊烟"],
}

def check_sensitive(text: str) -> list:
    """敏感词检查（对正文散文匹配）。

    先剥离代码围栏、围栏化运行结果与行内代码；单字敏感词（酒/烟）跳过中性复合词
    （酒店、烟囱…），避免把中性表述当成烟酒内容。
    """
    t = prose_only(text)
    found = []
    for w in SENSITIVE:
        if w not in t:
            continue
        if w not in SINGLE_CHAR_SENSITIVE:
            found.append(w)
            continue
        neutral = SINGLE_CHAR_NEUTRAL_COMPOUNDS[w]
        for m in re.finditer(re.escape(w), t):
            window = t[max(0, m.start() - 1):m.end() + 1]
            if any(c in window for c in neutral):
                continue
            found.append(w)
            break
    return found

def check_vague_time(text: str) -> list:
    """模糊时间词检查（对正文散文匹配），跳过术语用法（如「当前目录」）。"""
    t = prose_only(text)
    found = []
    for w in VAGUE_TIME:
        if w not in t:
            continue
        tails = VAGUE_TIME_TECH_TAIL.get(w, [])
        for m in re.finditer(re.escape(w), t):
            if any(t[m.end():m.end() + len(x)] == x for x in tails):
                continue
            found.append(w)
            break
    return found

def check_personal(text: str) -> list:
    """人称代词检查。对单字「你/我/他」做排除：避免「我国」「图形用户界面」等误报。"""
    found = [w for w in PERSONAL if len(w) > 1 and w in text]
    # 单字人称：需排除常见误报组合
    for single in ["你", "我", "他", "她", "它"]:
        for m in re.finditer(single, text):
            ctx = text[max(0, m.start()-1):m.end()+1]
            # 排除「我国」「我方」「他们」「你们」「用户」等
            if any(ctx.startswith(p) or ctx.endswith(p) for p in ["我国", "我方", "用户", "图形", "他们", "你们", "我们", "她们"]):
                continue
            found.append(single)
            break
    return found

def check_forbidden(text: str) -> list:
    """禁用词检查（用语替换表），返回命中词及建议替代。"""
    return [(w, FORBIDDEN_WORDS[w]) for w in FORBIDDEN_WORDS if w in text]

def check_colloquial(text: str) -> list:
    """口语词检查，返回命中词。"""
    return [w for w in COLLOQUIAL_WORDS if w in text]

def check_vague_ref(text: str) -> list:
    """指代不明词检查。"""
    return [w for w in VAGUE_REF_WORDS if w in text]

def check_blog(text: str) -> list:
    return [d for d in BLOG_SOURCES if d in text]


# ── 审核运行器 ────────────────────
class Runner:
    def __init__(self, name, checks, skipped=None):
        self.name = name
        self.checks = checks
        self.skipped = skipped or []
        self.passes = []
        self.fails = []
        self.warns = []
        self.revs = []

    def run(self, content, context=None):
        ctx = context or {}
        for c in self.checks:
            try:
                r = c["check"](content, ctx)
            except Exception as e:
                r = {"ok": False, "error": str(e)}

            sev = c.get("severity", "error")
            cid = c.get("id", "?")
            desc = c.get("desc", cid)
            if r.get("ok"):
                self.passes.append(c.get("pass_msg", f"通过: {desc}"))
            elif sev == "review":
                self.revs.append({"id": cid, "desc": desc, "msg": c.get("fail_msg", f"待审: {desc}"), **r})
            elif sev == "warning":
                self.warns.append({"id": cid, "desc": desc, "msg": c.get("fail_msg", f"注意: {desc}"), **r})
            else:
                self.fails.append({"id": cid, "desc": desc, "msg": c.get("fail_msg", f"不通过: {desc}"), **r})
        return self.report()

    def report(self):
        lines = []
        hf = bool(self.fails)
        hw = bool(self.warns)
        lines.append(f"[审核结果] **{'不通过' if hf else '通过（有注意事项）' if hw else '通过'}**")
        lines.append("")
        if self.passes:
            lines.append("## 通过项")
            for p in self.passes:
                lines.append(f"- [PASS] {p}")
            lines.append("")
        if self.fails:
            lines.append("## 不通过项")
            for f in self.fails:
                lines.append(f"- [FAIL] **[{f['id']}]** {f['msg']}")
            lines.append("")
        if self.warns:
            lines.append("## 注意事项")
            for w in self.warns:
                lines.append(f"- [WARN] {w['msg']}")
            lines.append("")
        if self.revs:
            lines.append("## 待人工审核")
            for r in self.revs:
                lines.append(f"- [REVIEW] **[{r['id']}]** {r['msg']}")
            lines.append("")
        if self.skipped:
            lines.append("## 已跳过（按开关）")
            for s in self.skipped:
                lines.append(f"- [SKIP] **[{s['id']}]** {s['desc']}（{s['because']}）")
            lines.append("")
        pn, fn, wn, rn, sn = len(self.passes), len(self.fails), len(self.warns), len(self.revs), len(self.skipped)
        lines.append("## 综合评定")
        lines.append(f"- 通过 {pn} / 未通过 {fn} / 注意 {wn} / 待审 {rn} / 已跳过 {sn}")
        lines.append(f"\n**结论**: {'不通过，' + str(fn) + ' 项需修改' if hf else '自动化检查通过'}")
        return "\n".join(lines)


def cli(name, checks, extra_flags=None):
    """脚本入口。

    extra_flags：可选开关，形如 {"sizheng": {"choices": ["on", "off"], "default": "on",
    "help": "..."}}。开关值以 `--sizheng on` 形式传入并写入 ctx["flags"]；判据可用
    `"requires": {"flag": "sizheng", "value": "on", "reason": "思政_required=false"}` 声明
    依赖——开关值不匹配时该判据不参与判定，并在报告中列为「已跳过」。默认值须与历史行为
    一致，保证不传参时判据照旧执行。
    """
    import argparse, sys, os
    from pathlib import Path
    parser = argparse.ArgumentParser(description=f"{name} 规则审核")
    parser.add_argument("--target", required=True, help="待审核文件")
    parser.add_argument("--output", default="", help="输出报告路径")
    parser.add_argument("--prev", default="", help="已入库章节 JSON（一致性审核用）")
    for fname, spec in (extra_flags or {}).items():
        parser.add_argument(f"--{fname}", choices=spec.get("choices"), default=spec.get("default"),
                            help=spec.get("help", ""))
    args = parser.parse_args()
    if not os.path.exists(args.target):
        print(f"[错误] 文件不存在: {args.target}", file=sys.stderr)
        sys.exit(1)
    content = Path(args.target).read_text(encoding="utf-8", errors="replace")
    ctx = {}
    flags = {f: getattr(args, f.replace("-", "_")) for f in (extra_flags or {})}
    ctx["flags"] = flags
    if args.prev and os.path.exists(args.prev):
        ctx["prev"] = Path(args.prev).read_text(encoding="utf-8", errors="replace")
    active, skipped = [], []
    for c in checks:
        req = c.get("requires")
        if req and flags.get(req["flag"]) != req["value"]:
            skipped.append({
                "id": c.get("id", "?"),
                "desc": c.get("desc", c.get("id", "?")),
                "because": req.get("reason") or f"{req['flag']}={flags.get(req['flag'])}",
            })
            continue
        active.append(c)
    runner = Runner(name, active, skipped=skipped)
    report = runner.run(content, ctx)
    if args.output:
        Path(args.output).write_text(report, encoding="utf-8")
        print(f"[{name}] 报告: {args.output}")
    else:
        if sys.platform == "win32":
            try:
                sys.stdout.reconfigure(encoding="utf-8")
            except Exception:
                pass
        print(report)
    sys.exit(1 if runner.fails else 0)
