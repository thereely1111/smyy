package com.tipdm.agentplatform.harness;

import com.tipdm.agentplatform.service.AguiEventPersistenceService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 服务端自发 run 统一启动器契约（2026-09-20 收口）。
 *
 * <p>背景：停滞自检入口此前直接 {@code streamEvents().subscribe()}，run 以 ui_gate
 * 挂起收尾时框架与平台登记表均无该中断——reaper 扫不到、30 分钟超时取消失效、
 * watchdog 反复注入自检形成死循环。launcher 统一保证：租约登记 + 收尾闸门登记 +
 * 登记失败不阻断 run。</p>
 */
class ServerSideRunLauncherTest {

    private RunLeaseStore runLeaseStore;
    private WakeupInterruptRegistrar registrar;
    private AguiEventPersistenceService eventLog;
    private TruncationResumeNudger truncationResumeNudger;
    private ServerSideRunLauncher launcher;
    private HarnessAgent agent;

    private static final String AGENT = "agent-1";
    private static final String USER = "user-1";
    private static final String SESSION = "session-1";

    @BeforeEach
    void setUp() {
        runLeaseStore = mock(RunLeaseStore.class);
        registrar = mock(WakeupInterruptRegistrar.class);
        eventLog = mock(AguiEventPersistenceService.class);
        truncationResumeNudger = mock(TruncationResumeNudger.class);
        launcher = new ServerSideRunLauncher(runLeaseStore, registrar, eventLog, null,
                new RunSubscriptionRegistry(), truncationResumeNudger);
        agent = mock(HarnessAgent.class);
        // 悬空闸门对账要经agent 实例解析 state store（中间件链上是内层 ReActAgent，
        // 不能要求 instanceof HarnessAgent——那条判据在此恒真，靠 resolveStateStore
        // 的双类型回退兜住）。mock 默认返回 null 会让对账在读状态前静默跳过，
        // 那样断言"对账被调用"就是假绿——必须给一个可解析的 store。
        when(agent.getStateStore()).thenReturn(mock(AgentStateStore.class));
    }

    /**
     * 迭代上限截断：run 收尾时把截断事实交给续跑组件。
     *
     * <p>接手点必须在 run 收尾（doFinally）——截断时 run 仍被标记 running，此刻请求
     * 唤醒会被 WakeupDispatcher 跳过并丢弃唤醒条目。</p>
     */
    @Test
    void truncatedRunHandsOverToResumeNudger() {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(
                        new ExceedMaxItersEvent("reply-1", 10, 10),
                        new AgentResultEvent(new UserMessage("被截断收尾"))));

        List<AgentEvent> events = launcher
                .launch(agent, List.of(new UserMessage("自检")), rtc(), AGENT, USER, SESSION)
                .collectList()
                .block();

