#!/usr/bin/env python3
"""同步教材开发小助手技能的共享规范文件（_shared/references/* → 各技能 references/）。

背景：技能按「单技能目录」命名空间加载（workspace-namespaced），SKILL.md 里
`_shared/references/X.md` 这类跨技能目录引用走技能资源机制必然 Resource not found。
修复方式：把共享规范按清单复制进各技能自己的 references/ 目录，SKILL.md 一律改引
`references/X.md`。本脚本负责保持副本与 `_shared` 源一致（幂等，可反复执行）。

用法：
  python3 scripts/sync_shared_refs.py            # 执行同步（源 skills 目录）
  python3 scripts/sync_shared_refs.py --verify   # 只校验不写，不一致时退出码 1
"""
import argparse
import filecmp
import shutil
import sys
from pathlib import Path

SKILLS_ROOT = Path(__file__).resolve().parent.parent / "workspace/agents/教材开发小助手/skills"
SHARED_REF_DIR = SKILLS_ROOT / "_shared/references"

# 各技能需要的共享规范清单（按 SKILL.md 实际引用维护；缺文件会报错提示补清单）
MANIFEST: dict[str, list[str]] = {
    "jc-3-引言编写": ["全局写作原则.md", "用语与格式规范.md", "任务要点卡.md"],
    "jc-3-知识准备": ["全局写作原则.md", "例子规范.md", "用语与格式规范.md",
                      "思政融入规范.md", "提示词驱动型知识结构.md", "配图规范.md", "任务要点卡.md"],
    "jc-3-项目实现": ["全局写作原则.md", "用语与格式规范.md", "思政融入规范.md",
                      "提示词驱动型知识结构.md", "配图规范.md",
                      "代码规范.md", "表格规范.md", "公式规范.md", "任务要点卡.md"],
    "jc-3-小结生成": ["全局写作原则.md", "用语与格式规范.md", "任务要点卡.md"],
    "jc-3-目标生成": ["全局写作原则.md", "用语与格式规范.md", "思政融入规范.md", "任务要点卡.md"],
    "jc-3-实训生成": ["全局写作原则.md", "用语与格式规范.md", "代码规范.md", "任务要点卡.md"],
    "jc-3-课后习题": ["用语与格式规范.md", "题注与编号规范.md", "任务要点卡.md"],
    "jc-3-阅读拓展": ["全局写作原则.md", "用语与格式规范.md", "思政融入规范.md", "任务要点卡.md"],
    "jc-4-章节拼装": ["题注与编号规范.md"],
    "jc-5-文前材料": ["用语与格式规范.md", "思政融入规范.md"],
    "jc-5-附录编写": ["用语与格式规范.md", "思政融入规范.md"],
    "jc-5-参考文献整理": ["用语与格式规范.md", "思政融入规范.md"],
}


def sync(verify_only: bool) -> int:
    problems: list[str] = []
    copied = unchanged = 0
    for skill, files in MANIFEST.items():
        dest_dir = SKILLS_ROOT / skill / "references"
        for name in files:
            src = SHARED_REF_DIR / name
            dst = dest_dir / name
            if not src.is_file():
                problems.append(f"{skill}: 源缺失 {SHARED_REF_DIR.relative_to(SKILLS_ROOT)}/{name}（检查清单拼写）")
                continue
            if dst.is_file() and filecmp.cmp(src, dst, shallow=False):
                unchanged += 1
                continue
            if verify_only:
                problems.append(f"{skill}: 副本过期或缺失 → {dst}")
                continue
            dest_dir.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
            copied += 1
            print(f"sync  {skill}/references/{name}")
    if problems:
        for p in problems:
            print(f"[!] {p}")
        return 1
    mode = "verify" if verify_only else "sync"
    print(f"{mode} OK: copied={copied} unchanged={unchanged} skills={len(MANIFEST)}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--verify", action="store_true", help="只校验副本是否与 _shared 一致")
    args = ap.parse_args()
    if not SHARED_REF_DIR.is_dir():
        print(f"[!] 找不到共享规范目录: {SHARED_REF_DIR}")
        return 1
    return sync(args.verify)


if __name__ == "__main__":
    sys.exit(main())
