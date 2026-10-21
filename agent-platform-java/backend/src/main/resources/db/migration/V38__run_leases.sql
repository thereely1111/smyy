-- run 级 checkpoint：在途 run 租约（2026-09-14，可靠性收口专项·批次1）
--
-- 背景：框架已在每个工具边界把 AgentState（含完整消息上下文与迭代游标）持久化
-- 到 stateStore，但「会话有 run 在途」这一事实只存在于进程内存——重启后无从
-- 得知有 run 被打断，只能靠 Progress watchdog 空闲兜底被动发现（2026-09-14
-- 实证：17:13 重启打断第7批，返工 40 分钟）。
--
-- 本表把「run 在途」落库：AG-UI run（PlatformAguiRuntimeContextResolver）与
-- wakeup run（AguiWakeupTarget）启动时 upsert 一行；RunLeaseReconciler 周期
-- 心跳（会话运行中）/ 清理（会话空闲）。进程死亡时心跳停止，重启后被识别为
-- 孤儿租约 → 向会话 inbox push 恢复提示，由既有 InboxWatchdogWakeupService
-- 自动补醒，父会话从最后一个成功工具边界之后续推（框架保证上下文完整）。
--
-- instance_id 为进程级 UUID：区分「当前进程的正常生命周期」与「死亡进程遗留」
-- ——后者无需等待心跳过期，重启后首轮巡检即可确定性恢复。
--
-- 粒度：每会话一行（同一会话同时只有一个活跃 run；重叠 run 由
-- ON CONFLICT 覆盖，run 结束的清理以 SessionActivityTracker 为准）。

CREATE TABLE run_leases (
    id           UUID         PRIMARY KEY,
    user_id      VARCHAR(64)  NOT NULL,
    session_id   VARCHAR(255) NOT NULL,
    agent_id     VARCHAR(64),
    instance_id  VARCHAR(64)  NOT NULL,
    started_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    heartbeat_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_run_leases_session UNIQUE (session_id)
);

-- 孤儿巡检主路径：跨实例清理按 instance_id 过滤
CREATE INDEX idx_run_leases_instance ON run_leases (instance_id);