        assertThat(events).hasSize(2);
        verify(truncationResumeNudger).onTruncated(SESSION, 10, 10);
    }

    /** 反例：正常收尾的 run 不得触发续跑（判据不能恒真）。 */
    @Test
    void normalRunDoesNotNudgeResume() {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new AgentResultEvent(new UserMessage("ok"))));

        launcher.launch(agent, List.of(new UserMessage("自检")), rtc(), AGENT, USER, SESSION)
                .collectList()
                .block();

        verify(truncationResumeNudger, never()).onTruncated(anyString(), anyInt(), anyInt());
    }

    @Test
    void launchUpsertsLeaseAndRegistersSuspendedGatesOnResultEvent() {
        Msg result = new UserMessage("闸门已渲染");
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new AgentResultEvent(result)));

        List<AgentEvent> events = launcher
                .launch(agent, List.of(new UserMessage("自检")), rtc(), AGENT, USER, SESSION)
                .collectList()
                .block();

        assertThat(events).hasSize(1);
        verify(runLeaseStore).upsert(USER, SESSION, AGENT);
        verify(registrar).registerSuspendedGates(eq(result), eq(AGENT), eq(USER), eq(SESSION));
    }

    @Test
    void registrationFailureDoesNotBreakTheRun() {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new AgentResultEvent(new UserMessage("ok"))));
        doThrow(new IllegalStateException("reflection wiring lost"))
                .when(registrar).registerSuspendedGates(any(), anyString(), anyString(), anyString());

        List<AgentEvent> events = launcher
                .launch(agent, List.of(new UserMessage("自检")), rtc(), AGENT, USER, SESSION)
                .collectList()
                .block();

        // 登记是尽力而为：抛错只降级 WARN，事件流完整送达。
        assertThat(events).hasSize(1);
    }

    @Test
    void leaseFailureDoesNotBreakTheRun() {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new AgentResultEvent(new UserMessage("ok"))));
        Mockito.doThrow(new IllegalStateException("db down"))
                .when(runLeaseStore).upsert(anyString(), anyString(), anyString());

        List<AgentEvent> events = launcher
                .launch(agent, List.of(new UserMessage("自检")), rtc(), AGENT, USER, SESSION)
                .collectList()
                .block();

        assertThat(events).hasSize(1);
    }

    @Test
    void nonResultEventsDoNotTriggerRegistration() {
        @SuppressWarnings("unchecked")
        Flux<AgentEvent> raw = (Flux<AgentEvent>) (Flux<?>) Flux.empty();
        when(agent.streamEvents(anyList(), any(RuntimeContext.class))).thenReturn(raw);

        launcher.launch(agent, List.of(new UserMessage("自检")), rtc(), AGENT, USER, SESSION)
                .collectList()
                .block();

        verify(registrar, never()).registerSuspendedGates(any(), anyString(), anyString(), anyString());
    }

    /**
     * 3.1：自发 run 事件接入 agui_events 落库——RUN_STARTED 起跑即登记，
     * RUN_FINISHED 在 run 正常收场时落库（事件库/前端水位线可见后台工作）。
     */
    @Test
    void runLifecycleIsCapturedIntoEventLog() {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new AgentResultEvent(new UserMessage("ok"))));

        launcher.launch(agent, List.of(new UserMessage("自检")), rtc(), AGENT, USER, SESSION)
                .collectList()
                .block();

        ArgumentCaptor<AguiEvent> captor =
                ArgumentCaptor.forClass(AguiEvent.class);
        verify(eventLog, Mockito.atLeastOnce()).capture(captor.capture());
        List<String> types = captor.getAllValues().stream()
                .map(e -> e.getType().name()).toList();
        Assertions.assertThat(types).contains("RUN_STARTED", "RUN_FINISHED");
    }

    // ---------- 悬空闸门兜底对账接线（2026-10-07） ----------

    /**
     * 根因回归：<b>收尾闸门登记必须发生，且不受收尾形态影响</b>。
     *
     * <p>2026-10-07 实证（main-3f077bd9）：闸门在后台 run 里渲染后，工具被框架转后台
     * 执行（tool 返回「running in background over 60s」提醒），run <b>不是</b>以
     * {@code TOOL_SUSPENDED} 收尾 ⇒ {@code registerSuspendedGates} 入口判据不成立 ⇒
     * 两处登记全跳过⇒ 前端无法提交、30min reaper 扫不到、而唤醒守卫按
     * {@code hasPendingHitl}（读上下文，与内存源无关）判「有未决闸门」把每次唤醒永久
     * 延后（00:01→09:41 被挡 290 次，inbox 积压待办永不执行）。
     *
     * <p>本用例守护 {@code doFinally} 里的 {@code reconcileGateAfterRun} 接线：
     * 它是唯一能让三方重新一致的路径。反证方式（已实测）：把该调用去掉，本用例
     * 立即失败——不是「测试通过等于接线正确」，而是它真的能看见接线。</p>
     *
     * <p>前提：{@code setUp} 里给 mock agent 装了 state store，否则对账会在读状态前
     * 静默跳过，本用例变假绿（第一次跑就是这样错的：断言没生效，全绿却什么也没测到）。</p>
     */
    @Test
    void danglingGateReconcileRunsOnEveryRunCompletion() {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new AgentResultEvent(new UserMessage("ok"))));

        launcher.launch(agent, List.of(new UserMessage("自检")), rtc(), AGENT, USER, SESSION)
                .collectList()
                .block();

        Mockito.verify(registrar, Mockito.atLeastOnce())
                .reconcileDanglingGate(any(), eq(AGENT), eq(USER), eq(SESSION));
    }

    /**
     * 异常与取消路径同样要对账：闸门可能在 run 被中断时留下悬空 tool_use
     * （那正是「无 TOOL_SUSPENDED 收尾」的一种），只在正常收尾对账会漏。
     */
    @Test
    void danglingGateReconcileRunsOnErrorCompletion() {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.error(new RuntimeException("boom")));

        launcher.launch(agent, List.of(new UserMessage("自检")), rtc(), AGENT, USER, SESSION)
                .collectList()
                .onErrorResume(e -> Mono.just(List.of()))
                .block();

        Mockito.verify(registrar, Mockito.atLeastOnce())
                .reconcileDanglingGate(any(), eq(AGENT), eq(USER), eq(SESSION));
    }

    /** 对账失败不得影响 run 本身（尽力而为语义与其它登记一致）。 */
    @Test
    void danglingGateReconcileFailureDoesNotBreakTheRun() {
        when(agent.streamEvents(anyList(), any(RuntimeContext.class)))
                .thenReturn(Flux.just(new AgentResultEvent(new UserMessage("ok"))));
        Mockito.doThrow(new IllegalStateException("state store unreadable"))
                .when(registrar).reconcileDanglingGate(any(), anyString(), anyString(), anyString());

        List<AgentEvent> events = launcher
                .launch(agent, List.of(new UserMessage("自检")), rtc(), AGENT, USER, SESSION)
                .collectList()
                .block();

        assertThat(events).hasSize(1);
    }

    private static RuntimeContext rtc() {
        return RuntimeContext.builder().sessionId(SESSION).userId(USER).build();
    }
}
