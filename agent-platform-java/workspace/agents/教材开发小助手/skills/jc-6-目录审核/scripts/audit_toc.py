#!/usr/bin/env python3
"""jc-6-目录审核 — 规则审核脚本"""
import sys, re, json
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

# ── JSON字段完整性检查 ──────────
# 章级必填字段以 jc-1-目录生成 的权威 Schema 为准（课时字段名是 periods）；
# 平台强制元数据（version/generated_at/教材名称/总课时/chapter_no）不计为多余字段。
_CH_REQUIRED_FIELDS = ["id", "title", "chapter_type", "os", "tools", "children2"]
_CH_PERIOD_FIELDS = ("periods", "hours")   # hours 为历史别名，保留兼容

def _check_json_fields(data, errors):
    """递归检查JSON各级必填字段，将错误写入errors列表"""
    # 顶层字段
    for field in ["目标读者", "体例类型", "案例组织模式", "思政_required", "chapters"]:
        if field not in data:
            errors.append(f"缺少顶层字段: {field}")
    if "chapters" not in data or not isinstance(data["chapters"], list):
        return
    # 章级字段
    for i, ch in enumerate(data["chapters"]):
        prefix = f"第{i+1}章"
        for field in _CH_REQUIRED_FIELDS:
            if field not in ch:
                errors.append(f"{prefix}缺少字段: {field}")
        if not any(f in ch for f in _CH_PERIOD_FIELDS):
            errors.append(f"{prefix}缺少字段: periods（课时）")
        if not isinstance(ch.get("children2"), list):
            errors.append(f"{prefix}: children2 应为数组")
            continue
        for j, h2 in enumerate(ch["children2"]):
            hp = f"{prefix} H2[{j+1}]"
            for field in ["id", "name"]:
                if field not in h2:
                    errors.append(f"{hp}缺少字段: {field}")
            for kid_key, kid_label in [("children3", "H3"), ("children4", "H4")]:
                if kid_key in h2 and isinstance(h2[kid_key], list):
                    for k, kid in enumerate(h2[kid_key]):
                        kp = f"{hp} {kid_label}[{k+1}]"
                        for field in ["id", "name"]:
                            if field not in kid:
                                errors.append(f"{kp}缺少字段: {field}")

def _extract_md_chapters(md_text):
    """从目录.md中提取章节层级结构，返回 [(level, num, title), ...]

    兼容两种章标题格式：
    - 项目任务式/章节式：`第X章 XX标题`（# 开头）
    - 项目式：`项目X XX知识主题——案例阶段`（# 开头）
    level 用整数（1=章, 2=H2, 3=H3, 4=H4），与 JSON 侧的 ch/h2/h3/h4 语义对齐。
    """
    entries = []
    for line in md_text.split("\n"):
        line = line.strip()
        if not line.startswith("#"):
            continue
        m = re.match(r'^(#+)\s*(.+)$', line)
        if not m:
            continue
        level = len(m.group(1))
        title = m.group(2).strip()
        # 去掉 Markdown 加粗/反引号等行内标记，仅作标题文本
        title_clean = re.sub(r'[*_`]', '', title).strip()
        entries.append((level, title_clean, title_clean))
    return entries

# 章级标题形态：`第X章 …`（章节式/项目任务式）或 `项目X …`（项目式）
_CHAPTER_TITLE_RE = re.compile(r'^(第[一二三四五六七八九十百零\d]+章|项目[一二三四五六七八九十百零\d]+)')

# 目录.md 里的文档级章节（不是章，也不是章的组件），不参与章数/概述/层级比对
_DOC_SECTION_PREFIXES = ("审阅辅助", "附录", "参考文献", "后记", "前言", "内容提要", "全书概览")

def _is_chapter_title(text: str) -> bool:
    """判断标题文本是否为章级标题。

    区分「章」与文档级标题：`# 目录`、`## 全书概览`、`## 审阅辅助表A` 等是文档结构，
    不是章——不排除会让章数统计、首章概述判定、层级比对全部错位。
    """
    return bool(_CHAPTER_TITLE_RE.match(text.strip()))

