-- 会话级告警推送订阅（2026-09-16，对话页 × 渠道推送）
--
-- 背景：告警扇出（AlertNotifier 第四条路径）此前是纯用户级——只要用户配了
-- 启用渠道，其名下所有会话的告警一律外发。用户要求在对话页提供会话粒度的
-- 开关：某个会话的告警/任务完成通知可以不再打扰 IM 群，只走站内铃铛。
--
-- 建模：每 (user_id, session_id) 一行。无记录 = 默认推送（与历史行为一致，
-- 不要求用户逐会话开启）；有记录时以 enabled 为准（opt-out 语义）。
-- 不用 JSONB（渠道表同批次教训）：纯标量列。

CREATE TABLE session_alert_push (
    id          UUID          PRIMARY KEY,
    user_id     VARCHAR(64)   NOT NULL,
    session_id  VARCHAR(255)  NOT NULL,
    enabled     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_session_alert_push_user_session UNIQUE (user_id, session_id)
);

CREATE INDEX idx_session_alert_push_session ON session_alert_push(session_id);
