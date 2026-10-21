"""对知识点/案例列表执行 KB 优先检索，返回结构化引用数据。
案例库命中后自动下载配套代码与数据文件。

检索策略: AI搜索 → 关键词搜索 → 返回空（双通道均支持按知识库空间限定）
所有检索并发执行，不再串行等待。
支持 scope 参数限定检索范围（knowledge=学科知识库 / case=案例库 / dataset=数据库）。
支持 skip_kb 跳过已知无 KB 命中的目标。

用法:
    from search_kb import search_knowledge_points, search_cases, download_kp_assets

    # 技术知识点检索（默认限定在学科知识库，仅检索，不下载）
    results = search_knowledge_points(["知识点1", "知识点2"])

    # 案例/示例检索（限定在案例库）+ 配套下载
    case_results = search_cases(["案例主题1", "案例主题2"])
    hits, _ = split_hits_misses(case_results)
    if hits:
        download_kp_assets(hits, "artifacts/第X章_标题/案例资料/", scope="case")

    # 指定 skip_kb 跳过某些知识点（如思政素材），直接返回 kb_hit=False
    results = search_knowledge_points(
        ["知识点1", "知识点2", "思政素材1"],
        skip_kb=["思政素材1"]
    )

    # 按命中/未命中分组，便于调用方并行外部搜索
    hits, misses = split_hits_misses(results)
"""

import sys
import concurrent.futures
from lexiang_api import get_kb


def title_match_score(query, title):
    """客户端关键词重叠得分：返回标题与搜索词的重叠词数。

    优先使用 jieba 分词，不可用时降级为中文 2-gram 滑动窗口。
    过滤单字和纯数字 token。

    Returns:
        int — 重叠的关键词数量
    """
    import re

    def _extract_fallback(text):
        """2-gram 滑动窗口兜底分词"""
        words = set(re.findall(r'[a-zA-Z0-9]+', text.lower()))
        ch = re.findall(r'[\u4e00-\u9fa5]', text)
        for i in range(len(ch) - 1):
            words.add(ch[i] + ch[i + 1])
        return words

    def _tokenize(text):
        try:
            import jieba
            return set(jieba.cut(text))
        except ImportError:
            return _extract_fallback(text)

    q_words = {w for w in _tokenize(query) if len(w) >= 2 and not w.isdigit()}
    t_words = {w for w in _tokenize(title) if len(w) >= 2 and not w.isdigit()}

    if not q_words:
        return 0

    return len(q_words & t_words)


def _title_matches(query, title):
    """客户端相关性过滤：标题与搜索词是否有足够重叠。

    使用 title_match_score 计算重叠数：
    - ≥ 2 个词重叠 → 匹配
    - 仅 1 个词但长度 ≥ 3 → 也匹配（SQLAlchemy/TRAE 单关键词场景）
    - 否则不匹配
    """
    score = title_match_score(query, title)
    if score >= 2:
        return True
    if score == 1:
        import re
        def _extract(text):
            w = set(re.findall(r'[a-zA-Z0-9]+', text.lower()))
            ch = re.findall(r'[\u4e00-\u9fa5]', text)
            for i in range(len(ch) - 1):
                w.add(ch[i] + ch[i+1])
            return {x for x in w if len(x) >= 2 and not x.isdigit()}
        qw = _extract(query)
        tw = _extract(title)
        overlap = qw & tw
        if overlap:
            return max(len(w) for w in overlap) >= 3
    return False


