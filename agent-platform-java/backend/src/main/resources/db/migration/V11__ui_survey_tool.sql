-- M8 平台内置 ui_survey 工具：在 chat 流中发起产品级问卷（多选 / 单选 / 文本 / 数字 / 联动）。
-- 与 ui_form / ui_card 同模式（builtin + external executionMode）：
-- 模型调 `ui_survey({title, pages:[{items:[{kind,label,options,...}]}]})`，
-- 后端运行时把 tool_call 入参整体转成 SURVEY_REQUEST 事件推给前端；
-- 前端提交后通过 /hitl/survey/answer 回传 values。
INSERT INTO tools (name, description, tool_type, execution_mode, input_schema, mcp_endpoint, status)
VALUES ('ui_survey',
        'Survey primitive: in-chat UI form with checkbox-group / radio-group / select / text / textarea / number / other-text. Title + multi-page + groups + conditional visibility + validation. Returns values to next agent turn.',
        'builtin',
        'external',
        '{"type":"object","properties":{"title":{"type":"string","description":"Survey title"},"description":{"type":"string","description":"Subtitle/description"},"closeable":{"type":"boolean","description":"Show close button"},"submitLabel":{"type":"string"},"pages":{"type":"array","minItems":1,"items":{"type":"object","properties":{"key":{"type":"string"},"title":{"type":"string"},"items":{"type":"array","items":{"type":"object","properties":{"key":{"type":"string"},"label":{"type":"string"},"kind":{"type":"string","enum":["checkbox-group","radio-group","select","text","textarea","number","other-text"]},"options":{"type":"array","items":{"type":"string"}},"min":{"type":"number"},"max":{"type":"number"},"minLength":{"type":"integer"},"maxLength":{"type":"integer"},"pattern":{"type":"string"},"placeholder":{"type":"string"},"required":{"type":"boolean"},"step":{"type":"number"},"initialValue":{"type":["string","number","array","boolean","null"]}},"required":["kind","key","label"]}}},"required":["items"]}}},"required":["title","pages"]}'::jsonb,
        NULL, 'active')
    ON CONFLICT (name) DO NOTHING;
