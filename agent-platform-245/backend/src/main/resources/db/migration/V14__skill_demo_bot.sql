-- M9-C 补充：纯技能触发演示智能体（不挂子智能体，隔离「技能」链路）。
-- 用于与 Survey Demo Bot（工具触发）、Survey Subagent Bot（子智能体触发）区分，
-- 单独演示「技能 → ui_survey → SurveyCard」这一条路径。
INSERT INTO agent_configs (id, name, description, system_prompt, model, status, skill_ids, tool_ids, subagent_ids)
VALUES (
    'c0ffee01-1111-2222-3333-444455556666',
    'Skill Demo Bot',
    '仅挂载 survey-helper 技能（不挂子智能体），用于单独演示「技能触发 SurveyCard」。',
    E'你配置了 survey-helper 技能。\n\n'
        || '规则：\n'
        || '1. 当用户表达出填写偏好、配置、调研意图时，先加载 survey-helper 技能获取指引，'
        || '然后亲自调用 ui_survey 工具发起一份简洁问卷。\n'
        || '2. 用户提交问卷后，以自然语言总结其偏好。\n'
        || '3. 严禁委派任何子智能体，所有 ui_survey 调用都应由你自己直接发起。',
    'dashscope:qwen-plus',
    'active',
    '["f47ac10b-58cc-4372-a567-0e02b2c3d479"]'::jsonb,
    '[]'::jsonb,
    '[]'::jsonb
)
ON CONFLICT (id) DO NOTHING;