def _search_one_kp(kp, scope="knowledge"):
    """单个知识点的 KB 检索（worker 函数，供线程池调用）。

    每个线程独立获取 KB 实例（单例线程安全）。
    scope: "knowledge" | "case" | "dataset" — 限定检索对应知识库空间。
    """
    kb = get_kb()
    result = kb.search(query=kp, scope=scope)
    results = []

    if result["source"] == "ai":
        for item in result["ai_results"]:
            title = item.get("title", "")

            # ── 客户端相关性过滤 ──
            # 标题与搜索词至少 2 个关键词重叠才算命中
            # 防止搜索词过宽时溢出（如搜"智能体"返回 WorkBuddy）
            if not _title_matches(kp, title):
                continue

            引用行 = kb.format_citation(item)
            # 从 URL 提取 entry_id（format_citation 内部也用同样逻辑）
            url = item.get("url", "")
            entry_id = url.rsplit("/", 1)[-1] if "/" in url else ""
            results.append({
                "知识点": kp,
                "kb_hit": True,
                "来源": "ai",
                "scope": scope,
                "引用行": 引用行,
                "entry_id": entry_id,
            })

    elif result["source"] == "keyword":
        for item in result["kw_results"]:
            全文 = kb.get_entry_content(item["id"])
            乐享URL = f"https://lexiangla.com/pages/{item['id']}"
            引用行 = (
                f"- 来源：[知识库] {item['name']}，{乐享URL}\n"
                f"  entry_id: {item['id']}\n"
                f"  摘要：{全文[:200]}…"
            )
            results.append({
                "知识点": kp,
                "kb_hit": True,
                "来源": "keyword",
                "scope": scope,
                "引用行": 引用行,
                "entry_id": item["id"],
            })

    else:  # none
        results.append({
            "知识点": kp,
            "kb_hit": False,
            "来源": "none",
            "scope": scope,
            "引用行": None,
        })

    return results


def search_knowledge_points(knowledge_points, skip_kb=None, max_workers=8, scope="knowledge"):
    """对知识点列表执行 KB 并行检索（默认限定在学科知识文件夹）。

    检索策略: AI搜索 → 关键词搜索 → 返回空（每个知识点独立并发）。

    Args:
        knowledge_points: list[str] 知识点名称列表
        skip_kb: list[str] | None — 跳过 KB 检索的知识点，直接标记为 kb_hit=False
                 适用于已知 KB 中不存在的内容（如思政素材、非技术类内容）
        max_workers: 最大并发线程数（默认 8）
        scope: "knowledge" | "case" | "dataset" | None（全库，默认 "knowledge"）

    Returns:
        list[dict]: 每个元素:
            {知识点, kb_hit(bool), 来源(str), scope(str), 引用行(str|None)}
    """
    results = []
    skip_set = set(skip_kb or [])

    # ── 分离：跳过的直接返回 kb_hit=False ──
    to_search = [kp for kp in knowledge_points if kp not in skip_set]
    skipped = [kp for kp in knowledge_points if kp in skip_set]

    for kp in skipped:
        results.append({
            "知识点": kp,
            "kb_hit": False,
            "来源": "skipped",
            "scope": scope,
            "引用行": None,
        })

    # ── 并发检索 ──
    if to_search:
        workers = min(max_workers, len(to_search))
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
            future_to_kp = {
                executor.submit(_search_one_kp, kp, scope): kp
                for kp in to_search
            }
            for future in concurrent.futures.as_completed(future_to_kp):
                try:
                    results.extend(future.result())
                except Exception as e:
                    kp = future_to_kp[future]
                    results.append({
                        "知识点": kp,
                        "kb_hit": False,
                        "来源": "error",
                        "scope": scope,
                        "引用行": None,
                    })

    return results


def search_cases(case_topics, skip_kb=None, max_workers=8):
    """对案例/示例主题列表执行 KB 检索（限定在案例集文件夹）。

    与 search_knowledge_points 逻辑相同，但 scope 固定为 "case"。

    Args:
        case_topics: list[str] 案例主题名称
        skip_kb: list[str] | None — 跳过 KB 检索的主题
        max_workers: 最大并发线程数

    Returns:
        list[dict]: 同 search_knowledge_points 返回格式
    """
    return search_knowledge_points(case_topics, skip_kb=skip_kb, max_workers=max_workers, scope="case")


def split_hits_misses(results):
    """将检索结果按命中/未命中分组，便于调用方对未命中项并发外部搜索。

    Args:
        results: search_knowledge_points 的返回值

    Returns:
        (hits, misses): 两个 list[dict]
    """
    hits = [r for r in results if r["kb_hit"]]
    misses = [r for r in results if not r["kb_hit"]]
    return hits, misses


