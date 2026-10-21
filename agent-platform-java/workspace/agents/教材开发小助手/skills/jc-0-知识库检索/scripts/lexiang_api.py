"""
知识库检索 API 封装
==================
为 jc-2-资料查找 / jc-2-数据准备 提供 KB 检索与数据拉取能力。

功能：
  search_by_ai()       — AI 语义搜索，返回 片段 + URL（资料查找·主力）
  search_by_keyword()  — 传统关键词搜索，返回 节点列表（资料查找·兜底）
  search_datasets()    — 递归浏览数据库空间中的数据集条目（数据准备·主力）
  download_file()      — 下载 KB 条目到本地（数据准备）
  download_folder()    — 递归下载整个文件夹（数据准备）
  get_entry_content()  — 读取 page 节点全文（资料查找·补全引用摘要）
  export_smartsheet()  — smartsheet → CSV 导出（乐享无专用端点，AI搜索提取）

设计原则：
  - Token 自动获取 + 缓存，过期前 5 分钟自动刷新
  - 所有网络错误静默降级，不抛异常
  - 限频 429 自动指数退避重试（最多 3 次）
  - 不依赖外部第三方库（仅 requests）
"""

import requests, json, time, threading, os, csv
from datetime import datetime, timedelta

# ═══════════════════════════════════════════════════════════
# Token 管理（线程安全 + 缓存 + 自动刷新）
# ═══════════════════════════════════════════════════════════

class _TokenManager:
    def __init__(self, app_key, app_secret, base_url):
        self._key = app_key
        self._secret = app_secret
        self._base = base_url
        self._token = None
        self._expire_at = None
        self._lock = threading.Lock()

    def get(self):
        with self._lock:
            if self._token and self._expire_at and datetime.now() < self._expire_at - timedelta(minutes=5):
                return self._token
            return self._refresh()

    def _refresh(self):
        try:
            resp = requests.post(
                f"{self._base}/cgi-bin/token",
                json={"grant_type": "client_credentials", "app_key": self._key, "app_secret": self._secret},
                headers={"Content-Type": "application/json; charset=utf-8"},
                timeout=15,
            )
            if resp.status_code == 200:
                data = resp.json()
                self._token = data["access_token"]
                self._expire_at = datetime.now() + timedelta(seconds=data.get("expires_in", 7200))
                return self._token
        except Exception:
            pass
        return None


# ═══════════════════════════════════════════════════════════
# 核心 API 类
# ═══════════════════════════════════════════════════════════

