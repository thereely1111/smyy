"""按需下载 KB 图片，以正文引用命名规则保存。

由 jc-3-视觉素材 在处理待补齐(来源:KB)条目时调用：
  1. 从 @图片清单 提取 file_id
  2. 按 图X-N_描述[KB].png 格式命名
  3. 存入指定 /图片/ 目录

用法:
    python image_download.py <file_id> <output_path> [--id <entry_id>]
    python image_download.py --batch <catalog_json> <output_dir>

独立函数:
    from image_download import download_kb_image, download_batch
"""

import sys, os, json


def _get_kb():
    """懒加载 KB API。"""
    try:
        import importlib
        script_dir = os.path.dirname(os.path.abspath(__file__))
        kb_api_path = os.path.join(
            script_dir, "..", "..", "jc-2-知识库检索", "scripts", "lexiang_api.py"
        )
        spec = importlib.util.spec_from_file_location("lexiang_api", kb_api_path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module.get_kb()
    except Exception as e:
        print(f"[ERROR] KB API 加载失败: {e}", file=sys.stderr)
        return None


def download_kb_image(file_id, output_path):
    """下载单张 KB 图片。

    Args:
        file_id: 图片 file_id
        output_path: 完整输出路径（含目录、文件名、扩展名）

    Returns:
        str | None — 成功返回 output_path，失败返回 None
    """
    kb = _get_kb()
    if kb is None:
        return None

    os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
    result = kb.download_image(file_id, output_path)
    if result:
        print(f"[下载] {file_id[:8]} -> {os.path.basename(output_path)}", file=sys.stderr)
    else:
        print(f"[失败] {file_id[:8]} 下载失败", file=sys.stderr)
    return result


def download_batch(image_catalog, output_dir, figure_map):
    """批量下载 KB 图片，使用正文图号命名。

    Args:
        image_catalog: read_sources.py 产出的 image_catalog
            {entry_id: [{file_id, context, figure_context, width, height}]}
        output_dir: 图片输出目录（如 第1章.../图片/）
        figure_map: 图号到 file_id 的映射
            [{"图号": "1-10", "file_id": "884e...", "描述": "WorkBuddy下载页面"}, ...]

    Returns:
        dict: {图号: {"path": 本地路径, "file_id": ...}, ...}
    """
    kb = _get_kb()
    if kb is None:
        return {}

    os.makedirs(output_dir, exist_ok=True)
    results = {}

    for item in figure_map:
        fig_num = item["图号"]
        file_id = item["file_id"]
        desc = item.get("描述", "截图")
        filename = f"图{fig_num}_{desc}[KB].png"
        out_path = os.path.join(output_dir, filename)

        success = kb.download_image(file_id, out_path)
        if success:
            results[fig_num] = {"path": out_path, "file_id": file_id, "filename": filename}
            print(f"[下载] 图{fig_num} {desc} <- {file_id[:8]}", file=sys.stderr)
        else:
            results[fig_num] = {"path": None, "file_id": file_id, "filename": filename, "error": "下载失败"}
            print(f"[失败] 图{fig_num} {desc} 下载失败", file=sys.stderr)

    return results


# ── CLI ────────────────────────────────────────────────

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(f"用法:")
        print(f"  单张: python {sys.argv[0]} <file_id> <output_path>")
        print(f"  批量: python {sys.argv[0]} --batch <figure_map.json> <output_dir>")
        sys.exit(1)

    if sys.argv[1] == "--batch":
        if len(sys.argv) < 4:
            print("批量模式: python image_download.py --batch <figure_map.json> <output_dir>")
            sys.exit(1)
        with open(sys.argv[2], "r", encoding="utf-8") as f:
            figure_map = json.load(f)
        results = download_batch({}, sys.argv[3], figure_map)
        print(json.dumps(results, ensure_ascii=False, indent=2))
    else:
        file_id = sys.argv[1]
        output_path = sys.argv[2]
        result = download_kb_image(file_id, output_path)
        if result:
            print(f"OK: {result}")
        else:
            print("FAILED")
            sys.exit(1)
