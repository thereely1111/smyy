-- M1 种子数据：技能中心 / 工具市场示例

INSERT INTO skills (name, description, implementation_type, status, created_by)
VALUES ('信息检索', '搜索网页并整理结果，用于回答需要外部资料的问题', 'skill', 'active', 'system'),
       ('报告撰写', '将对话过程整理为结构化报告（Markdown 输出）', 'skill', 'active', 'system'),
       ('代码审查', '对代码变更做静态审查并输出问题清单与建议', 'skill', 'active', 'system');

INSERT INTO tools (name, description, tool_type, input_schema, mcp_endpoint, status)
VALUES ('Web 搜索', '通过搜索引擎获取最新网页结果', 'mcp',
        '{"type":"object","properties":{"query":{"type":"string","description":"搜索关键词"},"limit":{"type":"integer","default":5}},"required":["query"]}'::jsonb,
        NULL, 'active'),
       ('文件读写', '在工作区内读取和写入文件', 'builtin',
        '{"type":"object","properties":{"path":{"type":"string","description":"文件路径"},"content":{"type":"string"}},"required":["path"]}'::jsonb,
        NULL, 'active'),
       ('数据库查询', '对平台 PostgreSQL 执行只读 SQL 查询', 'mcp',
        '{"type":"object","properties":{"sql":{"type":"string","description":"只读 SQL"}},"required":["sql"]}'::jsonb,
        NULL, 'inactive');