def _md_chapter_titles(md_text):
    """目录.md 中的章级标题文本（按出现顺序），已剔除文档级标题。"""
    return [e[1] for e in _extract_md_chapters(md_text) if e[0] == 1 and _is_chapter_title(e[1])]

def _has_overview_chapter(md_text):
    """判断第一章是否为概述章：概述关键词应落在章级（H1）标题中，而非全文任意位置。"""
    ch_titles = _md_chapter_titles(md_text)
    if not ch_titles:
        return False
    first = ch_titles[0]
    return any(kw in first for kw in ["概述", "绪论", "导论", "基础", "入门"])

def _check_title_format_by_mode(md_text, mode):
    """按案例组织模式分支校验章标题格式（与 jc-1-目录生成 案例组织模板一致）。

    - 先导实战式：所有章标题为纯知识主题名，不含 `——`，无"案例实战："前缀
    - 综合案例式：前 M 章为纯知识主题名（不含 `——`）；后 K 章格式 `第X章 案例实战：XX案例名称`
    - 单章案例式：每章标题含 `——`（左侧知识主题 + 右侧案例名）
    - 全书贯穿式：每章标题含 `——`（右侧体现案例执行阶段）
    返回 {"ok": bool, "mode": mode, "problems": [...]}。
    """
    ch_titles = _md_chapter_titles(md_text)
    if not ch_titles:
        return {"ok": True, "mode": mode, "info": "未提取到章标题，跳过"}
    problems = []
    if mode == "先导实战式":
        for t in ch_titles:
            if "——" in t:
                problems.append(f"先导实战式章标题不应含'——': {t}")
    elif mode == "综合案例式":
        # 前 M 章不含——，后 K 章含"案例实战："
        for t in ch_titles:
            if "案例实战：" in t:
                continue
            if "——" in t:
                problems.append(f"综合案例式知识点章标题不应含'——': {t}")
        # 案例章须以"案例实战："前缀且案例名不重复
        case_names = []
        for t in ch_titles:
            if "案例实战：" in t:
                name = t.split("案例实战：")[-1]
                if name in case_names:
                    problems.append(f"综合案例式案例名重复: {name}")
                case_names.append(name)
    elif mode == "单章案例式":
        for t in ch_titles:
            if "——" not in t:
                problems.append(f"单章案例式章标题应含'——'分隔符: {t}")
    elif mode == "全书贯穿式":
        for t in ch_titles:
            if "——" not in t:
                problems.append(f"全书贯穿式章标题应含'——'分隔符: {t}")
    # 模式未知或为空：不做强制判断
    return {"ok": len(problems) == 0, "mode": mode, "problems": problems}


def _check_titleti_template(md_text, data):
    """校验每章 H2 组件与体例模板一致（名称与顺序）。

    体例模板定义于 jc-1-目录生成/references/体例模板/{体例}.md。
    此处内置两体例的 H2 组件清单（与模板一致），按顺序比对。
    返回 {"ok": bool, "problems": [...]}。
    """
    if not data:
        return {"ok": True, "info": "未提供 JSON，跳过体例模板一致性检查"}
    ti = data.get("体例类型", "")
    # 体例模板 H2 组件清单（顺序敏感）
    TEMPLATE = {
        "项目任务式": ["引言", "教学目标", "思维导图", "项目小结", "实训", "课后习题", "阅读拓展"],
        "项目式": ["教学目标", "项目描述", "思维导图", "知识准备", "项目实施", "项目小结", "实训", "课后习题", "阅读拓展"],
    }
    if ti not in TEMPLATE:
        return {"ok": True, "info": f"未知体例类型 '{ti}'，跳过体例模板一致性检查"}
    expected = TEMPLATE[ti]
    problems = []
    for ch in data.get("chapters", []):
        h2_names = [h2.get("name", "") for h2 in ch.get("children2", [])]
        # 提取组件名（去除编号前缀，如 "任务1.1 标题" → 仅比对固定组件）
        # 固定组件（引言/教学目标/思维导图/项目小结/实训/课后习题/阅读拓展/项目描述/知识准备/项目实施）
        fixed = [n for n in h2_names if any(k in n for k in expected)]
        # 顺序比对固定组件出现顺序
        exp_found = [n for n in h2_names if any(k == n or n.startswith(k) for k in expected)]
        # 简化：检查每个期望组件是否在 h2_names 中出现
        missing = [e for e in expected if not any(e in n or n.startswith(e) for n in h2_names)]
        if missing:
            problems.append(f"第{ch.get('id','?')}章缺少体例组件: {missing}")
        # 顺序检查：固定组件在 h2_names 中的相对顺序须与 expected 一致
        idxs = []
        for e in expected:
            for i, n in enumerate(h2_names):
                if e in n or n.startswith(e):
                    idxs.append(i)
                    break
        if idxs and idxs != sorted(idxs):
            problems.append(f"第{ch.get('id','?')}章体例组件顺序异常")
    return {"ok": len(problems) == 0, "problems": problems}

