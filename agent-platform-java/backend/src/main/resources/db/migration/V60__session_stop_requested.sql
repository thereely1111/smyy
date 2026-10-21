-- V60: 会话「用户停止」抑制标记
--
-- interrupt 置位、用户主动发起新 run 时清除。置位期间所有自发 run 唤醒源静默：
-- 框架 wakeup 在 AguiWakeupTarget.runWakeup 入口拦截，停滞看护自检注入在
-- SessionProgressService.shouldWake 拦截。背景：interrupt 只杀当前 run，
-- 被中断 pipeline 的子任务完成回执会事件驱动拉起下一轮（清数据跑不赢推送），
-- 2026-09-29 实证形成复活循环。
ALTER TABLE agent_session_progress
    ADD COLUMN IF NOT EXISTS stop_requested BOOLEAN NOT NULL DEFAULT FALSE;
