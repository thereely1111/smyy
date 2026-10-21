-- 标签系统（2026-09-20）：管理员统一定义的封闭标签集，智能体与知识库消费。
-- tags = 标签本体（一等资源，改名/停用/排序全平台生效）；
-- agent_tags / knowledge_base_tags = 两张实体关联表（不用多态单表，保外键完整）。
-- 停用走 enabled=false 软停，不物理删；删除标签时关联行随 FK CASCADE 清理。
CREATE TABLE IF NOT EXISTS tags (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(64)  NOT NULL UNIQUE,
    color       VARCHAR(16),
    sort        INT          NOT NULL DEFAULT 0,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS agent_tags (
    agent_id    UUID NOT NULL REFERENCES agent_configs(id) ON DELETE CASCADE,
    tag_id      UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (agent_id, tag_id)
);

CREATE TABLE IF NOT EXISTS knowledge_base_tags (
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    tag_id            UUID NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (knowledge_base_id, tag_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_tags_tag ON agent_tags (tag_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_base_tags_tag ON knowledge_base_tags (tag_id);
