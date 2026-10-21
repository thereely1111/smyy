-- 资料库语义索引（P2）：library_chunk + 节点索引状态
--
-- 设计取舍：
-- 1) chunk 表沿用 knowledge_base_chunks 的做法——原生 SQL + vector(1536)，
--    **不建 JPA 实体**（规避 Hibernate 对 vector 类型的 ddl-auto:validate 校验冲突）。
-- 2) 索引状态放独立表 library_chunk_index 而非塞进 library_node：
--    node 表是「薄壳」（只管树结构与元信息，正文按 kind 外置），
--    把 ingesting/ready 这类摄入态加进去会污染它，也会让 listNodes 的查询变重。
-- 3) 检索**先按 node 软删过滤、再按可见性过滤**，故索引表冗余 space_id：
--    使「某空间内所有分块」不必 join library_nodes。

CREATE TABLE library_chunk_index (
    node_id     UUID PRIMARY KEY REFERENCES library_nodes (id) ON DELETE CASCADE,
    space_id    UUID NOT NULL REFERENCES library_spaces (id) ON DELETE CASCADE,
    status      VARCHAR(16) NOT NULL CHECK (status IN ('pending', 'parsing', 'chunking', 'indexing', 'ready', 'error')),
    chunk_count INTEGER NOT NULL DEFAULT 0,
    error       TEXT,
    -- 分块参数快照：摄入时用的值，便于排查「为什么切成这样」而不必回查知识库
    chunk_size      INTEGER,
    chunk_overlap   INTEGER,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_library_chunk_index_space ON library_chunk_index (space_id, status);

CREATE TABLE library_chunks (
    id          UUID PRIMARY KEY,
    node_id     UUID NOT NULL,
    space_id    UUID NOT NULL,
    chunk_index INTEGER NOT NULL,
    content     TEXT NOT NULL,
    embedding   vector(1536) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 不建外键到 library_nodes：摄取时先删后插，且节点软删不影响分块留存
    -- （分块随 library_chunk_index 的 ON DELETE CASCADE 清理）
    UNIQUE (node_id, chunk_index)
);

-- 检索主路径：给定 node 取其分块
CREATE INDEX idx_library_chunks_node ON library_chunks (node_id, chunk_index);
-- 空间级检索：按余弦相似度排序。与知识库同用 ivfflat（lists=100），
-- 正式环境按数据量调整 lists 并 SET ivfflat.probes
CREATE INDEX idx_library_chunks_embedding ON library_chunks
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
-- 空间内分块定位（可见性过滤时按空间裁剪）
CREATE INDEX idx_library_chunks_space ON library_chunks (space_id);
