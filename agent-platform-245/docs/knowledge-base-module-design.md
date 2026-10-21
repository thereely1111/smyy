# 知识库管理模块 — 技术方案 / 数据模型 / API 契约

> 状态：已实现（后端 rag / external / text 三路线与前端页面均已上线）
> 归属：本仓库 Java（Spring）后端实现；前端沿用现有 `knowledge-bases/*` 模块并按本契约扩展
> 关联：规格说明（rag / external / text 三路线、文件系统 + 相对路径、用户/群组授权）

---

## 0. 模块构成

- **前端**：`pages/knowledge/`、`components/dialog/Create|EditKnowledgeBaseDialog`、`api/knowledgeBase.ts`、`hooks/useKnowledgeBases`；`KnowledgeBaseView { id, name, description, type, owner, embedding_model_config, external_*, editable, permission }`。
- **后端**：`KnowledgeBaseController`（`/api/knowledge-bases`，平台创建走 `@RequirePermission("knowledge:create")`）+ `Group` / `KnowledgeBaseGrant` / `UserGroup` 实体承载群组与授权。
- **存储**：`application.yml` 的 `agent-platform.workspace-root` 之外独立 KB 根目录，DB 只存相对路径。

**沿用的现有先例（保持一致性）：**
- `WorkspaceGenerator`：绝对根目录 + 相对路径 + `startsWith(root)` 路径穿越防护。KB vector 方案的存储直接复用同一套守卫逻辑。
- `AgentManagementController` + `web.dto.AgentDtos`：Controller 用 `@RequestMapping("/api/...")`，DTO 以嵌套 record 分组。
- `CommonDtos.PageResponse<T>`：但前端 KB 列表用的是**自定义** `{ knowledgeBases:[], total }` 包装（非 PageResponse）——契约按前端既有形状对齐，不强行套 PageResponse。

---

## 1. 范围与三条路线

### 1.1 rag（本地语义向量检索方案）
- 文档文件存**文件系统**，根目录从配置读取，DB 只存**相对路径**（迁移友好）。
- 分块与向量存 **Postgres（pgvector）**，检索走 SQL 余弦相似度。
- 嵌入模型复用平台既有 embedding 体系（前端 `useKbEmbeddingModels` 已暴露 providers）。
- **必须向量化**：文件经「解析 → 分块 → 嵌入（pgvector）→ 入库」异步管道，否则语义检索无从谈起。

### 1.2 external（外部 RAG 平台：RAGFlow / Dify / 阿里云百炼）
- 本平台**只存连接配置 + 加密密钥**，不托管文档与向量。
- 检索**委托外部平台 API**，结果归一化为统一 `VectorSearchResult` 返回前端。

### 1.3 text（文本检索：grep / cat，不做向量化）
- **不做向量化、不分块、不建索引**。文档文件与 rag 一样存**文件系统**（DB 只存相对路径），但检索不在入库阶段做，而是**在查询时由 agent 通过 grep / cat 工具直接读取文件内容**。
- 适用场景：结构化/纯文本知识、代码库、日志、FAQ 等适合关键字/全文匹配、且无需语义召回的场景；或希望把"检索"交给 agent 工具调用灵活性（多文件拼接、正则、上下文窗口内裁剪）的场景。
- 与 rag 的差异仅在**检索路径**：text 无 `embedding_model_config`、`knowledge_base_chunks` 表与异步向量管道；文档上传后**立即 `ready`**（无 parsing/chunking/indexing 状态）。
- 检索两种呈现方式（见 §7.3）：
  1. **agent 运行时**：把 grep / cat 工具的作用域限定在该 KB 的解析目录（与 `WorkspaceGenerator` 同一守卫），由 agent 在对话中按需调用工具完成检索。
  2. **服务端 grep 兜底**：`POST /search` 在 KB 目录内对文件执行 grep，返回命中行（`TextSearchResult`），供非 agent / 预览场景使用。

---

## 2. 模块结构（Java 包）

