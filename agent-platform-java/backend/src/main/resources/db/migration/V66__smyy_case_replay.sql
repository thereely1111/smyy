-- V66: 外包案例新增「复现」板块（replay_html）
-- 复现板块为独立的全屏交互演示（如微网电力调控复现），由案例管理「提交案例」上传单个 HTML 写入本列。
ALTER TABLE smyy_outsourced_cases
    ADD COLUMN IF NOT EXISTS replay_html TEXT;