CHECKS = [
    {"id": "dir_ch_count_match", "desc": "章节数符合用户要求", "severity": "error",
     "check": lambda c,ctx: {
         "ok": ctx.get("chapter_count", 0) == 0 or len(_md_chapter_titles(c)) == ctx["chapter_count"],
         "actual": len(_md_chapter_titles(c)),
         "expected": ctx.get("chapter_count", 0)
     },
     "pass_msg": "章节数与用户要求一致", "fail_msg": "章节数不符合用户要求"},

    {"id": "dir_overview", "desc": "第一章为概述", "severity": "error",
     "check": lambda c,ctx: {"ok": _has_overview_chapter(c)},
     "pass_msg": "检测到概述章节", "fail_msg": "未检测到概述性第一章"},

    {"id": "dir_title_fmt", "desc": "标题格式（按案例组织模式分支）", "severity": "error",
     "check": lambda c,ctx: _check_title_format_by_mode(c, ctx.get("case_mode", "")),
     "pass_msg": "章标题格式符合案例组织模式", "fail_msg": "章标题格式不符合案例组织模式"},

    {"id": "dir_case", "desc": "案例内容积极向上（无犯罪/死亡/传染病等消极例）", "severity": "error",
     "check": lambda c,ctx: {"ok": len(has_none(c, ["犯罪", "死亡", "传染病", "自杀", "欺诈"])) == 0, "found": has_none(c, ["犯罪", "死亡", "传染病", "自杀", "欺诈"])},
     "pass_msg": "案例无消极内容", "fail_msg": "案例存在消极词汇"},

    {"id": "dir_tools", "desc": "包含工具/环境章", "severity": "info",
     "check": lambda c,ctx: {"ok": has_any(c, ["工具", "环境", "配置", "搭建", "IDE", "SDK"])},
     "pass_msg": "检测到工具/环境相关章节", "fail_msg": "未检测到工具/环境相关章节（非强制）"},

    {"id": "dir_appendix", "desc": "附录结构", "severity": "info",
     "check": lambda c,ctx: {"ok": has_any(c, ["附录"])},
     "pass_msg": "检测到附录", "fail_msg": "未检测到附录（非强制）"},

    {"id": "dir_json_fields", "desc": "目录结构.json字段完整性", "severity": "error",
     "check": lambda c,ctx: (lambda d=ctx.get("json_data"): (
         {"ok": True, "info": "未提供JSON文件，跳过JSON字段检查"}
         if d is None else (
             (lambda errs=[]: (lambda _: {"ok": len(errs) == 0, "missing_fields": errs} if errs else {"ok": True})(
                 _check_json_fields(d, errs))
             )()
         )
     ))(),
     "pass_msg": "JSON字段完整", "fail_msg": "JSON字段缺失"},

    {"id": "dir_template", "desc": "体例模板结构一致性（H2组件名称与顺序）", "severity": "error",
     "check": lambda c,ctx: _check_titleti_template(c, ctx.get("json_data")),
     "pass_msg": "体例组件完整且顺序正确", "fail_msg": "体例组件缺失或顺序异常"},

    {"id": "dir_md_json_consistency", "desc": "目录.md与目录结构.json一致性", "severity": "error",
     "check": lambda c,ctx: (lambda d=ctx.get("json_data"): (
         {"ok": True, "info": "未提供JSON文件，跳过一致性检查"}
         if d is None else _check_md_json_consistency(c, d)
     ))(),
     "pass_msg": "目录.md与目录结构.json内容一致", "fail_msg": "两个文件内容不一致"},
]

