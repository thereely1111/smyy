-- 下线子 Agent 模板库链路：subagent_templates 表与 agent_configs.subagent_ids 列不再使用
-- （现配子 Agent 存 agent_configs.inline_subagents，见 V45）。
DROP TABLE IF EXISTS subagent_templates;

ALTER TABLE agent_configs
    DROP COLUMN IF EXISTS subagent_ids;