```
com.tipdm.agentplatform
├── config
│   └── KnowledgeBaseProperties          // knowledge-base-root 注入（@Value，复用 WorkspaceGenerator 模式）
├── knowledge                            // 新增子域
│   ├── domain
│   │   ├── KnowledgeBase.java
│   │   ├── KnowledgeBaseDocument.java
│   │   ├── KnowledgeBaseChunk.java       // rag 专用：分块 + 向量存储（text 类型不涉及）
│   │   ├── KnowledgeBaseGrant.java       // 用户/群组授权（三类型通用）
│   │   ├── Group.java                    // 内置轻量群组（可选，见 §5）
│   │   └── UserGroup.java
│   ├── repository
│   │   ├── KnowledgeBaseRepository.java
│   │   ├── KnowledgeBaseDocumentRepository.java
│   │   ├── KnowledgeBaseChunkRepository.java
│   │   ├── KnowledgeBaseGrantRepository.java
│   │   └── GroupRepository.java
│   ├── service
│   │   ├── KnowledgeBaseService.java      // 编排 + 授权校验（三类型通用）
│   │   ├── KnowledgeStorageService.java   // vector/file 文件系统（相对路径 + 防穿越）
│   │   ├── KnowledgeRetrievalService.java // rag: pgvector；external: 委托；text: grep 兜底
│   │   └── ExternalRagClient.java         // RAGFlow/Dify/百炼 适配
│   └── web
│       ├── KnowledgeBaseController.java   // /api/knowledge-bases
│       ├── dto/KnowledgeBaseDtos.java
│       └── error/KnowledgeBaseException.java
└── （复用）service/WorkspaceGenerator.java // 路径安全工具
```

---

## 3. 配置（application.yml）

```yaml
agent-platform:
  # 复用 workspace-root 的 ${ENV:默认值} 风格
  knowledge-base-root: ${AGENT_PLATFORM_KB_ROOT:${agent-platform.workspace-root}/knowledge_bases}
  # 外部 RAG 密钥加密密钥（AES-GCM），生产务必走环境变量
  knowledge-base-secret-key: ${AGENT_PLATFORM_KB_SECRET:change-me-in-production-0123456789}
```

注入方式与 `WorkspaceGenerator` 一致：`@Value("${agent-platform.knowledge-base-root}")` → `Paths.get(root).toAbsolutePath().normalize()`。

---

## 4. 数据模型（Flyway 迁移）

> 最新迁移为 `V21`，新知识库从 **V22** 起。

### V22__knowledge_base_core.sql
```sql
CREATE TABLE knowledge_bases (
    id                  UUID PRIMARY KEY,
    name                VARCHAR(200) NOT NULL,
    description         TEXT,
    type                VARCHAR(16) NOT NULL CHECK (type IN ('rag','external','text')),
    owner_user_id       UUID NOT NULL REFERENCES users(id),
    -- vector 路线
    embedding_model_config JSONB,                      -- 创建时锁定，维度随模型固定
    storage_root_rel    VARCHAR(512),                   -- 相对 knowledge-base-root 的目录，如 {kb_id}
    -- external 路线
    external_provider   VARCHAR(32) CHECK (external_provider IN ('ragflow','dify','bailian')),
    external_endpoint   VARCHAR(512),
    external_api_key_enc TEXT,                          -- AES-GCM 加密，绝不返回前端
    external_extra      JSONB,                          -- 各平台额外参数
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ( (type='rag'    AND embedding_model_config IS NOT NULL AND storage_root_rel IS NOT NULL)
         OR (type='external' AND external_provider     IS NOT NULL AND external_endpoint IS NOT NULL)
         OR (type='text'    AND storage_root_rel       IS NOT NULL) )  -- text 仅文件系统，无向量/外部配置
);

CREATE TABLE knowledge_base_documents (
    id                  UUID PRIMARY KEY,
    knowledge_base_id   UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    filename            VARCHAR(255) NOT NULL,
    relative_path       VARCHAR(512) NOT NULL,          -- 相对 storage_root（vector）或根
    content_type        VARCHAR(128),
    size                BIGINT,
    status              VARCHAR(16) NOT NULL
                          CHECK (status IN ('pending','parsing','chunking','indexing','ready','error')),
    chunk_count         INT NOT NULL DEFAULT 0,
    error               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_kb_docs_kb_status ON knowledge_base_documents(knowledge_base_id, status);

-- 文档状态说明：
--   vector: pending → parsing → chunking → indexing → ready（异步向量管道，状态机对齐前端）
--   external: 文档托管在外部平台，本表仅作占位，状态恒为 ready
--   file   : 无解析/分块/索引，上传完成即 ready；chunk_count 恒为 0、无 knowledge_base_chunks 记录
```

