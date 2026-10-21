-- M11 整改：把运行时遗留的 ui_survey / survey 名称迁移到 form 体系，与 Java 侧对齐。
-- 仅做数据迁移；前端 schema 列已在 V17 重命名。
-- 注意：skill/subagent 的 ID（survey-helper / survey-collector / Survey Demo Bot）保留，
-- 仅替换内容中调用的工具名 ui_survey → ui_form_advanced。

UPDATE tools
SET name = 'ui_form_advanced'
WHERE name = 'ui_survey';

UPDATE skills
SET content = REPLACE(content, 'ui_survey', 'ui_form_advanced')
WHERE name LIKE 'survey-%'
  AND content LIKE '%ui_survey%';

UPDATE subagent_templates
SET content = REPLACE(content, 'ui_survey', 'ui_form_advanced')
WHERE content LIKE '%ui_survey%';