-- 会话归档：在归属表上追加 archived_at，让「已归档会话」从默认列表隐藏但保留产物。
--
-- 归档与删除是两件事：
--   * 删除（POST /{id}/sessions/{sessionId}/clear）= 状态行 + 产物目录 + 归属记录一并清除，
--     不可逆，产物（runtimes/{agentId}/{sessionId}）也一起没了；
--   * 归档 = 只打时间戳，状态与产物目录原样保留，随时可恢复。
--
-- 归档落在归属表而不是状态库：状态库（agentscope_sessions）按 (user, session, key)
-- 整体覆盖写入，模型每次回复都会重写，塞归档标记进去会被覆盖；归属表只在会话
-- 首次出现时写入、之后只刷新 last_seen_at，是放这类「用户侧元数据」的合理位置。
--
-- 用「时间戳」而非 boolean：归档本身是有时间的事件，前端可按归档时间排序，
-- 将来要做「归档满 N 天自动清理」也无需再加字段。NULL 即未归档。
ALTER TABLE agent_session_owners
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;

-- 默认列表只查未归档，走 partial index 避免全表过滤
CREATE INDEX IF NOT EXISTS idx_session_owners_active
    ON agent_session_owners (agent_id, user_id)
    WHERE archived_at IS NULL;
