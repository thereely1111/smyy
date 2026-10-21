-- 文件驱动智能体在线编辑：草稿持久化（2026-09-24）
-- 真相源仍是 workspace/agents/{名}/ 目录（发布 = 草稿落盘 + reload），草稿存 DB
-- 以便跨重启保留并支持「草稿 → 试运行 → 发布」三态，与 DB 智能体发布快照模型对齐。
CREATE TABLE IF NOT EXISTS file_agent_drafts (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL,
    agents_md TEXT NOT NULL,
    subagents JSONB NOT NULL DEFAULT '{}'::jsonb,
    draft_seq INT NOT NULL DEFAULT 1,
    updated_by VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_file_agent_draft_agent UNIQUE (agent_id)
);

CREATE INDEX IF NOT EXISTS idx_file_agent_draft_agent ON file_agent_drafts (agent_id);
