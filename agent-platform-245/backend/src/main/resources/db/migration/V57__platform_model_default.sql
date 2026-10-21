-- 平台默认模型（2026-09-22）：全局有且只有一个，单例行（id 恒为 1）。
-- credential_id 指向 scope=PLATFORM 的 model_credentials 行；model 为该凭证下的启用模型。
-- 不加外键：凭证删除/模型禁用时由服务层清理默认行（避免删除顺序耦合）。
CREATE TABLE IF NOT EXISTS platform_model_default (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    credential_id UUID NOT NULL,
    model VARCHAR(128) NOT NULL,
    updated_by UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
