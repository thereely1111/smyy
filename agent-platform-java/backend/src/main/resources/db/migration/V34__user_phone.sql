-- 用户表增加手机号列：登录同步时从 tipdm userinfo 的 phone 字段刷新
-- （实体 User.phone 对应 tipdm 返回的 phone；可空，仅 OAuth 同步用户填充）
ALTER TABLE users ADD COLUMN IF NOT EXISTS phone VARCHAR(20);