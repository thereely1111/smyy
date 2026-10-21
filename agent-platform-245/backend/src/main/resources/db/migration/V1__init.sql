-- 平台核心表（阶段一：资源中心 + Agent 配置）

CREATE TABLE skills (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(128) NOT NULL UNIQUE,
    description         TEXT,
    implementation_type VARCHAR(64)  NOT NULL DEFAULT 'skill',
    status              VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_by          VARCHAR(128),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_skills_status ON skills (status);

CREATE TABLE tools (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(128) NOT NULL UNIQUE,
    description   TEXT,
    tool_type     VARCHAR(64)  NOT NULL DEFAULT 'mcp',
    input_schema  JSONB,
    mcp_endpoint  VARCHAR(512),
    status        VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_tools_status ON tools (status);

CREATE TABLE subagent_templates (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(128) NOT NULL,
    description TEXT,
    file_path   VARCHAR(512) NOT NULL,
    content     TEXT,
    uploaded_by VARCHAR(128),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE agent_configs (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(128) NOT NULL,
    description    TEXT,
    system_prompt  TEXT,
    model          VARCHAR(128) NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'draft',
    skill_ids      JSONB        NOT NULL DEFAULT '[]',
    tool_ids       JSONB        NOT NULL DEFAULT '[]',
    subagent_ids   JSONB        NOT NULL DEFAULT '[]',
    workspace_path VARCHAR(512),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_agent_configs_status ON agent_configs (status);
