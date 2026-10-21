-- M9 A+B+C 支撑迁移：
-- A：skills 表补充 content 列，承载技能正文（供 JpaSkillRepository 适配 AgentScope AgentSkill）。
-- B：新增演示子智能体模板 survey-collector（专门调用 ui_survey 发起问卷）。
-- C：新增演示技能 survey-helper + 演示智能体 Survey Subagent Bot，
--    将技能与子智能体同时挂到智能体上，用于验证「技能 / 子智能体 内触发 ui_survey（SurveyCard）」。

ALTER TABLE skills ADD COLUMN IF NOT EXISTS content TEXT;

-- 演示技能：正文指导模型在合适时机调用 ui_survey（验证 A：技能可触发 SurveyCard）
INSERT INTO skills (id, name, description, implementation_type, status, created_by, content)
VALUES (
    'f47ac10b-58cc-4372-a567-0e02b2c3d479',
    'survey-helper',
    '在需要收集用户结构化偏好时，指导使用 ui_survey 工具发起问卷。',
    'skill',
    'active',
    'system',
    '# survey-helper
当用户表达出填写偏好、配置、调研意图时，主动调用 ui_survey 工具发起一份简洁问卷，
收集后再以自然语言总结用户偏好。'
)
ON CONFLICT (name) DO UPDATE SET content = EXCLUDED.content;

-- 演示子智能体：委派后调用 ui_survey 发起问卷（验证 B：子智能体可触发 SurveyCard）
INSERT INTO subagent_templates (id, name, description, file_path, content, uploaded_by)
VALUES (
    '9b2e4f1c-3d7a-4c5e-8f10-2a6b1c9d4e5f',
    'survey-collector',
    '收集用户偏好问卷的子智能体，调用 ui_survey 发起结构化问卷并汇总。',
    'templates/9b2e4f1c-3d7a-4c5e-8f10-2a6b1c9d4e5f.md',
    E'---\n'
        || 'id: survey-collector\n'
        || 'name: survey-collector\n'
        || 'description: 收集用户偏好问卷的子智能体，调用 ui_survey 发起结构化问卷并汇总。\n'
        || 'tools: [ui_survey, ui_form, ui_card]\n'
        || 'mode: subagent\n'
        || '---\n'
        || '你是一个问卷收集子智能体。收到委派后，立即调用 ui_survey 工具发起一份关于用户偏好的简短问卷'
        || E'（标题"偏好收集"，含多选场景、单选深度、文本原因三题），用户提交后汇总其偏好并以一句话回传主智能体。\n',
    'system'
)
ON CONFLICT (id) DO NOTHING;

-- 演示智能体：把演示技能 + 演示子智能体同时挂上，用于端到端验证 A+B+C
INSERT INTO agent_configs (name, description, system_prompt, model, status, skill_ids, tool_ids, subagent_ids)
VALUES (
    'Survey Subagent Bot',
    '演示技能与子智能体均可触发 ui_survey（SurveyCard）的端到端链路。',
    '你有一个名为 survey-collector 的子智能体，专门负责向用户发起问卷；你还有一个 survey-helper 技能，'
        || E'指导如何收集结构化偏好。\n\n'
        || '规则：\n'
        || '1. 当用户想要"填写问卷 / 收集偏好 / 做调研"时，必须委派 survey-collector 子智能体处理，'
        || '不要自己直接调用 ui_survey。\n'
        || '2. 当用户需要"结构化偏好建议"时，可使用 survey-helper 技能获取指引。\n'
        || '3. 子智能体或技能完成收集后，以自然语言向用户总结结果。',
    'dashscope:qwen-plus',
    'active',
    '["f47ac10b-58cc-4372-a567-0e02b2c3d479"]'::jsonb,
    '[]'::jsonb,
    '["9b2e4f1c-3d7a-4c5e-8f10-2a6b1c9d4e5f"]'::jsonb
)
ON CONFLICT (id) DO NOTHING;
