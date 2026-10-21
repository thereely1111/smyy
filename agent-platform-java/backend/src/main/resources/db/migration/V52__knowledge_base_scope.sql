-- 知识库归属范围（2026-09-20 个人知识库开放需求）：
-- PLATFORM = 平台库（创建需 knowledge:manage 权限码，存量数据全部归此）；
-- PERSONAL = 个人库，创建者私有，走既有 owner/授权可见性模型。
-- 口径对齐 V48 model_credentials.scope：scope 当前仅做归属分类，
-- 不改变可见性判定（平台库他人使用仍需显式授权，数据级权限后续补充）。
ALTER TABLE knowledge_bases
    ADD COLUMN IF NOT EXISTS scope VARCHAR(16) NOT NULL DEFAULT 'PLATFORM';

CREATE INDEX IF NOT EXISTS idx_kb_scope ON knowledge_bases (scope);
