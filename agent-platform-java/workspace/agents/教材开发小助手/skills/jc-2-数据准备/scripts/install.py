#!/usr/bin/env python3
"""Auto-install dependencies for jc-2-数据准备 Skill"""

import subprocess
import sys

def install(package):
    subprocess.check_call([sys.executable, "-m", "pip", "install", package, "-q"])

deps = ["pandas", "openpyxl"]
for dep in deps:
    try:
        __import__(dep)
        print(f"[jc-2-数据准备] {dep} 已安装")
    except ImportError:
        print(f"[jc-2-数据准备] 正在安装 {dep}...")
        install(dep)

print("[jc-2-数据准备] 依赖安装完成")
