-- 停滞补醒封顶与人工介入标记（2026-09-16，停滞自愈真修复）
--
-- 背景：补醒判据原本建立在「模型自报 [进度] 行 / 尾语关键词」上（next_action=CONTINUE
-- 才补醒）。模型一旦不守约判为 UNKNOWN，看护就既不补醒也不告警，会话只能静默空转
-- ——教材会话 2026-09-16 实证连续空转 7 小时（04:53→11:5x），18d/18e 一直没派出去。
--
-- 判据改为平台自有事实后，UNKNOWN 也会补醒，必须配套两个新能力，否则会从
-- 「静默空转」退化成「无限补醒烧 token」：
--   wake_count      —— 同一轮停滞内已补醒次数（无实质进展才累加）
--   last_progress_at—— 最近一次「真有进展」的时间（补醒本身不算进展）
--   needs_human     —— 补醒达上限后不再自愈，转人工介入（前端/告警据此提示）
--
-- 判据（service 侧实现）：无活跃 run + inbox 无待消费 + 无未决 HITL 登记
-- + idle 超阈值 + 未达补醒上限 → 补醒；达上限 → 置 needs_human 并发 error 级告警。

ALTER TABLE agent_session_progress
    ADD COLUMN wake_count        INT          NOT NULL DEFAULT 0,
    ADD COLUMN last_progress_at  TIMESTAMPTZ,
    ADD COLUMN needs_human       BOOLEAN      NOT NULL DEFAULT false;

COMMENT ON COLUMN agent_session_progress.wake_count IS
    '本轮停滞已补醒次数；产生实质进展时归零，达 progress-watchdog.max-wake-attempts 后停止补醒';
COMMENT ON COLUMN agent_session_progress.last_progress_at IS
    '最近一次实质进展时间（新消息/尾文本变化/活跃时间推进）；补醒本身不算进展';
COMMENT ON COLUMN agent_session_progress.needs_human IS
    '补醒达上限仍无进展 → 转人工介入，看护不再自愈，避免无限烧 token';
