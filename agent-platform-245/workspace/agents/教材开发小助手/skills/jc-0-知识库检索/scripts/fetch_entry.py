"""
通用 KB 条目下载脚本：下载原文件 + 配套文件 + 提取图片

用法：
  python fetch_entry.py --fulltext <entry_id> <entry_id> ... --out <输出目录>
  python fetch_entry.py --fulltext <entry_id> ... --out <输出目录> --no-extract-images
  python fetch_entry.py --case <entry_id> --out <输出目录>
  python fetch_entry.py --case <entry_id> --out <输出目录> --download-assets

  --fulltext    : 下载 KB 条目原文件（保留原始格式，page→.md / file→原格式 / smartsheet→.csv）
  --case        : 获取案例条目信息 + 下载原文件（案例库），支持 --download-assets 下载配套
  --out         : 输出目录（必填）
  --download-assets : 下载案例配套文件到 数据/ 子目录
  --no-extract-images : 不提取原文中的图片（默认会提取到 图片/KB图片/ 并生成索引.json）
"""

import sys, os, io, json, argparse, re, zipfile

sys.path.insert(0, os.path.dirname(__file__))
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

from lexiang_api import LexiangKB
from search_kb import download_kp_assets


# ── 图片提取 ────────────────────────────────────────────

def _sanitize_name(name, max_len=60):
    """清洗文件名，去除路径分隔符和非法字符。"""
    name = re.sub(r'[\\/:*?"<>|\n\r\t]', '_', name)
    return name[:max_len].strip()


