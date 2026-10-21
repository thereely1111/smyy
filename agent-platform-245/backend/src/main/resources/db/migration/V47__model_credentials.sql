-- 模型凭证（模型供应商 API Key 等接入配置，用户级，归属即权限）。
-- 密钥字段（writeOnly，如 api_key）在写入侧整体 AES-256-GCM 加密后存入 data，
-- 读取侧任何接口不回显明文（返回掩码哨兵，见 ModelCredentialService）。
CREATE TABLE IF NOT EXISTS model_credentials (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL,
    cred_type   VARCHAR(64) NOT NULL,
    data        JSONB NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_model_credentials_user ON model_credentials (user_id);