def _build_parent_map(kb, root_entry_id, space_id):
    """递归遍历知识库空间，构建 {entry_id: parent_folder_id} 映射。"""
    parent_map = {}
    sid = space_id or kb.space_id

    def _walk(folder_id):
        children = kb.list_folder(folder_id, space_id=sid)
        for c in children:
            parent_map[c["id"]] = folder_id
            if c["entry_type"] == "folder" and c.get("has_children"):
                _walk(c["id"])

    _walk(root_entry_id)
    return parent_map


def download_kp_assets(search_results, output_dir, scope="case"):
    """案例库命中后，下载配套的代码与数据文件。

    对于 file 类型的命中条目，自动定位其所属父文件夹，查找同级配套资料
    （如 git_info、代码/、数据/ 等文件夹）。对于 folder 类型条目，直接列出子项。

    Args:
        search_results: search_cases 的返回值（scope="case"）
        output_dir: 本地输出根目录（如 "artifacts/教材名/第3章_标题/案例资料/"）
        scope: 下载来源空间，默认 "case"（案例库）

    Returns:
        dict: 同原函数
    """
    import os as _os
    from lexiang_api import get_kb

    kb = get_kb()
    spaces = kb._spaces or {}
    space_info = spaces.get(scope, {})
    space_id = space_info.get("space_id", "")
    root_entry_id = space_info.get("root_entry_id", "")

    hits = [r for r in search_results if r.get("kb_hit") and r.get("entry_id")]
    skipped = [r for r in search_results if r.get("kb_hit") and not r.get("entry_id")]

    # 预构建父子关系映射（用于 file 类型条目找到父文件夹）
    parent_map = _build_parent_map(kb, root_entry_id, space_id)

    result = {
        "total_kps": len(hits) + len(skipped),
        "downloaded": 0,
        "no_assets": 0,
        "skipped": len(skipped),
        "total_files": 0,
        "details": [],
    }

    for hit in hits:
        kp_name = hit["知识点"]
        entry_id = hit["entry_id"]
        kp_dir = _os.path.join(output_dir, _safe_dirname(kp_name))

        detail = {
            "知识点": kp_name,
            "entry_id": entry_id,
            "source": hit.get("来源", ""),
            "output_dir": kp_dir,
            "files": [],
        }

        # 智能定位子项列表：file 类型 → 查父文件夹；folder 类型 → 直接列子项
        target_for_listing = entry_id
        entry_detail = kb.get_entry_detail(entry_id) or {}
        entry_type = entry_detail.get("attributes", {}).get("entry_type", "")

        if entry_type == "file":
            parent_id = parent_map.get(entry_id)
            if parent_id:
                target_for_listing = parent_id

        children = kb.list_folder(target_for_listing, space_id=space_id)

        if not children:
            result["no_assets"] += 1
            detail["status"] = "no_assets"
            result["details"].append(detail)
            continue

        # ── Git fallback：子项中有 git_info 时，clone 仓库 ──
        git_config = next((c for c in children if c["name"] == "git_info"), None)
        if git_config:
            git_tmp = _os.path.join(output_dir, "_" + git_config["name"])
            _os.makedirs(output_dir, exist_ok=True)
            ok = kb.download_file(git_config["id"], git_tmp)
            if ok:
                from git_download import clone_from_git_config
                git_result = clone_from_git_config(git_tmp, kp_dir)
                if git_result["ok"]:
                    result["downloaded"] += 1
                    result["total_files"] += git_result["files"]
                    detail["status"] = "has_assets"
                    detail["files"] = [{"name": f"git: {git_result['repo']}",
                                         "path": kp_dir,
                                         "status": "ok",
                                         "count": git_result["files"]}]
                    result["details"].append(detail)
                    try:
                        _os.remove(git_tmp)
                    except Exception:
                        pass
                    continue
                else:
                    detail["git_error"] = git_result["error"]
            try:
                _os.remove(git_tmp)
            except Exception:
                pass
            # git 下载失败，过滤掉 git_info，继续正常 KB 下载
            children = [c for c in children if c["name"] != "git_info"]

        # 只下载 file/smartsheet/page 类型的子项（不递归深层子文件夹）
        file_children = [c for c in children if c["entry_type"] != "folder"]
        folder_children = [c for c in children if c["entry_type"] == "folder"]

        file_count = 0

        # 下载直接子文件
        for fc in file_children:
            out_path = _os.path.join(kp_dir, fc["name"])
            _os.makedirs(kp_dir, exist_ok=True)
            ok = kb.download_file(fc["id"], out_path)
            if ok:
                file_count += 1
                detail["files"].append({"name": fc["name"], "path": ok, "status": "ok"})
            else:
                detail["files"].append({"name": fc["name"], "path": out_path, "status": "fail"})

        # 下载子文件夹（如 "代码"/"数据" 等配套目录）
        for fld in folder_children:
            if fld.get("has_children"):
                sub_dir = _os.path.join(kp_dir, fld["name"])
                dl_result = kb.download_folder(
                    fld["id"], sub_dir, recursive=True, skip_errors=True, space_id=space_id
                )
                file_count += dl_result.get("ok", 0)
                for item in dl_result.get("items", []):
                    detail["files"].append({
                        "name": item.get("name", ""),
                        "path": item.get("path", ""),
                        "status": item.get("status", ""),
                    })

        if file_count > 0:
            result["downloaded"] += 1
            detail["status"] = "has_assets"
        else:
            result["no_assets"] += 1
            detail["status"] = "no_assets"

        result["total_files"] += file_count
        result["details"].append(detail)

    # 无 entry_id 的跳过项也记录
    for s in skipped:
        result["details"].append({
            "知识点": s["知识点"],
            "entry_id": "",
            "source": s.get("来源", ""),
            "output_dir": "",
            "status": "skipped",
            "files": [],
        })

    return result


