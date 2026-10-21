-- V61: 纯文本收尾登记（完成契约的前置事实）
--
-- 宣告式收尾检测器（run 级）改为纯观察：run 以「末条助手文本后无工具调用」
-- 收场时只登记时间戳，不再自行决策续跑——决策收敛到停滞看护（它手里才有
-- waitingHuman / stop_requested / inbox / 完成契约等完整事实）。
-- 看护读到「近期存在纯文本收尾且完成契约未核验通过」时提前补醒（宽限期后），
-- 不必等满 8 分钟停滞阈值。
-- 写入语义同 stop_requested（V60）：upsert 周期写不覆盖，仅由登记入口显式更新。
ALTER TABLE agent_session_progress
    ADD COLUMN IF NOT EXISTS last_text_ending_at TIMESTAMPTZ;