def _norm_node(s) -> str:
    """节点文本归一化：去掉行内标记与首尾空白，供跨格式比对。"""
    return re.sub(r'[*_`]', '', str(s)).strip()

def _strip_chapter_no(t: str) -> str:
    """去掉章标题的章号前缀（`第X章 ` / `项目X `），便于与 JSON 的 title 对齐。"""
    return _CHAPTER_TITLE_RE.sub('', _norm_node(t)).strip()

def _split_chapter_blocks(md_text):
    """把 目录.md 切成章块，返回 [(章标题, 块文本), ...]。

    章块自章级标题起，止于「下一个章级标题 / 水平分割线 / 文档级标题」。
    文档级标题（`# 目录`、`## 全书概览`、`## 审阅辅助表A`、附录等）与尾部辅助表
    不属于任何章，必须排除——否则会被算成章的组件，使层级比对整体错位。
    """
    blocks, title, buf, in_doc = [], None, [], False
    for line in md_text.split("\n"):
        s = line.strip()
        m = re.match(r'^(#+)\s*(.+)$', s)
        if m:
            lvl, text = len(m.group(1)), _norm_node(m.group(2))
            if lvl == 1 and _is_chapter_title(text):
                if title is not None:
                    blocks.append((title, "\n".join(buf)))
                title, buf, in_doc = text, [line], False
                continue
            if lvl == 1 or text.startswith(_DOC_SECTION_PREFIXES):
                if title is not None:
                    blocks.append((title, "\n".join(buf)))
                title, buf, in_doc = None, [], True
                continue
        if re.match(r'^-{3,}\s*$', s):
            if title is not None:
                blocks.append((title, "\n".join(buf)))
            title, buf = None, []
            continue
        if title is not None and not in_doc:
            buf.append(line)
    if title is not None:
        blocks.append((title, "\n".join(buf)))
    return [(t, b) for t, b in blocks if b.strip()]

def _block_nodes(block_text):
    """章块 → 节点序列（按出现顺序）：层级 ≥2 的标题文本 + 有序列表项文本。

    md 侧把 JSON 的深层节点按两种形态渲染——标题（`### 【任务描述】`、`#### A-01 …`）
    或有序列表项（教学目标三项、实训/习题子项、任务实现步骤）。两种形态都收，按
    出现顺序与 JSON 的深度优先节点序列比对。
    """
    nodes = []
    for line in block_text.split("\n"):
        s = line.strip()
        m = re.match(r'^#{2,6}\s*(.+)$', s)
        if m:
            nodes.append(_norm_node(m.group(1)))
            continue
        m = re.match(r'^\d+[.、]\s*(.+)$', s)
        if m:
            nodes.append(_norm_node(m.group(1)))
    return [n for n in nodes if n]

def _node_forms(node):
    """节点的可接受渲染形式：(显示名, [候选文本, ...])。

    JSON 把编号与名称分列（如 `id="A-01"`、`name="Python 程序与运行方式"`），而 md 侧
    对编号的渲染不统一——知识点层级写作 `#### A-01 Python 程序与运行方式`，任务描述层级
    只写 `### 【任务描述】`。故比对时同时接受「名称」与「编号 + 名称」两种形式。
    """
    name = _norm_node(node.get("name", ""))
    nid = _norm_node(node.get("id", ""))
    forms = {name}
    if nid:
        forms.add(f"{nid} {name}".strip())
        forms.add(f"{nid}{name}".strip())
    return name, [f for f in sorted(forms) if f]

def _json_chapter_nodes(ch):
    """JSON 单章节点（深度优先），不含章标题本身；每项为 (显示名, 候选文本)。"""
    nodes = []
    for h2 in ch.get("children2", []):
        nodes.append(_node_forms(h2))
        for h3 in h2.get("children3", []):
            nodes.append(_node_forms(h3))
            for h4 in h3.get("children4", []):
                nodes.append(_node_forms(h4))
    return [(n, f) for n, f in nodes if f]

