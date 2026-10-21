# -*- coding: utf-8 -*-
"""安装 mm-2 渲染链路依赖（幂等：已满足的包不会被重装）。

用法：
    python <skill>/scripts/install_deps.py [--check-only]

--check-only 只报告缺哪些包，不做安装；退出码 0 表示依赖齐备，1 表示有缺失。
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

REQUIREMENTS = Path(__file__).resolve().parent.parent / "requirements.txt"

# 包名 -> import 名（不一致的才需要映射）
IMPORT_NAMES = {
    "python-docx": "docx",
    "pypdf": "pypdf",
    "openpyxl": "openpyxl",
    "pymupdf": "pymupdf",
    "latex2mathml": "latex2mathml",
}

# 条件依赖：{包: 替代包} —— 替代包在时装了，这个包就不必装
# pypdf 只是 pymupdf 缺失时的解析兜底；有 pymupdf 时不需要它。
OPTIONAL_IF = {"pypdf": "pymupdf"}


def _requirements() -> list[tuple[str, str]]:
    items: list[tuple[str, str]] = []
    for line in REQUIREMENTS.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        name = line.split(">=")[0].split("<")[0].split("==")[0].strip()
        items.append((name, IMPORT_NAMES.get(name, name.replace("-", "_"))))
    return items


def _can_import(module: str) -> bool:
    """在子进程里真正 import 一次。

    不能用 importlib.util.find_spec：它只检查模块文件在不在磁盘上，
    而"文件在、导入就崩"的情况很常见（实测 Anaconda 的 docx 因 lxml DLL 缺失
    在 import 时就抛异常，find_spec 却报依赖齐备 → 假阳性）。
    """
    try:
        done = subprocess.run(
            [sys.executable, "-c", f"import {module}"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=60,
        )
    except Exception:  # noqa: BLE001 — 解释器起不来/超时都按不可用处理
        return False
    return done.returncode == 0


def _missing() -> tuple[list[str], list[str]]:
    """返回 (必需但缺失, 可选但缺失)。"""
    required: list[str] = []
    optional: list[str] = []
    for package, module in _requirements():
        if _can_import(module):
            continue
        stand_in = OPTIONAL_IF.get(package)
        if stand_in and _can_import(IMPORT_NAMES.get(stand_in, stand_in)):
            optional.append(f"{package}（已由 {stand_in} 替代，可不装）")
            continue
        required.append(package)
    return required, optional


def _fix_stdout_encoding() -> None:
    """把 stdout/stderr 重设为 UTF-8。

    本脚本整篇 print 中文，而平台 shell 的 locale 不可控（Windows 上是 GBK/OEM 码页，
    Linux 上可能是 C/POSIX）。不重设的话中文会变乱码、甚至抛 UnicodeEncodeError 让脚本
    在报告依赖状态之前就崩掉——那会让「缺依赖」这个信息完全传不出来。
    与 render_animation.py / prepare_input.py / probe_inputs.py 里的同名函数保持一致。
    """
    for stream in (sys.stdout, sys.stderr):
        if stream and hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8", errors="replace")
            except Exception:  # noqa: BLE001
                pass


def main() -> int:
    _fix_stdout_encoding()
    parser = argparse.ArgumentParser(description="Install or check mm-2 rendering dependencies")
    parser.add_argument("--check-only", action="store_true", help="只检查，不安装")
    args = parser.parse_args()

    missing, optional = _missing()

    print(f"解释器：{sys.executable}（Python {sys.version.split()[0]}）")
    for note in optional:
        print(f"可选：{note}")

    if not missing:
        print("依赖齐备：无需安装")
        return 0

    print(f"缺失依赖：{', '.join(missing)}")
    if args.check_only:
        return 1

    print(f"执行：{sys.executable} -m pip install -r {REQUIREMENTS}")
    code = subprocess.call([sys.executable, "-m", "pip", "install", "-r", str(REQUIREMENTS)])
    if code != 0:
        print("安装失败：请检查网络或改用 --check-only 后手工安装", file=sys.stderr)
        return code

    still_missing, _ = _missing()
    if still_missing:
        print(f"安装后仍缺失：{', '.join(still_missing)}", file=sys.stderr)
        return 1
    print("依赖安装完成")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
