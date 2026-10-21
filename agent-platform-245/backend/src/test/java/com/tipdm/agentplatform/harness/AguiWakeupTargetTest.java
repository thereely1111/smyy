package com.tipdm.agentplatform.harness;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import com.tipdm.agentplatform.service.AgentAssemblyService;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import com.tipdm.agentplatform.service.SessionOwnerService;
import com.tipdm.agentplatform.service.SessionWorkspaceManager;
import com.tipdm.agentplatform.service.AguiEventPersistenceService;
import com.tipdm.agentplatform.service.PendingFormInterruptStore;
import com.tipdm.agentplatform.service.SessionKnowledgeRef;
import com.tipdm.agentplatform.service.SessionKnowledgeService;
import com.tipdm.agentplatform.service.SessionProgressService;
import com.tipdm.agentplatform.service.notify.SessionNotifyPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AG-UI Wakeup 目标单元测试。
 *
 * <p>背景：2026-09-03 目录生成场景中，子智能体任务完成（COMPLETED）后父会话一直 idle，
 * 因为 AG-UI 模式下从未启动 WakeupDispatcher，结果永远不会被 push 给父智能体。
 * 本测试守护 {@link AguiWakeupTarget} 的恢复语义：
 * 空闲会话在收到 wakeup 后会以空输入触发一次 run，拿到子任务结果。</p>
 */
@SuppressWarnings("unchecked")
class AguiWakeupTargetTest {

    private SessionAgentRegistry registry;
    private SessionActivityTracker tracker;
    private AgentAssemblyService assemblyService;
    private SessionWorkspaceManager workspaceManager;
    private SessionOwnerService sessionOwnerService;
    private SessionKnowledgeService sessionKnowledgeService;
    private MessageBus messageBus;
    private PendingFormInterruptStore pendingFormInterruptStore;
    private SessionPermissionReconciler permissionReconciler;
    private WakeupInterruptRegistrar wakeupInterruptRegistrar;

    private final UUID agentId = UUID.fromString("631f97d0-c074-3069-8196-e850831bbee0");
    private final String sessionId = "wb-c74744fc-2f4e-4dc5-bac8-3aa2614b78bd";

