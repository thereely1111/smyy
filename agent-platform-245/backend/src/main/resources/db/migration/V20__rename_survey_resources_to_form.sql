-- M12 资源命名整改：survey-helper / survey-collector / Survey Subagent Bot
-- 统一改为功能性命名，不带业务偏向。
--
--   skill:    survey-helper       → form-trigger-helper
--   subagent: survey-collector    → form-collector
--   agent:    Survey Subagent Bot → Skill+Subagent Demo Bot
--
-- 同步刷新 skills/subagent_templates 的 description / content 与
-- agent_configs 的 system_prompt / description / name 中残留的旧名字与
-- ui_survey 字面量。V18 只替换了 skill/subagent content 中的 ui_survey，
-- 未触 agent_configs.system_prompt（V12 Survey Demo Bot 与 V14 两个 demo agent
-- 的提示词仍含 ui_survey / survey-helper / survey-collector）—— 本次一并清掉。
--
-- 注：不动 tools.name（V18 已经 ui_survey → ui_form_advanced）。
-- 不动 skills/subagent 的 UUID；rename 只改 name 列，agent_configs.skill_ids
-- 与 subagent_ids JSONB 数组里存的 UUID 保持指向同一资源。

-- 1) skills：rename + content/description 字符串替换
UPDATE skills
SET name        = 'form-trigger-helper',
    description = REPLACE(description, 'survey-helper', 'form-trigger-helper'),
    content     = REPLACE(content,     'survey-helper', 'form-trigger-helper')
WHERE name = 'survey-helper';

-- 2) subagent_templates：rename + content/description 字符串替换
UPDATE subagent_templates
SET name        = 'form-collector',
    description = REPLACE(description, 'survey-collector', 'form-collector'),
    content     = REPLACE(content,     'survey-collector', 'form-collector')
WHERE name = 'survey-collector';

-- 3) agent_configs.system_prompt：所有旧名字 / 旧工具名 → 新名字
UPDATE agent_configs
SET system_prompt = REPLACE(system_prompt, 'survey-helper', 'form-trigger-helper')
WHERE system_prompt LIKE '%survey-helper%';

UPDATE agent_configs
SET system_prompt = REPLACE(system_prompt, 'survey-collector', 'form-collector')
WHERE system_prompt LIKE '%survey-collector%';

UPDATE agent_configs
SET system_prompt = REPLACE(system_prompt, 'ui_survey', 'ui_form_advanced')
WHERE system_prompt LIKE '%ui_survey%';

-- 4) agent_configs.description：ui_survey 残留
UPDATE agent_configs
SET description = REPLACE(description, 'ui_survey', 'ui_form_advanced')
WHERE description LIKE '%ui_survey%';

-- 5) agent_configs.name：V14 的 "Survey Subagent Bot" → "Skill+Subagent Demo Bot"
--    （V1/V2 的 "Survey Demo Bot" / "Survey Demo Bot V2" 是产品级 demo bot 名字，
--     不在本轮整改范围——与功能命名整改无关，是品牌/产品名。）
UPDATE agent_configs
SET name = 'Skill+Subagent Demo Bot'
WHERE name = 'Survey Subagent Bot';