class LexiangKB:
    """知识库 API 封装。

    使用方式:
        kb = LexiangKB(config_path_override=None)  # 默认读同目录 config.json
        results = kb.search_by_ai("AI编程工具")
        datasets = kb.search_datasets("订单")
        kb.download_file(entry_id, "/data/output.csv")
    """

    def __init__(self, config_path=None):
        if config_path is None:
            config_path = os.path.join(os.path.dirname(__file__), "config.json")
        self._cfg = self._load_json(config_path)
        self._tm = _TokenManager(self._cfg["app_key"], self._cfg["app_secret"], self._cfg["base_url"])
        self._base = self._cfg["base_url"]
        self._staff_id = self._cfg["staff_id"]
        self._search_cfg = self._cfg.get("search", {})
        self._spaces = self._cfg.get("spaces", {})

    @staticmethod
    def _load_json(path):
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)

    def _resolve_scope(self, scope):
        """根据 scope 返回 (space_id, root_entry_id)。

        scope: "knowledge" | "case" | "dataset" | None（None=全团队，不限定空间）
        """
        if scope and scope in self._spaces:
            sp = self._spaces[scope]
            return sp["space_id"], sp.get("root_entry_id", "")
        return None, None

    @property
    def space_id(self):
        """默认知识库 space_id（学科知识库）。"""
        return self._spaces.get("knowledge", {}).get("space_id", "")

    @property
    def team_id(self):
        return self._cfg["team_id"]

    # ── 通用请求 ────────────────────────────────────────

    def _request(self, method, path, params=None, json_body=None, extra_headers=None, timeout=30, retries=3):
        token = self._tm.get()
        if not token:
            return None

        headers = {"Authorization": f"Bearer {token}"}
        if extra_headers:
            headers.update(extra_headers)

        for attempt in range(retries):
            try:
                resp = requests.request(method, f"{self._base}{path}",
                                        params=params, json=json_body,
                                        headers=headers, timeout=timeout)
                if resp.status_code == 429:
                    wait = 2 ** attempt
                    time.sleep(wait)
                    continue
                if resp.status_code == 401:
                    self._tm._refresh()
                    token = self._tm.get()
                    if not token:
                        return None
                    headers["Authorization"] = f"Bearer {token}"
                    continue
                return resp
            except requests.exceptions.Timeout:
                continue
            except requests.exceptions.ConnectionError:
                if attempt == retries - 1:
                    return None
                time.sleep(1)
        return None

    def _staff_headers(self):
        return {"x-staff-id": self._staff_id}

    # ── 资料查找·主力: AI 搜索 ──────────────────────────

    def search_by_ai(self, query, top_n=None, space_id=None):
        """AI 语义搜索，返回 [{title, content, url}]。

        Args:
            query: 搜索关键词/句子（最长 1024 字符）
            top_n: 返回片段数（默认 5，最大 50）
            space_id: 可选，限定在指定知识库空间（物理隔离，不跨空间召回）

        Returns:
            list[dict] | []  — 失败时返回空列表
        """
        top_n = top_n or self._search_cfg.get("ai_top_n", 5)
        body = {"query": query[:1024], "top_n": min(top_n, 50)}

        if space_id:
            body["targets"] = [{"type": "space", "id": space_id}]
        else:
            body["targets"] = [{"type": "team", "id": self._cfg["team_id"]}]

        resp = self._request("POST", "/cgi-bin/v1/ai/search",
                             json_body=body,
                             extra_headers=self._staff_headers(),
                             timeout=20)
        if resp and resp.status_code == 200:
            data = resp.json()
            if data.get("code") == 0:
                items = data.get("data", {}).get("list", [])
                return [{"title": it.get("title", "无标题"),
                         "content": it.get("content", ""),
                         "url": it.get("url", "")} for it in items]
        return []

    # ── 资料查找·兜底: 关键词搜索 ────────────────────────

    def search_by_keyword(self, keyword, limit=None, sort_by="-edited_at", space_id=None):
        """传统关键词搜索，返回 [{name, id, entry_type, updated_at}]。

        Args:
            keyword: 搜索关键词（最大 200 字符）
            limit: 返回条数（默认 10，最大 100）
            sort_by: 排序方式（-edited_at / -created_at / ''=相关性）
            space_id: 限定搜索的知识库空间（默认取知识库空间）

        Returns:
            list[dict] | []
        """
        limit = limit or self._search_cfg.get("fallback_limit", 10)
        body = {
            "keyword": keyword[:200],
            "space_id": space_id or self.space_id,
            "limit": min(limit, 100),
            "sort_by": sort_by,
        }
        resp = self._request("POST", "/cgi-bin/v1/kb/entries/search",
                             json_body=body,
                             extra_headers=self._staff_headers(),
                             timeout=15)
        if resp and resp.status_code == 200:
            entries = resp.json().get("data", [])
            return [{"name": e["attributes"]["name"],
                     "id": e["id"],
                     "entry_type": e["attributes"]["entry_type"],
                     "updated_at": e["attributes"].get("updated_at", "")} for e in entries]
        return []

    # ── 综合检索（AI优先） ──────────────────────────────

    def search(self, query, keyword=None, ai_top_n=5, kw_limit=10, scope=None):
        """一站式检索：AI 优先，传统兜底。双通道均支持按知识库空间限定。

        Args:
            query: AI 搜索查询句
            keyword: 传统搜索关键词（默认取 query 的前 50 字符）
            ai_top_n: AI 返回条数
            kw_limit: 传统返回条数
            scope: 可选 "knowledge" | "case" | "dataset" | None（全团队）

        Returns:
            dict: {
                "ai_results": [...],
                "kw_results": [...],
                "source": "ai" | "keyword" | "both" | "none",
                "scope": str | None
            }
        """
        keyword = keyword or query[:50]
        result = {"ai_results": [], "kw_results": [], "source": "none", "scope": scope}

        # 根据 scope 获取对应知识库的 space_id（root_entry_id 已不再用于 AI 通道）
        space_id, _ = self._resolve_scope(scope)

        # AI 优先 — 使用 space_id 限定在目标知识库空间（物理隔离，不跨空间召回）
        result["ai_results"] = self.search_by_ai(
            query, top_n=ai_top_n, space_id=space_id
        )
        if result["ai_results"]:
            result["source"] = "ai"
            return result

        # 关键词兜底 — 使用 space_id 限定搜索范围（双通道均支持分库）
        result["kw_results"] = self.search_by_keyword(
            keyword, limit=kw_limit, space_id=space_id
        )
        if result["kw_results"]:
            result["source"] = "keyword"

        return result

    # ── 数据准备·主力: 浏览数据集 ────────────────────────

    def search_datasets(self, keyword=None, entry_types=None):
        """浏览「数据库」知识库空间中的数据集（根目录 + 子文件夹递归）。

        搜索限定在 config.json 中 spaces.dataset 指定的独立知识库空间。

        Args:
            keyword: 可选过滤关键词（客户端 name contains 过滤）
            entry_types: 要检索的类型，默认 ["file", "page", "smartsheet"]

        Returns:
            list[dict] — 每个元素包含 {name, id, entry_type, extension?, rel_path}
        """
        entry_types = entry_types or ["file", "page", "smartsheet"]
        ds_space = self._spaces.get("dataset", {})
        dataset_space_id = ds_space.get("space_id", "")
        dataset_root_id = ds_space.get("root_entry_id", "")

        # 递归浏览所有条目（含嵌套文件夹）
        all_entries = self.list_folder_recursive(dataset_root_id, space_id=dataset_space_id)
        matched = []
        for c in all_entries:
            if c["entry_type"] in entry_types:
                item = {
                    "name": c["name"],
                    "id": c["id"],
                    "entry_type": c["entry_type"],
                    "updated_at": c.get("updated_at", ""),
                    "rel_path": c.get("rel_path", c["name"]),
                }
                if c["entry_type"] in ("file", "smartsheet"):
                    item["extension"] = self._guess_extension(c["name"])
                # 关键词过滤（客户端）
                if keyword and keyword.lower() not in c["name"].lower():
                    continue
                matched.append(item)
        return matched

    # ── 文件夹遍历 ──────────────────────────────────────

    def list_folder(self, parent_id, space_id=None, limit=100):
        """列出文件夹下的直接子条目。

        官方端点: GET /cgi-bin/v1/kb/entries?space_id=xxx&parent_id=xxx

        Args:
            parent_id: 文件夹 entry_id
            space_id: 知识库空间 ID（默认取知识库空间）
            limit: 每页条数（最大 100）

        Returns:
            list[dict]: 每个元素 {name, id, entry_type, updated_at, has_children}
        """
        params = {
            "space_id": space_id or self.space_id,
            "parent_id": parent_id,
            "limit": min(limit, 100),
        }
        resp = self._request("GET", "/cgi-bin/v1/kb/entries",
                             params=params,
                             extra_headers=self._staff_headers(),
                             timeout=15)
        if not resp or resp.status_code != 200:
            return []

        entries = resp.json().get("data", [])
        return [{
            "name": e["attributes"]["name"],
            "id": e["id"],
            "entry_type": e["attributes"]["entry_type"],
            "updated_at": e["attributes"].get("updated_at", ""),
            "has_children": e["attributes"].get("has_children", False),
        } for e in entries]

    def list_folder_recursive(self, parent_id, space_id=None):
        """递归列出文件夹下所有子条目（包括嵌套层级）。

        Args:
            parent_id: 起始文件夹 entry_id
            space_id: 知识库空间 ID（默认取知识库空间）

        Returns:
            list[dict]: 每个元素 {name, id, entry_type, rel_path, has_children}
            其中 rel_path 是相对于 parent_id 的路径
        """
        all_entries = []
        sid = space_id or self.space_id

        def _walk(folder_id, prefix=""):
            children = self.list_folder(folder_id, space_id=sid)
            for c in children:
                rel_path = os.path.join(prefix, c["name"]) if prefix else c["name"]
                all_entries.append({
                    "name": c["name"],
                    "id": c["id"],
                    "entry_type": c["entry_type"],
                    "updated_at": c.get("updated_at", ""),
                    "rel_path": rel_path,
                    "has_children": c.get("has_children", False),
                })
                if c["entry_type"] == "folder" and c.get("has_children"):
                    _walk(c["id"], rel_path)
            return children

        _walk(parent_id)
        return all_entries

    # ── 读取 page 内容（官方端点） ──────────────────────

    def get_page_content(self, entry_id, content_type="markdown", scope=None):
        """读取在线文档（page 类型）的完整内容。

        官方端点: GET /cgi-bin/v1/kb/entries/{entry_id}/content?content_type=html

        API 返回 JSON 包裹，本方法自动解包为纯文本。

        Args:
            entry_id: page 节点 ID
            content_type: "html" | "markdown"
            scope: 保留参数，与 search/download 系列保持签名一致（entry_id 全局唯一，无需用到）

        Returns:
            str | "" — 解包后的文档纯文本内容
        """
        resp = self._request("GET", f"/cgi-bin/v1/kb/entries/{entry_id}/content",
                             params={"content_type": content_type},
                             extra_headers=self._staff_headers(), timeout=15)
        if not resp or resp.status_code != 200:
            return ""

        try:
            data = resp.json()
            attrs = data.get("data", {}).get("attributes", {})
            # content_type 决定取哪个字段: markdown_content / html_content
            field_map = {"markdown": "markdown_content", "html": "html_content"}
            field = field_map.get(content_type, "markdown_content")
            return attrs.get(field, "") or ""
        except Exception:
            return ""

    # ── 通用文本提取（支持 page + 所有 file 格式） ─────

    def get_entry_text(self, entry_id):
        """读取任意类型条目的文本内容（自动路由到最佳提取方式）。

        自动识别 entry_type 并选择最佳提取策略：
        - page → get_page_content (markdown)
        - file → 下载 + 按扩展名提取文本
        - smartsheet → export_smartsheet 返回 CSV 文本
        - folder → 返回空（文件夹无文本）

        Args:
            entry_id: KB 条目 ID

        Returns:
            str | "" — 提取的纯文本内容
        """
        detail = self.get_entry_detail(entry_id)
        if not detail:
            return ""

        entry_type = detail.get("attributes", {}).get("entry_type", "")
        name = detail.get("attributes", {}).get("name", "")

        if entry_type == "page":
            return self.get_page_content(entry_id) or ""

        elif entry_type == "file":
            download_url = (detail.get("links", {}) or {}).get("download", "")
            if not download_url:
                download_url = detail.get("attributes", {}).get("download_url", "")
            if not download_url:
                return ""
            try:
                import requests as _rq
                resp = _rq.get(download_url, timeout=60)
                if resp.status_code == 200:
                    return self._extract_text_from_bytes(resp.content, name)
            except Exception:
                pass
            return ""

        elif entry_type == "smartsheet":
            import tempfile, os
            with tempfile.TemporaryDirectory() as tmpdir:
                csv_path = self.export_smartsheet(entry_id, os.path.join(tmpdir, "tmp.csv"))
                if csv_path:
                    try:
                        with open(csv_path, "r", encoding="utf-8-sig") as f:
                            return f.read()
                    except Exception:
                        pass
            return ""

        elif entry_type == "folder":
            return ""

        else:
            # 未知类型 → 尝试 get_page_content 兜底
            return self.get_page_content(entry_id) or ""

    @staticmethod
    def _extract_text_from_bytes(raw_bytes, file_name=""):
        """从文件原始字节中提取文本内容。

        按优先级尝试识别格式并提取：
        TXT/MD/CSV/JSON → UTF-8 解码
        PDF   → pdfplumber → PyPDF2
        DOCX  → python-docx
        DOC   → python-docx (尝试)
        PPTX  → python-pptx
        XLSX  → pandas → CSV 文本
        ZIP   → 列出文件清单（不提取）

        Args:
            raw_bytes: 文件的原始字节内容
            file_name: 文件名（用于推断格式）

        Returns:
            str — 提取的文本，失败返回 ""
        """
        import io, os

        ext = file_name.rsplit(".", 1)[-1].lower() if "." in file_name else ""

        # ── 1. 纯文本格式 ──
        if ext in ("md", "txt", "csv", "json", "xml", "html", "htm",
                   "py", "js", "ts", "tsx", "jsx", "yaml", "yml",
                   "toml", "ini", "cfg", "bat", "sh", "sql", "css",
                   "java", "go", "rs", "c", "cpp", "h", "r", "ipynb"):
            try:
                return raw_bytes.decode("utf-8-sig")
            except UnicodeDecodeError:
                pass
            try:
                return raw_bytes.decode("utf-8")
            except UnicodeDecodeError:
                pass

        # ── 2. PDF ──
        if ext == "pdf" or raw_bytes[:4] == b"%PDF":
            try:
                import pdfplumber
                with pdfplumber.open(io.BytesIO(raw_bytes)) as pdf:
                    texts = [p.extract_text() or "" for p in pdf.pages]
                result = "\n".join(texts).strip()
                if result:
                    return result
            except Exception:
                pass
            try:
                from PyPDF2 import PdfReader
                reader = PdfReader(io.BytesIO(raw_bytes))
                result = "\n".join(p.extract_text() or "" for p in reader.pages).strip()
                if result:
                    return result
            except Exception:
                pass

        # ── 3. DOCX (ZIP-based XML) ──
        if ext == "docx" or (raw_bytes[:2] == b"PK" and not ext in ("xlsx",)):
            try:
                from docx import Document
                doc = Document(io.BytesIO(raw_bytes))
                result = "\n".join(p.text for p in doc.paragraphs).strip()
                if result:
                    return result
            except Exception:
                pass

        # ── 4. PPTX (ZIP-based XML) ──
        if ext == "pptx":
            try:
                from pptx import Presentation
                prs = Presentation(io.BytesIO(raw_bytes))
                slides = []
                for i, slide in enumerate(prs.slides, 1):
                    slide_texts = []
                    for shape in slide.shapes:
                        if shape.has_text_frame:
                            for para in shape.text_frame.paragraphs:
                                t = para.text.strip()
                                if t:
                                    slide_texts.append(t)
                        if shape.has_table:
                            table = shape.table
                            for row in table.rows:
                                row_text = " | ".join(
                                    cell.text.strip() for cell in row.cells
                                )
                                if row_text.strip():
                                    slide_texts.append(row_text)
                    if slide_texts:
                        slides.append(f"[Slide {i}]\n" + "\n".join(slide_texts))
                result = "\n\n".join(slides).strip()
                if result:
                    return result
            except Exception:
                pass

        # ── 5. XLSX / XLS ──
        if ext in ("xlsx", "xls"):
            try:
                import pandas as pd
                df = pd.read_excel(io.BytesIO(raw_bytes), sheet_name=None)
                sheets = []
                for sname, sdf in df.items():
                    sheets.append(f"[Sheet: {sname}]")
                    sheets.append(sdf.to_csv(index=False))
                result = "\n".join(sheets).strip()
                if result:
                    return result
            except Exception:
                pass

        # ── 6. 通用 UTF-8 兜底 ──
        try:
            text = raw_bytes.decode("utf-8-sig")
            # 排除二进制垃圾：可打印字符字符占比 > 80% 才认为有效
            printable = sum(1 for c in text if c.isprintable() or c in "\n\r\t")
            if len(text) > 0 and printable / len(text) > 0.7:
                return text
        except Exception:
            try:
                text = raw_bytes.decode("utf-8", errors="replace")
                printable = sum(1 for c in text if c.isprintable() or c in "\n\r\t")
                if len(text) > 0 and printable / len(text) > 0.7:
                    return text
            except Exception:
                pass

        return ""

    # ── 数据准备: 下载文件 ──────────────────────────────

    def download_file(self, entry_id, output_path):
        """下载/导出 KB 中的单个条目到本地文件。

        根据 entry_type 自动选择策略：
        - page / 在线文档 → 读取内容保存为 .md（优先官方 content API，fallback → blocks）
        - file / 附件 → 通过 links.download 临时下载链接（60分钟有效）
        - folder → 不处理（请用 download_folder）
        - 其他 → 尝试读取内容保存为 .txt

        Returns:
            str | None — 成功返回 output_path，失败返回 None
        """
        detail = self.get_entry_detail(entry_id)
        if not detail:
            return None

        entry_type = detail.get("attributes", {}).get("entry_type", "")
        name = detail.get("attributes", {}).get("name", "untitled")

        os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)

        if entry_type == "page":
            # 在线文档 → 优先用官方 content API，fallback 到 blocks/children
            content = self.get_page_content(entry_id) or self.get_entry_content(entry_id)
            if not content:
                return None
            # 清洗 KB 内部引用标记：[UUID](@yunji) 等
            content = self._clean_kb_markers(content)
            if not output_path.lower().endswith(".md"):
                output_path += ".md"
            with open(output_path, "w", encoding="utf-8") as f:
                f.write(f"# {name}\n\n{content}")
            return output_path

        elif entry_type == "file":
            # 附件 → 通过 links.download 临时下载链接（60分钟有效）
            # 注意：下载 URL 是外部预签名 URL（file.lexiang-asset.com），不能拼接 base_url
            download_url = (detail.get("links", {}) or {}).get("download", "")
            if not download_url:
                download_url = detail.get("attributes", {}).get("download_url", "")
            if download_url:
                for attempt in range(3):
                    try:
                        file_resp = requests.get(download_url, timeout=60)
                        if file_resp.status_code == 200:
                            ext = self._guess_extension(name) or self._ext_from_url(download_url)
                            if ext and not output_path.lower().endswith(f".{ext}"):
                                output_path += f".{ext}"
                            os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
                            with open(output_path, "wb") as f:
                                f.write(file_resp.content)
                            return output_path
                    except requests.exceptions.RequestException:
                        if attempt == 2:
                            return None
                        time.sleep(1)
            return None

        elif entry_type == "smartsheet":
            return self.export_smartsheet(entry_id, output_path)

        elif entry_type == "folder":
            return None

        else:
            content = self.get_page_content(entry_id) or self.get_entry_content(entry_id)
            if content:
                content = self._clean_kb_markers(content)
                if not output_path.lower().endswith(".txt"):
                    output_path += ".txt"
                with open(output_path, "w", encoding="utf-8") as f:
                    f.write(f"# {name}\n\n{content}")
                return output_path
            return None

    def download_folder(self, folder_id, output_dir, recursive=True, skip_errors=True, space_id=None):
        """下载整个文件夹及其子文件夹到本地目录。

        按照 KB 中原始目录结构保存，page 导出为 .md，file 尝试下载。

        Args:
            folder_id: 文件夹 entry_id
            output_dir: 本地输出根目录
            recursive: 是否递归下载子文件夹（默认 True）
            skip_errors: 单个条目失败是否继续（默认 True）
            space_id: 知识库空间 ID（默认取知识库空间）

        Returns:
            dict: {
                "total": int,       # 条目总数
                "ok": int,          # 成功数
                "fail": int,        # 失败数
                "skipped": int,     # 跳过数
                "items": [dict],    # 每个条目的下载结果
            }
        """
        results = {"total": 0, "ok": 0, "fail": 0, "skipped": 0, "items": []}

        # 拉取条目列表
        if recursive:
            entries = self.list_folder_recursive(folder_id, space_id=space_id)
        else:
            children = self.list_folder(folder_id, space_id=space_id)
            entries = [{
                "name": c["name"], "id": c["id"],
                "entry_type": c["entry_type"],
                "updated_at": c.get("updated_at", ""),
                "rel_path": c["name"],
                "has_children": c.get("has_children", False),
            } for c in children]

        results["total"] = len(entries)

        for entry in entries:
            eid = entry["id"]
            ename = entry["name"]
            etype = entry["entry_type"]
            rel_path = entry.get("rel_path", ename)

            if etype == "folder":
                item = {"name": ename, "path": os.path.join(output_dir, rel_path), "status": "skipped", "reason": "folder, not downloaded"}
                results["skipped"] += 1
                results["items"].append(item)
                continue

            out_path = os.path.join(output_dir, rel_path)
            ok = self.download_file(eid, out_path)
            if ok:
                item = {"name": ename, "path": ok, "status": "ok", "reason": ""}
                results["ok"] += 1
            else:
                item = {"name": ename, "path": out_path, "status": "fail", "reason": f"download failed ({etype})"}
                results["fail"] += 1
                if not skip_errors:
                    break
            results["items"].append(item)

        return results

    # ── 资料查找·补全: 读取 page 全文 ────────────────────

    def get_entry_content(self, entry_id):
        """读取在线文档（page 类型）的全文内容。

        Returns:
            str | "" — markdown 格式文本
        """
        resp = self._request("GET", f"/cgi-bin/v1/kb/page/entries/{entry_id}/blocks/children",
                             extra_headers=self._staff_headers(), timeout=15)
        if not resp or resp.status_code != 200:
            return ""

        data = resp.json()
        blocks = data.get("data", {}).get("blocks", [])
        return self._blocks_to_text(blocks)

    # block_type → block 数据字段名映射
    _BT_KEY_MAP = {
        "p": "text", "h1": "heading1", "h2": "heading2",
        "h3": "heading3", "h4": "heading4", "h5": "heading5",
        "code": "code", "image": "image",
    }

    def _blocks_to_text(self, blocks):
        """将 block 列表转换为 markdown 文本。"""
        parts = []
        for b in blocks:
            bt = b.get("block_type", "")
            key = self._BT_KEY_MAP.get(bt, bt)
            content_obj = b.get(key, {})
            elements = content_obj.get("elements", [])
            text = "".join(e.get("text_run", {}).get("content", "") for e in elements)
            if bt.startswith("h") and bt[1:].isdigit():
                text = f"\n{'#' * int(bt[1:])} {text}" if text else ""
            elif bt == "code":
                text = f"```\n{text}\n```" if text else ""
            elif bt == "p":
                text = f"\n{text}" if text else ""
            elif bt == "image":
                # image block 没有文本，可选输出占位
                text = ""
            parts.append(text)
        return "".join(parts)

    def get_entry_detail(self, entry_id):
        """获取节点完整元数据。"""
        resp = self._request("GET", f"/cgi-bin/v1/kb/entries/{entry_id}",
                             extra_headers=self._staff_headers(), timeout=15)
        if resp and resp.status_code == 200:
            return resp.json().get("data", {})
        return {}

    # ── smartsheet 导出（通过 AI 搜索提取数据） ──────────

    def export_smartsheet(self, entry_id, output_path, headers=None):
        """从 smartsheet 条目中提取数据并保存为 CSV。

        乐享 API 未提供 smartsheet 专用导出端点，故此方法通过 AI 搜索
        间接提取数据内容（已验证可获取完整分号分隔值）。

        Args:
            entry_id: smartsheet 条目 ID
            output_path: 输出路径（自动添加 .csv 后缀）
            headers: 可选列名列表。未提供时尝试从 AI 结果中提取表头，
                     提取失败则自动编号 col1, col2, ...

        Returns:
            str | None — 成功返回实际写入路径，失败返回 None
        """
        import re

        detail = self.get_entry_detail(entry_id)
        name = (detail.get("attributes", {}) or {}).get("name", "untitled")

        # AI 搜索提取数据（多个结果用于不同用途）
        results = self.search_by_ai(name, top_n=5)
        candidates = [r for r in results if name in r.get("title", "")]
        if not candidates:
            return None

        # 最短内容 = 纯数据；较长内容可能含表头行
        best = min(candidates, key=lambda r: len(r.get("content", "")))
        content = best.get("content", "")

        # 去掉 markdown 标题行
        content = re.sub(r'^#{1,6}\s+.*$', '', content, flags=re.MULTILINE).strip()
        if not content:
            return None

        # 分号分隔值拆解
        parts = [p.strip() for p in content.split(";") if p.strip()]
        if not parts:
            return None

        # 确保输出路径有 .csv 后缀
        if not output_path.lower().endswith(".csv"):
            output_path += ".csv"
        os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)

        # ── 确定表头 ──
        if not headers:
            # 尝试从较长 AI 结果中提取带表头的行
            if len(candidates) >= 2:
                longer = candidates[1]  # 第二短的内容可能含表头行
                longer_content = longer.get("content", "")
                longer_content = re.sub(r'^#{1,6}\s+.*$', '', longer_content, flags=re.MULTILINE).strip()
                longer_parts = [p.strip() for p in longer_content.split(";") if p.strip()]
                # 表头行通常在第一组重复出现之前，且字段值看起来像列名（非数字开头、含中文）
                fc = self._detect_field_count(parts)
                if fc and len(longer_parts) > len(parts):
                    extra = len(longer_parts) - len(parts)
                    if 0 < extra <= fc:
                        candidate_headers = longer_parts[:extra]
                        # 简单校验：表头不应纯数字或日期格式
                        if all(not re.match(r'^\d+[\d\-/.]*$', h) for h in candidate_headers):
                            headers = candidate_headers

            # 仍未获取 → 自动编号
            if not headers:
                fc = self._detect_field_count(parts)
                if fc:
                    headers = [f"col{i+1}" for i in range(fc)]

        # ── 写入 CSV ──
        if headers and len(headers) > 0:
            n = len(headers)
            rows = [parts[i:i+n] for i in range(0, len(parts), n)]
            # 补齐最后一行不足的字段
            if rows and len(rows[-1]) < n:
                rows[-1] = rows[-1] + [""] * (n - len(rows[-1]))
            with open(output_path, "w", encoding="utf-8-sig", newline="") as f:
                writer = csv.writer(f)
                writer.writerow(headers)
                for row in rows:
                    writer.writerow(row)
        else:
            # 兜底：单行输出
            with open(output_path, "w", encoding="utf-8-sig", newline="") as f:
                f.write(";".join(parts))

        return output_path

    @staticmethod
    def _detect_field_count(parts):
        """通过分析分号分隔值中的重复模式，自动检测每行字段数。

        原理：在纵向拼接的多行数据中，同一列的取值类型相似，
        但更重要的是——同一行中如果有唯一 ID 列（如学号），
        则相邻 ID 值之间的字段间隔即为每行列数。

        回退策略：如果 fields ≥ 10，尝试按常见除数 3~30 匹配。
        """
        if len(parts) <= 1:
            return len(parts)

        # 策略1：找重复值的位置间隔作为字段数候选
        val_positions = {}
        for i, val in enumerate(parts):
            val_positions.setdefault(val, []).append(i)

        gap_counts = {}
        for positions in val_positions.values():
            if len(positions) >= 2:
                for j in range(len(positions) - 1):
                    gap = positions[j + 1] - positions[j]
                    if 3 <= gap <= 50:  # 合理字段数范围
                        gap_counts[gap] = gap_counts.get(gap, 0) + 1

        if gap_counts:
            return max(gap_counts, key=gap_counts.get)

        # 策略2：回退 — 如果总数能被某个常见值整除
        total = len(parts)
        for n in range(3, min(31, total + 1)):
            if total % n == 0:
                return n

        return len(parts)  # 兜底：单行

    # ── 按名称精确查找 ─────────────────────────────────

    def find_entry(self, name, space_id=None, entry_type=None):
        """在指定知识库空间中按名称精确查找条目。

        先查根目录直接子条目（list_folder），未命中再用关键词搜索兜底。

        Args:
            name: 条目名称（精确匹配，大小写敏感）
            space_id: 知识库空间 ID（默认取学科知识库）
            entry_type: 可选，限定条目类型（"file"/"page"/"smartsheet"/"folder"）

        Returns:
            dict | None — {name, id, entry_type, updated_at, has_children} 或 None
        """
        sid = space_id or self.space_id

        # 策略1：从各空间根目录直接查找（更可靠）
        for sp_key, sp in self._spaces.items():
            if sp.get("space_id") == sid:
                children = self.list_folder(sp.get("root_entry_id", ""), space_id=sid)
                for c in children:
                    if c["name"] == name:
                        if entry_type is None or c["entry_type"] == entry_type:
                            return c
                break

        # 策略2：关键词搜索兜底（覆盖面更广）
        results = self.search_by_keyword(name, limit=50, space_id=sid)
        for r in results:
            if r["name"] == name:
                if entry_type is None or r["entry_type"] == entry_type:
                    return r
        return None

    # ── 图片下载 ────────────────────────────────────────

    def get_page_image_blocks(self, entry_id):
        """获取 page 条目中的所有图片 block。

        通过 blocks/children API 获取，返回 image block 列表，
        每个包含 file_id / width / height 等元数据。

        Args:
            entry_id: page 节点 ID

        Returns:
            list[dict]: [{"file_id": "...", "width": 800, "height": 424}, ...]
        """
        resp = self._request("GET",
            f"/cgi-bin/v1/kb/page/entries/{entry_id}/blocks/children",
            extra_headers=self._staff_headers(), timeout=15)
        if not resp or resp.status_code != 200:
            return []

        try:
            blocks = resp.json().get("data", {}).get("blocks", [])
            return [b["image"] for b in blocks if b.get("block_type") == "image"]
        except Exception:
            return []

    def get_image_download_url(self, file_id):
        """获取 KB 图片的预签名下载 URL。

        端点: GET /cgi-bin/v1/kb/files/{file_id}
        返回 links.download 中的预签名 S3 URL（有效期约 1 小时）。

        Args:
            file_id: 图片文件 ID（来自 /kb_files/{file_id} 或 image block）

        Returns:
            str | "" — 预签名下载 URL
        """
        resp = self._request("GET", f"/cgi-bin/v1/kb/files/{file_id}",
                             extra_headers=self._staff_headers(), timeout=10)
        if not resp or resp.status_code != 200:
            return ""

        try:
            return resp.json().get("data", {}).get("links", {}).get("download", "")
        except Exception:
            return ""

    def download_image(self, file_id, output_path):
        """下载 KB 图片到本地。

        组合 get_image_download_url + HTTP GET 下载。

        Args:
            file_id: 图片文件 ID
            output_path: 本地输出路径（含文件名和扩展名）

        Returns:
            str | None — 成功返回 output_path，失败返回 None
        """
        import requests as req

        url = self.get_image_download_url(file_id)
        if not url:
            return None

        try:
            os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
            resp = req.get(url, timeout=30)
            if resp.status_code == 200:
                with open(output_path, "wb") as f:
                    f.write(resp.content)
                return output_path
        except Exception:
            pass
        return None

    def extract_markdown_images(self, markdown_text):
        """从 markdown 文本中提取所有图片引用。

        匹配 /kb_files/{file_id} 格式的相对路径。

        Args:
            markdown_text: markdown 文本

        Returns:
            list[str]: file_id 列表（去重）
        """
        import re
        pattern = r'!\[.*?\]\(/kb_files/([a-f0-9]+)\)'
        matches = re.findall(pattern, markdown_text)
        # 去重保序
        seen = set()
        result = []
        for m in matches:
            if m not in seen:
                seen.add(m)
                result.append(m)
        return result

    # ── 辅助 ────────────────────────────────────────────

    @staticmethod
    def _clean_kb_markers(text):
        """清洗 KB 内部引用标记，如 [UUID](@yunji)、[UUID](@kb) 等。

        KB API 返回的 markdown 原文中散布着形如 [c20a88717dcf...](@yunji)
        的内部引用锚点。这些标记对教材编写无意义，需在落盘前清除。

        清洗规则：
        1. 独立成行的 [UUID](@tag) 标记 → 删除整行
        2. 行尾/行中的 [UUID](@tag) 标记 → 删除标记本身，保留换行和标题层级
        3. 合并多余空行
        """
        import re
        # 匹配 [32位hex](@任意字母) 格式
        marker = r'\[[a-f0-9]{32}\]\(@[a-z]+\)'
        # 1. 独立成行的标记（整行只有这个标记）→ 删除整行
        text = re.sub(r'^[^\S\n]*' + marker + r'[^\S\n]*$', '', text, flags=re.MULTILINE)
        # 2. 嵌在行中/行尾的标记 → 只删标记本身，前后的空白（非换行）也一并清理
        text = re.sub(r'[^\S\n]*' + marker + r'[^\S\n]*', '', text)
        # 3. 清理多余空行（连续 3 个以上换行 → 2 个）
        text = re.sub(r'\n{3,}', '\n\n', text)
        return text.strip()

    @staticmethod
    def _guess_extension(filename):
        if "." in filename:
            return filename.rsplit(".", 1)[-1].lower()
        return ""

    @staticmethod
    def _ext_from_url(url):
        """从预签名 URL 的 content-disposition / filename 参数中提取扩展名。
        
        URL 中通常包含:
        response-content-disposition=attachment; filename="xxx.docx"; filename*=utf-8''xxx.docx
        （value 经过 URL encode，需先 decode 再提取）
        """
        import re, urllib.parse
        # 提取 response-content-disposition 的 value 并 decode
        m = re.search(r'response-content-disposition=([^&]+)', url, re.IGNORECASE)
        if m:
            val = urllib.parse.unquote(m.group(1))
            m2 = re.search(r'filename="([^"]+)"', val, re.IGNORECASE)
            if m2:
                fname = m2.group(1)
                if "." in fname:
                    return fname.rsplit(".", 1)[-1].lower()
        return ""

    def format_citation(self, ai_result, max_summary_len=200):
        """将 AI 搜索结果格式化为 资料来源.md 的引用行。

        包含 entry_id，下游可调 get_entry_content(entry_id) 读取全文。

        Args:
            ai_result: search_by_ai 返回的单条结果 dict
            max_summary_len: 摘要截断长度

        Returns:
            str — 符合资料来源.md 格式的引用行
        """
        title = ai_result.get("title", "无标题")
        url = ai_result.get("url", "")
        summary = ai_result.get("content", "")[:max_summary_len].replace("\n", " ")
        if len(ai_result.get("content", "")) > max_summary_len:
            summary += "…"
        # 从 URL 提取 entry_id: https://lexiangla.com/pages/{entry_id}
        entry_id = url.rsplit("/", 1)[-1] if "/" in url else ""
        return f"- 来源：[知识库] {title}，{url}\n  entry_id: {entry_id}\n  摘要：{summary}"


# ═══════════════════════════════════════════════════════════
# 便捷工厂函数
# ═══════════════════════════════════════════════════════════

_default_instance = None
_default_lock = threading.Lock()


def get_kb(config_path=None):
    """获取单例 LexiangKB 实例。"""
    global _default_instance
    if _default_instance is None:
        with _default_lock:
            if _default_instance is None:
                _default_instance = LexiangKB(config_path)
    return _default_instance


if __name__ == "__main__":
    kb = get_kb()
    print(f"LexiangKB OK — 已配置空间: {list(kb._spaces.keys())}")
