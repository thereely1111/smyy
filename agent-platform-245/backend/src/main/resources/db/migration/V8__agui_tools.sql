-- AG-UI 平台内置工具：Agent 运行中驱动前端 UI（表单收集 / 内容卡片渲染）。
-- 代码侧（UiTools）已对所有 Agent 注册同名工具；此处入库用于工具市场展示与
-- 运行时事件（TOOL_RESULT_END state=running）附加 inputSchema。

INSERT INTO tools (name, description, tool_type, execution_mode, input_schema, mcp_endpoint, status)
VALUES ('ui_form',
        '请求用户填写表单（按 JSON Schema 定义字段，提交后把表单值返回给智能体）',
        'builtin',
        'external',
        '{"type":"object","properties":{"title":{"type":"string","description":"表单标题"},"description":{"type":"string","description":"表单说明"},"schema":{"type":"object","description":"表单字段的 JSON Schema（type=object，含 properties/required）"},"values":{"type":"object","description":"预填值（可选）"},"submitLabel":{"type":"string","description":"提交按钮文案，默认：下一步"}},"required":["title","schema"]}'::jsonb,
        NULL, 'active'),
       ('ui_card',
        '展示结构化内容卡片（text/table/metrics/list，data 为内容数据）',
        'builtin',
        'internal',
        '{"type":"object","properties":{"title":{"type":"string","description":"卡片标题"},"kind":{"type":"string","enum":["text","table","metrics","list"],"description":"内容类型"},"data":{"type":"object","description":"内容数据：text 为 string；table 为 {columns:string[],rows:any[][]}；metrics 为 [{label,value}]；list 为 string[]"}},"required":["title","kind","data"]}'::jsonb,
        NULL, 'active')
    ON CONFLICT (name) DO NOTHING;