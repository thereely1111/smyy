-- 移除四个演示智能体（2026-09-21）：广场不再展示内置 demo bot。
--   * Survey Demo Bot          （V12 创建）
--   * Skill Demo Bot           （V14 创建，fixed id c0ffee01-…）
--   * Survey Demo Bot V2       （V19 创建，fixed id 3c8f7a21-…）
--   * Skill+Subagent Demo Bot  （V13 以 "Survey Subagent Bot" 创建，V20 改名）
-- 历史迁移已应用不可改写（Flyway checksum），故在新迁移中统一删除；
-- 全新库则按 V12→V53 顺序先插后删，净效果同样是不存在。
--
-- 关联数据清理：
--   * agent_tags      —— FK ON DELETE CASCADE 随行清理；
--   * agent_form_answers —— 对 agent_id 无外键，显式删除；
--   * 历史会话表对 agent_id 无外键，保留为孤儿数据，不影响会话列表（按
--     agent_configs join）。
-- 平台能力不在删除范围：tools.ui_form_advanced、skills.form-trigger-helper
-- （V13 演示技能，删除后暂无智能体引用，保留待后续处置）。

DELETE FROM agent_form_answers
WHERE agent_id IN (
    SELECT id FROM agent_configs
    WHERE name IN (
        'Survey Demo Bot', 'Survey Demo Bot V2',
        'Skill Demo Bot', 'Skill+Subagent Demo Bot'
    )
);

DELETE FROM agent_configs
WHERE name IN (
    'Survey Demo Bot', 'Survey Demo Bot V2',
    'Skill Demo Bot', 'Skill+Subagent Demo Bot'
);
