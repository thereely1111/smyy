-- 知识库核心表（P1）：knowledge_bases + knowledge_base_documents
-- 群组 / 授权 / 向量分块表在后续迁移（P2 / P3）中扩展。

CREATE TABLE knowledge_bases (
    id                    UUID PRIMARY KEY,
    name                  VARCHAR(200) NOT NULL,
    description           TEXT,
    type                  VARCHAR(16) NOT NULL CHECK (type IN ('rag', 'external', 'text')),
    owner_user_id         UUID NOT NULL REFERENCES users (id),
    embedding_model_config JSONB,
    storage_root_rel      VARCHAR(512),
    external_provider     VARCHAR(32) CHECK (external_provider IN ('ragflow', 'dify', 'bailian')),
    external_endpoint     VARCHAR(512),
    external_api_key_enc  TEXT,
    external_extra        JSONB,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ( (type = 'rag'     AND embedding_model_config IS NOT NULL AND storage_root_rel IS NOT NULL)
         OR (type = 'external' AND external_provider IS NOT NULL AND external_endpoint IS NOT NULL)
         OR (type = 'text'    AND storage_root_rel IS NOT NULL) )
);

CREATE INDEX idx_kb_owner ON knowledge_bases (owner_user_id);

CREATE TABLE knowledge_base_documents (
    id             UUID PRIMARY KEY,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_bases (id) ON DELETE CASCADE,
    filename       VARCHAR(255) NOT NULL,
    relative_path  VARCHAR(512) NOT NULL,
    status         VARCHAR(16) NOT NULL CHECK (status IN ('pending', 'parsing', 'chunking', 'indexing', 'ready', 'error')),
    chunk_count    INTEGER NOT NULL DEFAULT 0,
    error          TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_kb_docs_kb ON knowledge_base_documents (knowledge_base_id);
CREATE INDEX idx_kb_docs_kb_status ON knowledge_base_documents (knowledge_base_id, status);
