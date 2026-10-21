# -*- coding: utf-8 -*-
"""输入整备：把平台上传的平铺文件整理成解析链路要求的目录结构。

平台聊天里上传的附件都落在 `uploads/` 下、是**平铺**的；而解析链路要求
「题目目录/<题名>.pdf + 题目目录/附件/**」。本脚本负责补齐这层结构，
**只做复制与归类，不改写任何内容**。

用法：
    python prepare_input.py --pdf <题目.pdf> --title <题名> --out-dir <input 目录> [附件文件…]

示例（uploads 下有一个 PDF 和三份附件）：
    python prepare_input.py \
      --pdf "<运行态根>/<会话>/uploads/A题.pdf" --title "A题" \
      --out-dir "<运行态根>/<会话>/input" \
      "<运行态根>/<会话>/uploads/附件1.xlsx" \
      "<运行态根>/<会话>/uploads/附件2.xlsx" \
      "<运行态根>/<会话>/uploads/附件3.xlsx"

产出：
    <out-dir>/<题名>/<题名>.pdf      题目正文（原名保留在 manifest 里）
    <out-dir>/<题名>/附件/<原文件名>  附件（扁平文件全部归到这里）
    <out-dir>/<题名>/input_manifest.json  原始路径 + 大小 + sha256

stdout：整备后的题目目录绝对路径（下一句 probe_inputs.py 就用它）
退出码：0 成功；1 参数不合法（缺 PDF、题名非法等）
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
from datetime import datetime
from pathlib import Path

SAFE_TITLE = re.compile(r"^[A-Za-z0-9一-鿿][\w一-鿿._-]{0,63}$")
ATTACHMENT_EXTS = {".xlsx", ".xls", ".csv", ".docx", ".doc", ".txt", ".md", ".json"}


def _fix_stdout_encoding() -> None:
    for stream in (sys.stdout, sys.stderr):
        if stream and hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8", errors="replace")
            except Exception:  # noqa: BLE001
                pass


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    _fix_stdout_encoding()
    parser = argparse.ArgumentParser(description="Normalize uploaded files into <题目目录>/<题名>.pdf + 附件/")
    parser.add_argument("--pdf", required=True, help="题目 PDF 路径（绝对路径）")
    parser.add_argument("--title", required=True, help="题名，作为目录名（如 A题）")
    parser.add_argument("--out-dir", required=True, help="整备输出的父目录（如 <会话>/input）")
    parser.add_argument("attachments", nargs="*", help="附件文件路径（可多个）")
    args = parser.parse_args()

    title = args.title.strip()
    if not SAFE_TITLE.match(title) or title in {".", ".."} or "/" in title or "\\" in title:
        print(f"题名不合法：{title!r}（只允许中英文/数字/下划线/中划线/点，且不能含路径分隔符）", file=sys.stderr)
        return 1

    pdf = Path(args.pdf)
    if not pdf.is_file():
        print(f"题目 PDF 不存在：{pdf}", file=sys.stderr)
        return 1
    if pdf.suffix.lower() != ".pdf":
        print(f"题目文件必须是 PDF，收到：{pdf.name}", file=sys.stderr)
        return 1

    problem_dir = Path(args.out_dir).resolve() / title
    attach_dir = problem_dir / "附件"
    attach_dir.mkdir(parents=True, exist_ok=True)

    manifest: dict = {
        "prepared_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "title": title,
        "problem_dir": str(problem_dir),
        "pdf": {},
        "attachments": [],
    }

    target_pdf = problem_dir / f"{title}{pdf.suffix.lower()}"
    shutil.copy2(pdf, target_pdf)
    manifest["pdf"] = {
        "original": str(pdf.resolve()),
        "stored_as": target_pdf.name,
        "size": target_pdf.stat().st_size,
        "sha256": _sha256(target_pdf),
    }
    print(f"题目：{target_pdf}")

    skipped: list[str] = []
    for raw in args.attachments:
        source = Path(raw)
        if not source.is_file():
            skipped.append(f"{raw}（不存在）")
            continue
        if source.suffix.lower() not in ATTACHMENT_EXTS:
            skipped.append(f"{source.name}（扩展名不参与解析：{source.suffix}）")
            continue
        target = attach_dir / source.name
        if target.exists():
            skipped.append(f"{source.name}（同名已存在，保留先到的）")
            continue
        shutil.copy2(source, target)
        manifest["attachments"].append({
            "original": str(source.resolve()),
            "stored_as": f"附件/{target.name}",
            "size": target.stat().st_size,
            "sha256": _sha256(target),
        })
        print(f"附件：附件/{target.name}")

    for item in skipped:
        print(f"! 跳过 {item}")

    (problem_dir / "input_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"清单：{problem_dir / 'input_manifest.json'}")
    print(f"\n题目目录（下一步传给 probe_inputs.py --problem）：{problem_dir}")
    if not manifest["attachments"]:
        print("! 没有任何附件：题目若含数据附表，图表的真实数组将无据可依，请在结果里如实说明")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