    @BeforeEach
    void setUp() {
        registry = new SessionAgentRegistry();
        tracker = new SessionActivityTracker();
        assemblyService = mock(AgentAssemblyService.class);
        workspaceManager = mock(SessionWorkspaceManager.class);
        sessionOwnerService = mock(SessionOwnerService.class);
        // 会话知识库 ref：目标构造时无条件消费 refFor，stub 成空快照
        sessionKnowledgeService = mock(SessionKnowledgeService.class);
        when(sessionKnowledgeService.refFor(any(), any()))
                .thenReturn(SessionKnowledgeRef.NONE);
        messageBus = mock(MessageBus.class);
        pendingFormInterruptStore = new PendingFormInterruptStore();
        // 权限对账在本测试里是旁路：用 mock 避免依赖装配服务
        permissionReconciler = mock(SessionPermissionReconciler.class);
        wakeupInterruptRegistrar = mock(WakeupInterruptRegistrar.class);
        // 闸门存活判定 = 平台登记表 ∩ 框架 pendingInterruptsByThread（见
        // AguiWakeupTarget#countLiveFormInterrupts）。默认 stub 成"框架保留登记表里的一切"，
        // 与"框架对账不可达时原样放行"的降级行为一致；要测陈旧闸门则覆写成返回空。
        when(wakeupInterruptRegistrar.filterLive(anyString(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        // 默认 stub：inbox 非空，给既有 happy-path 测试一个能跑的注入源。
        when(messageBus.inboxDrain(any(), anyInt()))
                .thenReturn(Mono.just(List.of(new BusEntry(
                        "entry-1",
                        Map.of(
                                "id", "hint-1",
                                "source", "async-tool-notification",
                                "hint", "Tool 'agent_spawn' (id=call_1) completed successfully.")))));
    }

    /** 构造时统一传入 inbox seed；测试可在此基础上覆写 inbox 行为。 */
    private AguiWakeupTarget newTarget() {
        return new AguiWakeupTarget(
                registry, tracker, assemblyService, workspaceManager, sessionOwnerService,
                messageBus, pendingFormInterruptStore, permissionReconciler,
                wakeupInterruptRegistrar,
                // 收口后由 ServerSideRunLauncher 发起 run（租约 + 收尾闸门登记）：
                // registrar 用同一 mock，闸门登记断言仍然可达。
                new ServerSideRunLauncher(mock(RunLeaseStore.class), wakeupInterruptRegistrar,
                        mock(AguiEventPersistenceService.class),
                        null, new RunSubscriptionRegistry(), mock(TruncationResumeNudger.class)),
                mock(SessionNotifyPublisher.class),
                sessionKnowledgeService,
                mock(SessionProgressService.class));
    }

    @Test
    void runWakeup_resolvesAgentBySession_andReplaysResult() {
        registry.register(agentId.toString(), sessionId);

        HarnessAgent agent = mock(HarnessAgent.class);
        Msg result = new UserMessage("目录生成完成");
        when(assemblyService.requireAssembledById(agentId.toString())).thenReturn(agent);
        when(agent.streamEvents((List) any(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new AgentResultEvent(result)));

        AguiWakeupTarget target = newTarget();
        Msg reply = target.runWakeup("user-1", sessionId).block();
        assertThat(reply).isSameAs(result);
    }

    @Test
    void runWakeup_unknownSession_returnsEmpty() {
        AguiWakeupTarget target = newTarget();
        assertThat(target.runWakeup("user-1", "unknown-session").block()).isNull();
    }

    @Test
    void runWakeup_subagentClobber_fallsBackToParent() {
        // 场景：同一次 run 内子智能体运行把 session→agent 映射的最新注册覆盖为
        // 子智能体运行期 UUID（平台无法装配），唤醒器必须回退到父智能体。
        String parentId = agentId.toString();
        String subagentId = "872d5132-7640-409e-ac7b-f5921a6b87a9";
        registry.register(parentId, sessionId);
        registry.register(subagentId, sessionId);

        when(assemblyService.requireAssembledById(subagentId)).thenReturn(null);
        HarnessAgent parent = mock(HarnessAgent.class);
        Msg result = new UserMessage("目录生成完成");
        when(assemblyService.requireAssembledById(parentId)).thenReturn(parent);
        when(parent.streamEvents((List) any(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new AgentResultEvent(result)));

        AguiWakeupTarget target = newTarget();

        Msg reply = target.runWakeup("user-1", sessionId).block();
        assertThat(reply).isSameAs(result);
    }

    @Test
    void registry_keepsCandidatesNewestFirst_andAgentForReturnsLatest() {
        String parentId = agentId.toString();
        String subagentId = "872d5132-7640-409e-ac7b-f5921a6b87a9";
        registry.register(parentId, sessionId);
        registry.register(subagentId, sessionId);
        registry.register(parentId, sessionId); // 父智能体再次运行，最新注册应为父

        // agentFor 保持向后兼容：返回最近注册
        assertThat(registry.agentFor(sessionId)).isEqualTo(parentId);
        // agentsCandidates 最新在前：父在首位，子智能体仍在候选里
        assertThat(registry.agentCandidates(sessionId))
                .containsExactly(parentId, subagentId);

        // 未知会话
        assertThat(registry.agentFor("unknown")).isNull();
        assertThat(registry.agentCandidates("unknown")).isEmpty();
    }

    @Test
    void runWakeup_missingAgent_returnsEmpty() {
        registry.register(agentId.toString(), sessionId);
        when(assemblyService.requireAssembledById(agentId.toString())).thenReturn(null);

        AguiWakeupTarget target = newTarget();
        assertThat(target.runWakeup("user-1", sessionId).block()).isNull();
    }

    /**
     * 根因修复回归守护：wakeup 必须把 inbox 里的待办 hint 取出来作为 user 输入，
     * 否则 InboxMiddleware 在 modelInput 已冻结后注入的 hint 对本轮模型不可见，
     * 父智能体只会「重新开场」。
     */
    @Test
    void runWakeup_emptyInbox_skipsRunAndDoesNotCallStreamEvents() {
        when(messageBus.inboxDrain(any(), anyInt())).thenReturn(Mono.just(List.of()));
        registry.register(agentId.toString(), sessionId);
        HarnessAgent agent = mock(HarnessAgent.class);
        when(assemblyService.requireAssembledById(agentId.toString())).thenReturn(agent);

        AguiWakeupTarget target = newTarget();
        Msg reply = target.runWakeup("user-1", sessionId).block();

        assertThat(reply).isNull();
        verify(agent, never()).streamEvents((List) any(), any(RuntimeContext.class));
    }

    /**
     * 守护「hint 进入 modelInput」语义：inbox 非空时，streamEvents 的输入列表里
     * 必须包含把 hint 文本渲染进去的那条 user 消息，确保模型本轮能读到完成通知。
     */
    @Test
    @SuppressWarnings("unchecked")
    void runWakeup_inboxHints_arePassedAsUserInputToStreamEvents() {
        when(messageBus.inboxDrain(any(), anyInt()))
                .thenReturn(Mono.just(List.of(
                        new BusEntry(
                                "e1",
                                Map.of(
                                        "id", "h1",
                                        "source", "async-tool-notification",
                                        "hint", "Tool 'agent_spawn' completed in 120s.")),
                        new BusEntry(
                                "e2",
                                Map.of(
                                        "id", "h2",
                                        "source", "subagent-completion",
                                        "hint", "目录生成智能体 finished.")))));
        registry.register(agentId.toString(), sessionId);
        HarnessAgent agent = mock(HarnessAgent.class);
        Msg result = new UserMessage("目录已生成");
        when(assemblyService.requireAssembledById(agentId.toString())).thenReturn(agent);
        when(agent.streamEvents((List) any(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new AgentResultEvent(result)));

        AguiWakeupTarget target = newTarget();
        Msg reply = target.runWakeup("user-1", sessionId).block();

        assertThat(reply).isSameAs(result);
        var captor = ArgumentCaptor.forClass(List.class);
        verify(agent).streamEvents(captor.capture(), any(RuntimeContext.class));
        List<Msg> inputs = captor.getValue();
        assertThat(inputs).hasSize(1);
        Msg seed = inputs.get(0);
        // 验证 hint 文本渲染进了 user 消息，模型本轮即可看到
        String rendered = seed.getContent().toString();
        assertThat(rendered).contains("agent_spawn").contains("目录生成智能体 finished");
    }

    /**
     * 守护「drain 失败时不 crash、不空转 run」：drain 出错应降级为空 inbox，
     * 即跳过 streamEvents，避免给上层制造「无输入但产出开场白」的副作用。
     */
    @Test
    void runWakeup_inboxDrainError_skipsRunGracefully() {
        when(messageBus.inboxDrain(any(), anyInt()))
                .thenReturn(Mono.error(new RuntimeException("bus offline")));
        registry.register(agentId.toString(), sessionId);
        HarnessAgent agent = mock(HarnessAgent.class);
        when(assemblyService.requireAssembledById(agentId.toString())).thenReturn(agent);

        AguiWakeupTarget target = newTarget();
        Msg reply = target.runWakeup("user-1", sessionId).block();

        assertThat(reply).isNull();
        verify(agent, never()).streamEvents((List) any(), any(RuntimeContext.class));
    }

    // ---------- 跨重启「陈旧闸门」恢复 ----------

    /** 构造一个「ui_gate 已挂起、无主编提交结果」的持久化上下文。 */
    private HarnessAgent agentWithPendingGate() {
        var gateUse = new ToolUseBlock(
                "call_gate_1", "ui_gate", Map.of(), Map.of());
        var gateResult = ToolResultBlock.builder()
                .id("call_gate_1")
                .name("ui_gate")
                .output(TextBlock.builder().text("waiting").build())
                .state(ToolResultState.RUNNING)
                .build();
        AgentState state =
                AgentState.builder()
                        .sessionId(sessionId)
                        .userId("user-1")
                        .context(List.of(
                                Msg.builderForRole(MsgRole.ASSISTANT)
                                        .content(List.of(gateUse)).build(),
                                Msg.builderForRole(MsgRole.TOOL)
                                        .content(List.of(gateResult)).build()))
                        .build();
        AgentStateStore store =
                mock(AgentStateStore.class);
        Mockito.doReturn(Optional.of(state))
                .when(store).get("user-1", sessionId, "agent_state",
                        AgentState.class);
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.getStateStore()).thenReturn(store);
        return agent;
    }

    /** 把 userId 固定为 "user-1"（guard 按 owner 解析出的用户读状态）。 */
    private void stubOwnerUser() {
        when(sessionOwnerService.owner(agentId.toString(), sessionId))
                .thenReturn(Optional.of("user-1"));
    }

    /**
     * 回归守护（2026-09-11）：闸门挂起但本进程没有任何存活表单登记 = 后端重启前遗留的
     * 陈旧闸门（无人能提交）。此时必须放行唤醒，否则会话被永久延后、每 2 分钟空转
     * （实证 main-f9462507 30 分钟 28 次 deferring）。放行后由守卫注入「重新渲染闸门」指示。
     */
    @Test
    void runWakeup_staleGateWithoutLiveForm_replaysInsteadOfDeadlocking() {
        registry.register(agentId.toString(), sessionId);
        stubOwnerUser();
        HarnessAgent agent = agentWithPendingGate();
        Msg result = new UserMessage("闸门已重新渲染");
        when(assemblyService.requireAssembledById(agentId.toString())).thenReturn(agent);
        when(agent.streamEvents((List) any(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new AgentResultEvent(result)));

        AguiWakeupTarget target = newTarget();
        Msg reply = target.runWakeup(null, sessionId).block();

        assertThat(reply).isSameAs(result);
    }

    /**
     * 反向守护：闸门挂起且本进程存在存活表单登记（主编正看着表单）时，唤醒必须继续
     * 延后——否则框架 pending-recovery 会把挂起的 ui_gate 自动补错，造成「假通过」。
     */
    @Test
    void runWakeup_liveGateWithRegisteredForm_defersReplay() {
        registry.register(agentId.toString(), sessionId);
        stubOwnerUser();
        HarnessAgent agent = agentWithPendingGate();
        when(assemblyService.requireAssembledById(agentId.toString())).thenReturn(agent);
        pendingFormInterruptStore.put(agentId.toString(), "user-1", sessionId,
                new PendingFormInterruptStore.PendingFormInterrupt(
                        "interrupt-1", "reply-1", "call_gate_1", "ui_gate", "{}", "2026-09-11T10:00:00"));

        AguiWakeupTarget target = newTarget();
        Msg reply = target.runWakeup(null, sessionId).block();

        assertThat(reply).isNull();
        verify(agent, never()).streamEvents((List) any(), any(RuntimeContext.class));
    }

    /**
     * 登记表残留但框架已无该中断 → 视为陈旧闸门，必须放行而不是永久延后。
     *
     * <p>登记表只在 RUN_FINISHED(success) 时清空：闸门经 cancelled resume 被消费后旧条目
     * 会残留。原实现直接取登记表 size，一条永不过期的陈旧条目就让「陈旧闸门恢复」分支
     * 永不进入（实证：30 分钟内 28 次 deferring、会话死锁）。现按框架
     * {@code pendingInterruptsByThread} 对账，对账为空即放行。</p>
     */
    @Test
    void runWakeup_staleRegistryEntryNotInFramework_isTreatedAsStaleAndReplays() {
        registry.register(agentId.toString(), sessionId);
        stubOwnerUser();
        HarnessAgent agent = agentWithPendingGate();
        Msg result = new UserMessage("闸门已重新渲染");
        when(assemblyService.requireAssembledById(agentId.toString())).thenReturn(agent);
        when(agent.streamEvents((List) any(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new AgentResultEvent(result)));
        // 平台登记表里还留着一条，但框架侧已没有对应 open interrupt
        pendingFormInterruptStore.put(agentId.toString(), "user-1", sessionId,
                new PendingFormInterruptStore.PendingFormInterrupt(
                        "interrupt-stale", "reply-1", "call_gate_1", "ui_gate", "{}", "2026-09-11T10:00:00"));
        when(wakeupInterruptRegistrar.filterLive(anyString(), any()))
                .thenReturn(List.of());

        AguiWakeupTarget target = newTarget();
        Msg reply = target.runWakeup(null, sessionId).block();

        assertThat(reply).isSameAs(result);
    }

    /**
     * 反向守护：陈旧闸门恢复的节流窗口内不得重复放行。恢复 run 的重渲染中断前端看不到
     * （SSE 只推它自己发起的 run），不会上报 form-interrupt，因此"存活表单登记"仍为空；
     * 若不加节流，下一次唤醒会把刚渲染的闸门再判为陈旧而再次放行，待决闸门越堆越多
     * （后续 resume 报 {@code must address all of them}）。
     *
     * <p>2026-10-07 修正：语义由"每进程只放行一次"改为「{@code STALE_GATE_RECOVERY_TTL_MS}
     * 内只放行一次」。一次性语义下那次放行若没能让闸门变得可提交，标记永不复位
     * （复位只在"无未决 HITL"分支里），会话被永久延后——实证 main-3f077bd9
     * 从 00:01 到 09:41 被挡 290 次、inbox 积压待办永不执行。本测试守护窗口内仍延后。
     */
    @Test
    void runWakeup_staleGateRecovery_isThrottledWithinTtl() {
        registry.register(agentId.toString(), sessionId);
        stubOwnerUser();
        HarnessAgent agent = agentWithPendingGate();
        Msg result = new UserMessage("闸门已重新渲染");
        when(assemblyService.requireAssembledById(agentId.toString())).thenReturn(agent);
        when(agent.streamEvents((List) any(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new AgentResultEvent(result)));

        AguiWakeupTarget target = newTarget();
        assertThat(target.runWakeup(null, sessionId).block()).isSameAs(result);

        // 第二次唤醒仍在 TTL 窗口内：闸门还在挂起且无存活表单登记 → 必须延后
        Msg second = target.runWakeup(null, sessionId).block();
        assertThat(second).isNull();
        verify(agent, Mockito.times(1))
                .streamEvents((List) any(), any(RuntimeContext.class));
    }

    /**
     * TTL 过期后必须允许再试一次（防永久锁死回归）。
     *
     * <p>反向守护：若实现退回一次性语义、或把 TTL 写成永不过期，本测试必然失败。
     * 通过手法：把上一次放行时刻回拨到 TTL 之前，模拟「很久以前放过一次、闸门仍未提交」。</p>
     */
    @Test
    void runWakeup_staleGateRecovery_isAllowedAgainAfterTtl() throws Exception {
        registry.register(agentId.toString(), sessionId);
        stubOwnerUser();
        HarnessAgent agent = agentWithPendingGate();
        Msg result = new UserMessage("闸门已重新渲染");
        when(assemblyService.requireAssembledById(agentId.toString())).thenReturn(agent);
        when(agent.streamEvents((List) any(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new AgentResultEvent(result)));

        AguiWakeupTarget target = newTarget();
        assertThat(target.runWakeup(null, sessionId).block()).isSameAs(result);

        // 标记已置位（节流生效），把它回拨到 TTL 之前 ⇒ 应重新允许放行
        Field mapField = AguiWakeupTarget.class.getDeclaredField("staleGateRecoveryAt");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ConcurrentMap<String, Long> map =
                (ConcurrentMap<String, Long>) mapField.get(target);
        assertThat(map).containsKey(sessionId);
        long longAgo = System.currentTimeMillis() - AguiWakeupTarget.STALE_GATE_RECOVERY_TTL_MS - 1_000L;
        map.put(sessionId, longAgo);

        Msg third = target.runWakeup(null, sessionId).block();

        assertThat(third).isSameAs(result);
        verify(agent, Mockito.times(2))
                .streamEvents((List) any(), any(RuntimeContext.class));
    }

    /**
     * 已归档（弃用）会话必须跳过唤醒，且<b>不drain inbox</b>。
     *
     * <p>用户报障「弃用的会话启动后还在继续跑」的根因守护：归档此前只在前端列表侧
     * 生效（{@code listSessions} 的 includeArchived 过滤），唤醒链路从不读
     * {@code archived_at} ⇒ 弃用会话照跑。修复后归档成为唤醒的一等抑制判据。</p>
     *
     * <p>断言「不drain inbox」是关键：drain 是破坏性的，若一边抑制唤醒一边把
     * hint 取走，用户取消归档后会话就丢了积压工作（与 stopRequested 的
     * 「hint kept in inbox」语义必须一致）。</p>
     */
    @Test
    void runWakeup_archivedSession_skipsWakeupAndKeepsInbox() {
        registry.register(agentId.toString(), sessionId);
        HarnessAgent agent = mock(HarnessAgent.class);
        when(assemblyService.requireAssembledById(agentId.toString())).thenReturn(agent);
        when(sessionOwnerService.isArchivedBySession(sessionId)).thenReturn(true);

        AguiWakeupTarget target = newTarget();
        assertThat(target.runWakeup("user-1", sessionId).block()).isNull();
        verify(messageBus, never()).inboxDrain(anyString(), anyInt());
        // 不得起 run
        verify(agent, never()).streamEvents((List) any(), any(RuntimeContext.class));
    }

    /** 取消归档后应恢复正常唤醒（判据读的是实时归档态，不是缓存的抑制名单）。 */
    @Test
    void runWakeup_unarchivedSession_stillWakes() {
        registry.register(agentId.toString(), sessionId);
        HarnessAgent agent = mock(HarnessAgent.class);
        Msg result = new UserMessage("目录生成完成");
        when(assemblyService.requireAssembledById(agentId.toString())).thenReturn(agent);
        when(agent.streamEvents((List) any(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new AgentResultEvent(result)));
        when(sessionOwnerService.isArchivedBySession(sessionId)).thenReturn(false);

        AguiWakeupTarget target = newTarget();
        assertThat(target.runWakeup("user-1", sessionId).block()).isSameAs(result);
    }

    @Test
    void isSessionRunning_reflectsActiveRun() {
        assertThat(tracker.isRunning(sessionId)).isFalse();
        tracker.begin(sessionId);
        assertThat(tracker.isRunning(sessionId)).isTrue();
        tracker.end(sessionId);
        assertThat(tracker.isRunning(sessionId)).isFalse();
    }

    /**
     * 平台装配的模型执行配置必须保留超时+重试（2026-09-03 事故回归守护）：
     * 子智能体在 qwen3.7-max 调用上挂死 >10 分钟，因没有模型超时兜底导致父智能体误判死锁。
     */
    @Test
    void platformToolExecutionConfig_keepsSubagentChainAlive() throws Exception {
        // 2026-09-15 修正：模型执行配置已改为 AgentAssemblyService 实例字段注入
        // （modelExecutionConfig()），原静态字段 PLATFORM_MODEL_EXECUTION_CONFIG
        // 不复存在。工具执行配置仍是静态契约——30 分钟超时防杀 agent_spawn
        // 子智能体链（2026-09-04 实证），单次尝试不做模型式重试。
        Field f = AgentAssemblyService.class.getDeclaredField("PLATFORM_TOOL_EXECUTION_CONFIG");
        f.setAccessible(true);
        ExecutionConfig cfg = (ExecutionConfig) f.get(null);
        assertThat(cfg.getTimeout()).isNotNull();
        assertThat(cfg.getTimeout()).isGreaterThanOrEqualTo(Duration.ofMinutes(30));
        assertThat(cfg.getMaxAttempts()).isEqualTo(1);
    }
}

