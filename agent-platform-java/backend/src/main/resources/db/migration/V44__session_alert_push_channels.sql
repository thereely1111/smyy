-- 会话级告警推送：渠道显式勾选（2026-09-16）
--
-- 背景与语义变更：V43 上线的是 opt-out 开关（无记录/开启 = 扇出到用户全部
-- 启用渠道）。用户反馈：推送到哪些渠道应该是用户在对话页主动勾选的，
-- 而不是开关一开就全推。本迁移把订阅行升级为「总开关 + 渠道白名单」：
--
--   enabled = false 或无记录        → 不扇出（会话告警只走站内铃铛/SSE）
--   enabled = true 且 channel_ids 空 → 不扇出（总开关开了但还没勾渠道）
--   enabled = true 且 channel_ids 非空 → 只扇出勾选的渠道（仍按各渠道
--     min_level 过滤；渠道已删除/停用自动失效；越权渠道 id 因不属于
--     该用户而在扇出侧天然无匹配，无害）
--
-- 全局告警（无 sessionId，如系统级事件）不受会话白名单约束，仍按用户级
-- 全渠道扇出——会话维度选不出「全局」的归属。
--
-- 存储形态：CSV 标量列（TEXT，逗号分隔渠道 UUID），不用 JSONB（渠道表
-- 同批次教训）；数量级是个位数，无需关联表。
-- 历史行为兼容：V43 已产生的 enabled=true 行升级后 channel_ids 为空串
-- → 不再外发，直到用户在面板勾选渠道。这是本次需求的预期语义。

ALTER TABLE session_alert_push
    ADD COLUMN channel_ids TEXT NOT NULL DEFAULT '';

COMMENT ON COLUMN session_alert_push.channel_ids IS
    '会话告警外发的渠道白名单（CSV 渠道 UUID）；空 = 不外发，仅 enabled=true 且非空时扇出';
