#!/usr/bin/env python3
"""
代码执行器 — 执行 Markdown 中的 Python 代码块并插入结果。

用法：python execute_code.py <md文件路径> [--img-dir <图片目录>]

读取 Markdown 中的 ```python 代码块，逐块执行：
- 文本输出（print/DataFrame/返回值）→ 插入 md 作为"结果展示"段落
- matplotlib 图表 → 保存为 PNG → 插入 md 作为 ![]() 引用
- 执行失败 → 保留原始代码块，插入 [执行失败] 注释
"""

import argparse
import re
import subprocess
import sys
import tempfile
from pathlib import Path


def extract_code_blocks(md_text: str) -> list[dict]:
    blocks = []
    pattern = re.compile(r"```python\s*\n(.*?)```", re.DOTALL)
    for m in pattern.finditer(md_text):
        code = m.group(1).strip()
        if code:
            blocks.append({"start": m.start(), "end": m.end(), "code": code})
    return blocks


def has_plot(code: str) -> bool:
    plot_keywords = [
        "plt.", "plot(", "scatter(", "bar(", "hist(", "pie(", "boxplot",
        "sns.", "heatmap", "pairplot", "savefig",
    ]
    return any(kw in code for kw in plot_keywords)


def build_script(code: str, img_dir: Path, fig_name: str) -> str:
    lines = code.strip().split("\n")
    last_plot_line = -1
    for i, line in enumerate(lines):
        if any(k in line for k in ["plt.", "sns."]):
            last_plot_line = i

    script_parts = ["import warnings", "warnings.filterwarnings('ignore')"]
    script_parts.extend(lines)

    has_plt_show = any("plt.show()" in l for l in lines)

    if last_plot_line >= 0 and not has_plt_show:
        img_path = img_dir / fig_name
        script_parts.extend([
            "",
            "import matplotlib; matplotlib.use('Agg')",
            "import matplotlib.pyplot as _plt_save",
            f"_plt_save.savefig(r'{img_path}', dpi=1080, bbox_inches='tight')",
            "_plt_save.close()",
        ])

    return "\n".join(script_parts)


def run_code(code: str, img_dir: Path, block_idx: int) -> dict:
    result = {"output_text": "", "img_path": "", "success": False}

    has_plot_code = has_plot(code)
    fig_name = f"图_代码结果_{block_idx:02d}.png"

    script = build_script(code, img_dir, fig_name)
    tmp = tempfile.NamedTemporaryFile(mode="w", suffix=".py", delete=False, encoding="utf-8")
    tmp.write(script)
    tmp.close()

    try:
        proc = subprocess.run(
            [sys.executable, tmp.name],
            capture_output=True, text=True, timeout=30,
            cwd=img_dir.parent,
        )
        Path(tmp.name).unlink()

        stdout = proc.stdout.strip()
        stderr = proc.stderr.strip()

        if stderr and "Traceback" not in stderr:
            stdout = (stderr + "\n" + stdout).strip()
        elif stderr and "Traceback" in stderr:
            result["output_text"] = f"[代码执行错误]\n{stderr[-500:]}"
            return result

        result["output_text"] = stdout
        result["success"] = True

        if has_plot_code:
            saved = img_dir / fig_name
            if saved.exists():
                result["img_path"] = fig_name

    except subprocess.TimeoutExpired:
        result["output_text"] = "[代码执行超时]"
        Path(tmp.name).unlink(missing_ok=True)
    except Exception as e:
        result["output_text"] = f"[代码执行异常] {e}"
    finally:
        try:
            Path(tmp.name).unlink(missing_ok=True)
        except Exception:
            pass

    return result


def apply_results(md_text: str, blocks: list[dict], results: list[dict]) -> str:
    output = md_text
    for block, res in reversed(list(zip(blocks, results))):
        end_pos = block["end"]
        after = output[end_pos:]

        insertion = ""
        if res["success"]:
            if res["img_path"]:
                insertion = f"\n运行代码得到的结果如图X-N所示。\n\n![图X-N 代码运行结果](图片/{res['img_path']})\n"
            elif res["output_text"]:
                insertion = f"\n运行代码得到的结果如下。\n\n```\n{res['output_text']}\n```\n"
        else:
            if res["output_text"]:
                insertion = f"\n{res['output_text']}\n"

        output = output[:end_pos] + insertion + after

    return output


def process(md_path: str, img_dir: str = "img") -> str:
    md_file = Path(md_path)
    if not md_file.exists():
        raise FileNotFoundError(f"文件不存在: {md_path}")

    md_text = md_file.read_text(encoding="utf-8")
    blocks = extract_code_blocks(md_text)

    if not blocks:
        print("[execute] 未找到 Python 代码块，跳过执行")
        return md_path

    img_path = Path(img_dir)
    img_path.mkdir(parents=True, exist_ok=True)

    print(f"[execute] 共找到 {len(blocks)} 个 Python 代码块")

    results = []
    for i, block in enumerate(blocks):
        code_preview = block["code"][:60].replace("\n", " ")
        print(f"  [{i+1}/{len(blocks)}] 执行: {code_preview}...")
        res = run_code(block["code"], img_path, i)
        results.append(res)
        status = "✓" if res["success"] else "✗"
        detail = ""
        if res["img_path"]:
            detail = f" → 图片: {res['img_path']}"
        elif res["output_text"]:
            detail = f" → 文本输出({len(res['output_text'])}字)"
        print(f"    {status}{detail}")

    new_text = apply_results(md_text, blocks, results)
    md_file.write_text(new_text, encoding="utf-8")

    img_count = sum(1 for r in results if r["img_path"])
    text_count = sum(1 for r in results if r["success"] and r["output_text"] and not r["img_path"])
    fail_count = sum(1 for r in results if not r["success"])

    print(f"[execute] 完成: {img_count} 图, {text_count} 文本输出, {fail_count} 失败")
    return md_path


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="执行 Markdown 中的 Python 代码块")
    parser.add_argument("md_path", help="Markdown 文件路径")
    parser.add_argument("--img-dir", default="img", help="图片输出目录，默认 img")
    args = parser.parse_args()

    try:
        result = process(args.md_path, args.img_dir)
        print(result)
    except Exception as e:
        print(f"[ERROR] {e}", file=sys.stderr)
        sys.exit(1)
