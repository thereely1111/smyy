-- 技能归属范围（2026-09-21 个人技能开放需求，修复 skill:* 码「持码即可改全平台」的
-- 水平越权面）：PLATFORM = 平台库（维护仍需 skill:new/edit/switch/remove 码）；
-- PERSONAL = 个人库，创建者私有，owner 自治（改/停用/删不需要权限码）。
-- 口径对齐 V52 knowledge_base_scope / V48 model_credentials.scope。
ALTER TABLE skills
    ADD COLUMN IF NOT EXISTS scope VARCHAR(16) NOT NULL DEFAULT 'PLATFORM';

-- 个人库归属人（PERSONAL 必填；PLATFORM 为空 = 平台共有）。存量数据全部归 PLATFORM。
ALTER TABLE skills
    ADD COLUMN IF NOT EXISTS owner_user_id UUID;

CREATE INDEX IF NOT EXISTS idx_skills_scope ON skills (scope);
CREATE INDEX IF NOT EXISTS idx_skills_owner ON skills (owner_user_id);
