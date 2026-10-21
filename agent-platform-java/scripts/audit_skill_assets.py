#!/usr/bin/env python3
"""审计 workspace/agents/*/skills：SKILL.md 引用资产是否落盘、CodeBuddy 遗产路径、git 跟踪缺口。只读扫描。"""
import os, re, subprocess, json

ROOT = "/Volumes/Data/Work/project/agent-platform-java"
AGENTS_DIR = os.path.join(ROOT, "workspace", "agents")

REF_PAT = re.compile(
    r"(?:[\w\-.\u4e00-\u9fff]+/)?(?:scripts|references|assets|examples)/[\w\-./\u4e00-\u9fff]+\.(?:py|sh|json|md|csv|xlsx|txt|js|html)",
    re.X,
)

def git(*args):
    return subprocess.run(
        ["git", "-C", ROOT, "-c", "core.quotepath=off"] + list(args),
        capture_output=True, text=True,
    ).stdout

tracked = set(git("ls-files", "workspace/agents/").splitlines())
report = {}

for agent in sorted(os.listdir(AGENTS_DIR)):
    skills_root = os.path.join(AGENTS_DIR, agent, "skills")
    if not os.path.isdir(skills_root):
        continue
    agent_report = []
    for skill in sorted(os.listdir(skills_root)):
        sdir = os.path.join(skills_root, skill)
        smd = os.path.join(sdir, "SKILL.md")
        if not os.path.isfile(smd):
            continue
        issues = []
        text = open(smd, encoding="utf-8", errors="replace").read()

        # 1) CodeBuddy 遗产路径 / 平台不存在的变量
        if "${CODEBUDDY_SKILL_DIR}" in text:
            issues.append("SKILL.md 使用 ${CODEBUDDY_SKILL_DIR}（AgentHub 不注入）")
        if ".codebuddy/" in text:
            issues.append("SKILL.md 使用 .codebuddy/ 路径（运行时不存在该目录）")

        # 2) SKILL.md 引用的脚本/文件是否落盘（兼容 _shared/ 与跨技能 jc-* 引用）
        skills_root = os.path.dirname(sdir)
        refs = sorted(set(REF_PAT.findall(text)))
        for r in refs:
            candidates = [
                os.path.join(sdir, r),                 # 相对本技能目录
                os.path.join(sdir, "_shared", r),      # 审计正则截断 _shared/ 前缀的情况
                os.path.join(skills_root, r),          # 跨技能引用（jc-0-知识库检索/scripts/...）
                os.path.join(skills_root, "_shared", r),
                os.path.join(ROOT, r),                 # 仓库根脚本（scripts/sync_shared_refs.py 等）
            ]
            if not any(os.path.exists(c) for c in candidates):
                issues.append(f"引用缺失: {r}")

        # 3) git 跟踪缺口：磁盘上非忽略文件未入库（丢失即不可恢复）
        rel_prefix = os.path.relpath(sdir, ROOT)
        disk = []
        for dp, dns, fns in os.walk(sdir):
            dns[:] = [d for d in dns if d not in ("__pycache__",) and d != "browsers" and not d.startswith(".")]
            for f in fns:
                if f == ".DS_Store":
                    continue
                disk.append(os.path.relpath(os.path.join(dp, f), ROOT))
        untracked = [f for f in disk if f not in tracked]
        if untracked:
            issues.append("git 未跟踪: " + ", ".join(untracked[:6]) + ("…" if len(untracked) > 6 else ""))

        # 4) 空 scripts/browsers 目录但 SKILL.md 声称有脚本
        for sub in ("scripts", "browsers", "references", "assets"):
            subp = os.path.join(sdir, sub)
            if os.path.isdir(subp) and not os.listdir(subp):
                issues.append(f"{sub}/ 为空目录")

        if issues:
            agent_report.append({"skill": skill, "issues": issues})

    if agent_report:
        report[agent] = agent_report

print(json.dumps(report, ensure_ascii=False, indent=1))