### V23__knowledge_base_authorization.sql（授权表 + 内置轻量群组，见 §5）
```sql
-- 知识库授权（用户/群组对知识库的 READ/WRITE）；与群组表同属 P2
CREATE TABLE knowledge_base_grants (
    id                  UUID PRIMARY KEY,
    knowledge_base_id   UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    principal_type      VARCHAR(8) NOT NULL CHECK (principal_type IN ('USER','GROUP')),
    principal_id        UUID NOT NULL,                  -- users.id 或 groups.id
    permission          VARCHAR(8) NOT NULL CHECK (permission IN ('READ','WRITE')),
    granted_by          UUID NOT NULL REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 每个主体（用户或群组）对同一知识库仅一条授权，权限取最大（WRITE > READ）
    UNIQUE (knowledge_base_id, principal_type, principal_id)
);
CREATE INDEX idx_kb_grants_principal ON knowledge_base_grants (principal_type, principal_id);
CREATE INDEX idx_kb_grants_kb ON knowledge_base_grants (knowledge_base_id);

CREATE TABLE groups (
    id          UUID PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    created_by  UUID NOT NULL REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE user_groups (
    id          UUID PRIMARY KEY,
    group_id    UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (group_id, user_id)
);
CREATE INDEX idx_user_groups_user ON user_groups (user_id);
CREATE INDEX idx_user_groups_group ON user_groups (group_id);
```

### V24__knowledge_base_pgvector.sql（vector 检索）
```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE knowledge_base_chunks (
    id                  UUID PRIMARY KEY,
    document_id         UUID NOT NULL REFERENCES knowledge_base_documents(id) ON DELETE CASCADE,
    knowledge_base_id   UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    chunk_index         INT NOT NULL,
    content             TEXT NOT NULL,
    embedding           vector(1536) NOT NULL,         -- 维度见 §9 决策点（1536 固定）
    metadata            JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_kb_chunks_kb ON knowledge_base_chunks(knowledge_base_id);
-- 余弦距离索引（先在线上建表后 CREATE INDEX 使用 ivfflat）
CREATE INDEX idx_kb_chunks_embedding ON knowledge_base_chunks
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
```

**ER 关系（文本示意）：**
```
users 1──* knowledge_bases(owner_user_id)
users 1──* knowledge_base_grants(principal_type=USER)
groups 1──* knowledge_base_grants(principal_type=GROUP)
users *──* groups (user_groups)
knowledge_bases 1──* knowledge_base_documents
knowledge_base_documents 1──* knowledge_base_chunks
knowledge_bases 1──* knowledge_base_grants
```

---

## 5. 授权模型

- **owner**：`owner_user_id` 默认拥有 `WRITE`。
- **管理员**：`User.role = ADMIN` 对所有 KB 可读写（复用现有角色）。
- **显式授权**：`knowledge_base_grants` 记录 `USER`/`GROUP` 的 `READ`/`WRITE`。
- **群组展开**：用户对某 KB 的有效权限 = 直接授权 ∪ 所属群组授权（取最大：`WRITE > READ`）。
- **`editable` 派生**：`KnowledgeBaseView.editable = 当前用户对该 KB 有 WRITE`（owner / admin / WRITE 授权）。
- **列表可见性**：`GET /api/knowledge-bases/` 只返回「owner 或 拥有 READ 授权（直接或经群组）」的 KB。
- **安全**：`external_api_key_enc` 永不出现在任何响应中；`external_endpoint` 仅对 owner/admin 可见。

---

## 6. 存储（vector / file 共用）

`KnowledgeStorageService` 职责：
- `resolve(root, storageRootRel, relativePath)` → 拼绝对路径后**校验 `absolute.startsWith(kbRoot)`**，拒绝穿越（复用 `WorkspaceGenerator` 守卫）。
- **上传**：文件落 `{kbRoot}/{kbId}/{docId}/{filename}`，DB 记 `relative_path = {docId}/{filename}`（vector / file 完全一致）。
- **删除**：删文件 + 级联删 `documents`；vector 额外级联删 `chunks`（file 无 chunks）。
- **迁移友好**：DB 仅存相对路径；换机器/换盘只需改 `knowledge-base-root` 配置，文件整体拷贝即可。
- **text 与 rag 的差异**：仅在于"是否跑向量管道"，文件落盘与路径守卫逻辑完全相同，因此 `KnowledgeStorageService` 三类型通用，无需按 type 分支。

