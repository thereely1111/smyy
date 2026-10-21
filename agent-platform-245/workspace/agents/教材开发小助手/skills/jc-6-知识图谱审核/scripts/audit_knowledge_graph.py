#!/usr/bin/env python3
"""jc-6-知识图谱审核 — 规则审核脚本"""
import sys, os, json, re
from pathlib import Path
from collections import defaultdict

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
from _audit_base import Runner

# ── JSON 字段完整性检查 ──────────
def _check_json_fields(data, errors):
    """递归检查知识图谱JSON各级必填字段"""
    # meta 字段
    if "meta" not in data:
        errors.append("缺少顶层字段: meta")
    # theme 字段
    themes = data.get("themes", [])
    if not isinstance(themes, list) or len(themes) == 0:
        errors.append("themes 为空或不是数组")
        return
    all_kids = []
    for ti, theme in enumerate(themes):
        tp = f"theme[{ti}]"
        for f in ["id", "name", "order"]:
            if f not in theme:
                errors.append(f"{tp} 缺少字段: {f}")
        # 知识点
        nodes = theme.get("knowledge_points", [])
        for ni, node in enumerate(nodes):
            np = f"{tp}.kp[{ni}]"
            for f in ["id", "name", "difficulty"]:
                if f not in node:
                    errors.append(f"{np} 缺少字段: {f}")
            if "id" in node:
                all_kids.append(node["id"])
    # 思政点（顶层字段，与 jc-1-知识图谱 生成端 Schema 一致）
    sp_ids = []
    sps = data.get("思政_points", [])
    if not isinstance(sps, list):
        errors.append("顶层字段 思政_points 应为数组")
    else:
        for si, sp in enumerate(sps):
            if "target_knowledge" not in sp:
                errors.append(f"思政_points[{si}] 缺少字段: target_knowledge")
            if "id" in sp:
                sp_ids.append(sp["id"])
    dupes = [sid for sid in sp_ids if sp_ids.count(sid) > 1]
    if dupes:
        errors.append(f"思政点 ID 重复: {set(dupes)}")
    return all_kids


# ── 依赖图构建 ──────────
def _build_graph(data):
    """构建邻接表 {kid: set(后置kids)} 和 入度统计"""
    graph = defaultdict(set)
    indeg = defaultdict(int)
    all_kids = set()
    for theme in data.get("themes", []):
        for node in theme.get("knowledge_points", []):
            kid = node.get("id", "")
            all_kids.add(kid)
            for dep in node.get("dependents", []):
                graph[kid].add(dep)
                indeg[dep] += 1
            for pre in node.get("prerequisites", []):
                graph[pre].add(kid)
                indeg[kid] += 1
    return graph, indeg, all_kids


# ── 循环检测 (DFS) ──────────
def _detect_cycles(graph, all_kids):
    """返回所有环路"""
    cycles = []
    visited = set()
    rec_stack = set()
    path = []

    def dfs(node):
        visited.add(node)
        rec_stack.add(node)
        path.append(node)
        for nb in graph.get(node, set()):
            if nb not in visited:
                dfs(nb)
            elif nb in rec_stack:
                cycle_start = path.index(nb)
                cycles.append(path[cycle_start:] + [nb])
        path.pop()
        rec_stack.discard(node)

    for kid in list(all_kids):
        if kid not in visited:
            dfs(kid)
    return cycles


# ── 难度校验 ──────────
VALID_DIFFICULTY = {"基础", "进阶", "高级"}

def _check_difficulty(data):
    invalid = []
    stats = {"基础": 0, "进阶": 0, "高级": 0}
    for theme in data.get("themes", []):
        for node in theme.get("knowledge_points", []):
            d = node.get("difficulty", "")
            if d not in VALID_DIFFICULTY:
                invalid.append(f"{node.get('id', '?')}: {d}")
            else:
                stats[d] += 1
    total = sum(stats.values())
    ratio = stats["基础"] / total if total > 0 else 0
    return invalid, stats, ratio, total


# ── 双格式一致性 ──────────
def _check_dual_format(json_path, md_path, errors):
    """对比 .json 和 .md 的关键字段。

    以 JSON 为唯一权威源，仅校验 MD 是否「包含」JSON 的关键要素（知识点 ID、
    name、difficulty、theme 名、meta 关键值）。不猜测 MD 的结构化呈现方式，
    因此 MD 无论用表格还是标题列表呈现均不会误报。
    """
    with open(json_path, "r", encoding="utf-8") as f:
        jd = json.load(f)
    with open(md_path, "r", encoding="utf-8") as f:
        md = f.read()

    # 1. 知识点数量与 ID 覆盖：以知识点 ID（形如 "A-01"）为稳定锚点，
    #    在 MD 中做整词匹配，避免按标题数/表格行数猜测导致误报。
    j_kps = []
    for t in jd.get("themes", []):
        for n in t.get("knowledge_points", []):
            j_kps.append(n)
    j_count = len(j_kps)
    md_kp_ids = re.findall(r'(?<![-\w])([A-Z]-\d+(?:-\d+)?)(?![-\w])', md)
    md_id_set = set(md_kp_ids)
    missing = [n.get("id", "?") for n in j_kps if n.get("id", "") not in md_id_set]
    if missing:
        errors.append(f"知识点在 MD 中缺失（共 {j_count} 个，缺失 {len(missing)} 个）: {missing}")

    # 2. name/difficulty 一致性：每个知识点名称、难度值需在 MD 中出现
    for n in j_kps:
        kid = n.get("id", "?")
        name = n.get("name", "")
        if name and name not in md:
            errors.append(f"知识点 '{kid}' 的 name '{name}' 在 MD 中未找到")
        diff = n.get("difficulty", "")
        if diff and diff not in md:
            errors.append(f"知识点 '{kid}' 的 difficulty '{diff}' 在 MD 中未找到")

    # 3. meta 一致性（字段名对齐真实 schema：面向对象）
    j_meta = jd.get("meta", {})
    if j_meta.get("面向对象") and j_meta["面向对象"] not in md:
        errors.append(f"meta.面向对象 '{j_meta['面向对象']}' 在 MD 中未找到")

    # 4. theme 一致性
    for ti, t in enumerate(jd.get("themes", [])):
        t_name = t.get("name", "")
        if t_name and t_name not in md:
            errors.append(f"theme[{ti}].name '{t_name}' 在 MD 中未找到")


