#!/usr/bin/env python3
"""Render docs/openapi.yaml -> docs/api.md (Markdown 版 API 文档).
单一数据源 = openapi.yaml，避免两份文档漂移。
用法：修改 docs/openapi.yaml 后执行 python3 scripts/render_api_md.py（需 pyyaml）。"""
import yaml, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "docs" / "openapi.yaml"
DST = ROOT / "docs" / "api.md"

spec = yaml.safe_load(open(SRC))
schemas = spec["components"]["schemas"]

def anchor(s):  # github-style anchor
    return re.sub(r"[^a-z0-9-]", "", s.lower().replace(" ", "-"))

def type_str(s, depth=0):
    if s is None: return "object"
    if "$ref" in s:
        return f"[{s['$ref'].split('/')[-1]}](#" + anchor(s['$ref'].split('/')[-1]) + ")"
    t = s.get("type")
    if isinstance(t, list):
        return " | ".join(str(x) for x in t)
    if t == "array":
        return type_str(s.get("items"), depth) + "[]"
    if "oneOf" in s or "anyOf" in s:
        return " / ".join(type_str(v, depth) for v in s.get("oneOf") or s.get("anyOf"))
    if t == "object" and "properties" not in s and "allOf" not in s:
        return "object"
    return t or "object"

def esc(s):  # escape pipes for markdown table cells
    return str(s).replace("|", "\\|")

def render_props(s, depth=0, lines=None, prefix=""):
    """Render an object schema's properties as a markdown table body."""
    if depth > 4: return
    if "$ref" in s:
        render_props(schemas[s["$ref"].split("/")[-1]], depth + 1, lines, prefix)
        return
    if "allOf" in s:
        for part in s["allOf"]:
            render_props(part, depth + 1, lines, prefix)
        return
    props = s.get("properties") or {}
    required = set(s.get("required") or [])
    for name, p in props.items():
        if not isinstance(p, dict):
            continue
        row = f"| `{prefix}{name}` | {esc(type_str(p, depth))} | {'是' if name in required else '否'} | {esc(p.get('description','')).replace(chr(10),' ')} |"
        if "enum" in p:
            row = row + " 枚举：" + ", ".join(str(e) for e in p["enum"]) + " |"
        lines.append(row)
        if p.get("type") == "object" and p.get("properties") and depth < 2:
            render_props(p, depth + 1, lines, prefix=f"{prefix}{name}.")
        if p.get("type") == "array" and isinstance(p.get("items"), dict) and p["items"].get("properties") and depth < 2:
            render_props(p["items"], depth + 1, lines, prefix=f"{prefix}{name}[].")

def schema_block(s, title="Schema"):
    lines = ["| 字段 | 类型 | 必填 | 说明 |", "|---|---|---|---|"]
    render_props(s, 0, lines)
    if len(lines) == 2:
        return None
    return "\n".join(lines)

def body_of(op):
    rb = op.get("requestBody")
    if not rb: return None
    return rb["content"].get("application/json", {}).get("schema")

def resp_schema(op, code):
    r = op.get("responses", {}).get(code)
    if not r: return None
    content = r.get("content") or {}
    for ct in ("application/json",):
        if ct in content:
            return content[ct].get("schema")
    return None

out = []
w = out.append

w(f"# {spec['info']['title']}（Markdown 版）")
w("")
w(f"> 版本 {spec['info']['version']} · 由 [`openapi.yaml`](./openapi.yaml) 同步渲染（生成时间 {__import__('datetime').date.today().isoformat()}）。")
w("> 接口细节以 `openapi.yaml` 为准；本文是便于阅读的 Markdown 版本，两者由生成脚本保持一致。")
w("")
# description 首行是文档标题句，通用约定标题已内嵌，直接输出
w(spec["info"]["description"])
w("")

# table of contents by tag
tag_order = [t["name"] for t in spec["tags"]]
by_tag = {t: [] for t in tag_order}
for path, item in spec["paths"].items():
    for method, op in item.items():
        if method in ("parameters",) or not isinstance(op, dict): continue
        for tag in op.get("tags", ["Other"]):
            by_tag.setdefault(tag, []).append((method.upper(), path, op))

w("## 目录")
w("")
for t in tag_order:
    w(f"- **{t}**：" + "、".join(f"`{m} {p}`" for m, p, _ in by_tag.get(t, [])))
w("")

