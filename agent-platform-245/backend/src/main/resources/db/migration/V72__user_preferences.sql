CREATE TABLE IF NOT EXISTS user_preferences (
    -- 一个人一行，偏好体为 JSONB：不新增偏好项就不用改表结构
    user_id     UUID        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    -- 偏好 JSON。字段定义见 UserPreferencePayload，前端拿到的是同一份结构
    prefs       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE user_preferences IS '用户偏好：界面主题/语言、对话行为、通知策略；JSONB 便于不改表结构增项';

-- 通知偏好按 alertKey 去重/抑制时需要按 user_id 取行，主键已覆盖该查询，无需额外索引。