# ── 主流程 ──────────
def main():
    import argparse
    parser = argparse.ArgumentParser(description="jc-6-知识图谱审核")
    parser.add_argument("--json", required=True, help="知识图谱.json 路径")
    parser.add_argument("--md", required=True, help="知识图谱.md 路径")
    args = parser.parse_args()

    if not os.path.exists(args.json):
        print(f"[错误] 文件不存在: {args.json}", file=sys.stderr)
        sys.exit(1)
    if not os.path.exists(args.md):
        print(f"[错误] 文件不存在: {args.md}", file=sys.stderr)
        sys.exit(1)

    with open(args.json, "r", encoding="utf-8") as f:
        data = json.load(f)

    errors = []
    passes = []

    # 1. JSON 字段完整性
    all_kids = _check_json_fields(data, errors)
    passes.append("[PASS] JSON字段完整性检测" if not errors or "[{tp} 缺少字段" not in str(errors[:2]) else "")
    # 去重相关错误消息
    json_field_errors = [e for e in errors if "缺少字段" in e or "重复" in e or "为空" in e]

    # 2. 思政点校验（顶层字段，与 jc-1-知识图谱 生成端 Schema 一致）
    sp_ok = True
    for sp in data.get("思政_points", []):
        tid = sp.get("target_knowledge", "")
        if tid and tid not in all_kids:
            errors.append(f"思政点 target_knowledge '{tid}' 不在知识图谱中")
            sp_ok = False
    if sp_ok:
        passes.append("[PASS] 思政点 target_knowledge 全部有效")

    # 3. 依赖循环检测
    graph, indeg, all_kids_set = _build_graph(data)
    cycles = _detect_cycles(graph, all_kids_set)
    if cycles:
        for cy in cycles:
            errors.append(f"依赖循环: {' → '.join(str(c) for c in cy)}")
    else:
        passes.append("[PASS] 无依赖循环")

    # 4. 孤点检测
    orphans = []
    entries = [kid for kid in all_kids_set if indeg.get(kid, 0) == 0]
    for kid in all_kids_set:
        if indeg.get(kid, 0) == 0 and not graph.get(kid):
            orphans.append(kid)
    if orphans:
        errors.append(f"疑似孤立节点 (无前置无后置): {orphans}")
    else:
        passes.append("[PASS] 无孤立节点")

    # 5. 入口统计
    theme_count = len(data.get("themes", []))
    if len(entries) > theme_count:
        errors.append(f"入口过多: {len(entries)} 个无前置节点 > {theme_count} 个主题域")
    else:
        passes.append(f"[PASS] 入口数 ({len(entries)}) ≤ 主题域数 ({theme_count})")

    # 6. 难度分布
    invalid_diff, diff_stats, ratio, total = _check_difficulty(data)
    if invalid_diff:
        for iv in invalid_diff:
            errors.append(f"非法难度值: {iv}")
    if ratio < 0.3:
        errors.append(f"基础占比不足: {ratio:.1%} < 30% (基础{ratio*100:.0f}%/进阶{diff_stats.get('进阶',0)}/高级{diff_stats.get('高级',0)})")
    else:
        passes.append(f"[PASS] 基础占比 {ratio:.1%} ≥ 30%")

    # 7. 双格式一致性
    _check_dual_format(args.json, args.md, errors)
    dual_errors = [e for e in errors if "不一致" in e or "不存在" in e]
    if not dual_errors:
        passes.append("[PASS] .md 与 .json 双格式一致")

    # ── 输出报告 ──
    print(f"## [审核结果] {'不通过' if errors else '通过'}")
    print()
    print("### 通过项")
    for p in passes:
        print(f"- {p}")
    print()
    if errors:
        print("### 不通过项")
        for e in errors:
            print(f"- [FAIL] {e}")
        print()
    print("### [综合评定]")
    print(f"- 通过 {len(passes)} / 未通过 {len(errors)}")
    print(f"\n**结论**: {'不通过，需退回 jc-1-知识图谱 修改后再审' if errors else 'PASS，通过审核'}")

    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
