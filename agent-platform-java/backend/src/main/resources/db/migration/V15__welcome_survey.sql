-- M10 欢迎表单（进入对话即渲染，无需用户先发消息）：
-- agent_configs 增加 welcome_survey（JSONB），承载一份「欢迎问卷」预设；
-- 前端在空会话挂载时直接渲染 SurveyCard，提交后后端以合成消息静默启动对话。

ALTER TABLE agent_configs ADD COLUMN IF NOT EXISTS welcome_survey JSONB;

-- 给 Survey Demo Bot 写入一份欢迎问卷示例，便于端到端验证。
UPDATE agent_configs
SET welcome_survey = '{
  "title": "欢迎使用 Survey Demo Bot",
  "description": "填写以下信息，让我更了解你的偏好，提交后我会主动开始对话。",
  "closeable": true,
  "pages": [
    {
      "key": "pref",
      "title": "你的偏好",
      "items": [
        {"key": "topics", "label": "你关注的方向", "kind": "checkbox-group", "options": ["代码辅助", "数据分析", "内容创作", "自动化"], "required": true},
        {"key": "language", "label": "偏好语言", "kind": "radio-group", "options": ["中文", "英文"]},
        {"key": "depth", "label": "回答深度", "kind": "radio-group", "options": ["简洁", "带示例"]},
        {"key": "reason", "label": "备注", "kind": "textarea", "maxLength": 200}
      ]
    }
  ]
}'::jsonb
WHERE id = '21257a9b-ef95-4218-bb9b-5dc9cd37ac28';
