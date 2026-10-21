"""从「案例库」和「数据库」知识库拉取数据到本地目录。

支持四种模式：
  1. pull_datasets(keyword)  — 关键词搜索 → 下载全部匹配数据集（仅数据库）
  2. pull_file(space, name)  — 按名称精确查找 → 下载单个文件（案例库/数据库）
  3. pull_folder(space, name) — 按名称精确查找 → 下载指定文件夹（案例库/数据库）
  4. pull_space(space)       — 下载整个知识库空间（案例库/数据库）

用法:
    from pull_datasets import pull_datasets, pull_file, pull_folder, pull_space

    # 关键词搜索下载（仅数据库）
    results = pull_datasets("订单", "artifacts/.../数据/")

    # 下载单个文件（案例库/数据库均可）
    result = pull_file("案例集", "某案例.md", "输出目录/")

    # 下载指定文件夹（案例库/数据库均可）
    result = pull_folder("案例集", "某项目文件夹", "输出目录/")

    # 下载整个知识库空间
    result = pull_space("数据集", "输出目录/")
"""

import sys, os
from lexiang_api import get_kb

# ── 空间名称映射 ──────────────────────────────────────

_NAME_TO_KEY = {
    "案例库": "case", "案例集": "case",
    "数据库": "dataset", "数据集": "dataset",
}


def _resolve_space(space_name):
    """将中文空间名解析为 (key, space_info)。"""
    kb = get_kb()
    spaces = kb._cfg.get("spaces", {})
    key = _NAME_TO_KEY.get(space_name)
    if not key:
        raise ValueError(f"不支持的空间名称 '{space_name}'，仅支持: {list(_NAME_TO_KEY.keys())}")
    space_info = spaces.get(key, {})
    if not space_info.get("space_id"):
        raise ValueError(f"未在 config.json 中找到空间 '{space_name}'（key={key}）")
    return key, space_info, kb


# ── 1. 关键词搜索下载（仅数据库） ─────────────────────

def pull_datasets(keyword, output_dir):
    """从「数据库」知识库空间搜索并下载数据集（递归含子文件夹）。

    Args:
        keyword: 数据集名称关键词（contains 模糊匹配）
        output_dir: 输出目录路径

    Returns:
        list[dict]: 每个元素:
            {文件名, 路径, 状态("ok"/"fail"), 原因(str)}
    """
    kb = get_kb()
    datasets = kb.search_datasets(keyword=keyword)
    results = []

    for ds in datasets:
        name = ds["name"]
        etype = ds["entry_type"]

        ext = ds.get("extension", "")
        filename = f"{name}.{ext}" if ext else name
        out_path = os.path.join(output_dir, filename)
        ok = kb.download_file(ds["id"], out_path)
        if ok:
            results.append({
                "文件名": os.path.basename(ok),
                "路径": ok,
                "状态": "ok",
                "原因": "",
            })
        else:
            results.append({
                "文件名": filename,
                "路径": out_path,
                "状态": "fail",
                "原因": f"下载失败 ({etype})",
            })

    return results


# ── 2. 下载单个文件（按名称精确匹配） ──────────────────

def pull_file(space_name, file_name, output_dir):
    """从指定知识库空间下载单个文件（按名称精确匹配）。

    适用于案例库和数据库。名称必须完全匹配（含扩展名）。

    Args:
        space_name: "案例集" | "数据集"
        file_name: 文件/条目名称（精确匹配）
        output_dir: 输出目录路径

    Returns:
        dict | None:
            {文件名, 路径, 状态("ok"/"fail"/"not_found"), 原因(str)}
    """
    _, space_info, kb = _resolve_space(space_name)
    space_id = space_info["space_id"]

    # 查找条目（排除 folder 类型）
    entry = kb.find_entry(file_name, space_id=space_id, entry_type=None)
    # 如果是文件夹，拒绝
    if entry and entry["entry_type"] == "folder":
        return {
            "文件名": file_name,
            "路径": os.path.join(output_dir, file_name),
            "状态": "fail",
            "原因": f"'{file_name}' 是文件夹而非文件，请使用 pull_folder()",
        }

    if not entry:
        return {
            "文件名": file_name,
            "路径": os.path.join(output_dir, file_name),
            "状态": "not_found",
            "原因": f"在 '{space_name}' 中未找到名为 '{file_name}' 的文件",
        }

    out_path = os.path.join(output_dir, file_name)
    ok = kb.download_file(entry["id"], out_path)
    if ok:
        return {
            "文件名": os.path.basename(ok),
            "路径": ok,
            "状态": "ok",
            "原因": "",
        }
    else:
        return {
            "文件名": file_name,
            "路径": out_path,
            "状态": "fail",
            "原因": f"下载失败 ({entry['entry_type']})",
        }


# ── 3. 下载指定文件夹（按名称精确匹配） ────────────────

def pull_folder(space_name, folder_name, output_dir, recursive=True, skip_errors=True):
    """从指定知识库空间下载指定文件夹（按名称精确匹配），保持子目录结构。

    适用于案例库和数据库。

    Args:
        space_name: "案例集" | "数据集"
        folder_name: 文件夹名称（精确匹配）
        output_dir: 输出目录路径
        recursive: 是否递归下载子文件夹（默认 True）
        skip_errors: 单个条目下载失败是否继续（默认 True）

    Returns:
        dict: download_folder() 的返回结果，额外包含 error 字段
    """
    _, space_info, kb = _resolve_space(space_name)
    space_id = space_info["space_id"]

    # 按名称精确查找文件夹
    entry = kb.find_entry(folder_name, space_id=space_id, entry_type="folder")
    if not entry:
        return {
            "total": 0, "ok": 0, "fail": 0, "skipped": 0, "items": [],
            "error": f"在 '{space_name}' 中未找到名为 '{folder_name}' 的文件夹",
        }

    return kb.download_folder(
        entry["id"], output_dir,
        recursive=recursive, skip_errors=skip_errors, space_id=space_id
    )


