-- 子 Agent 现配改为数据库存储：agent_configs 增加 inline_subagents JSONB 列
-- （List<AgentSubagent>：name/description/prompt/model/tools/steps），随主配置整体读写。
-- 弃用 workspace/agents/{id}/subagents/*.md 文件驱动方式（该方式仅作过参考实现）。
ALTER TABLE agent_configs
    ADD COLUMN inline_subagents jsonb NOT NULL DEFAULT '[]'::jsonb;