---

## 7. 检索

- **vector**：`KnowledgeRetrievalService`
  - 异步管道：`pending → parsing → chunking → embedding(pgvector) → ready`（状态机对齐前端 `KnowledgeDocumentStatus`）。
  - 查询：问题向量化 → `ORDER BY embedding <=> :q LIMIT :top_k`（pgvector 余弦）。
  - 结果归一化为 `VectorSearchResult { score, document_id, chunk }`。
- **external**：`ExternalRagClient` 按 `external_provider` 适配：
  - RAGFlow `/api/v1/retrieval`、Dify `/v1/datasets/{id}/retrieval`、百炼对应检索接口。
  - 用存储的 `external_endpoint` + 解密 `external_api_key_enc` 调用；结果映射到统一结构。
- **text**：**无向量化**，检索由 grep / cat 工具完成（见 §7.3）。

### 7.3 text 检索：grep / cat 工具

text 类型把"知识检索"交给工具调用，而非预建索引。两条互补路径：

1. **agent 运行时（主路径）**
   - agent 运行时把 **grep / cat 工具的作用域限定在该 KB 的解析目录** `{kbRoot}/{kbId}`，复用 `WorkspaceGenerator` 的 `startsWith` 守卫防止越界读其他 KB / 系统文件。
   - agent 在对话中按需调用 `grep`（正则/关键字命中）与 `cat`（读取命中文件或切片），自行决定上下文裁剪与多文件拼接。
   - 这要求 agent 运行时在挂载 KB 时，将 `storage_root_rel` 解析出的绝对目录作为工具沙箱根传入；授权（READ）即代表"允许 agent 对该目录使用 grep/cat"。

2. **服务端 grep 兜底（次路径，非 agent / 预览场景）**
   - `POST /api/knowledge-bases/{id}/search` 在 KB 目录内对文件执行 grep，返回命中行：
     ```ts
    interface TextSearchResult {
      file: string;          // 相对 storage_root_rel 的路径
      line: number;          // 命中行号
      content: string;       // 命中行原文
      context?: string[];    // 可选：上下几行
    }
    ```
   - 该端点的结果结构与 rag 的 `VectorSearchResult` 不同（无 `score`），前端按 `type` 分支渲染。

> 注意：text 类型**不创建 `knowledge_base_chunks` 记录**，也**不消耗 embedding 推理**，因此无向量化成本、入库即时可用。



---

## 8. API 契约（与现有前端对齐 + 扩展）

> 端点基路径统一为 **`/api/knowledge-bases`**（Java/Spring 风格 kebab-case；其他资源单字节路径如 `/api/agents` 不受影响）。
> 前端 `knowledgeBase.ts` 统一走 `/api/knowledge-bases`。

响应包装沿用前端既有形状（**非** PageResponse）：
`ListKnowledgeBasesResponse { knowledgeBases: KnowledgeBaseView[]; total: number }`

> **JSON 字段命名约定（重要）**：后端 DTO 字段一律 **camelCase**（Spring Boot Jackson 默认），即 `externalProvider` / `externalEndpoint` / `externalApiKey` / `externalExtra` / `embeddingModelConfig` / `documentId` / `topK` / `chunkCount` / `createdAt`。这是 Java 后端唯一线上契约——**snake_case 不参与序列化**，接口模块的 DTO 不使用 `@JsonNaming(SnakeCaseStrategy)` / `@JsonProperty` 显式标注。

### 端点总表

