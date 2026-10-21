-- 标签 color 字段下线（2026-09-21）：选中态统一品牌蓝后，前端不再消费
-- per-tag 颜色，字段无消费方，直接删除。V50 建列、V51 种子数据中的色值一并失效。
ALTER TABLE tags DROP COLUMN color;
