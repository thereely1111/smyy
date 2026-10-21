-- 渠道（2026-09-16，外部 IM / 出站 webhook 集成骨架）
--
-- 背景：告警此前只有三条出口——站内铃铛、SSE、以及单个全局写死的
-- 企业微信机器人 webhook（agent-platform.alert.wecom）。用户无法把告警
-- 发到自己所在的钉钉群 / 飞书群，也无法按人区分去向。本表把「出站渠道」
-- 变成用户级可管理资源：每用户自建渠道（webhook 凭据自持），告警按归属人
-- 扇出到其全部启用渠道。
--
-- 建模（2026-09-16 二次修订，弃 JSONB）：
--   凭据是「渠道 × 键 → 值」的多值结构，用关联表 channel_credentials 表达，
--   不在 channels 上放 JSONB 列。密钥值 TEXT 存储，接口出参一律不回显。
--   platform_config 里实际只用 min_level 一个键，提升为真实列；
--   入站能力（绑定规则 / bot 会话设置）本期未启用，等落地时再建真实表，
--   不再预留空 JSONB 列。
--
-- 安全：channel_credentials.cred_value 存机器人 webhook 地址与加签密钥，
-- 接口出参一律不回显；渠道删除凭据随之删除（ON DELETE CASCADE）。

CREATE TABLE channels (
    id              UUID         PRIMARY KEY,
    user_id         VARCHAR(64)  NOT NULL,
    -- wechat_work / dingtalk / feishu / webhook
    channel_type    VARCHAR(32)  NOT NULL,
    name            VARCHAR(200),
    -- info / warn / error：达到该级别的告警才外发到此渠道（默认 warn）
    min_level       VARCHAR(8)   NOT NULL DEFAULT 'warn',
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    -- stopped / connected / failed：由最近一次出站投递结果推导
    status          VARCHAR(16)  NOT NULL DEFAULT 'stopped',
    last_error      TEXT,
    last_sent_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 渠道列表主路径：按用户倒序
CREATE INDEX idx_channels_user ON channels (user_id, created_at DESC);

-- 渠道凭据关联表：每行一个键值（webhook_url / secret / url ...）
CREATE TABLE channel_credentials (
    id          UUID          PRIMARY KEY,
    channel_id  UUID          NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    cred_key    VARCHAR(100)  NOT NULL,
    cred_value  TEXT          NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_channel_credentials UNIQUE (channel_id, cred_key)
);

-- 凭据读取路径：按渠道取全部键值
CREATE INDEX idx_channel_credentials_channel ON channel_credentials (channel_id);
