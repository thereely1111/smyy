package com.tipdm.agentplatform.harness;

import com.tipdm.agentplatform.config.HitlTimeoutProperties;
import com.tipdm.agentplatform.domain.AgentSessionOwner;
import com.tipdm.agentplatform.service.AgentAssemblyService;
import com.tipdm.agentplatform.service.PendingFormInterruptStore;
import com.tipdm.agentplatform.service.SessionOwnerService;
import com.tipdm.agentplatform.service.notify.AlertNotifier;
import com.tipdm.agentplatform.service.notify.SessionNotifyPublisher;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 启动对账：进程重启后，把持久化 agent state 里幸存的孤儿 HITL 闸门直接销毁。
 *
 * <p><b>缺口（2026-09-20 实证）</b>：闸门以 TOOL_SUSPENDED 收尾后进程重启，
 * 框架 {@code pendingInterruptsByThread} 与平台 {@link PendingFormInterruptStore}
 * 同为进程内存、双双清空，闸门只剩持久化 state 里的未决 tool_use（无
 * tool_result；TOOL_SUSPENDED 持久化的 state 是 allowed 而非 ASKING）——前端
 * 弹窗照样渲染，但 reaper 扫不到（超时取消永不触发）、提交被框架 resume 契约
 * 拒绝（"does not match any open interrupt"）、watchdog 判「球在主编手里」不再
 * 补醒：弹窗永久挂起，用户不知道对话为什么中断。</p>
 *
 * <p><b>处置（2026-09-20 定版：销毁，不补登记）</b>：启动后延迟扫一遍全部父会话
 * 的持久化 state，对「最后一条 assistant 消息里的无结果 HITL tool_use 且其后
 * 无主编发言」直接打超时补丁（{@link HitlTimeoutReaper#patchAgentState}）：
 * tool_use 置 FINISHED + 追加 {@code [HITL_TIMEOUT]} tool_result + 在智能体输出
 * 下方追加 {@code [平台留痕]} 超时说明——弹窗随之关闭，用户能从转录里看到对话
 * 中断原因。不恢复可提交状态（重启前即已长时间无人处理，重新发起比续挂更干净）。
 * 运行期闸门的正常超时取消仍由 {@link HitlTimeoutReaper} 负责；运行期登记漂移由
 * 其框架对账安全网覆盖；本类只处理重启瞬间的内存丢失，且不做任何登记。</p>
 *
 * <p><b>口径与安全边界</b>：</p>
 * <ul>
 *   <li>只看最后一条 assistant 消息（与框架 askingToolCalls 同口径）；更早的
 *       tool_use 已被后续轮次消费或压缩，不碰。</li>
 *   <li>闸门之后有 user 消息 → 主编已回来（走 user-message 降级答复），不销毁。</li>
 *   <li>登记表已有同 toolCallId → 说明并非重启丢失（登记仍在），跳过、交给
 *       reaper 正常流程；本类只处理登记彻底消失的孤儿。</li>
 *   <li>会话在跑 → 跳过；只扫父会话（sub-* 的 pending 调用由
 *       {@link SubagentSessionMaintenance} 修复，职责互斥）。</li>
 *   <li>补丁走 CAS（at-least-once）：本轮失败仅告警，留待下次重启或人工处理；
 *       已有结果（并发提交）由 {@code patchAgentState} 自判 ALREADY_RESOLVED，
 *       不会覆盖真实提交。</li>
 * </ul>
 */
@Component
public class StartupHitlReconciler {

    private static final Logger log = LoggerFactory.getLogger(StartupHitlReconciler.class);

    /** 与 SubagentSessionMaintenance / 框架一致的 state 键。 */
    static final String STATE_KEY = "agent_state";

    /** 启动后延迟扫描秒数：等 AG-UI 反射链、装配缓存与告警通道就绪。 */
    static final long DELAY_SECONDS = 30;

    private final SessionOwnerService sessionOwnerService;
    private final AgentAssemblyService assemblyService;
    private final SessionActivityTracker activityTracker;
    private final PendingFormInterruptStore pendingFormInterruptStore;
    private final HitlTimeoutProperties timeoutProps;
    private final AlertNotifier alertNotifier;
    private final SessionNotifyPublisher notifyPublisher;
    private final ScheduledExecutorService scheduler;

    public StartupHitlReconciler(
            SessionOwnerService sessionOwnerService,
            AgentAssemblyService assemblyService,
            SessionActivityTracker activityTracker,
            PendingFormInterruptStore pendingFormInterruptStore,
            HitlTimeoutProperties timeoutProps,
            AlertNotifier alertNotifier,
            SessionNotifyPublisher notifyPublisher) {
        this.sessionOwnerService = sessionOwnerService;
        this.assemblyService = assemblyService;
        this.activityTracker = activityTracker;
        this.pendingFormInterruptStore = pendingFormInterruptStore;
        this.timeoutProps = timeoutProps;
        this.alertNotifier = alertNotifier;
        this.notifyPublisher = notifyPublisher;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "startup-hitl-reconciler");
            t.setDaemon(true);
            return t;
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        scheduler.schedule(this::sweep, DELAY_SECONDS, TimeUnit.SECONDS);
    }

    void sweep() {
        List<AgentSessionOwner> owners;
        try {
            owners = sessionOwnerService.all();
        } catch (RuntimeException e) {
            log.warn("Startup HITL reconciliation: cannot list session owners: {}", e.getMessage());
            return;
        }
        int scanned = 0;
        int destroyed = 0;
        for (AgentSessionOwner owner : owners) {
            String agentId = owner.getAgentId();
            String userId = owner.getUserId();
            if (agentId == null || userId == null || agentId.isBlank() || userId.isBlank()) {
                continue;
            }
            HarnessAgent agent;
            try {
                agent = assemblyService.requireAssembledById(agentId);
            } catch (RuntimeException e) {
                log.debug("Startup HITL reconciliation: agent {} not assemblable: {}",
                        agentId, e.getMessage());
                continue;
            }
            if (agent == null || agent.getStateStore() == null) {
                continue;
            }
            AgentStateStore store = agent.getStateStore();
            Set<String> sessionIds;
            try {
                sessionIds = store.listSessionIds(userId);
            } catch (RuntimeException e) {
                log.warn("Startup HITL reconciliation: list sessions failed (agent={}, user={}): {}",
                        agentId, userId, e.getMessage());
                continue;
            }
            for (String sessionId : sessionIds) {
                if (sessionId == null
                        || sessionId.startsWith(SubagentSessionMaintenance.SUB_SESSION_PREFIX)) {
                    continue;
                }
                if (activityTracker.isRunning(sessionId)) {
                    continue; // 在跑的一律不动
                }
                scanned++;
                AgentState state;
                try {
                    state = store.get(userId, sessionId, STATE_KEY, AgentState.class).orElse(null);
                } catch (RuntimeException e) {
                    log.debug("Startup HITL reconciliation: state read failed for {}: {}",
                            sessionId, e.getMessage());
                    continue;
                }
                if (state == null) {
                    continue;
                }
                destroyed += reconcileSession(agentId, userId, sessionId, state, store);
            }
        }
        if (destroyed > 0) {
            log.warn("Startup HITL reconciliation: scanned {} session(s), destroyed {} orphaned "
                    + "gate(s) surviving a restart", scanned, destroyed);
        } else {
            log.info("Startup HITL reconciliation: scanned {} session(s), no orphan gate found",
                    scanned);
        }
    }

    /**
     * 单会话对账：返回销毁（打超时补丁成功）的闸门数。
     *
     * <p><b>孤儿判定口径（2026-09-20 实测校准）</b>：TOOL_SUSPENDED 挂起的闸门
     * 持久化后 tool_use state 是 {@code allowed}（ASKING 只是运行内存态，不落盘），
     * 不能按 ASKING 识别。真实特征是：最后一条 assistant 消息里的 HITL tool_use
     * 在<b>全上下文中没有对应的 tool_result</b>——正常消费的闸门必有结果（主编
     * 提交回填 / reaper 超时补写），无结果即孤儿。tool_use state 取什么值不关心，
     * 是否补丁由 {@code patchAgentState} 的 ALREADY_RESOLVED/STALE 兜底。</p>
     */
    int reconcileSession(
            String agentId, String userId, String sessionId, AgentState state, AgentStateStore store) {
        List<Msg> context = state.getContext() == null ? List.of() : state.getContext();
        int lastAssistant = -1;
        for (int i = context.size() - 1; i >= 0; i--) {
            if (context.get(i).getRole() == MsgRole.ASSISTANT) {
                lastAssistant = i;
                break;
            }
        }
        if (lastAssistant < 0) {
            return 0;
        }
        // 闸门之后主编已发言（含 user-message 降级答复）→ 线程已推进，不销毁。
        for (int i = lastAssistant + 1; i < context.size(); i++) {
            if (context.get(i).getRole() == MsgRole.USER) {
                return 0;
            }
        }
        // 全上下文中已有结果的 tool_call id（闸门结果可能落在后续 TOOL 消息里）。
        Set<String> answered = new HashSet<>();
        for (Msg m : context) {
            for (ToolResultBlock r : m.getContentBlocks(ToolResultBlock.class)) {
                if (r.getId() != null) {
                    answered.add(r.getId());
                }
            }
        }
        int count = 0;
        Msg last = context.get(lastAssistant);
        for (ContentBlock block : last.getContent()) {
            if (!(block instanceof ToolUseBlock toolUse)
                    || toolUse.getId() == null || toolUse.getId().isBlank()
                    || answered.contains(toolUse.getId())) {
                continue;
            }
            String toolName = toolUse.getName();
            if (toolName == null || !HitlRecoveryGuardMiddleware.HITL_TOOLS.contains(toolName)) {
                continue; // 非 HITL 工具的未决调用不在对账范围
            }
            // 登记仍在 → 不是重启孤儿（可能只是运行期漂移），交给 reaper 正常流程。
            if (isRegistered(agentId, sessionId, toolUse.getId())) {
                continue;
            }
            String toolCallId = toolUse.getId();
            String traceText = traceText(toolUse);
            HitlTimeoutReaper.PatchOutcome outcome;
            try {
                outcome = HitlTimeoutReaper.patchAgentState(
                        store, userId, sessionId, toolCallId, toolName,
                        timeoutProps.getApprovalTimeoutMinutes(), traceText);
            } catch (RuntimeException e) {
                log.warn("Startup HITL reconciliation: patch failed for session {} gate {}: {}",
                        sessionId, toolCallId, e.getMessage());
                continue;
            }
            if (outcome == HitlTimeoutReaper.PatchOutcome.PATCHED) {
                count++;
                log.warn("Startup HITL reconciliation: destroyed orphaned gate {} for session {} "
                                + "(agent={}, tool={}) — the gate survived a restart with both "
                                + "in-memory registries wiped; it can no longer be submitted",
                        toolCallId, sessionId, agentId, toolName);
                // 告警 + 游标信号（触发前端转录同步，弹窗随同步关闭）。尽力而为。
                try {
                    alertNotifier.publish(new AlertNotifier.AlertRequest(
                            AlertNotifier.LEVEL_WARN,
                            "会话中断遗留的审批表单已自动关闭",
                            "系统重启后检测到一笔未提交的审批表单/闸门（等待已超过 "
                                    + timeoutProps.getApprovalTimeoutMinutes()
                                    + " 分钟），已按超时自动关闭并在会话中留痕。"
                                    + "这不是审核结论；如需继续，请在会话中说明，智能体会重新发起审批。",
                            userId, sessionId, agentId,
                            "startup-hitl-timeout:" + sessionId + ":" + toolCallId));
                } catch (RuntimeException e) {
                    log.debug("Startup HITL reconciliation: alert failed for {}: {}",
                            sessionId, e.getMessage());
                }
                try {
                    notifyPublisher.publish(userId, sessionId, agentId, null,
                            SessionNotifyPublisher.REASON_INTERRUPT);
                } catch (RuntimeException e) {
                    log.debug("Startup HITL reconciliation: notify failed for {}: {}",
                            sessionId, e.getMessage());
                }
            } else {
                log.info("Startup HITL reconciliation: gate {} for session {} not patched "
                                + "(outcome={}); FAILED cases are retried on next startup",
                        toolCallId, sessionId, outcome);
            }
        }
        return count;
    }

    private boolean isRegistered(String agentId, String sessionId, String toolCallId) {
        try {
            List<PendingFormInterruptStore.PendingFormInterrupt> existing =
                    pendingFormInterruptStore.listBySession(agentId, sessionId);
            if (existing == null) {
                return false;
            }
            for (PendingFormInterruptStore.PendingFormInterrupt e : existing) {
                if (toolCallId.equals(e.toolCallId())) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            // 查询失败按未登记处理：销毁路径有 ALREADY_RESOLVED 保护，不会覆盖真实提交。
            log.debug("Startup HITL reconciliation: store lookup failed for {}: {}", sessionId,
                    e.getMessage());
            return false;
        }
    }

    /**
     * 输出下方的超时说明（{@code [平台留痕]} 前缀，前端按注脚行渲染）。让用户在
     * 弹窗消失后仍能看到对话中断原因。前缀是前端识别标记（transcriptSync
     * {@code isPlatformTraceMessage}），不可省略；正文保持一句事实，时间由前端
     * 从消息 createdAt 渲染，不在文本内重复。
     */
    private String traceText(ToolUseBlock toolUse) {
        String title = extractTitle(toolUse);
        String display = (title == null || title.isBlank())
                ? "表单/闸门" : "表单/闸门「" + title + "」";
        return "[平台留痕] 等待用户审核超时，" + display + "已自动关闭";
    }

    /** 尽力提取表单标题（ui_gate/ui_form_advanced 入参里的 title 字段）；缺失返回 null。 */
    private static String extractTitle(ToolUseBlock toolUse) {
        try {
            Map<String, Object> input = toolUse.getInput();
            if (input == null) {
                return null;
            }
            Object title = new HashMap<>(input).get("title");
            return title instanceof String s && !s.isBlank() ? s : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
