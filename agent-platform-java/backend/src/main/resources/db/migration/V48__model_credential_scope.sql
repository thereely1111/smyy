-- 模型凭证共享范围（2026-09-19 智能体对话选模型需求）：
-- USER = 用户私有（默认，历史数据全部归此）；PLATFORM = 平台共享，
-- 管理员（credential:manage 权限码）创建，全员在对话模型选择中可见可用。
-- user_id 保持 NOT NULL：PLATFORM 凭证归属于创建它的管理员，编辑/删除仍走归属校验。
ALTER TABLE model_credentials
    ADD COLUMN IF NOT EXISTS scope VARCHAR(16) NOT NULL DEFAULT 'USER';

CREATE INDEX IF NOT EXISTS idx_model_credentials_scope ON model_credentials (scope);
