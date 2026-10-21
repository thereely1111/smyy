-- V64: 数模演易外包案例可视化演示分段
--
-- animation_segments_json 保存案例可视化 HTML 的分段结构，例如：
-- [
--   {"key":"global","label":"全局分析","fileName":"p5_sem-ad-optimization_animation_global_v1.html","html":"..."},
--   {"key":"problem1","label":"问题一","fileName":"p5_sem-ad-optimization_animation_problem1_v1.html","html":"..."}
-- ]
ALTER TABLE smyy_outsourced_cases
    ADD COLUMN IF NOT EXISTS animation_segments_json TEXT;
