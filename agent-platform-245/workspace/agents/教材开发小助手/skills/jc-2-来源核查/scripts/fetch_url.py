"""外部 URL 本地化下载：抓取网页并打包为单个离线 .html 文件（含图片/CSS 内联）。

用途：jc-2-来源核查 Step 4c —— 将外部来源网页本地化为单个 .html 存档，
     图片、CSS 等资源内联为 data URI，保证离线可读。

用法：
  python fetch_url.py <url> --out <输出目录> [--name <文件名前缀>] [--timeout 30]

  url             : 外部网页 URL（http/https）
  --out           : 输出目录（必填）
  --name          : 输出文件名（不含扩展名，默认取 URL 域名+路径摘要）
  --no-inline-images : 不内联 <img> 图片（默认会内联为 base64 data URI）
  --no-inline-css    : 不内联 <link rel=stylesheet>（默认会内联为 <style>）
  --timeout       : 请求超时秒数（默认 30）

退出码：
  0 成功；1 抓取失败（网络错误/HTTP 非 200/空内容）
"""

import sys, os, io, re, json, argparse, base64
from urllib.parse import urljoin, urlparse

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

try:
    import requests
except ImportError:
    requests = None


def _sanitize_filename(name, max_len=80):
    """将字符串转为安全文件名（不含路径分隔符和非法字符）。"""
    name = re.sub(r'[\\/:*?"<>|\n\r\t]+', "_", name).strip().strip("._")
    name = re.sub(r'[^A-Za-z0-9._\-\u4e00-\u9fa5]', "_", name)
    return name[:max_len] or "page"


def _default_name(url):
    """根据 URL 生成默认文件名（域名 + 路径摘要）。"""
    p = urlparse(url)
    base = p.netloc.replace(":", "_").replace(".", "_")
    path = p.path.strip("/").split("/")[-1] or "index"
    return _sanitize_filename(f"{base}_{path}")


def _fetch(url, timeout):
    """抓取 URL，返回 (html_bytes, final_url) 或 (None, url)。"""
    if requests is None:
        return None, url
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                      "AppleWebKit/537.36 (KHTML, like Gecko) "
                      "Chrome/120.0 Safari/537.36"
    }
    try:
        resp = requests.get(url, headers=headers, timeout=timeout, allow_redirects=True)
    except requests.RequestException:
        return None, url
    if resp.status_code != 200 or not resp.content:
        return None, resp.url
    return resp.content, resp.url


def _fetch_resource(res_url, timeout, max_bytes=5 * 1024 * 1024):
    """抓取子资源（图片/CSS），返回 bytes 或 None。"""
    if requests is None:
        return None
    if not re.match(r'^https?://', res_url, re.I):
        return None
    try:
        resp = requests.get(res_url, timeout=timeout, stream=True)
    except requests.RequestException:
        return None
    if resp.status_code != 200:
        return None
    data = b""
    for chunk in resp.iter_content(chunk_size=65536):
        data += chunk
        if len(data) > max_bytes:
            return None
    return data


def _guess_mime(url, content_type=None):
    """根据 URL 扩展名 / content-type 推断 MIME 类型。"""
    if content_type:
        ct = content_type.split(";")[0].strip().lower()
        if ct and ct != "application/octet-stream":
            return ct
    ext = os.path.splitext(urlparse(url).path)[1].lower()
    mime_map = {
        ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
        ".gif": "image/gif", ".svg": "image/svg+xml", ".webp": "image/webp",
        ".ico": "image/x-icon", ".css": "text/css",
    }
    return mime_map.get(ext, "image/png")


