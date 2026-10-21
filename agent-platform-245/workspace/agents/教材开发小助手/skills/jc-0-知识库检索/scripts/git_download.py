"""Git 仓库下载 — 从 git_info 页面读取仓库信息并 clone 到本地。

KB 案例条目下放置名为 git_info 的页面，内容为 CSV 格式。
命中案例后检测 git_info，存在则 clone，不存在则静默跳过。

CSV 内容格式（表头必须一致，每行一个仓库配置）:
  repo,subdir,branch
  https://gitee.com/user/repo.git,案例目录名/,master

config.json 中的 gitee 认证信息：
  {"gitee": {"username": "xxx", "token": "私人令牌"}}

流程:
  1. 检测 KB 子项中是否有 git_info
  2. 下载 git_info 页面内容（CSV 格式）
  3. 解析 repo / subdir / branch
  4. shallow clone（--depth 1）
  5. 复制 subdir 到 output_dir
  6. 清理临时目录
"""

import os
import csv
import json
import shutil
import subprocess
import tempfile

# config.json 路径（与当前脚本同目录）
_CONFIG_PATH = os.path.join(os.path.dirname(__file__), "config.json")


def _load_gitee_config():
    """从 config.json 读取 Gitee 认证信息。"""
    try:
        with open(_CONFIG_PATH, "r", encoding="utf-8") as f:
            cfg = json.load(f)
            gitee = cfg.get("gitee", {})
            return gitee.get("username", ""), gitee.get("token", "")
    except Exception:
        return "", ""


def _inject_auth(repo_url):
    """如果是 Gitee 私有仓库且有 token，注入认证信息到 URL。

    https://gitee.com/user/repo.git
    → https://user:token@gitee.com/user/repo.git
    """
    username, token = _load_gitee_config()
    if not token or "gitee.com" not in repo_url:
        return repo_url
    if "@" in repo_url:
        return repo_url
    return repo_url.replace(
        "https://gitee.com/",
        f"https://{username}:{token}@gitee.com/"
    )


def clone_from_git_config(csv_path, output_dir):
    """从 git_info 导出的内容文件读取配置，clone 代码到 output_dir。

    Args:
        csv_path: KB 下载的 git_info 页面内容（CSV 格式）的本地路径
        output_dir: 输出目标目录

    Returns:
        dict: {ok: bool, files: int, repo: str, subdir: str, error: str}
    """
    result = {"ok": False, "files": 0, "repo": "", "subdir": "", "error": ""}

    # 1. 读取 CSV 内容
    try:
        with open(csv_path, "r", encoding="utf-8-sig") as f:
            reader = csv.DictReader(f)
            row = next(reader, {})
            repo = (row.get("repo") or "").strip()
            subdir = (row.get("subdir") or "").strip("/")
            branch = (row.get("branch") or "master").strip() or "master"
    except Exception as e:
        result["error"] = f"读取 git_info 内容失败: {e}"
        return result

    if not repo:
        result["error"] = "git_info 缺少 repo 字段"
        return result

    result["repo"] = repo
    result["subdir"] = subdir

    # 2. 注入认证信息（私有仓库）
    repo = _inject_auth(repo)

    # 3. 检查 git 是否可用
    try:
        subprocess.run(["git", "--version"], capture_output=True, check=True)
    except Exception:
        result["error"] = "系统未安装 Git，无法 clone"
        return result

    # 4. 浅克隆到临时目录
    tmpdir = tempfile.mkdtemp(prefix="git_dl_")

    try:
        cmd = [
            "git", "clone",
            "--depth", "1",
            "--branch", branch,
            "--single-branch",
            repo, tmpdir
        ]
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
        if proc.returncode != 0:
            result["error"] = f"git clone 失败: {proc.stderr.strip()}"
            return result
    except subprocess.TimeoutExpired:
        result["error"] = "git clone 超时"
        return result
    except Exception as e:
        result["error"] = f"git clone 异常: {e}"
        return result

    # 5. 复制 subdir 到 output_dir
    src_dir = os.path.join(tmpdir, subdir) if subdir else tmpdir
    if not os.path.isdir(src_dir):
        result["error"] = f"仓库中不存在子目录: {subdir}"
        shutil.rmtree(tmpdir, ignore_errors=True)
        return result

    os.makedirs(output_dir, exist_ok=True)
    file_count = 0

    for root, dirs, files in os.walk(src_dir):
        dirs[:] = [d for d in dirs if d != ".git"]
        rel_root = os.path.relpath(root, src_dir)
        target_root = output_dir if rel_root == "." else os.path.join(output_dir, rel_root)
        os.makedirs(target_root, exist_ok=True)
        for fname in files:
            shutil.copy2(os.path.join(root, fname), os.path.join(target_root, fname))
            file_count += 1

    result["ok"] = True
    result["files"] = file_count

    shutil.rmtree(tmpdir, ignore_errors=True)
    return result


if __name__ == "__main__":
    import sys
    if len(sys.argv) < 3:
        print(f"用法: python {sys.argv[0]} <git_info内容文件> <输出目录>")
        sys.exit(1)

    r = clone_from_git_config(sys.argv[1], sys.argv[2])
    if r["ok"]:
        print(f"✅ 成功: {r['files']} 个文件 → {sys.argv[2]}")
    else:
        print(f"❌ 失败: {r['error']}")
