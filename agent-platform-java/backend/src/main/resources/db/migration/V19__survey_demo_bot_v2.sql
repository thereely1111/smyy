-- M11+ Survey Demo Bot V2：新架构对齐示例。
-- 在 V1（V12 + V16 增强）基础上，把"系统提示词内嵌 JSON 模板 + 调用 ui_survey"
-- 的旧机制替换为新架构：
--   * form + form_trigger：运行时由后端按触发时机自动注入 FORM_REQUEST，
--     模型不再需要自行构造问卷 JSON
--   * tool_ids 含 ui_form_advanced：保留模型主动发问的兜底通道
--   * skill_ids 含 survey-helper：技能驱动的发问路径（用户在消息中表达偏好）
--   * subagent_ids 含 survey-collector：委派给子 Agent 收集并汇总
--   * system_prompt 不再嵌入 JSON 模板，改为行为级说明
-- 表单定义复用 V1 的 5 字段偏好采集（topics/depth/language/age/reason）以
-- 保证 V2 与 V1 的功能对等：同一份表单、同一触发时机、同一组题项。
--
-- 使用 fixed UUID 便于端到端测试 / curl 验证。
INSERT INTO agent_configs (
    id, name, description, system_prompt, model, status,
    skill_ids, tool_ids, subagent_ids,
    form, form_trigger
)
SELECT
    '3c8f7a21-9d4e-4b6a-8f12-1e0a7c5d44b9'::uuid,
    'Survey Demo Bot V2',
    '基于新架构的偏好表单示例：表单由运行时注入（form + form_trigger），' ||
        '同时装配 survey-helper 技能、ui_form_advanced 工具与 survey-collector 子 Agent。' ||
        '用于演示配置驱动表单 + 多通道表单收集的端到端流程。',
    -- 行为级说明（不嵌入 JSON 模板；运行时由 form 字段注入）
    '你是一个偏好询问助手，进入新会话时平台会自动向你发送一份用户表单，' ||
        '用户提交后请总结其偏好并以礼貌口吻回应（用于后续轮次）。' ||
        E'\n\n行为约定：' ||
        E'\n1) 平台自动注入的表单：直接读取 system 推送的 form 结构（无需自己构造）；' ||
        E'\n2) 用户在对话中临时表达偏好：可调用 ui_form_advanced 工具发起单次表单；' ||
        E'\n3) 需要复杂问卷收集：可委派 survey-collector 子 Agent 收集并汇总。',
    'dashscope:qwen-plus',
    'active',
    -- skill_ids：技能驱动
    jsonb_build_array(
        (SELECT id FROM skills WHERE name = 'survey-helper' LIMIT 1)
    ),
    -- tool_ids：模型主动调用兜底（form_trigger 走运行时注入，不需要工具介入）
    jsonb_build_array(
        (SELECT id FROM tools WHERE name = 'ui_form_advanced' LIMIT 1)
    ),
    -- subagent_ids：子 Agent 委派
    jsonb_build_array(
        (SELECT id FROM subagent_templates WHERE name = 'survey-collector' LIMIT 1)
    ),
    -- form：复用 V1 的 5 字段偏好表单（功能对等）
    '{
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
    'on_session_start'
WHERE NOT EXISTS (
    SELECT 1 FROM agent_configs WHERE name = 'Survey Demo Bot V2'
);
