#!/usr/bin/env python3
"""Auto-install dependencies for jc-4-md转word Skill"""

import subprocess
import sys


def install(package):
    subprocess.check_call([sys.executable, "-m", "pip", "install", package, "-q"])


# ── 核心依赖：python-docx ──
try:
    import docx
    print("[jc-4-md转word] python-docx 已安装")
except ImportError:
    print("[jc-4-md转word] 正在安装 python-docx...")
    install("python-docx")
    print("[jc-4-md转word] python-docx 安装完成")

# ── 公式转换（可选）：latex2mathml + lxml ──
try:
    from latex2mathml.converter import convert
    from lxml import etree
    print("[jc-4-md转word] latex2mathml + lxml 已安装（公式 OMML 渲染可用）")
except ImportError:
    print("[jc-4-md转word] 正在安装 latex2mathml...")
    install("latex2mathml")
    # lxml 通常是 latex2mathml 的依赖，但单独确认
    try:
        from lxml import etree
    except ImportError:
        install("lxml")
    print("[jc-4-md转word] 公式渲染依赖安装完成（未安装则公式降级为纯文本）")