def _extract_zip_media(zip_path, media_dirs):
    """从 zip 内指定的 media 目录提取图片。

    Args:
        zip_path: docx 文件路径（本质是 zip）
        media_dirs: 需要提取的目录名列表，如 ["word/media"]

    Returns:
        list[bytes]: 图片二进制列表（保持 zip 内顺序）
    """
    images = []
    try:
        with zipfile.ZipFile(zip_path) as zf:
            names = zf.namelist()
            for media_dir in media_dirs:
                # 收集该 media 目录下的图片，按名称排序保证稳定顺序
                media_files = sorted(
                    n for n in names
                    if n.startswith(media_dir + "/")
                    and n.lower().endswith((".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".svg", ".emf", ".wmf"))
                )
                for n in media_files:
                    try:
                        images.append((n, zf.read(n)))
                    except Exception:
                        continue
    except Exception:
        pass
    return images


def _extract_pdf_images(pdf_path):
    """从 PDF 提取内嵌图片。

    Returns:
        list[bytes]: 图片二进制列表
    """
    images = []
    try:
        import pdfplumber
        with pdfplumber.open(pdf_path) as pdf:
            for page in pdf.pages:
                for img in getattr(page, "images", []):
                    try:
                        data = img.get("stream", {}).get("rawdata")
                        if data:
                            images.append(data)
                    except Exception:
                        continue
    except ImportError:
        # pdfplumber 不可用时的兜底：尝试 PyPDF2 的 image 对象
        try:
            from PyPDF2 import PdfReader
            reader = PdfReader(pdf_path)
            for page in reader.pages:
                res = page.get("/Resources", {})
                xobjects = res.get("/XObject", {}) if isinstance(res, dict) else {}
                for obj in xobjects.values():
                    try:
                        obj = obj.get_object()
                        if obj.get("/Subtype") == "/Image":
                            data = obj.get_data()
                            if data:
                                images.append(data)
                    except Exception:
                        continue
        except Exception:
            pass
    except Exception:
        pass
    return images


def _guess_ext(data):
    """根据图片二进制头猜测扩展名。"""
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "png"
    if data[:3] == b"\xff\xd8\xff":
        return "jpg"
    if data[:6] in (b"GIF87a", b"GIF89a"):
        return "gif"
    if data[:2] == b"BM":
        return "bmp"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "webp"
    if data[:4] == b"<?xm" or data[:5] == b"<svg ":
        return "svg"
    return "png"


def extract_images_from_source(kb, saved_file, entry_id, name, images_dir):
    """从已下载的 KB 原文中提取图片，保存到 images_dir，返回图片索引条目。

    支持的原文类型：
      - page (.md)：markdown 中的 /kb_files/{file_id} 图片引用 → download_image
      - file (.docx)：解 zip 提取 word/media（pptx/ppt 不提取图片）
      - file (.pdf)：pdfplumber / PyPDF2 提取内嵌图

    Returns:
        list[dict]: [{"file": "相对路径", "context": "原文上下文"}, ...]
    """
    if not saved_file or not os.path.exists(saved_file):
        return []

    ext = os.path.splitext(saved_file)[1].lower()
    short_id = entry_id[:8]
    images = []  # [(原始名或file_id, bytes)]
    context_map = {}  # file_id → 上下文文字（仅 page 类型）

    # 1) page → markdown
    if ext == ".md":
        try:
            with open(saved_file, "r", encoding="utf-8") as f:
                md_text = f.read()
        except Exception:
            return []
        file_ids = kb.extract_markdown_images(md_text) if hasattr(kb, "extract_markdown_images") else []
        for fid in file_ids:
            # 提取该图片附近的文字作为上下文
            m = re.search(r'!\[(.*?)\]\(/kb_files/' + fid + r'\)', md_text)
            context = m.group(1).strip() if m else ""
            context_map[fid] = context
            # 直接下载图片
            tmp = os.path.join(images_dir, f"_tmp_{fid}")
            saved_img = kb.download_image(fid, tmp)
            if saved_img and os.path.exists(saved_img):
                try:
                    with open(saved_img, "rb") as f:
                        images.append((fid, f.read()))
                except Exception:
                    pass
                os.remove(saved_img)
        # 保存 file_id 集合供命名
        id_to_ctx = context_map
    else:
        id_to_ctx = {}

    # 2) file → docx（zip 解包）；pptx/ppt 不提取图片
    if ext in (".docx",):
        for orig_name, data in _extract_zip_media(saved_file, ["word/media"]):
            images.append((orig_name, data))
    elif ext == ".pdf":
        for idx, data in enumerate(_extract_pdf_images(saved_file)):
            images.append((f"pdf_{idx}", data))

    if not images:
        return []

    # 3) 落地保存 + 生成索引
    os.makedirs(images_dir, exist_ok=True)
    result = []
    for i, (orig_key, data) in enumerate(images):
        img_ext = _guess_ext(data)
        fname = f"kb_{short_id}_{i + 1:02d}.{img_ext}"
        out_path = os.path.join(images_dir, fname)
        try:
            with open(out_path, "wb") as f:
                f.write(data)
        except Exception:
            continue
        # context：page 类型用 markdown 图片 alt 文本；file 类型用原文件名
        context = id_to_ctx.get(orig_key, "") if isinstance(orig_key, str) and orig_key in id_to_ctx else ""
        if not context:
            context = os.path.basename(orig_key) if isinstance(orig_key, str) else ""
        result.append({
            "file": os.path.join("KB图片", fname).replace("\\", "/"),
            "context": context,
        })
    return result


def fetch_fulltext(kb, entry_ids, output_dir, extract_images=True):
    """获取 KB 条目原文件并保存到本地（保留原始格式，不提取文本）。
    
    page → 下载为 .md（markdown 原文）
    file → 原样下载（.docx/.pdf/.xlsx 等保留原始格式）
    smartsheet → 导出为 .csv

    若 extract_images=True，顺带提取原文中的图片到 图片/KB图片/，
    并生成 图片/KB图片/索引.json。
    """
    os.makedirs(output_dir, exist_ok=True)
    results = []
    image_index = []  # 全部条目的图片索引汇总
    images_dir = os.path.join(output_dir, "图片", "KB图片")

    for entry_id in entry_ids:
        print(f"[INFO] Downloading {entry_id}...")
        # 获取条目名用于本地文件名
        detail = kb.get_entry_detail(entry_id)
        name = detail.get("attributes", {}).get("name", entry_id) if detail else entry_id
        safe_name = name.replace('/', '_').replace('\\', '_').replace(':', '_')
        out_path = os.path.join(output_dir, f"kb_{entry_id}_{safe_name}")
        saved = kb.download_file(entry_id, out_path)
        if saved:
            actual_file = os.path.basename(saved)
            file_size = os.path.getsize(saved) if os.path.exists(saved) else 0
            print(f"[OK] Saved to {saved} ({file_size} bytes)")
            entry_result = {"entry_id": entry_id, "file": saved, "size": file_size, "status": "ok"}

            # 提取图片
            if extract_images:
                imgs = extract_images_from_source(kb, saved, entry_id, name, images_dir)
                if imgs:
                    entry_result["images"] = imgs
                    image_index.append({
                        "entry_id": entry_id,
                        "title": name,
                        "source_type": os.path.splitext(saved)[1].lstrip("."),
                        "images": imgs,
                    })
                    print(f"  [IMG] extracted {len(imgs)} image(s)")
            results.append(entry_result)
        else:
            print(f"[FAIL] Download failed: {entry_id}")
            results.append({"entry_id": entry_id, "file": None, "size": 0, "status": "fail"})

    # 生成图片索引 json
    if extract_images and image_index:
        os.makedirs(images_dir, exist_ok=True)
        index_path = os.path.join(images_dir, "索引.json")
        with open(index_path, "w", encoding="utf-8") as f:
            json.dump(image_index, f, ensure_ascii=False, indent=2)
        print(f"[IMG] index written to {index_path} ({len(image_index)} entries)")

    return results


def fetch_case(kb, entry_id, output_dir, download_assets=False):
    """获取案例条目信息、全文，可选下载配套"""
    os.makedirs(output_dir, exist_ok=True)
    result = {"entry_id": entry_id, "status": "ok", "files": []}

    # 1. 下载原文件（自动路由：page→.md / file→原格式 / smartsheet→.csv）
    print(f"[Method 1] download_file({entry_id})...")
    name = (kb.get_entry_detail(entry_id) or {}).get("attributes", {}).get("name", "unknown")
    safe_name = name.replace('/', '_').replace('\\', '_').replace(':', '_') if name else "unknown"
    out_path = os.path.join(output_dir, f"kb_{entry_id}_{safe_name}")
    saved = kb.download_file(entry_id, out_path)
    if saved:
        file_size = os.path.getsize(saved) if os.path.exists(saved) else 0
        print(f"  OK: {file_size} bytes → {saved}")
        result["fulltext"] = {"file": saved, "size": file_size}
        result["files"].append(saved)
    else:
        print("  FAIL")
        result["fulltext"] = {"file": None, "size": 0}

    # 2. 获取元数据
    print(f"\n[Method 2] get_entry_info({entry_id})...")
    try:
        resp = kb._request("GET", f"/cgi-bin/v1/kb/entries/{entry_id}",
                          extra_headers=kb._staff_headers(), timeout=15)
        if resp and resp.status_code == 200:
            data = resp.json()
            entry_data = data.get("data", {})
            result["meta"] = {
                "entry_type": entry_data.get("entry_type", "N/A"),
                "name": entry_data.get("name", "N/A"),
                "has_children": entry_data.get("has_children", "N/A"),
            }
            print(f"  entry_type: {result['meta']['entry_type']}")
            print(f"  name: {result['meta']['name']}")

            # 3. 如果是 folder，列出子项
            if entry_data.get('entry_type') == 'folder':
                print("\n[Method 3] Listing children...")
                children_resp = kb._request("GET",
                    f"/cgi-bin/v1/kb/entries/{entry_id}/children?limit=50",
                    extra_headers=kb._staff_headers(), timeout=15)
                if children_resp and children_resp.status_code == 200:
                    children_data = children_resp.json()
                    children = children_data.get("data", {}).get("entries", [])
                    result["children"] = []
                    for child in children:
                        child_info = {
                            "id": child.get("id"),
                            "name": child.get("name"),
                            "entry_type": child.get("entry_type"),
                        }
                        result["children"].append(child_info)
                        print(f"  - [{child.get('entry_type')}] {child.get('name')} (id={child.get('id')})")
        else:
            print(f"  FAIL: status={resp.status_code if resp else 'None'}")
    except Exception as e:
        print(f"  ERROR: {e}")

    # 4. 下载配套文件（案例代码工程，存放到 案例资料/{案例名}/）
    if download_assets:
        print(f"\n[Method 4] download_kp_assets({entry_id})...")
        assets_dir = os.path.join(output_dir, "..", "案例资料", safe_name)
        try:
            # download_kp_assets 需要 search_results 格式的列表
            search_results = [{"知识点": safe_name, "entry_id": entry_id, "kb_hit": True, "来源": "KB"}]
            asset_result = download_kp_assets(search_results, assets_dir, scope="case")
            if asset_result and asset_result.get("total_files", 0) > 0:
                result["assets"] = asset_result
                print(f"  OK: {asset_result.get('total_files', 0)} files downloaded")
            else:
                print("  No assets found")
        except Exception as e:
            print(f"  ERROR: {e}")

    return result


def main():
    parser = argparse.ArgumentParser(description="KB 条目精读脚本")
    parser.add_argument("--fulltext", nargs="+", help="获取 KB 全文的 entry_id 列表")
    parser.add_argument("--case", help="案例 entry_id")
    parser.add_argument("--out", required=True, help="输出目录")
    parser.add_argument("--download-assets", action="store_true", help="下载案例配套文件")
    parser.add_argument("--no-extract-images", action="store_true", help="不提取原文中的图片")
    args = parser.parse_args()

    kb = LexiangKB()

    if args.fulltext:
        results = fetch_fulltext(kb, args.fulltext, args.out,
                                 extract_images=not args.no_extract_images)
        # 输出 JSON 摘要
        print("\n[RESULT]")
        print(json.dumps(results, ensure_ascii=False, indent=2))

    if args.case:
        result = fetch_case(kb, args.case, args.out, args.download_assets)
        print("\n[RESULT]")
        # 精简输出（去 children 避免太长）
        result_out = {k: v for k, v in result.items() if k != "children"}
        print(json.dumps(result_out, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
