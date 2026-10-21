-- M6.4：Agent 启用 Plan 模式开关（plan_write/plan_exit + 计划文件 + 批准暂停）
ALTER TABLE agent_configs
    ADD COLUMN plan_enabled BOOLEAN NOT NULL DEFAULT FALSE;
