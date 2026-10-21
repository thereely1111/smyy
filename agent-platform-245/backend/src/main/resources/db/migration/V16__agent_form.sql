-- M11 配置驱动表单（form）：form(JSONB) + form_trigger(varchar 枚举)。
-- form 与既有 welcome_survey 并存；form_trigger=on_session_start 语义对齐 welcomeForm。
-- 列名 form_trigger 匹配 Hibernate PhysicalNamingStrategy（Java 字段 formTrigger → form_trigger），
-- 与既有 welcome_survey 的命名风格一致。
ALTER TABLE agent_configs ADD COLUMN IF NOT EXISTS form JSONB;
ALTER TABLE agent_configs ADD COLUMN IF NOT EXISTS form_trigger VARCHAR(16) NOT NULL DEFAULT 'disabled';

-- once 去重：同一 user×agent 仅注入一次（跨会话、重启后依然有效）。
CREATE TABLE IF NOT EXISTS agent_form_answers (
    id         UUID PRIMARY KEY,
    agent_id   UUID        NOT NULL,
    user_id    VARCHAR(64) NOT NULL,
    survey_id  VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_agent_form_answers_agent_user UNIQUE (agent_id, user_id)
);

CREATE INDEX IF NOT EXISTS ix_agent_form_answers_agent_user
    ON agent_form_answers (agent_id, user_id);

-- 给 Survey Demo Bot 预置一份偏好表单，便于端到端验证（与既有 welcome_survey 示例同源）。
UPDATE agent_configs
SET form = '{
  "title": "配置首偏好",
  "description": "为下次体验调优；多选、填空皆可。",
  "submitLabel": "提交",
  "pages": [
    {
      "key": "p1",
      "title": "场景偏好",
      "items": [
        {"key":"topics","label":"你在哪些场景使用本助手？（多选）","kind":"checkbox-group","options":["代码辅助","文档撰写","数据分析","翻译","生活问答","其他补充..."],"required":true},
        {"key":"depth","label":"回答深度（单选）","kind":"radio-group","options":["直接作答","带示例","完整分析"],"required":true},
        {"key":"language","label":"回答语言","kind":"select","options":["中文","English","日本語","Español"],"required":true},
        {"key":"age","label":"年龄（数字）","kind":"number","min":13,"max":99,"step":1,"required":false},
        {"key":"reason","label":"你选择这个深度的原因","kind":"textarea","maxLength":280,"required":false}
      ]
    }
  ]
}'::jsonb,
    form_trigger = 'on_session_start'
WHERE name = 'Survey Demo Bot' AND form IS NULL;