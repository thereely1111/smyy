#!/usr/bin/env python3
"""扫描 Markdown 中 Python 代码块的 import，安装缺失的第三方包。

用法：python install.py <md文件路径>
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path

# Python 标准库模块名集合（不安装）
STDLIB = {
    "abc", "aifc", "argparse", "array", "ast", "asyncio", "atexit",
    "audioop", "base64", "bdb", "binascii", "binhex", "bisect", "builtins",
    "bz2", "calendar", "cgi", "cgitb", "chunk", "cmath", "cmd", "code",
    "codecs", "codeop", "collections", "colorsys", "compileall", "concurrent",
    "configparser", "contextlib", "contextvars", "copy", "copyreg", "cProfile",
    "crypt", "csv", "ctypes", "curses", "dataclasses", "datetime", "dbm",
    "decimal", "difflib", "dis", "distutils", "doctest", "email", "encodings",
    "enum", "errno", "faulthandler", "fcntl", "filecmp", "fileinput", "fnmatch",
    "formatter", "fractions", "ftplib", "functools", "gc", "getopt", "getpass",
    "gettext", "glob", "grp", "gzip", "hashlib", "heapq", "hmac", "html",
    "http", "idlelib", "imaplib", "imghdr", "imp", "importlib", "inspect",
    "io", "ipaddress", "itertools", "json", "keyword", "lib2to3", "linecache",
    "locale", "logging", "lzma", "mailbox", "mailcap", "marshal", "math",
    "mimetypes", "mmap", "modulefinder", "multiprocessing", "netrc", "nis",
    "nntplib", "numbers", "operator", "optparse", "os", "ossaudiodev", "parser",
    "pathlib", "pdb", "pickle", "pickletools", "pipes", "pkgutil", "platform",
    "plistlib", "poplib", "posix", "posixpath", "pprint", "profile", "pstats",
    "pty", "pwd", "py_compile", "pyclbr", "pydoc", "queue", "quopri",
    "random", "re", "readline", "reprlib", "resource", "rlcompleter", "runpy",
    "sched", "secrets", "select", "selectors", "shelve", "shlex", "shutil",
    "signal", "site", "smtpd", "smtplib", "sndhdr", "socket", "socketserver",
    "sqlite3", "ssl", "stat", "statistics", "string", "stringprep", "struct",
    "subprocess", "sunau", "symtable", "sys", "sysconfig", "syslog", "tabnanny",
    "tarfile", "telnetlib", "tempfile", "termios", "test", "textwrap",
    "threading", "time", "timeit", "tkinter", "token", "tokenize", "trace",
    "traceback", "tracemalloc", "tty", "turtle", "turtledemo", "types",
    "typing", "unicodedata", "unittest", "urllib", "uu", "uuid", "venv",
    "warnings", "wave", "weakref", "webbrowser", "winreg", "winsound",
    "wsgiref", "xdrlib", "xml", "xmlrpc", "zipapp", "zipfile", "zipimport",
    "zlib", "_thread",
}

# 常用 import 名 → pip 包名映射
IMPORT_TO_PIP: dict[str, str] = {
    "sklearn":       "scikit-learn",
    "cv2":           "opencv-python",
    "PIL":           "pillow",
    "bs4":           "beautifulsoup4",
    "yaml":          "pyyaml",
    "dotenv":        "python-dotenv",
    "scipy":         "scipy",
    "tensorflow":    "tensorflow",
    "torch":         "torch",
    "torchvision":   "torchvision",
    "xgboost":       "xgboost",
    "lightgbm":      "lightgbm",
    "catboost":      "catboost",
    "nltk":          "nltk",
    "gensim":        "gensim",
    "spacy":         "spacy",
    "transformers":  "transformers",
    "datasets":      "datasets",
    "tokenizers":    "tokenizers",
    "sentencepiece": "sentencepiece",
    "jieba":         "jieba",
    "wordcloud":     "wordcloud",
    "networkx":      "networkx",
    "plotly":        "plotly",
    "dash":          "dash",
    "streamlit":     "streamlit",
    "gradio":        "gradio",
    "flask":         "flask",
    "fastapi":       "fastapi",
    "uvicorn":       "uvicorn",
    "django":        "django",
    "requests":      "requests",
    "httpx":         "httpx",
    "aiohttp":       "aiohttp",
    "beautifulsoup4":"beautifulsoup4",
    "lxml":          "lxml",
    "openpyxl":      "openpyxl",
    "xlrd":          "xlrd",
    "xlwt":          "xlwt",
    "pymysql":       "pymysql",
    "psycopg2":      "psycopg2-binary",
    "sqlalchemy":    "sqlalchemy",
    "pymongo":       "pymongo",
    "redis":         "redis",
    "kafka":         "kafka-python",
    "elasticsearch": "elasticsearch",
    "tqdm":          "tqdm",
    "rich":          "rich",
    "click":         "click",
    "fire":          "fire",
    "dill":          "dill",
    "joblib":        "joblib",
    "h5py":          "h5py",
    "nibabel":       "nibabel",
    "pydicom":       "pydicom",
    "soundfile":     "soundfile",
    "librosa":       "librosa",
    "pydub":         "pydub",
    "opencv":        "opencv-python",
    "graphviz":      "graphviz",
    "pydot":         "pydot",
    "pyarrow":       "pyarrow",
    "dask":          "dask",
    "polars":        "polars",
    "vaex":          "vaex",
    "modin":         "modin",
    "ray":           "ray",
    "onnx":          "onnx",
    "onnxruntime":   "onnxruntime",
    "mlflow":        "mlflow",
    "wandb":         "wandb",
    "optuna":        "optuna",
    "hyperopt":      "hyperopt",
    "bayesian":      "bayesian-optimization",
    "shap":          "shap",
    "lime":          "lime",
    "eli5":          "eli5",
    "imblearn":      "imbalanced-learn",
    "category_encoders": "category-encoders",
    "feature_engine": "feature-engine",
    "statsmodels":   "statsmodels",
    "prophet":       "prophet",
    "pmdarima":      "pmdarima",
    "holidays":      "holidays",
    "pyod":          "pyod",
    "anomaly":       "anomaly-detection",
    "surprise":      "scikit-surprise",
    "implicit":      "implicit",
    "faiss":         "faiss-cpu",
    "annoy":         "annoy",
    "umap":          "umap-learn",
    "pacmap":        "pacmap",
    "matplotlib":    "matplotlib",
    "seaborn":       "seaborn",
    "bokeh":         "bokeh",
    "altair":        "altair",
    "pyecharts":     "pyecharts",
    "numpy":         "numpy",
    "pandas":        "pandas",
}


def extract_imports(md_path: str) -> set[str]:
    """从 Markdown 中提取所有 Python 代码块的顶级 import 模块名"""
    text = Path(md_path).read_text(encoding="utf-8")
    imports: set[str] = set()

    for code in re.findall(r"```python\s*\n(.*?)```", text, re.DOTALL):
        for line in code.splitlines():
            line = line.strip()
            m = re.match(r"^import\s+(\w+)", line)
            if m:
                imports.add(m.group(1))
                continue
            m = re.match(r"^from\s+(\w+)", line)
            if m:
                imports.add(m.group(1))

    return imports


def is_installed(import_name: str) -> bool:
    try:
        __import__(import_name)
        return True
    except ImportError:
        return False


def install(package: str) -> None:
    subprocess.check_call(
        [sys.executable, "-m", "pip", "install", package, "-q"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="扫描 Markdown 中 Python 代码块的 import 并安装缺失的第三方包"
    )
    parser.add_argument("md_path", help="Markdown 文件路径")
    args = parser.parse_args()

    md_file = Path(args.md_path)
    if not md_file.exists():
        print(f"[install] 文件不存在: {args.md_path}")
        sys.exit(1)

    imports = extract_imports(args.md_path)
    if not imports:
        print("[install] 未找到 Python 代码块，无需安装依赖")
        return

    third_party = {name for name in imports if name not in STDLIB}

    if not third_party:
        print("[install] 所有 import 均为标准库，无需安装")
        return

    print(f"[install] 检测到 {len(third_party)} 个第三方模块: {', '.join(sorted(third_party))}")

    for name in sorted(third_party):
        if is_installed(name):
            print(f"  ✓ {name} 已安装")
            continue

        pip_name = IMPORT_TO_PIP.get(name, name)
        label = f"{name} → {pip_name}" if pip_name != name else name
        print(f"  ⬇ 正在安装 {label}...")
        try:
            install(pip_name)
            print(f"  ✓ {label} 安装完成")
        except subprocess.CalledProcessError:
            print(f"  ✗ {label} 安装失败，请手动执行: pip install {pip_name}")

    print("[install] 依赖检查完成")


if __name__ == "__main__":
    main()
