-- P5：RAG 知识库开放「按知识库」的分块参数（chunk_size / chunk_overlap）。
-- 仅 rag 摄入管线使用；存量 rag 库此前硬编码 1000/200，这里默认值与之对齐。
-- external / text 类型不使用分块，对应列为 NULL（DEFAULT 仅用于迁移回填现有行）。

ALTER TABLE knowledge_bases
    ADD COLUMN chunk_size   INTEGER DEFAULT 1000,
    ADD COLUMN chunk_overlap INTEGER DEFAULT 200;

-- 重叠不得超过分块大小的一半；chunk_size 为 NULL 时（external/text）约束自动放行。
ALTER TABLE knowledge_bases
    ADD CONSTRAINT chk_kb_chunk
    CHECK (chunk_size IS NULL
        OR (chunk_size > 0
            AND chunk_overlap >= 0
            AND chunk_overlap <= chunk_size / 2));