def _inline_images(html, base_url, timeout):
    """将 <img src> 内联为 data URI。"""
    def _repl(m):
        tag = m.group(0)
        src = m.group(1)
        if src.startswith(("data:", "#", "javascript:", "about:")):
            return tag
        abs_url = urljoin(base_url, src)
        data = _fetch_resource(abs_url, timeout)
        if data is None:
            return tag
        mime = _guess_mime(abs_url)
        b64 = base64.b64encode(data).decode("ascii")
        return tag.replace(src, f"data:{mime};base64,{b64}", 1)

    return re.sub(r'<img\b[^>]*\bsrc=["\']([^"\']+)["\']', _repl, html, flags=re.I)


def _inline_css(html, base_url, timeout):
    """将 <link rel=stylesheet href> 内联为 <style>。"""
    def _repl(m):
        tag = m.group(0)
        href = m.group(1)
        if href.startswith(("data:", "#")):
            return tag
        abs_url = urljoin(base_url, href)
        data = _fetch_resource(abs_url, timeout)
        if data is None:
            return tag
        try:
            css = data.decode("utf-8", errors="replace")
        except Exception:
            return tag
        # 内联 CSS 中的 url() 图片资源
        def _css_url_repl(m2):
            inner = m2.group(1)
            if inner.startswith(("data:", "#")):
                return m2.group(0)
            img_url = urljoin(abs_url, inner)
            img_data = _fetch_resource(img_url, timeout)
            if img_data is None:
                return m2.group(0)
            mime = _guess_mime(img_url)
            b64 = base64.b64encode(img_data).decode("ascii")
            return f'url(data:{mime};base64,{b64})'
        css = re.sub(r'url\(["\']?([^"\')]+)["\']?\)', _css_url_repl, css, flags=re.I)
        return f'<style>{css}</style>'

    return re.sub(
        r'<link\b[^>]*\brel=["\']stylesheet["\'][^>]*\bhref=["\']([^"\']+)["\'][^>]*>',
        _repl, html, flags=re.I)


def localize_url(url, output_dir, name=None, inline_images=True, inline_css=True, timeout=30):
    """抓取 URL 并本地化为单个 .html 文件。

    Returns:
        dict: {ok, file, url, final_url, bytes} — ok=False 表示抓取失败
    """
    html_bytes, final_url = _fetch(url, timeout)
    if html_bytes is None:
        return {"ok": False, "url": url, "final_url": final_url, "reason": "fetch_failed"}

    try:
        html = html_bytes.decode("utf-8", errors="replace")
    except Exception:
        html = html_bytes.decode("latin-1", errors="replace")

    if inline_css:
        html = _inline_css(html, final_url or url, timeout)
    if inline_images:
        html = _inline_images(html, final_url or url, timeout)

    fname = _sanitize_filename(name) if name else _default_name(final_url or url)
    out_path = os.path.join(output_dir, f"{fname}.html")
    os.makedirs(output_dir, exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(html)

    return {
        "ok": True,
        "file": out_path,
        "url": url,
        "final_url": final_url,
        "bytes": os.path.getsize(out_path),
    }


def main():
    parser = argparse.ArgumentParser(description="外部 URL 本地化为单个离线 .html")
    parser.add_argument("url", help="外部网页 URL")
    parser.add_argument("--out", required=True, help="输出目录")
    parser.add_argument("--name", help="输出文件名（不含扩展名）")
    parser.add_argument("--no-inline-images", action="store_true", help="不内联图片")
    parser.add_argument("--no-inline-css", action="store_true", help="不内联 CSS")
    parser.add_argument("--timeout", type=int, default=30, help="请求超时秒数")
    args = parser.parse_args()

    result = localize_url(
        args.url,
        args.out,
        name=args.name,
        inline_images=not args.no_inline_images,
        inline_css=not args.no_inline_css,
        timeout=args.timeout,
    )

    print("[RESULT]")
    print(json.dumps(result, ensure_ascii=False, indent=2))

    if not result.get("ok"):
        print("\n[FAIL] 抓取失败，请由上层走摘要兜底并标记 fetch_error")
        sys.exit(1)


if __name__ == "__main__":
    main()
