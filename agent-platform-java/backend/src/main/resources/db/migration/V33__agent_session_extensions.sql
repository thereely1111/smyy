-- 智能体会话扩展信息：类似 MongoDB 文档结构，主表 + 子表存储垂类智能体的自定义扩展数据。
--
-- 主表 agent_session_extensions 代表一个会话的扩展文档，
-- 子表 agent_session_extension_entries 存储各个扩展字段（key-value），
-- 同一 (extension_id, entry_key) 唯一，类似 MongoDB 文档内 field 不重复。
--
-- 例如教材编写智能体可写入：
--   entry_key = 'chapterCount',  entry_value = '12'
--   entry_key = 'tocItems',      entry_value = '[{"title":"第一章","pageCount":25}, ...]'

CREATE TABLE agent_session_extensions (
    id          UUID PRIMARY KEY,
    agent_id    VARCHAR(64)  NOT NULL,
    session_id  VARCHAR(255) NOT NULL,
    user_id     VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (agent_id, session_id)
);
CREATE INDEX idx_session_extensions_user ON agent_session_extensions (user_id);

CREATE TABLE agent_session_extension_entries (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    extension_id  UUID         NOT NULL REFERENCES agent_session_extensions (id) ON DELETE CASCADE,
    entry_key     VARCHAR(128) NOT NULL,
    entry_value   TEXT,
    UNIQUE (extension_id, entry_key)
);
CREATE INDEX idx_extension_entries_extension ON agent_session_extension_entries (extension_id);
