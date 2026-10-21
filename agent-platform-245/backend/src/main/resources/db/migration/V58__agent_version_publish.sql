-- 智能体版本与发布模型（发布快照，2026-09-24）：
-- 对外永远只呈现一个发布版本。agent_configs 主行 = 当前发布内容；
-- version 为当前发布版本号，每次发布动作 +1；已发布智能体的修改先落
-- draft_snapshot（草稿快照），发布时覆盖主行并清空；旧版本不删除，
-- 由 agent_config_revisions 存档（历史查看 / 回滚用，不对外）。
ALTER TABLE agent_configs
    ADD COLUMN version INT NOT NULL DEFAULT 1,
    ADD COLUMN draft_snapshot jsonb;

CREATE TABLE agent_config_revisions (
    id           uuid PRIMARY KEY,
    agent_id     uuid NOT NULL,
    version      INT NOT NULL,
    snapshot     jsonb NOT NULL,
    published_by varchar(128),
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    CONSTRAINT uk_agent_config_revision UNIQUE (agent_id, version)
);
CREATE INDEX idx_agent_config_revisions_agent ON agent_config_revisions (agent_id);

-- 存量已发布（active）智能体补 v1 发布快照：保证升级后对它们的第一笔修改
-- 走「草稿 / 发布 +1」语义，广场在编辑期间仍展示本快照对应的发布内容。
-- 快照按 AgentConfigRequest 字段形状（camelCase）组织，回滚时可直接反序列化。
INSERT INTO agent_config_revisions
    (id, agent_id, version, snapshot, published_by, created_at, updated_at)
SELECT gen_random_uuid(), c.id, 1,
       jsonb_build_object(
           'name', c.name,
           'description', c.description,
           'systemPrompt', c.system_prompt,
           'skillIds', c.skill_ids,
           'toolIds', c.tool_ids,
           'subagents', c.inline_subagents,
           'launchTemplates', c.launch_templates,
           'planEnabled', c.plan_enabled,
           'skillSedimentation', c.skill_sedimentation,
           'welcomeForm', c.welcome_form,
           'form', c.form,
           'formTrigger', c.form_trigger
       ),
       NULL, c.updated_at, c.updated_at
FROM agent_configs c
WHERE c.status = 'active';
