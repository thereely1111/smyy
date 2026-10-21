-- M11 整改：把遗留的 survey 列重命名为 form，与 feature 命名统一。
-- 仅重命名列，不动数据；welcome_survey → welcome_form、survey_id → form_id。
ALTER TABLE agent_configs
    RENAME COLUMN welcome_survey TO welcome_form;

ALTER TABLE agent_form_answers
    RENAME COLUMN survey_id TO form_id;