def _first_missing_in_order(needles, haystack):
    """needles（每项为 (显示名, 候选文本)）是否为 haystack 的顺序子序列。

    返回第一个缺失节点的显示名，全命中返回 None。用顺序子序列而非等值序列：md 侧会
    额外渲染 JSON 未建模的细粒度文本（知识点下的具体写法条目等），多出属正常；
    缺项或次序颠倒才算不一致。
    """
    i = 0
    for name, forms in needles:
        while i < len(haystack) and haystack[i] not in forms:
            i += 1
        if i >= len(haystack):
            return name
        i += 1
    return None

def _check_md_json_consistency(md_text, json_data):
    """检查.md与.json的章/组件层级一致性。

    比对面：章数 → 各章标题 → 各章 H2 组件（数量与顺序严格一致）→ 更深层节点
    （顺序子序列）。章块定界后逐章比对，文档级标题与尾部辅助表不参与。
    """
    blocks = _split_chapter_blocks(md_text)
    chapters = json_data.get("chapters", [])
    mismatches = []
    if len(blocks) != len(chapters):
        return {"ok": False, "mismatches": [f"章数量不一致: .md={len(blocks)}, .json={len(chapters)}"]}
    for i, ((md_title, block), ch) in enumerate(zip(blocks, chapters)):
        jt = _norm_node(ch.get("title", ""))
        if _strip_chapter_no(md_title) != jt:
            mismatches.append(f"章[{i+1}]标题不一致: .md='{md_title}' vs .json='{jt}'")
        md_h2 = [e[1] for e in _extract_md_chapters(block) if e[0] == 2]
        js_h2 = [_norm_node(h2.get("name", "")) for h2 in ch.get("children2", [])]
        if md_h2 != js_h2:
            miss = [n for n in js_h2 if n not in md_h2]
            extra = [n for n in md_h2 if n not in js_h2]
            detail = []
            if miss:
                detail.append(f".md 缺 {miss}")
            if extra:
                detail.append(f".md 多 {extra}")
            if not detail:
                detail.append("名称齐全但顺序不一致")
            mismatches.append(f"章[{i+1}] H2 组件不一致: {'；'.join(detail)}")
        miss = _first_missing_in_order(_json_chapter_nodes(ch), _block_nodes(block))
        if miss:
            mismatches.append(f"章[{i+1}] 深层节点缺失或次序不符，首个问题节点: '{miss}'")
    return {"ok": len(mismatches) == 0, "mismatches": mismatches}

if __name__ == "__main__":
    import argparse, sys, os
    from pathlib import Path
    parser = argparse.ArgumentParser(description="jc-6-目录审核 规则审核")
    parser.add_argument("--target", required=True, help="目录.md 文件路径")
    parser.add_argument("--json", default="", help="目录结构.json 文件路径")
    parser.add_argument("--output", default="", help="输出报告路径")
    parser.add_argument("--prev", default="", help="已入库章节 JSON")
    parser.add_argument("--chapter-count", type=int, default=0, help="用户指定的章节数（0=不检查）")
    args = parser.parse_args()
    if not os.path.exists(args.target):
        print(f"[错误] 文件不存在: {args.target}", file=sys.stderr)
        sys.exit(1)
    content = Path(args.target).read_text(encoding="utf-8", errors="replace")
    ctx = {"chapter_count": args.chapter_count, "json_data": None, "case_mode": ""}
    if args.prev and os.path.exists(args.prev):
        ctx["prev"] = Path(args.prev).read_text(encoding="utf-8", errors="replace")
    if args.json and os.path.exists(args.json):
        try:
            ctx["json_data"] = json.loads(Path(args.json).read_text(encoding="utf-8"))
            ctx["case_mode"] = ctx["json_data"].get("案例组织模式", "")
        except Exception as e:
            print(f"[警告] JSON文件解析失败: {e}", file=sys.stderr)
            ctx["json_data"] = None
    runner = Runner("jc-6-目录审核", CHECKS)
    report = runner.run(content, ctx)
    if args.output:
        Path(args.output).write_text(report, encoding="utf-8")
        print(f"[jc-6-目录审核] 报告: {args.output}")
    else:
        if sys.platform == "win32":
            try:
                sys.stdout.reconfigure(encoding="utf-8")
            except Exception:
                pass
        print(report)
    sys.exit(1 if runner.fails else 0)