def _safe_dirname(name):
    """将知识点/案例名转为安全的目录名，替换非法字符。"""
    import re
    # 替换 Windows/Linux 非法文件名字符为下划线
    safe = re.sub(r'[<>:"/\\\\|?*]', '_', name)
    # 去除首尾空白和点
    safe = safe.strip().rstrip('.')
    return safe if safe else "unnamed"


if __name__ == "__main__":
    # Windows 控制台 GBK 编码兜底：重配置 stdout 为 UTF-8 + 替换非法字符，
    # 避免打印摘要中的特殊字符（如 ▶）时抛 UnicodeEncodeError。
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    if len(sys.argv) < 2:
        print(f"用法: python {sys.argv[0]} 知识点1 知识点2 ... [--scope knowledge|case] [--skip 跳过的知识点 ...]")
        print(f"  --scope knowledge  学科知识库检索（默认）")
        print(f"  --scope case       案例库检索")
        print(f"  （数据库检索请使用 pull_datasets.py 的 pull_datasets()，检索命中即下载）")
        sys.exit(1)

    # 解析 --scope 和 --skip 参数
    kps = []
    skip_list = []
    scope = "knowledge"
    skip_mode = False
    scope_mode = False
    for arg in sys.argv[1:]:
        if arg == "--skip":
            skip_mode = True
            scope_mode = False
            continue
        if arg == "--scope":
            scope_mode = True
            skip_mode = False
            continue
        if skip_mode:
            skip_list.append(arg)
        elif scope_mode:
            scope = arg if arg in ("knowledge", "case") else "knowledge"
            scope_mode = False
        else:
            kps.append(arg)

    import time
    t0 = time.time()
    if scope == "case":
        results = search_cases(kps, skip_kb=skip_list or None)
    else:
        results = search_knowledge_points(kps, skip_kb=skip_list or None, scope=scope)
    elapsed = time.time() - t0

    hits, misses = split_hits_misses(results)
    # 使用 ASCII 兼容标记，避免 Windows GBK 编码问题
    print(f"[检索完成] ({elapsed:.1f}s, scope={scope}): {len(hits)} hits / {len(misses)} misses\n")

    for r in hits:
        print(f"[KB HIT] [{r['知识点']}] entry_id={r.get('entry_id','?')}")
        if r.get("引用行"):
            print(r["引用行"])

    for r in misses:
        print(f"[KB MISS] [{r['知识点']}] source={r.get('来源', '?')}")