| Method | Path | 请求 | 响应 | 说明 |
|---|---|---|---|---|
| GET | `/api/knowledge-bases/` | `?search&page&size` | `ListKnowledgeBasesResponse` | 按可见性过滤 |
| GET | `/api/knowledge-bases/embedding_models` | — | `ListKbEmbeddingModelsResponse` | 复用现有 embedding providers |
| GET | `/api/knowledge-bases/middleware/parameters_schema` | — | schema | 复用现有 |
| GET | `/api/knowledge-bases/supported_content_types` | — | types | 复用现有 |
| POST | `/api/knowledge-bases/` | `CreateKnowledgeBaseRequest` | `CreateKnowledgeBaseResponse { knowledge_base_id }` | 见下 |
| GET | `/api/knowledge-bases/{id}` | — | `KnowledgeBaseView` | |
| PATCH | `/api/knowledge-bases/{id}` | `UpdateKnowledgeBaseRequest` | `KnowledgeBaseView` | 仅可变字段 |
| DELETE | `/api/knowledge-bases/{id}` | — | 204 | 级联删文档/分块/授权 |
| GET | `/api/knowledge-bases/{id}/documents` | `?page&size` | `ListKnowledgeDocumentsResponse { documents, total }` | |
| POST | `/api/knowledge-bases/{id}/documents` | multipart `file` | `UploadKnowledgeDocumentResponse { document_id, filename, status }` | vector 触发异步解析管道；file/external 直接 ready |
| GET | `/api/knowledge-bases/{id}/documents/status?ids=` | — | `ListKnowledgeDocumentStatusResponse { items }` | |
| DELETE | `/api/knowledge-bases/{id}/documents/{docId}` | — | 204 | |
| POST | `/api/knowledge-bases/{id}/search` | `SearchKnowledgeBaseRequest { query, top_k? }` | `SearchKnowledgeBaseResponse` | rag: pgvector（`VectorSearchResult`）；external: 委托；text: 目录内 grep（`TextSearchResult`） |
| GET | `/api/knowledge-bases/{id}/grants` | — | `KnowledgeBaseGrantsView { grants: GrantView[], groups: GroupSummary[] }` | 授权列表 |
| POST | `/api/knowledge-bases/{id}/grants` | `GrantRequest { principal_type, principal_id, permission }` | `GrantView` | 仅 owner/admin |
| DELETE | `/api/knowledge-bases/{id}/grants/{grantId}` | — | 204 | 仅 owner/admin |

### `KnowledgeBaseView`（扩展后，对齐前端 + 新增）
```ts
interface KnowledgeBaseView {
  id: string;
  name: string;
  description: string;
  type: 'rag' | 'external' | 'text';
  owner: { id: string; name: string };          // 新增
  embeddingModelConfig?: EmbeddingModelConfig; // rag
  externalProvider?: 'ragflow' | 'dify' | 'bailian'; // external，owner/admin 可见
  externalEndpoint?: string;                    // external，仅 owner/admin 可见（无密钥）
  editable: boolean;                            // 派生自 WRITE 权限
  permission: 'READ' | 'WRITE' | 'NONE';        // 当前用户有效权限
  createdAt: string;
  updatedAt: string;
}
```

### `CreateKnowledgeBaseRequest`（按 type 分支）
```ts
interface CreateKnowledgeBaseRequest {
  name: string;
  description?: string;
  type: 'rag' | 'external' | 'text';
  // rag 必填
  embeddingModelConfig?: EmbeddingModelConfig;
  // external 必填
  externalProvider?: 'ragflow' | 'dify' | 'bailian';
  externalEndpoint?: string;
  externalApiKey?: string;                       // 仅在创建/更新时接收，加密落库，绝不回显
  externalExtra?: Record<string, unknown>;
  // text：无必填项（仅文件系统，不向量化、不配置外部平台）
}

> **`/search` 响应按 `type` 分支**（`SearchKnowledgeBaseResponse.results` 的元素类型随类型变化）：
> - rag：`VectorSearchResult { score, documentId, chunk }`（余弦相似度排序）
> - external：归一化后的 `VectorSearchResult`（来自外部平台）
> - text：`TextSearchResult { file, line, content, context? }`（目录内 grep 命中行，无 score）
>
> ```ts
> interface TextSearchResult {
>   file: string;        // 相对 storage_root_rel 的路径
>   line: number;        // 命中行号
>   content: string;     // 命中行原文
>   context?: string[];  // 可选：上下几行
> }
> ```
> 前端渲染需依据 `KnowledgeBaseView.type` 走不同分支。
```

---

## 9. 关键设计决策

