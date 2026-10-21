-- 演示标签种子数据（2026-09-20）：为 file-based 演示智能体提供可匹配的标签池。
-- AGENTS.md frontmatter 里的 tags 按名匹配本表（忽略大小写），未知名静默跳过；
-- 因此种子先行落库，四个演示智能体的 frontmatter 才能在广场上显示标签。
-- ON CONFLICT DO NOTHING：标签是封闭集合，管理员后续改名/停用以 DB 为准，重放不覆盖。
INSERT INTO tags (name, color, sort, enabled) VALUES
    ('数据分析', '#2563eb', 1, TRUE),
    ('办公协作', '#7c3aed', 2, TRUE),
    ('教育培训', '#059669', 3, TRUE),
    ('生活助手', '#ea580c', 4, TRUE)
ON CONFLICT (name) DO NOTHING;
