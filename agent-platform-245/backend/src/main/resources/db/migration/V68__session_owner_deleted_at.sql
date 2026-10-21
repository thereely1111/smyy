-- V67: 会话删除墓碑（deleted_at）
--
-- 背景（2026-10-13 用户报障）：删除会话后该会话仍在后台运行，且删除后又生成了产物。
-- 根因不是「没停run」，而是**删除是纯删除、全库无任何「已删除」记录**：
--   1. SessionCleanupService.purgeSessionData 把 agent_session_progress 行删掉
--      —— 而 stop_requested / awaiting_human_gate 两道唤醒抑制标记的载体正是这张表；
--   2. sessionOwnerService.deleteSession 把 agent_session_owners 行删掉
--      —— 而「会话是否已归档」的判据 isArchivedBySession 走 findBySessionId().orElse(false)，
--         行不存在即返回 false（= 未归档 = 放行）；
--   三个抑制判据的载体被删除动作本身清掉 ⇒ 全部读成「未封存」⇒ 一律放行。
--   随后 AguiWakeupTarget.doWakeup 执行 ensureSessionDir + record，
--   **重建产物目录并重写归属行** ⇒ 会话在列表里复活 ⇒ 模型继续 write_file 出产物。
--
-- 本列提供墓碑：删除会话时不再物理删除归属行，改为置 deleted_at。
-- 所有唤醒链路（AguiWakeupTarget / InboxWatchdogWakeupService / TaskDeliveryWatchdog /
-- SessionProgressService 停滞看护 / StartupStaleTaskCleaner）在入口读本列，
-- 命中即静默跳过，使「已删除」成为一等判据而非「查无此行」。
--
-- 与 archived_at 的区别（不可复用同一列）：
--   archived_at = 弃用，可逆（取消归档即恢复，数据全在）；
--   deleted_at  = 删除，不可逆（状态行与产物目录已物理删除），且必须留痕以防复活。
--
-- 行保留策略：owner 行不再随会话删除而消失，故会话列表 / 按用户清理 / 统计
-- 一律需显式排除 deleted_at IS NOT NULL（见 AgentSessionOwnerRepository）。
ALTER TABLE agent_session_owners
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

-- 唤醒链路的墓碑查询走 session_id（isDeletedBySession 不带 agentId），
-- 复用既有 idx_session_owners_active 之外的独立索引以免拖累列表查询。
CREATE INDEX IF NOT EXISTS idx_session_owners_deleted
    ON agent_session_owners (session_id)
    WHERE deleted_at IS NOT NULL;