-- 为 agent_configs 表添加 file_based 列，标识是否为文件形式的 Agent
-- 默认值为 false，且不允许为空（与实体 @Column(nullable = false) 保持一致）
ALTER TABLE agent_configs
    ADD COLUMN file_based BOOLEAN NOT NULL DEFAULT false;