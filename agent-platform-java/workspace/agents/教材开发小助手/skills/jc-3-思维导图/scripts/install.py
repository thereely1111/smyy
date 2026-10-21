#!/usr/bin/env python3
"""
jc-3-导图生成 — 环境安装
原则：先验证，存在则跳过，不存在才安装。

  python install.py
"""

import json
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path
from urllib.request import urlopen, Request

SKILL_DIR = Path(__file__).resolve().parent
BROWSERS_DIR = SKILL_DIR / "browsers"
TMP_DIR = SKILL_DIR / "_browsers_tmp"
PREFIX = "[jc-3-导图生成]"


# ────────────────────────────────────────────
# 验证工具
# ────────────────────────────────────────────

def _find_browser_exe():
    """在 browsers/ 中查找可执行文件"""
    if not BROWSERS_DIR.exists():
        return None
    for root, dirs, files in os.walk(str(BROWSERS_DIR)):
        for f in files:
            if f in ("chrome.exe", "chrome", "chromium.exe", "chromium"):
                return os.path.join(root, f)
    return None


# ────────────────────────────────────────────
# Playwright 安装
# ────────────────────────────────────────────

def _ensure_playwright():
    try:
        import playwright  # noqa: F401
        print(f"{PREFIX} OK Playwright")
        return True
    except ImportError:
        print(f"{PREFIX} MISS Playwright, installing ...")
        subprocess.check_call([sys.executable, "-m", "pip", "install", "playwright"])
        import playwright  # noqa: F401
        print(f"{PREFIX} OK Playwright (installed)")
        return True


# ────────────────────────────────────────────
# Chromium 安装
# ────────────────────────────────────────────

def _get_browser_config():
    """从 playwright 包中读取 chromium 版本配置"""
    import playwright
    pdir = os.path.dirname(playwright.__file__)
    for root, dirs, files in os.walk(pdir):
        if "browsers.json" in files:
            with open(os.path.join(root, "browsers.json")) as f:
                for b in json.load(f).get("browsers", []):
                    if b.get("name") == "chromium":
                        return b["browserVersion"], b["revision"]
    raise RuntimeError("Cannot find chromium config in playwright")


def _download(url, dest):
    print(f"  Downloading: {url}")
    req = Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urlopen(req, timeout=300) as resp:
        total = int(resp.headers.get("Content-Length", 0))
        downloaded = 0
        with open(dest, "wb") as f:
            while True:
                chunk = resp.read(65536)
                if not chunk:
                    break
                f.write(chunk)
                downloaded += len(chunk)
                if total > 0:
                    pct = downloaded * 100 // total
                    mb = downloaded / (1024 * 1024)
                    print(f"  {pct}% ({mb:.1f} MB)", end="\r")
        print()


def _ensure_chromium():
    existing = _find_browser_exe()
    if existing:
        print(f"{PREFIX} OK Chromium (browsers/)")
        return True

    version, revision = _get_browser_config()
    platform = "win64" if sys.platform == "win32" else \
               "mac" if sys.platform == "darwin" else "linux64"
    pkg = f"chrome-{platform}.zip"

    sources = [
        f"https://storage.googleapis.com/chrome-for-testing-public/{version}/{platform}/{pkg}",
        f"https://npmmirror.com/mirrors/playwright/builds/cft/{version}/{platform}/{pkg}",
        f"https://mirrors.tuna.tsinghua.edu.cn/playwright/builds/cft/{version}/{platform}/{pkg}",
    ]

    TMP_DIR.mkdir(parents=True, exist_ok=True)
    zip_path = TMP_DIR / pkg
    downloaded = False

    for url in sources:
        try:
            _download(url, str(zip_path))
            downloaded = True
            break
        except Exception as e:
            print(f"  Failed: {e}")
            if zip_path.exists():
                zip_path.unlink()

    if not downloaded:
        # 兜底: playwright install chromium
        print(f"{PREFIX} Fallback: playwright install chromium")
        env = os.environ.copy()
        env["PLAYWRIGHT_BROWSERS_PATH"] = str(BROWSERS_DIR)
        subprocess.run(
            [sys.executable, "-m", "playwright", "install", "chromium"],
            env=env, check=True,
        )

    if zip_path.exists():
        print(f"{PREFIX} Extracting ...")
        BROWSERS_DIR.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(str(zip_path), "r") as zf:
            names = zf.namelist()
            top = names[0].split("/")[0]
            zf.extractall(str(BROWSERS_DIR))
        extracted = BROWSERS_DIR / top
        if extracted.exists() and extracted.is_dir():
            for item in extracted.iterdir():
                shutil.move(str(item), str(BROWSERS_DIR / item.name))
            extracted.rmdir()
        zip_path.unlink()

    if TMP_DIR.exists():
        shutil.rmtree(str(TMP_DIR), ignore_errors=True)

    if _find_browser_exe():
        print(f"{PREFIX} OK Chromium (installed)")
        return True
    else:
        print(f"{PREFIX} FAIL chromium install")
        return False


# ────────────────────────────────────────────
# 主入口
# ────────────────────────────────────────────

def main():
    print(f"{PREFIX} XMind (stdlib)")
    ok = True
    ok &= _ensure_playwright()
    ok &= _ensure_chromium()

    print()
    if ok:
        print(f"{PREFIX} All OK")
    else:
        print(f"{PREFIX} Some items failed, check output above")
        sys.exit(1)


if __name__ == "__main__":
    main()
