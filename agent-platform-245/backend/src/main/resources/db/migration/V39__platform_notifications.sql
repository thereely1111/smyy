-- 平台通知（2026-09-16，主动告知闭环）
--
-- 背景：会话停摆 / run 静默判死 / 自愈失败这类事件，此前只落在服务日志里，
-- 服务端知道「它停了」，用户却完全无从知晓——教材会话曾静默空转 3.5 小时
-- 无人发现（2026-09-16 实证）。实时通道（GET /api/notify/stream，SSE）
-- 已就绪但仅能投递游标信号，且浏览器未打开时必然丢失。
--
-- 本表是「主动告知」的持久化面：告警产生时同时写库与推 SSE——在线用户
-- 由 SSE 即时感知（Toast + 铃铛角标），离线用户下次进站仍能看到未读红点。
-- 配合 AlertNotifier 的 alert_key 冷却去重，避免周期性看护把同一件事刷成
-- 几百条通知。
--
-- 粒度：每告警一行，强制按 user_id 隔离（写入与查询都带 user_id）。

CREATE TABLE platform_notification (
    id          UUID         PRIMARY KEY,
    user_id     VARCHAR(64)  NOT NULL,
    -- info / warn / error：error 级额外触发外部渠道（企业微信机器人 webhook）
    level       VARCHAR(16)  NOT NULL,
    title       VARCHAR(200) NOT NULL,
    body        TEXT,
    session_id  VARCHAR(255),
    agent_id    VARCHAR(64),
    -- 告警去重键（如 stall:main-xxx）：同键在冷却窗口内不重复落库
    alert_key   VARCHAR(128),
    read_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 铃铛列表主路径：按用户倒序取最近通知
CREATE INDEX idx_notification_user ON platform_notification (user_id, created_at DESC);
-- 未读角标：部分索引，已读行不进索引（通知读多写少，索引保持极小）
CREATE INDEX idx_notification_unread ON platform_notification (user_id, created_at DESC)
    WHERE read_at IS NULL;
-- 冷却去重查询：按 (user, alert_key) 找最近一条
CREATE INDEX idx_notification_alert_key ON platform_notification (user_id, alert_key, created_at DESC);
