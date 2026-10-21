-- 技能资源支持（A+B 方案）：
-- A：resources 文本资源（相对路径 → 文本内容，如 scripts/foo.py、references/api.md），
--    随 JpaSkillRepository 适配进 AgentScope AgentSkill.resources，load 技能时落进会话工作区。
-- B：origin_dir 磁盘源目录（绝对路径，git 型导入时快照拷贝），非空时框架 prompt provider
--    对该技能输出 <files-root>，模型可 shell 直执行目录内脚本；load 工具可回源磁盘。
ALTER TABLE skills ADD COLUMN IF NOT EXISTS resources JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE skills ADD COLUMN IF NOT EXISTS origin_dir TEXT;
