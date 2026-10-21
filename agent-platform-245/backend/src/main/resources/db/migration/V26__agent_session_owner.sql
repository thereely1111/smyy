-- 会话归属：在逻辑层把「会话产物」关联到用户。
--
-- 产物目录是两层 runtimes/{agentId}/{sessionId}（不含 userId）：装配期拿不到 userId
-- （AG-UI 注册闭包只有 agentId，LocalFilesystemSpec.env 又是装配期静态 Map），
-- 注入给模型的 WORKSPACE_ROOT 只能到 agent 层；平台若另建三层目录，
-- 就会与模型按「WORKSPACE_ROOT / SESSION_ID」实际写入的两层路径并存。
--
-- 因此用户归属改由本表承载：产物视图按 (agent_id, session_id) 查出 user_id 做校验，
-- 按用户清理 / 统计同样依赖本表。模型侧的隔离由装配期 IsolationScope.SESSION 负责，
-- 与目录里有没有 userId 层无关。
--
-- 用 VARCHAR 而非 UUID / 外键：agent_id 可能是子智能体的运行期 id（不保证是合法 UUID），
-- session_id 是前端生成的 wb-{uuid}，user_id 来自 JWT payload（String）。
CREATE TABLE agent_session_owners (
    id            UUID PRIMARY KEY,
    agent_id      VARCHAR(64)  NOT NULL,
    session_id    VARCHAR(255) NOT NULL,
    user_id       VARCHAR(64)  NOT NULL,
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 同一会话只归属一个用户：先到先得，后续请求仅刷新 last_seen_at
    UNIQUE (agent_id, session_id)
);
CREATE INDEX idx_session_owners_user ON agent_session_owners (user_id);
CREATE INDEX idx_session_owners_last_seen ON agent_session_owners (last_seen_at);
