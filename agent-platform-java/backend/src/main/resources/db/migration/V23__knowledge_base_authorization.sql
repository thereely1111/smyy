-- P2 授权与可见性：授权表 + 内置轻量群组
-- 知识库授权（用户/群组对知识库的 READ/WRITE）
CREATE TABLE knowledge_base_grants (
    id                  UUID PRIMARY KEY,
    knowledge_base_id   UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    principal_type      VARCHAR(8) NOT NULL CHECK (principal_type IN ('USER', 'GROUP')),
    principal_id        UUID NOT NULL,
    permission          VARCHAR(8) NOT NULL CHECK (permission IN ('READ', 'WRITE')),
    granted_by          UUID NOT NULL REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 每个主体（用户或群组）对同一知识库仅一条授权，权限取最大（WRITE > READ）
    UNIQUE (knowledge_base_id, principal_type, principal_id)
);
CREATE INDEX idx_kb_grants_principal ON knowledge_base_grants (principal_type, principal_id);
CREATE INDEX idx_kb_grants_kb ON knowledge_base_grants (knowledge_base_id);

-- 内置轻量群组
CREATE TABLE groups (
    id          UUID PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    created_by  UUID NOT NULL REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 群组成员（用户 ↔ 群组 多对多）
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
