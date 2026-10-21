#!/usr/bin/env python3
"""
jc-3-视觉素材 — 环境安装
原则：先验证，存在则跳过，不存在才安装。

  python install.py
"""

import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path
from urllib.request import urlretrieve

SCRIPTS_DIR = Path(__file__).resolve().parent
GRAPHVIZ_DIR = SCRIPTS_DIR / "graphviz"
DOT_EXE = GRAPHVIZ_DIR / "bin" / "dot.exe"
PREFIX = "[jc-3-视觉素材]"

GRAPHVIZ_VERSION = "15.0.0"
GRAPHVIZ_URL = (
    f"https://gitlab.com/api/v4/projects/4207231/packages/generic/"
    f"graphviz-releases/{GRAPHVIZ_VERSION}/"
    f"windows_10_cmake_Release_Graphviz-{GRAPHVIZ_VERSION}-win64.zip"
)


# ─── 验证 ──────────────────────────────────────────

def _check_python_pkg():
    try:
        import graphviz  # noqa: F401
        print(f"{PREFIX} OK graphviz (Python)")
        return True
    except ImportError:
        return False


def _check_dot():
    """检查 dot.exe: 本地 portable > 系统 PATH"""
    if DOT_EXE.exists():
        os.environ["PATH"] = str(GRAPHVIZ_DIR / "bin") + os.pathsep + os.environ.get("PATH", "")
        print(f"{PREFIX} OK dot.exe (portable)")
        return True
    try:
        r = subprocess.run("dot -V", shell=True, capture_output=True, text=True)
        if r.returncode == 0:
            print(f"{PREFIX} OK dot.exe (system)")
            return True
    except Exception:
        pass
    return False


def _verify():
    """渲染测试图验证全链路"""
    import graphviz
    dot = graphviz.Digraph(engine="dot")
    dot.node("t", "test")
    png = SCRIPTS_DIR / "_test.png"
    dot.render(filename=str(SCRIPTS_DIR / "_test"), cleanup=True, format="png")
    ok = png.exists()
    if ok:
        png.unlink()
        print(f"{PREFIX} Verify OK")
    else:
        print(f"{PREFIX} WARN verify failed")
    return ok


# ─── 安装 ──────────────────────────────────────────

def _install_python_pkg():
    print(f"{PREFIX} Installing graphviz (pip) ...")
    subprocess.check_call([sys.executable, "-m", "pip", "install", "graphviz", "-q"])
    print(f"{PREFIX} OK graphviz (installed)")


def _install_dot():
    GRAPHVIZ_DIR.mkdir(parents=True, exist_ok=True)
    zip_path = SCRIPTS_DIR / "_graphviz.zip"
    print(f"{PREFIX} Downloading Graphviz {GRAPHVIZ_VERSION} (~35MB) ...")
    urlretrieve(GRAPHVIZ_URL, str(zip_path))
    print(f"{PREFIX} Extracting ...")
    with zipfile.ZipFile(str(zip_path), "r") as z:
        tmp = SCRIPTS_DIR / "_gv_tmp"
        z.extractall(str(tmp))
        top = list(tmp.iterdir())[0]
        for item in top.iterdir():
            target = GRAPHVIZ_DIR / item.name
            if target.exists():
                shutil.rmtree(target, ignore_errors=True) if target.is_dir() else target.unlink()
            shutil.move(str(item), str(target))
        shutil.rmtree(str(tmp), ignore_errors=True)
    zip_path.unlink()
    # 写 PATH
    os.environ["PATH"] = str(GRAPHVIZ_DIR / "bin") + os.pathsep + os.environ.get("PATH", "")
    print(f"{PREFIX} OK dot.exe (installed)")


# ─── 主入口 ────────────────────────────────────────

def main():
    ok = True

    if not _check_python_pkg():
        _install_python_pkg()
        if not _check_python_pkg():
            print(f"{PREFIX} FAIL graphviz import")
            ok = False

    if not _check_dot():
        _install_dot()
        if not _check_dot():
            print(f"{PREFIX} FAIL dot.exe")
            ok = False

    if ok:
        _verify()

    print()
    if ok:
        print(f"{PREFIX} All OK")
    else:
        print(f"{PREFIX} Some items failed")
        sys.exit(1)


if __name__ == "__main__":
    main()
