-- AG-UI 事件落库（2026-09-13）
-- 在 AguiEventEnricher 层旁路拦截 AG-UI HTTP 端点输出的全部协议事件，
-- 按 (user_id, session_id, event_sequence) 持久化，供回放 / 审计 / 问题复盘。
-- payload 为事件线上 JSON（与 SSE data: 载荷一致，含 type 字段）。
--
-- event_sequence 为会话内单调递增（跨 run 连续）：写入端用内存 AtomicLong
-- 分配、启动时按库内 max 补齐，RUN_START..RUN_FINISHED 天然有序，可整流重放。
-- 注意：wakeup 续跑 run 不经 AguiAgentAdapter（直接 streamEvents），其事件
-- 不在本表覆盖范围（前端经转录轮询获取，本就无 SSE 事件流）。

CREATE TABLE agui_events (
    id             UUID         PRIMARY KEY,
    user_id        VARCHAR(64)  NOT NULL,
    session_id     VARCHAR(255) NOT NULL,
    run_id         VARCHAR(128) NOT NULL,
    event_sequence BIGINT       NOT NULL,
    event_type     VARCHAR(64)  NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_agui_events_seq UNIQUE (user_id, session_id, event_sequence)
);

-- 按会话取事件流（重放主路径）；user_id 前置过滤实现查询侧天然隔离
CREATE INDEX idx_agui_events_session ON agui_events (session_id, event_sequence);
CREATE INDEX idx_agui_events_user ON agui_events (user_id, session_id, event_sequence);
