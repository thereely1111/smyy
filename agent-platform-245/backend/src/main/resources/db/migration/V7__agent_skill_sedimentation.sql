-- M6.5：Agent 技能沉淀开关（skill_manage 创建技能 + SkillCurator + 安全扫描 + 自动 promote 到 prod）
ALTER TABLE agent_configs
    ADD COLUMN skill_sedimentation BOOLEAN NOT NULL DEFAULT FALSE;
