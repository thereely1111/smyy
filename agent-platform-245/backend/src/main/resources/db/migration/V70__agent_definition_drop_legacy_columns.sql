-- Agent 模块重构 M5：删除已无判据位置的列
--
-- 前置（已在测试环境核对）：
--   M3 收编自愈 —— 定义区目录补齐 agent_configs 绑定行（FileSystemAgentLoader.adoptIntoDatabase）
--   M4 去分流 —— 装配与响应不再按 file_based 分叉，判据改为「DB 有无行」
--   M1/M2/M2.1 —— frontmatter 标签写入 agent_tags；inline_subagents 回填为 subagents/*.md
--
-- 为什么现在可以删：
--   inline_subagents 全库为空（存量唯一一行「编程助手」的该列为空数组），
--   4 个文件型智能体的子智能体早已在磁盘 subagents/ 下（2/0/0/6），无数据依赖。
--   file_based 唯一一行为 false，且代码不再读它。
--
-- ⚠️ 不可回滚：列一旦删除，旧版本实例（仍映射这两列）会在查询时报
-- "column ... does not exist"。上线前必须确认没有旧版本 Pod 在跑。
-- 幂等写法保证重复执行不报错，但**不会**恢复已删的列。

-- 删除 file_based：每个智能体都必绑一个定义区 + 一行绑定，该列无处安放
ALTER TABLE agent_configs
    DROP COLUMN IF EXISTS file_based;

-- 删除 inline_subagents：子智能体定义的唯一真相源是 subagents/*.md，
-- 该列是重构前的过渡态（definition 域与元数据域重叠的产物）
ALTER TABLE agent_configs
    DROP COLUMN IF EXISTS inline_subagents;