-- 用户与认证（参考 agent-platform 参考项目设计：本地密码登录 + OAuth2.0 同步用户，统一签发本地 JWT）

CREATE TABLE users (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username       VARCHAR(100) NOT NULL UNIQUE,
    password_hash  VARCHAR(255) NOT NULL DEFAULT '',
    role           VARCHAR(20)  NOT NULL DEFAULT 'end_user',
    auth_type      VARCHAR(16)  NOT NULL DEFAULT 'password',
    oauth_user_id  VARCHAR(128),
    oauth_provider VARCHAR(64),
    email          VARCHAR(255),
    name           VARCHAR(128),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_oauth ON users (oauth_user_id);