1. **vector 检索引擎**：采用 **pgvector（复用 Postgres）**。vector 类型必须向量化（解析→分块→嵌入→入库），否则语义检索无从谈起；备选方案（进程内向量：无持久/规模；委托 AgentScope Python 服务：与"本仓库 Java"决策冲突）不采用。
2. **pgvector 维度固定**：pgvector 列维度是**表级固定**。平台统一嵌入维度为 **1536**，创建 KB 时校验其 embedding 模型维度一致；异维度需求留待后续动态建表方案。
3. **群组来源**：内置轻量 `groups` 表；未接入 OAuth `userinfo` 的群组。
4. **外部 RAG 密钥**：用 KB 内 `external_api_key_enc`（AES-GCM，密钥取自 env）；平台统一 Credential 服务不承载该密钥。
6. **文档解析/分块**：rag 管道的分块/解析由 `KnowledgeIngestionService` 承载。**text 类型不涉及解析/分块**（上传即 ready）。
7. **类型命名**：三类型枚举为 `rag` / `external` / `text`，采用"能力类别式"方案——`rag`=本地语义向量检索（自托管 pgvector）、`external`=外部 RAG 平台、`text`=文本检索（grep/cat，不向量化），写入 §1/§4/§8 的 CHECK 约束与 API 联合类型。
8. **text 检索呈现**：两个路径**都做**——(1) 主路径：**agent 运行时**把 grep/cat 工具作用域限定在 KB 目录（由 agent 自主调用）；(2) 次路径：服务端 `/search` grep 兜底，用于预览/非 agent 场景。两者复用同一 `{kbRoot}/{kbId}` 解析目录与 `WorkspaceGenerator` 的 `startsWith` 守卫。

---

## 10. 实现范围

- **核心**：V22 表（含 `text` 类型）+ `KnowledgeBaseProperties` + Controller + CRUD + 配置 + rag/text 文件系统存储。`type` 三元枚举与 CHECK 约束到位。
- **授权**：V23 群组 + grants + 可见性过滤 + `editable`/`permission` 派生（三类型通用）。
- **rag 检索**：V24 pgvector + 异步解析管道（pending→parsing→chunking→indexing→ready，`KnowledgeIngestionService` 后台线程，`afterCommit` 触发避免读到未提交文档）+ 检索端点 `POST /{id}/search`（`KnowledgeRetrievalService`）。embedding 默认 OpenAI 兼容 `/v1/embeddings`，无 `EMBEDDING_API_KEY` 时回退确定性本地向量（词哈希 + L2 归一化）以跑通管道；分块表经原生 SQL 读写规避 Hibernate `vector` 类型校验。
- **text 检索**：服务端 `/search` grep 兜底（`KnowledgeTextRetrievalService` 在 KB 目录内逐行匹配，返回 `TextSearchResult{file,line,content,context}`）；agent 运行时主路径通过 `KnowledgeBaseService.resolveStorageDir(kbId,userId)` 取绝对目录绑定 grep/cat 工具沙箱（复用 `KnowledgeStorageService.kbDir` 的 startsWith 防穿越守卫）。统一响应 `SearchKnowledgeBaseResponse{type, results:SearchResult[]}`，`SearchResult` 以 `vector(...)` / `text(...)` 两种构造覆盖 rag/external 与 text 两种形态。
- **external 路线**：`external_api_key_enc` 经 `ExternalApiKeyCipher`（AES-256-GCM，密钥取 `agent-platform.external.key` / env `AGENT_PLATFORM_EXTERNAL_KEY`，值为 base64 编码的 32 字节；**缺失或非法一律启动失败**，不回退固定开发密钥）加解密，绝不回显；`ExternalRagClient` 按 `external_provider` 适配 RAGFlow（`/api/v1/retrieval`）、Dify（`/v1/datasets/{dataset_id}/retrieval`，dataset_id 取自 `external_extra`）、百炼（`/retrieval`，app_id 取自 `external_extra`），解密密钥经 `java.net.http.HttpClient` 调用，结果归一化为 `SearchResult.vector`。
- **前端**：`type` 三选一切换 UI、external 配置表单、text 检索结果渲染、用户/群组授权面板均已实现。

---

## 11. 命名 / de-bias 备注

- 统一用功能性英文术语：`knowledge_base` / `grant` / `principal` / `provider` / `storage_root_rel`（相对路径列）。
- 表名 snake_case，与现有 `agent_configs` / `users` 一致。
- 避免业务偏向词；`external` 路线不绑定具体厂商名词做字段名（用 `external_provider` 枚举值承载 RAGFlow/Dify/百炼）。
- 三类型命名均为功能性：`vector`（语义向量检索）/ `external`（委托外部 RAG）/ `file`（文件工具检索、不向量化），与现有 `agent_configs` 的 `file_based` 风格一致。