# ── 4. 下载整个知识库空间 ─────────────────────────────

def pull_space(space_name, output_dir, recursive=True, skip_errors=True):
    """下载整个知识库空间（从根目录递归下载所有内容）。

    仅支持: "案例集" (case) 和 "数据集" (dataset)。

    Args:
        space_name: "案例集" | "数据集"
        output_dir: 输出目录路径
        recursive: 是否递归下载子文件夹（默认 True）
        skip_errors: 单个条目下载失败是否继续（默认 True）

    Returns:
        dict: download_folder() 的返回结果
    """
    _, space_info, kb = _resolve_space(space_name)
    space_id = space_info["space_id"]
    root_entry_id = space_info.get("root_entry_id", "")
    if not root_entry_id:
        return {
            "total": 0, "ok": 0, "fail": 0, "skipped": 0, "items": [],
            "error": f"未在 config.json 中找到空间 '{space_name}' 的 root_entry_id",
        }

    return kb.download_folder(
        root_entry_id, output_dir,
        recursive=recursive, skip_errors=skip_errors, space_id=space_id
    )


# ── CLI ──────────────────────────────────────────────

if __name__ == "__main__":
    # 用法:
    #   python pull_datasets.py search <关键词> <输出目录>
    #   python pull_datasets.py file <案例集|数据集> <文件名> <输出目录>
    #   python pull_datasets.py folder <案例集|数据集> <文件夹名> <输出目录>
    #   python pull_datasets.py space <案例集|数据集> <输出目录>

    if len(sys.argv) < 2:
        print("用法:")
        print(f"  python {sys.argv[0]} search <关键词> <输出目录>")
        print(f"  python {sys.argv[0]} file <案例集|数据集> <文件名> <输出目录>")
        print(f"  python {sys.argv[0]} folder <案例集|数据集> <文件夹名> <输出目录>")
        print(f"  python {sys.argv[0]} space <案例集|数据集> <输出目录>")
        sys.exit(1)

    cmd = sys.argv[1]

    if cmd == "search":
        if len(sys.argv) < 4:
            print("用法: pull_datasets.py search <关键词> <输出目录>")
            sys.exit(1)
        keyword, output_dir = sys.argv[2], sys.argv[3]
        results = pull_datasets(keyword, output_dir)
        ok = [r for r in results if r["状态"] == "ok"]
        fail = [r for r in results if r["状态"] == "fail"]
        print(f"数据集拉取: {len(ok)}成功, {len(fail)}失败")
        for r in ok:
            print(f"  ok {r['文件名']}")
        for r in fail:
            print(f"  fail {r['文件名']} - {r['原因']}")

    elif cmd == "file":
        if len(sys.argv) < 5:
            print("用法: pull_datasets.py file <案例集|数据集> <文件名> <输出目录>")
            sys.exit(1)
        space_name, file_name, output_dir = sys.argv[2], sys.argv[3], sys.argv[4]
        result = pull_file(space_name, file_name, output_dir)
        icon = "ok" if result["状态"] == "ok" else "fail"
        print(f"[{icon}] {result['文件名']} → {result.get('路径','')}")
        if result["原因"]:
            print(f"  原因: {result['原因']}")

    elif cmd == "folder":
        if len(sys.argv) < 5:
            print("用法: pull_datasets.py folder <案例集|数据集> <文件夹名> <输出目录>")
            sys.exit(1)
        space_name, folder_name, output_dir = sys.argv[2], sys.argv[3], sys.argv[4]
        result = pull_folder(space_name, folder_name, output_dir)
        print(f"文件夹下载 '{folder_name}' ({space_name}): "
              f"{result.get('total',0)}条 → {result['ok']}成功, {result['fail']}失败, {result['skipped']}跳过")
        if result.get("error"):
            print(f"  错误: {result['error']}")
        for item in result.get("items", []):
            icon = "ok" if item["status"] == "ok" else ("skip" if item["status"] == "skipped" else "fail")
            print(f"  [{icon}] {item['name']} → {item.get('path','')}")

    elif cmd == "space":
        if len(sys.argv) < 4:
            print("用法: pull_datasets.py space <案例集|数据集> <输出目录>")
            sys.exit(1)
        space_name, output_dir = sys.argv[2], sys.argv[3]
        result = pull_space(space_name, output_dir)
        print(f"空间下载 '{space_name}': "
              f"{result.get('total',0)}条 → {result['ok']}成功, {result['fail']}失败, {result['skipped']}跳过")
        if result.get("error"):
            print(f"  错误: {result['error']}")
        for item in result.get("items", []):
            icon = "ok" if item["status"] == "ok" else ("skip" if item["status"] == "skipped" else "fail")
            print(f"  [{icon}] {item['name']} → {item.get('path','')}")

    else:
        print(f"未知命令 '{cmd}'，支持: search, file, folder, space")
        sys.exit(1)
