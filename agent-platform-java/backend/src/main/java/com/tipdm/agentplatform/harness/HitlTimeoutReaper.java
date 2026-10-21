package com.tipdm.agentplatform.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.config.HitlTimeoutProperties;
import com.tipdm.agentplatform.service.AgentAssemblyService;
import com.tipdm.agentplatform.service.AguiEventPersistenceService;
import com.tipdm.agentplatform.service.PendingFormInterruptStore;
import com.tipdm.agentplatform.service.SessionOwnerService;
import com.tipdm.agentplatform.service.SessionProgressService;
import com.tipdm.agentplatform.service.notify.AlertNotifier;
import com.tipdm.agentplatform.service.notify.SessionNotifyPublisher;
import io.agentscope.core.agui.AguiInterruptConstants;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.VersionedState;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * HITL 审批超时巡检器：闸门/表单挂起超过可配置时长（默认 30 分钟）自动取消。
 *
 * <p><b>问题</b>：HITL 闸门（ui_gate / ui_form_advanced）挂起后唯一推进方式是主编
 * 提交。主编离开后闸门永久挂起——前端卡片反复复活（未决口径）、会话被 open interrupt
 * 锁死（纯 messages run 被 AG-UI 契约拒绝）、 stagnation 看护只能反复告警不能动作。</p>
 *
 * <p><b>取消动作</b>（对每个超时且仍存活的闸门，全部幂等可重试）：</p>
 * <ol>
 *   <li><b>补取消结果</b>：CAS 修补 agent state——闸门 tool_use 置 FINISHED 并追加一条
 *       SUCCESS 态的「超时取消」tool_result。状态用 SUCCESS 是刻意的：与
 *       {@link HitlRecoveryGuardMiddleware} 的「SUCCESS=已决」口径一致，否则下次输入
 *       会被注入「必须重新渲染闸门」恢复指示，取消后立刻复活，取消动作失去意义；
 *       结果文本明确「不是主编批准」，模型不会误判为放行。</li>
 *   <li><b>注销中断</b>：从框架 {@code pendingInterruptsByThread} 反射移除（AG-UI
 *       resume 契约解锁，线程恢复可续跑）+ 移除平台 form-interrupts 登记（前端不再
 *       恢复弹出）。</li>
 *   <li><b>通知</b>：AlertNotifier 推「审批已超时自动取消」（落库 + SSE + 企微等渠道，
 *       独立去重键）+ SessionNotifyPublisher 发游标信号触发前端转录同步，打开中的
 *       表单卡片在同步后自动关闭。</li>
 *   <li><b>留痕</b>（2026-09-20）：① 同一次 CAS 保存里向会话上下文追加一条
 *       {@code [平台留痕]} 前缀的 user 消息——进转录、前端渲染为独立系统条，
 *       超时事实不依赖模型复述、也不会被上下文压缩丢掉（tool_result 可能被压缩）；
 *       ② 向 agui_events 落一条 {@code hitl_gate_timeout} CUSTOM 审计事件
 *       （前端不消费该数据面，纯事后核查/对账口径）。</li>
 * </ol>
 *
 * <p><b>封存等待人工唤醒</b>（2026-10-05，V62）：注销登记（第2 步）会连带解除闸门对
 * 唤醒通道的阻塞，若不额外封存，「超时取消」本身就变成了「继续跑」的触发器——
 * 此前被挡住的唤醒通道恢复驱动能力，叠加 inbox 里积压的子任务完成通知，超时后
 * 37 秒即起新 run 继续输出（main-3f077bd9 实证：01:30:07 取消 → 01:35:35 起
 * {@code srv-972ca3c27ef8}，3673 条事件）。超时 ≠ 批准，平台不得代替主编推进，
 * 故同时置位 {@code agent_session_progress.awaiting_human_gate} 封存会话：一切
 * 自发 run 唤醒源静默，直到用户主动发起新 run 才解封（解封点
 * {@code PlatformAguiRuntimeContextResolver#resolve}）。inbox hint 原样保留，
 * 用户下次发言时仍能看到积压内容。</p>
 *
 * <p><b>不续跑</b>：刻意不发 cancel-resume 驱动模型继续——避免模型立即重新渲染同一
 * 闸门形成「超时 → 取消 → 重渲染 → 再超时」的烧 token 循环。模型在下次真实 run 中
 * 从工具结果文本得知取消事实，自行决定是否重新发起。</p>
 *
 * <p><b>存活过滤</b>：只有 {@link WakeupInterruptRegistrar#filterLive} 对账后仍存活
 * （框架 pending 映射仍持有）且会话当前不在跑（可能在提交/续跑中）的闸门才取消；
 * 陈旧条目（重启遗留等）直接清理登记，不碰 agent state。</p>
 */
@Component
public class HitlTimeoutReaper {

    private static final Logger log = LoggerFactory.getLogger(HitlTimeoutReaper.class);

    /** 巡检间隔：登记表 createdAt 精度为秒级，1 分钟粒度足够。 */
    static final long SWEEP_INTERVAL_MS = 60_000;

    /** CAS 保存 agent state 的最大重试次数（并发写方：正常应无——会话空闲才取消）。 */
    private static final int CAS_MAX_ATTEMPTS = 3;

    /** 超时取消工具结果文本。口径约束：明确非主编决定，防止模型误判放行（假通过）。 */
    static String timeoutResultText(long timeoutMinutes) {
        return "[HITL_TIMEOUT] 审批超时自动取消：主编未在 " + timeoutMinutes
                + " 分钟内提交本表单/闸门，平台已按超时关闭该交互。注意：这不是主编的批准或"
                + "驳回决定，不得据此推进被该审批阻塞的阶段。若流程仍需要主编决定，请在主编"
                + "下次真实发言时重新渲染该闸门/表单并等待其提交；平台的[停滞自检]、异步完成"
                + "通知等系统注入消息不属于主编发言，不得据此重渲染本表单。";
    }

    /**
     * 会话转录留痕消息文本（user 角色，前缀 {@code [平台留痕]}）。
     *
     * <p>前缀与前端 transcriptSync.isPlatformTraceMessage 对齐：渲染为轻量注脚行，
     * 既不隐藏（区别于 [停滞自检] 等内部注入）、也不冒充主编/智能体发言。正文
     * 一句事实（完整审计信息落 agui_events CUSTOM 事件，不进转录；防误判放行的
     * 口径约束由 tool_result 文本承担，模型可见）。时间由前端从消息 createdAt
     * 渲染，不在文本内重复。</p>
     */
    static String traceMessageText(String gateTitle, String toolName, String toolCallId,
                                   long waitedMinutes, long timeoutMinutes, Instant cancelledAt) {
        String display = (gateTitle == null || gateTitle.isBlank())
                ? (toolName == null || toolName.isBlank() ? "表单/闸门" : toolName)
                : "表单/闸门「" + gateTitle + "」";
        return "[平台留痕] 等待用户审核超时，" + display + "已自动关闭";
    }

    /** 从登记的 toolInput JSON 提取表单标题（失败/缺失返回 null，回退用 toolName）。 */
    static String extractGateTitle(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return null;
        }
        try {
            JsonNode node = new ObjectMapper().readTree(toolInput);
            if (node != null && node.has("title") && node.get("title").isTextual()) {
                String title = node.get("title").asText();
                return title.isBlank() ? null : title;
            }
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // toolInput 非法 JSON：留痕回退 toolName，不因展示信息失败放弃取消
        }
        return null;
    }

    /** agui_events 审计事件名（仅落库数据面，前端不消费）。 */
    static final String TRACE_EVENT_NAME = "hitl_gate_timeout";

    private final PendingFormInterruptStore pendingFormInterruptStore;
    private final WakeupInterruptRegistrar interruptRegistrar;
    private final SessionActivityTracker activityTracker;
    private final AgentAssemblyService assemblyService;
    private final SessionOwnerService sessionOwnerService;
    private final HitlTimeoutProperties props;
    private final AlertNotifier alertNotifier;
    private final SessionNotifyPublisher notifyPublisher;
    private final AguiEventPersistenceService aguiEventPersistenceService;
    private final SessionProgressService sessionProgressService;

    public HitlTimeoutReaper(
            PendingFormInterruptStore pendingFormInterruptStore,
            WakeupInterruptRegistrar interruptRegistrar,
            SessionActivityTracker activityTracker,
            AgentAssemblyService assemblyService,
            SessionOwnerService sessionOwnerService,
            HitlTimeoutProperties props,
            AlertNotifier alertNotifier,
            SessionNotifyPublisher notifyPublisher,
            AguiEventPersistenceService aguiEventPersistenceService,
            SessionProgressService sessionProgressService) {
        this.pendingFormInterruptStore = pendingFormInterruptStore;
        this.interruptRegistrar = interruptRegistrar;
        this.activityTracker = activityTracker;
        this.assemblyService = assemblyService;
        this.sessionOwnerService = sessionOwnerService;
        this.props = props;
        this.alertNotifier = alertNotifier;
        this.notifyPublisher = notifyPublisher;
        this.aguiEventPersistenceService = aguiEventPersistenceService;
        this.sessionProgressService = sessionProgressService;
    }

    @Scheduled(fixedDelay = SWEEP_INTERVAL_MS, initialDelay = 90_000)
    public void sweep() {
        if (!props.isEnabled() || props.getApprovalTimeoutMinutes() <= 0) {
            return;
        }
        List<PendingFormInterruptStore.StoredInterrupt> all;
        try {
            all = pendingFormInterruptStore.listAll();
        } catch (RuntimeException e) {
            log.warn("HITL timeout reaper: store scan failed: {}", e.getMessage());
            return;
        }
        Duration timeout = Duration.ofMinutes(props.getApprovalTimeoutMinutes());
        Instant now = Instant.now();
        for (PendingFormInterruptStore.StoredInterrupt stored : all) {
            try {
                inspect(stored, timeout, now);
            } catch (RuntimeException e) {
                log.warn("HITL timeout reaper: inspect failed for session {} toolCallId {}: {}",
                        stored.sessionId(), stored.interrupt().toolCallId(), e.getMessage());
            }
        }
        reconcileFrameworkGates(all);
    }

    /**
     * 安全网对账：框架 {@code pendingInterruptsByThread} 中存在、但平台登记表缺失的
     * HITL 中断 → 补登记（createdAt = 发现时刻）。
     *
     * <p>框架映射是「哪些中断仍活着」的权威事实源，登记表只是前端查询/巡检缓存。
     * 任何服务端自发 run 入口漏做收尾登记（2026-09-20 停滞自检入口实证）都会造成
     * 「闸门活着却巡检隐形」的盲区——超时取消、watchdog 等待判定、前端表单恢复全部
     * 失灵，会话陷入自检/重渲染死循环。对账保证该类漂移在一分钟内自愈，而不是依赖
     * 每个入口永不遗漏（正常路径经 {@link ServerSideRunLauncher} 收口后本方法应为
     * 空转）。</p>
     */
    private void reconcileFrameworkGates(List<PendingFormInterruptStore.StoredInterrupt> stored) {
        Map<String, Map<String, AguiEvent.Interrupt>> framework;
        try {
            framework = interruptRegistrar.snapshotFrameworkInterrupts();
        } catch (RuntimeException e) {
            log.debug("HITL timeout reaper: framework snapshot failed: {}", e.getMessage());
            return;
        }
        if (framework.isEmpty()) {
            return;
        }
        Set<String> registered = stored.stream()
                .map(s -> s.sessionId() + "|" + s.interrupt().toolCallId())
                .collect(Collectors.toSet());
        for (Map.Entry<String, Map<String, AguiEvent.Interrupt>> thread : framework.entrySet()) {
            for (AguiEvent.Interrupt interrupt : thread.getValue().values()) {
                try {
                    reconcileOne(thread.getKey(), interrupt, registered);
                } catch (RuntimeException e) {
                    log.warn("HITL timeout reaper: reconcile failed for session {}: {}",
                            thread.getKey(), e.getMessage());
                }
            }
        }
    }

    /** 对账单条中断：HITL 工具 + 登记缺失 + 会话空闲 + 归属可解析 → 补登记。 */
    private void reconcileOne(
            String sessionId, AguiEvent.Interrupt interrupt, Set<String> registered) {
        String toolName = interrupt.metadata() == null
                ? null : (String) interrupt.metadata().get(AguiInterruptConstants.METADATA_TOOL_NAME);
        if (toolName == null || !HitlRecoveryGuardMiddleware.HITL_TOOLS.contains(toolName)) {
            return; // 权限确认等其他类型中断不在巡检范围
        }
        String toolCallId = interrupt.toolCallId();
        if (toolCallId == null || toolCallId.isBlank()
                || registered.contains(sessionId + "|" + toolCallId)) {
            return;
        }
        // 会话在跑：提交/续跑中，登记可能马上被消费，等下轮对账。
        if (activityTracker.isRunning(sessionId)) {
            return;
        }
        String agentId = sessionOwnerService.agentBySessionId(sessionId).orElse(null);
        if (agentId == null) {
            return; // 无归属记录（临时会话等）：无法登记，留待下轮
        }
        String userId = sessionOwnerService.owner(agentId, sessionId).orElse(null);
        if (userId == null || userId.isBlank()) {
            return;
        }
        String toolInputJson = "{}";
        Object toolInput = interrupt.metadata().get(AguiInterruptConstants.METADATA_TOOL_INPUT);
        if (toolInput != null) {
            try {
                toolInputJson = new ObjectMapper().writeValueAsString(toolInput);
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
                log.debug("HITL timeout reaper: failed to serialize tool input for {}: {}",
                        toolCallId, e.getMessage());
            }
        }
        Object replyId = interrupt.metadata().get(AguiInterruptConstants.METADATA_REPLY_ID);
        pendingFormInterruptStore.put(agentId, userId, sessionId,
                new PendingFormInterruptStore.PendingFormInterrupt(
                        interrupt.id(), replyId instanceof String s ? s : null, toolCallId,
                        toolName, toolInputJson, Instant.now().toString()));
        // P-01：补登记成功后立刻播一次游标信号。本路径此前静默补登记，前端只能等
        // 30s/120s 兜底轮询才可能发现这张闸门，信号窗口内的丢包无从弥补。
        // 信号只含 {sessionId, runId, reason} 游标，重复投递由前端转录合并吸收。
        notifyPublisher.publish(userId, sessionId, replyId instanceof String r ? r : "", null,
                SessionNotifyPublisher.REASON_INTERRUPT);
        log.warn("HITL timeout reaper: backfilled unregistered gate {} for session {} "
                        + "(agent={}, tool={}) — framework holds the interrupt but the platform "
                        + "registration was missing; a server-spontaneous run entry point likely "
                        + "bypassed ServerSideRunLauncher",
                toolCallId, sessionId, agentId, toolName);
    }

    private void inspect(
            PendingFormInterruptStore.StoredInterrupt stored, Duration timeout, Instant now) {
        PendingFormInterruptStore.PendingFormInterrupt entry = stored.interrupt();
        Instant createdAt = parseCreatedAt(entry.createdAt());
        // createdAt 解析失败按未超时处理（宁漏勿误取消）。
        if (createdAt == null || Duration.between(createdAt, now).compareTo(timeout) < 0) {
            return;
        }
        String sessionId = stored.sessionId();
        // 会话在跑：可能正在提交/续跑，本轮跳过（下轮再判，闸门未消费则仍超时）。
        if (activityTracker.isRunning(sessionId)) {
            return;
        }
        // 存活对账：框架 pending 映射已不持有（已消费/陈旧）→ 只清平台登记，不碰 state。
        List<PendingFormInterruptStore.PendingFormInterrupt> live =
                interruptRegistrar.filterLive(sessionId, List.of(entry));
        boolean stillLive = !live.isEmpty();
        HarnessAgent agent = resolveAgent(stored.agentId());
        if (!stillLive || agent == null) {
            pendingFormInterruptStore.remove(
                    stored.agentId(), stored.userId(), sessionId, entry.toolCallId());
            if (stillLive) {
                log.warn("HITL timeout reaper: session {} gate {} timed out but agent not "
                                + "assemblable — cleared registration only",
                        sessionId, entry.toolCallId());
            }
            return;
        }
        String userId = resolveUserId(stored);
        long waitedMinutes = Duration.between(createdAt, now).toMinutes();
        String gateTitle = extractGateTitle(entry.toolInput());
        String traceText = traceMessageText(gateTitle, entry.toolName(), entry.toolCallId(),
                waitedMinutes, props.getApprovalTimeoutMinutes(), now);
        PatchOutcome outcome = patchAgentState(agent, userId, sessionId, entry, traceText);
        if (outcome == PatchOutcome.FAILED) {
            // state 修补失败：保留登记下轮重试（at-least-once）。
            return;
        }
        interruptRegistrar.unregisterInterrupts(sessionId, List.of(entry.interruptId()));
        pendingFormInterruptStore.remove(
                stored.agentId(), stored.userId(), sessionId, entry.toolCallId());
        log.warn("HITL timeout reaper: cancelled gate {} for session {} (agent={}, user={}, "
                        + "waited {}min > {}min timeout, outcome={})",
                entry.toolCallId(), sessionId, stored.agentId(), userId,
                waitedMinutes, props.getApprovalTimeoutMinutes(), outcome);
        if (outcome == PatchOutcome.PATCHED) {
            // 真·超时取消才告警 + 落审计事件：ALREADY_RESOLVED（主编并发提交）发
            // 「已超时取消」是误报（2026-09-20 前的行为），STALE 只是清残留登记。
            alertNotifier.publish(new AlertNotifier.AlertRequest(
                    AlertNotifier.LEVEL_WARN,
                    String.format("审批已超时自动取消（等待 %d 分钟未提交）", waitedMinutes),
                    "会话中的待审批表单/闸门已超过 "
                            + props.getApprovalTimeoutMinutes() + " 分钟未提交，平台已自动取消该审批"
                            + "并解锁会话。\n智能体尚未收到你的决定；如需继续，请在会话中说明，智能体"
                            + "会重新发起审批。",
                    userId, sessionId, stored.agentId(),
                    "hitl-timeout:" + sessionId + ":" + entry.toolCallId()));
            // agui_events 审计留痕：事件流此前只覆盖 run 内事件，run 外的取消动作
            // 完全无记录；这里补一条 CUSTOM 事件供事后核查/对账。尽力而为：
            // capture 内部入队失败/开关关闭只影响审计，不阻断取消主流程。
            Map<String, Object> traceValue = new LinkedHashMap<>();
            traceValue.put("reason", "HITL_TIMEOUT");
            traceValue.put("toolCallId", entry.toolCallId());
            traceValue.put("toolName", entry.toolName());
            traceValue.put("title", gateTitle);
            traceValue.put("waitedMinutes", waitedMinutes);
            traceValue.put("timeoutMinutes", props.getApprovalTimeoutMinutes());
            traceValue.put("cancelledAt", now.toString());
            traceValue.put("transcriptTrace", traceText);
            aguiEventPersistenceService.capture(new AguiEvent.Custom(
                    sessionId, "", TRACE_EVENT_NAME, traceValue));
            // 封存会话（V62）：本次超时取消同时把会话置为「等人工唤醒」——
            // 注销登记（第 2 步）已解除闸门对唤醒通道的阻塞，若不封存，此前被挡住的
            // 唤醒通道会立刻恢复驱动能力，叠加 inbox 里积压的子任务完成通知，
            // 「超时取消」本身就变成了「继续跑」的触发器（2026-10-05 main-3f077bd9：
            // 01:30:07 取消、01:35:35 即起新 run 输出 3673 条事件）。
            // 超时 ≠ 批准，平台不得代替主编推进；解封只发生在用户主动发起新 run
            // （PlatformAguiRuntimeContextResolver 入口）。
            // 失败不阻断取消主流程——最坏退化为封存缺失，即本 issue 的原行为。
            try {
                sessionProgressService.markAwaitingHumanGate(
                        stored.agentId(), sessionId, userId, true);
            } catch (RuntimeException e) {
                log.warn("HITL timeout reaper: failed to seal session {} awaiting-human "
                        + "after gate {} timeout: {}", sessionId, entry.toolCallId(), e.getMessage());
            }
        }
        // 游标信号：触发前端转录同步（表单卡片在同步后自动关闭、留痕消息落转录）。
        // 尽力而为。
        notifyPublisher.publish(userId, sessionId, stored.agentId(), null,
                SessionNotifyPublisher.REASON_INTERRUPT);
    }

    private HarnessAgent resolveAgent(String agentId) {
        try {
            return assemblyService.requireAssembledById(agentId);
        } catch (RuntimeException e) {
            log.debug("HITL timeout reaper: agent {} not assemblable: {}", agentId, e.getMessage());
            return null;
        }
    }

    private String resolveUserId(PendingFormInterruptStore.StoredInterrupt stored) {
        if (stored.userId() != null && !stored.userId().isBlank()) {
            return stored.userId();
        }
        return sessionOwnerService.owner(stored.agentId(), stored.sessionId()).orElse(null);
    }

    /**
     * state 修补结果分类（调用方据此决定重试 / 清登记 / 发留痕）。
     */
    enum PatchOutcome {
        /** 真·超时取消：tool_use 置 FINISHED + 追加超时 tool_result + 留痕消息。 */
        PATCHED,
        /** 已有结果（主编恰好并发提交 / 此前已取消）：只需清登记，不发「超时」留痕。 */
        ALREADY_RESOLVED,
        /** 无状态 / tool_use 已被压缩清理：登记视为残留，只需清登记。 */
        STALE,
        /** 状态不可读 / CAS 竞态超限：保留登记下轮重试。 */
        FAILED
    }

    /**
     * CAS 修补 agent state：闸门 tool_use 置 FINISHED + 追加「超时取消」tool_result
     * + 追加 {@code [平台留痕]} 转录留痕消息（三者同一次 CAS 保存，原子生效）。
     *
     * @return 修补结果（FAILED = 状态不可读/CAS 竞态超限——调用方保留登记下轮重试；
     *         其余结果还涵盖「无需修补」的清理场景：状态不存在 / tool_use 已被压缩 /
     *         已有结果（并发提交）——这些情况只需清理登记，且不产生留痕）
     */
    private PatchOutcome patchAgentState(
            HarnessAgent agent, String userId, String sessionId,
            PendingFormInterruptStore.PendingFormInterrupt entry, String traceText) {
        AgentStateStore store = agent.getStateStore();
        if (store == null || userId == null || userId.isBlank()) {
            return PatchOutcome.FAILED;
        }
        return patchAgentState(store, userId, sessionId, entry.toolCallId(),
                entry.toolName(), props.getApprovalTimeoutMinutes(), traceText);
    }

    /** 包可见静态实现（供单测复用），语义见上方实例方法注释。 */
    static PatchOutcome patchAgentState(
            AgentStateStore store, String userId, String sessionId,
            String toolCallId, String toolName, long timeoutMinutes, String traceText) {
        for (int attempt = 0; attempt < CAS_MAX_ATTEMPTS; attempt++) {
            VersionedState<AgentState> versioned;
            try {
                versioned = store.getVersioned(userId, sessionId, "agent_state", AgentState.class);
            } catch (RuntimeException e) {
                log.warn("HITL timeout reaper: state read failed for session {}: {}",
                        sessionId, e.getMessage());
                return PatchOutcome.FAILED;
            }
            if (versioned == null || !versioned.isPresent()
                    || versioned.value() == null) {
                // 无持久化状态（会话已被清理等）：登记是残留，报告成功让调用方清理登记。
                return PatchOutcome.STALE;
            }
            AgentState state = versioned.value();
            List<Msg> context = state.contextMutable();
            int targetIndex = -1;
            for (int i = context.size() - 1; i >= 0; i--) {
                Msg m = context.get(i);
                if (m.getRole() != MsgRole.ASSISTANT) {
                    continue;
                }
                boolean hasUse = false;
                boolean hasResult = false;
                for (ContentBlock block : m.getContent()) {
                    if (block instanceof ToolUseBlock use && toolCallId.equals(use.getId())) {
                        hasUse = true;
                    }
                    if (block instanceof ToolResultBlock result
                            && toolCallId.equals(result.getId())) {
                        hasResult = true;
                    }
                }
                if (hasUse) {
                    if (hasResult) {
                        // 已有结果（用户恰好并发提交 / 此前已取消）：只需清登记。
                        return PatchOutcome.ALREADY_RESOLVED;
                    }
                    targetIndex = i;
                    break;
                }
            }
            if (targetIndex < 0) {
                // 上下文中找不到该 tool_use（已被压缩/清理）：登记视为残留，清登记。
                log.warn("HITL timeout reaper: tool_use {} not found in context of session {}; "
                        + "treating registration as stale", toolCallId, sessionId);
                return PatchOutcome.STALE;
            }
            Msg target = context.get(targetIndex);
            List<ContentBlock> patchedContent = new ArrayList<>(target.getContent().size() + 1);
            for (ContentBlock block : target.getContent()) {
                if (block instanceof ToolUseBlock use && toolCallId.equals(use.getId())) {
                    patchedContent.add(use.withState(ToolCallState.FINISHED));
                } else {
                    patchedContent.add(block);
                }
            }
            patchedContent.add(ToolResultBlock.of(
                    toolCallId,
                    toolName == null ? "ui_gate" : toolName,
                    TextBlock.builder().text(timeoutResultText(timeoutMinutes)).build()
            ).withState(ToolResultState.SUCCESS));
            context.set(targetIndex, target.withContent(patchedContent));
            // 转录留痕：与 tool_result 同一次 CAS 保存，原子生效（user 角色，
            // 前缀 [平台留痕] 由前端渲染为独立系统条）。
            if (traceText != null && !traceText.isBlank()) {
                context.add(new UserMessage(traceText));
            }
            try {
                long saved = store.saveIfVersion(
                        userId, sessionId, "agent_state", state, versioned.version());
                // 约定：-1 = 版本冲突未保存；正数 = 新版本；异常 = 存储故障。
                if (saved != -1) {
                    return PatchOutcome.PATCHED;
                }
                log.debug("HITL timeout reaper: CAS version conflict for session {} "
                        + "(attempt {}/{})", sessionId, attempt + 1, CAS_MAX_ATTEMPTS);
            } catch (RuntimeException e) {
                // 存储故障：同样重读重试。
                log.debug("HITL timeout reaper: CAS error for session {} (attempt {}/{}): {}",
                        sessionId, attempt + 1, CAS_MAX_ATTEMPTS, e.getMessage());
            }
        }
        log.warn("HITL timeout reaper: CAS retries exhausted for session {} toolCallId {}",
                sessionId, toolCallId);
        return PatchOutcome.FAILED;
    }

    /** 解析登记 createdAt（Instant.toString 的 ISO 形态）；失败返回 null（按未超时处理）。 */
    static Instant parseCreatedAt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
