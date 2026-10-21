package com.tipdm.agentplatform.harness;

import io.agentscope.harness.agent.bus.MessageBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 孤儿 run 恢复巡检——run 级 checkpoint 的「启动恢复」半边。
 *
 * <p><b>问题</b>：框架 stateStore 已在每个工具边界持久化 AgentState（完整消息
 * 上下文，含 tool_use/tool_result 对），重启后状态可完整还原；但「会话有 run
 * 在途」只活在进程内存（{@link SessionActivityTracker}），进程死亡即蒸发——
 * 重启后没人知道有 run 被打断，也没人重新驱动它。2026-09-14 实证：17:13 重启
 * 打断第7批子任务后，靠 Progress watchdog 空闲兜底 + 子智能体自行申报才恢复，
 * 返工约 40 分钟。</p>
 *
 * <p><b>机制</b>：巡检 {@link RunLeaseStore}（run 启动时由
 * {@code PlatformAguiRuntimeContextResolver} / {@code AguiWakeupTarget} 登记），
 * 三条规则：</p>
 * <ol>
 *   <li><b>跨实例孤儿</b>：租约 instance_id ≠ 当前进程 ⇒ 写入方进程已死亡，
 *       run 被打断是确定事实 → 向会话 inbox push 恢复提示并删租约。重启后首轮
 *       巡检即恢复，无需等待心跳过期；</li>
 *   <li><b>运行中</b>：{@link SessionActivityTracker} 报告会话在跑 → 刷新心跳
 *       （心跳即生命线，LLM 流停滞的 run 依然算活跃，不在此处理范围）；</li>
 *   <li><b>已结束</b>：会话空闲且心跳陈旧（&gt;{@link #IDLE_DELETE_AGE}）→ 删租约。
 *       设 2 分钟宽限避免与「run 刚结束/新 run 刚接管」的瞬时竞态互踩；陈旧且
 *       空闲⇒运行端已消失（正常的 run 结束由本规则在 1-2 分钟内收尾）。</li>
 * </ol>
 *
 * <p><b>恢复动作刻意最小</b>：只向 inbox push 一条「重启恢复」提示，随后由既有
 * {@code InboxWatchdogWakeupService}（30s 巡检，自带 owner 解析与冷却）补醒，
 * 复用 wakeup 全链路（归属分区、闸门守卫、权限对账）。父会话被唤醒后从最后
 * 一个成功工具边界之后续推——上下文完整落盘，不重放工具调用，天然幂等。</p>
 *
 * <p><b>已知边界</b>：run 在「结束后的 60s 清理窗口内」撞上进程重启时会产生
 * 一次误报恢复提示（提示内容本就要求先自查再行动，代价可忽略）；hint push 失败
 * 保留租约下轮重试（at-least-once），重复提示由会话侧自查消化。</p>
 */
@Component
public class RunLeaseReconciler {

    private static final Logger log = LoggerFactory.getLogger(RunLeaseReconciler.class);

    /** 会话空闲多久后视为「运行端已消失」，清理本进程租约。 */
    private static final Duration IDLE_DELETE_AGE = Duration.ofMinutes(2);

    private final RunLeaseStore leaseStore;
    private final SessionActivityTracker activityTracker;
    private final MessageBus messageBus;

    public RunLeaseReconciler(
            RunLeaseStore leaseStore,
            SessionActivityTracker activityTracker,
            MessageBus messageBus) {
        this.leaseStore = leaseStore;
        this.activityTracker = activityTracker;
        this.messageBus = messageBus;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void sweep() {
        List<RunLeaseStore.RunLease> leases;
        try {
            leases = leaseStore.findAll();
        } catch (RuntimeException e) {
            log.warn("Run lease reconciler: scan failed: {}", e.getMessage());
            return;
        }
        for (RunLeaseStore.RunLease lease : leases) {
            try {
                inspect(lease);
            } catch (RuntimeException e) {
                log.debug("Run lease reconciler: inspect failed for session {}: {}",
                        lease.sessionId(), e.getMessage());
            }
        }
    }

    private void inspect(RunLeaseStore.RunLease lease) {
        String sessionId = lease.sessionId();

        // 1. 死亡进程遗留：重启后首轮即确定性恢复。
        if (!leaseStore.instanceId().equals(lease.instanceId())) {
            recover(lease, "previous process (restart interrupted the run)");
            return;
        }

        // 2. 本进程运行中：心跳即生命线。
        if (activityTracker.isRunning(sessionId)) {
            leaseStore.heartbeat(sessionId);
            return;
        }

        // 3. 本进程已空闲：宽限期后清理（正常结束的 run 由本规则收尾）。
        if (lease.age().compareTo(IDLE_DELETE_AGE) > 0) {
            leaseStore.delete(sessionId);
        }
    }

    /** 向会话 inbox push 恢复提示；投递成功才删租约（失败下轮重试）。 */
    private void recover(RunLeaseStore.RunLease lease, String reason) {
        String sessionId = lease.sessionId();
        log.warn(
                "Orphan run recovery: session {} has lease from {} — pushing recovery hint "
                        + "(agent={}, user={}, last heartbeat={})",
                sessionId, reason, lease.agentId(), lease.userId(), lease.heartbeatAtText());
        String hint = String.format(
                "<system-notification>Platform restart detected: a run in this session was "
                        + "interrupted by a restart (last activity %s). Before doing anything else, "
                        + "self-check current progress (task list and workspace files) to determine "
                        + "which deliverables are already written and which are missing, then decide "
                        + "whether to resume or redispatch the unfinished part.</system-notification>",
                lease.heartbeatAtText());
        Map<String, Object> payload = Map.of(
                "type", "hint",
                "id", UUID.randomUUID().toString().replace("-", ""),
                "hint", hint,
                "source", "run_lease_recovery");
        messageBus.inboxPush(sessionId, payload)
                .doOnSuccess(v -> {
                    leaseStore.delete(sessionId);
                    log.info("Orphan run recovery: hint pushed for session {}, lease released", sessionId);
                })
                .doOnError(e -> log.warn(
                        "Orphan run recovery: inboxPush failed for session {}: {} (will retry next sweep)",
                        sessionId, e.getMessage()))
                .subscribe();
    }
}
