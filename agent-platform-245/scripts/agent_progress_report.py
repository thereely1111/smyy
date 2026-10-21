#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Agent 超长任务进度报告（只读，无侵入）——L1 产物驱动观测。

背景：编排式长流程（如教材开发 12 章资料准备）可能数小时、多轮"spawn→分批返回→续跑"。
2026-09-05/06 实测曾出现父智能体把"子任务分批返回"误判为"仍在后台"，空等 10 小时停摆。
本脚本定期运行（建议配 WorkBuddy 定时自动化 15-30 分钟一次）即可把黑盒变成报告：

  * 会话尾部语义判定：RUNNING / WAITING_HITL(等主编表单) / STALLED(疑似停滞, 默认 idle>15min) / DONE
  * 产物完成度表：以磁盘为事实源（runtimes/{agent}/{session}/全局DB/...），按阶段输出关键产物存在性
  * 全程只读：只调用 GET 接口与 stat 磁盘，不写库、不改会话。

用法：
  python3 scripts/agent_progress_report.py [--agent <uuid>] [--session <sessionId>] [--stall-min 15]
      [--base http://localhost:8080] [--user admin] [--password admin] [--root <workspace>]

依赖：python3 标准库（urllib/os/json），后端在线 + 可登录；无 jq/curl/psycopg 依赖。
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from typing import Any, Optional

DEFAULT_AGENT = "631f97d0-c074-3069-8196-e850831bbee0"  # 教材开发小助手（file-agent UUID）
DEFAULT_ROOT = "/Volumes/Data/Work/project/agent-platform-java/workspace"

STALL_KEYWORDS = ("请稍候", "仍在后台", "仍在运行", "后台运行中", "自动通知", "等待自动唤醒", "进行中")


def http_json(url: str, method: str = "GET", payload: Optional[dict] = None,
              token: Optional[str] = None) -> Any:
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")[:300]
        raise SystemExit(f"HTTP {e.code} {url}\n{body}") from e


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def parse_ts(ts: Optional[str]) -> Optional[datetime]:
    if not ts:
        return None
    try:
        return datetime.fromisoformat(ts.replace("Z", "+00:00")).astimezone()
    except ValueError:
        try:
            return datetime.strptime(ts, "%Y-%m-%d %H:%M:%S.%f").astimezone()
        except ValueError:
            return None


def session_meaning(last_text: str, last_tool_text: str) -> str:
    """会话尾部语义粗判：等主编 / 完成 / 待查磁盘判定。"""
    tail = (last_text + "\n" + last_tool_text)[-400:]
    if "GATE_WAIT" in tail or ("闸门" in tail and "✅" not in tail):
        return "WAITING_HITL"
    if "[完成]" in tail or "全书定稿交付" in tail or "📕" in tail:
        return "DONE"
    return "UNKNOWN"


def product_snapshot(root: str, agent: str, session: str) -> dict:
    base = os.path.join(root, "runtimes", agent, session, "全局DB")
    rows: list[dict] = []
    if not os.path.isdir(base):
        return {"rows": rows, "latest_mtime": None, "books": []}
    latest = 0.0
    books = sorted(d for d in os.listdir(base) if os.path.isdir(os.path.join(base, d)))
    for book in books:
        bdir = os.path.join(base, book)
        toc = os.path.join(bdir, "章节目录")
        toc_files = {}
        if os.path.isdir(toc):
            for f in os.listdir(toc):
                toc_files[f] = os.path.getmtime(os.path.join(toc, f))
                latest = max(latest, toc_files[f])
        chapters = []
        for d in sorted(os.listdir(bdir)):
            # 只认「第N章…」目录；章节目录/样章模板 等元目录不算章节
            if not d.startswith("第"):
                continue
            cdir = os.path.join(bdir, d)
            if not os.path.isdir(cdir):
                continue
            mats = os.path.join(cdir, "资料全文")
            full = {}
            if os.path.isdir(mats):
                for f in os.listdir(mats):
                    p = os.path.join(mats, f)
                    full[f] = os.path.getmtime(p)
                    latest = max(latest, os.path.getmtime(p))
            docx = [f for f in os.listdir(cdir) if f.endswith(".docx")]
            chapters.append({"name": d, "mats": sorted(full), "docx": sorted(docx),
                             "mtime": max([full.get(f, 0) for f in full] or [0])})
        rows.append({"book": book, "toc": sorted(toc_files), "chapters": chapters})
    return {"rows": rows, "latest_mtime": latest or None, "books": books}


def main() -> None:
    ap = argparse.ArgumentParser(description="Agent 超长任务进度报告（只读）")
    ap.add_argument("--agent", default=DEFAULT_AGENT)
    ap.add_argument("--session", default=None, help="缺省取该 agent 最近更新的会话")
    ap.add_argument("--stall-min", type=int, default=15)
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--password", default="admin")
    ap.add_argument("--root", default=DEFAULT_ROOT)
    args = ap.parse_args()

    token = http_json(f"{args.base}/api/auth/login", "POST",
                      {"username": args.user, "password": args.password})["accessToken"]

    session = args.session
    if not session:
        sess = http_json(f"{args.base}/api/agents/{args.agent}/sessions", token=token)
        if not sess:
            raise SystemExit("无会话")
        def _ts(s): return parse_ts(s.get("updatedAt")) or datetime.min.astimezone()
        session = max(sess, key=_ts)["sessionId"]
    trans = http_json(
        f"{args.base}/api/agents/{args.agent}/sessions/{session}/transcript", token=token)
    msgs = trans.get("messages", [])

    last_ts: Optional[datetime] = None
    last_text = ""
    last_tool = ""
    for m in msgs:
        t = parse_ts(m.get("createdAt"))
        if t and (last_ts is None or t > last_ts):
            last_ts = t
        if m.get("role") == "assistant":
            for b in m.get("content", []):
                if b.get("type") == "text":
                    last_text = b.get("text", "") or last_text
                elif b.get("type") == "tool_call":
                    last_tool = b.get("name", "") or last_tool
    idle_min = round((datetime.now().astimezone() - (last_ts or datetime.now().astimezone())).total_seconds() / 60, 1)

    meaning = session_meaning(last_text, last_tool)
    if meaning == "UNKNOWN":
        stalled_words = any(k in last_text for k in STALL_KEYWORDS)
        meaning = "STALLED" if (stalled_words and idle_min > args.stall_min) else \
                  ("RUNNING" if idle_min < 10 else "IDLE_UNKNOWN")

    print(f"# 进度报告 · agent={args.agent}\n")
    print(f"- 会话：`{session}`")
    print(f"- 生成时间：{now_iso()}  最后消息：{last_ts or '-'}  idle：{idle_min} min")
    print(f"- 状态：**{meaning}**" + ("（疑似停滞：>15min 无新消息且尾部为等待文案）" if meaning == "STALLED" else ""))
    print(f"- 尾部语义：{last_text[-120:]!r}")

    snap = product_snapshot(args.root, args.agent, session)
    if not snap["rows"]:
        print("\n（无 全局DB 产物）")
        return
    print("\n## 产物完成度")
    if snap["latest_mtime"]:
        latest_dt = datetime.fromtimestamp(snap["latest_mtime"]).astimezone()
        prod_idle = round((datetime.now().astimezone() - latest_dt).total_seconds() / 60, 1)
        print(f"- 最近产物写入：{latest_dt:%H:%M:%S}（{prod_idle} min 前）")
    for r in snap["rows"]:
        print(f"\n### {r['book']}")
        print("- 章节目录：", "、".join(r["toc"]) if r["toc"] else "（空）")
        for ch in r["chapters"]:
            mats = "、".join(ch["mats"]) if ch["mats"] else "（空）"
            print(f"- {ch['name']}：资料全文[{mats}] docx={ch['docx']}")


if __name__ == "__main__":
    main()
