-- M6.3：Agent 非对话式启动模板（功能卡片：标题 + 结构化指令 + 可选预填参数）
ALTER TABLE agent_configs
    ADD COLUMN launch_templates JSONB NOT NULL DEFAULT '[]'::jsonb;