sec_no = 0
for t in tag_order:
    ops = by_tag.get(t, [])
    if not ops: continue
    w(f"## {t}")
    w("")
    desc = next((td["description"] for td in spec["tags"] if td["name"] == t and td.get("description")), None)
    if desc:
        w(f"{desc}")
        w("")
    for method, path, op in ops:
        sec_no += 1
        w(f"### {method} `{path}`")
        w("")
        summary = op.get("summary", "")
        if summary:
            w(f"**{summary}**")
            w("")
        # security line
        if op.get("security") == []:
            w("鉴权：**公开**（免 token）")
        else:
            w("鉴权：需登录（`Authorization: Bearer <accessToken>`）")
        w("")
        if op.get("description"):
            w(op["description"])
            w("")
        # path/query params
        params = op.get("parameters") or []
        pp = [p for p in params if p.get("in") in ("path", "query")]
        if pp:
            w("| 参数 | 位置 | 类型 | 必填 | 说明 |")
            w("|---|---|---|---|---|")
            for p in pp:
                sch = p.get("schema", {})
                w(f"| `{p['name']}` | {p['in']} | {esc(type_str(sch))} | {'是' if p.get('required') else '否'} | {esc(p.get('description',''))} |")
            w("")
        b = body_of(op)
        if b is not None:
            blk = schema_block(b)
            w("**请求体**（application/json）：")
            w("")
            w(blk if blk else "（空对象）")
            w("")
        # responses
        for code, r in (op.get("responses") or {}).items():
            desc_r = r.get("description", "")
            rs = resp_schema(op, code)
            w(f"- **{code}**：{desc_r}" if desc_r else f"- **{code}**")
            if rs and code.startswith("2"):
                blk = schema_block(rs)
                if blk:
                    w("")
                    w(blk)
        w("")

# hand-written request examples (kept in sync manually; content mirrors openapi.yaml)
w("## 关键请求示例（curl）")
w("""
### 登录并调用对话

```bash
# 1. 登录拿 token（或走 /api/auth/oauth/login + /api/auth/callback）
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \\
  -H 'Content-Type: application/json' \\
  -d '{"username":"demo","password":"******"}' | jq -r .accessToken)

# 2. 发起对话（SSE）。threadId=sessionId（新会话前端生成 main-<uuid>）；
#    runId 平台约定填 agentId；HITL resume 复用同一 (threadId, runId)
curl -N -X POST http://localhost:8080/agui/run/教材开发小助手 \\
  -H "Authorization: Bearer $TOKEN" \\
  -H 'Content-Type: application/json' \\
  -d '{
    "threadId": "main-3f6c2a90-1111-4ccc-8ddd-222233334444",
    "runId": "教材开发小助手",
    "messages": [
      { "id": "m-1", "role": "user",
        "content": [{ "type": "text", "text": "开始写《传感器技术》教材" }] }
    ]
  }'
```

### HITL：表单/人工确认中断与恢复

```bash
# 收到 RUN_FINISHED(outcome={type:"interrupt", interrupts:[…]}) 后，
# 以同一 (threadId, runId) 重新 POST，resume 覆盖全部 open interrupts：
curl -N -X POST http://localhost:8080/agui/run/教材开发小助手 \\
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \\
  -d '{
    "threadId": "main-3f6c2a90-…",
    "runId": "教材开发小助手",
    "messages": [],
    "resume": [
      { "interruptId": "<replyId>:<toolCallId>", "status": "resolved",
        "payload": { "output": "{\\"decision\\":\\"通过\\",\\"feedback\\":\\"…\\"}" } }
    ]
  }'
# 注意：external_execution 类中断只传 output 不传 approved；
# 权限确认类传 { "approved": true, "editedArgs": {} }；取消用 status:"cancelled"。
```

### 附件与会话资源

```bash
AGENT_ID=…; SESSION_ID=main-3f6c2a90-…
# 上传附件（≤20MB，白名单扩展名），返回的 ref 形如 @uploads/xxx，
# 在用户消息里以该 @path 引用
curl -s -X POST "http://localhost:8080/api/agents/$AGENT_ID/sessions/$SESSION_ID/attachments" \\
  -H "Authorization: Bearer $TOKEN" -F "file=@./讲义.pdf"

# 拉取转录（后台 wakeup 续跑产生的段落靠这里补齐，建议轮询）
curl -s "http://localhost:8080/api/agents/$AGENT_ID/sessions/$SESSION_ID/transcript" \\
  -H "Authorization: Bearer $TOKEN"

# 读取产物内容（会话内相对路径）
curl -s "http://localhost:8080/api/agents/$AGENT_ID/sessions/$SESSION_ID/artifacts/content?path=全局DB/教材/目录.md" \\
  -H "Authorization: Bearer $TOKEN"
```

### 知识库检索

```bash
# type=rag/external 返回 {documentId,chunkIndex,score,content}；
# type=text 返回 {file,line,content,context[]}
curl -s -X POST "http://localhost:8080/api/knowledge-bases/$KB_ID/search" \\
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \\
  -d '{"query":"电容传感器的测量电路","topK":5}'
```
""")

# schema appendix
w("## 附：Schema 字典（components.schemas）")
w("")
for name, s in schemas.items():
    w(f"<a id=\"{anchor(name)}\"></a>")
    w(f"### {name}")
    w("")
    blk = schema_block(s)
    if blk:
        w(blk)
    else:
        w("（无字段说明）")
    w("")

open(DST, "w").write("\n".join(out) + "\n")
print(f"written {DST}: {len(out)} lines")
