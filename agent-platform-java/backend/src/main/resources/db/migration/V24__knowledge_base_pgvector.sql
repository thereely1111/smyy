-- P3 rag 语义检索：pgvector 扩展 + 分块向量表
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_base_chunks (
    id                  UUID PRIMARY KEY,
    document_id         UUID NOT NULL REFERENCES knowledge_base_documents(id) ON DELETE CASCADE,
    knowledge_base_id   UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    chunk_index         INT NOT NULL,
    content             TEXT NOT NULL,
    embedding           vector(1536) NOT NULL,
    metadata            JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_kb_chunks_kb ON knowledge_base_chunks (knowledge_base_id);
CREATE INDEX idx_kb_chunks_doc ON knowledge_base_chunks (document_id);
-- 余弦距离索引（ivfflat）；正式环境按数据量调整 lists 并 SET ivfflat.probes
CREATE INDEX idx_kb_chunks_embedding ON knowledge_base_chunks
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
