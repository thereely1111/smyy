package com.tipdm.agentplatform.harness;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 相位级活性打点：{@code sub-*} 子智能体 run 的细粒度事件被框架转发到父会话流，子会话自身的
 * onAgent 链只收到起止事件 ⇒ 时钟冻在 run 起跑时刻、静默量恒等于 run 已跑时长，20 分钟后必被
 * {@link RunSilenceDetector} 判成上游流停滞。修复是让 reasoning / acting 相位钩子（独立于
 * 事件转发的那条链）进入即 touch。
 *
 * <p>两条正例是本修复的反证：注掉 {@code SessionActivityMiddleware.touchOnPhaseEntry} 里的
 * {@code eventClock.touch(...)}，两条正例都会退化成「时钟停在起跑时刻」而失败。</p>
 */
class SessionActivityMiddlewareTest {

    /** 与线上误报同一形态的会话 id（20 分钟告警时该子会话仍在写产物）。 */
    private static final String SUB_SESSION = "sub-a14a76bd-a543-4cc5-ad1c-fc14a6fd09d0";
    private static final String MAIN_SESSION = "main-9b94883c-f21d-4f48-b952-1dc2ac7c30d4";

    private final SessionEventClock clock = new SessionEventClock();
    private final SessionActivityTracker tracker = new SessionActivityTracker();
    private final SessionActivityMiddleware middleware =
            new SessionActivityMiddleware(tracker, new SessionAgentRegistry(), clock);

    private static RuntimeContext ctx(String sessionId) {
        return RuntimeContext.builder().userId("user-1").sessionId(sessionId).build();
    }

    private static void tick() throws InterruptedException {
        // 时钟值是毫秒时间戳，需跨过一次毫秒跳变才能观察到刷新。
        Thread.sleep(15);
    }

    private long clockAt(String sessionId) {
        Long at = clock.snapshot().get(sessionId);
        assertThat(at).as("会话 %s 应有事件时钟条目", sessionId).isNotNull();
        return at;
    }

    @Test
    void reasoningPhaseEntry_refreshesClock_evenWhenNoEventFlowsBack() throws Exception {
        clock.begin(SUB_SESSION);
        long atRunStart = clockAt(SUB_SESSION);
        tick();

        // 子会话形态：事件被转发到父会话流，本相位返回的流是空的。
        middleware.onReasoning(
                null, ctx(SUB_SESSION), new ReasoningInput(List.of(), null, null),
                in -> Flux.empty()).blockLast();

        assertThat(clockAt(SUB_SESSION)).isGreaterThan(atRunStart);
    }

    @Test
    void actingPhaseEntry_refreshesClock_evenWhenNoEventFlowsBack() throws Exception {
        clock.begin(SUB_SESSION);
        long atRunStart = clockAt(SUB_SESSION);
        tick();

        middleware.onActing(
                null, ctx(SUB_SESSION), new ActingInput(List.of()),
                in -> Flux.empty()).blockLast();

        assertThat(clockAt(SUB_SESSION)).isGreaterThan(atRunStart);
    }

    @Test
    void phaseEntry_touchesOncePerEventToo() throws Exception {
        clock.begin(SUB_SESSION);
        long atRunStart = clockAt(SUB_SESSION);
        tick();

        middleware.onReasoning(
                null, ctx(SUB_SESSION), new ReasoningInput(List.of(), null, null),
                in -> Flux.just(new AgentStartEvent(SUB_SESSION, "reply-1", "前后置智能体")))
                .blockLast();

        assertThat(clockAt(SUB_SESSION)).isGreaterThan(atRunStart);
    }

    /** 主会话（事件确实流回本会话）的既有路径不能因本次改动失效。 */
    @Test
    void agentChain_withEvents_stillRefreshesClock() throws Exception {
        clock.begin(MAIN_SESSION);
        long atRunStart = clockAt(MAIN_SESSION);
        tick();

        middleware.onAgent(
                null, ctx(MAIN_SESSION), new AgentInput(List.of()),
                in -> Flux.just(new AgentStartEvent(MAIN_SESSION, "reply-1", "教材开发小助手")))
                .blockLast();

        assertThat(clockAt(MAIN_SESSION)).isGreaterThan(atRunStart);
    }

    /**
     * 反证的另一半：修复前子会话的唯一形态（只有 onAgent 链、无任何事件流回、相位钩子从未
     * 被调用）下，时钟停在起跑时刻不再前进——这正是误报的成因。
     */
    @Test
    void agentChainWithoutPhaseHooks_leavesClockFrozenAtRunStart() throws Exception {
        long before = System.currentTimeMillis();
        // 流不完成 = run 仍在跑。完成（或取消）会走 doFinally 释放时钟条目，那是正常收尾，
        // 不是本用例要覆盖的形态。
        Disposable handle = middleware.onAgent(
                null, ctx(SUB_SESSION), new AgentInput(List.of()),
                in -> Flux.never()).subscribe();
        try {
            long atRunStart = clockAt(SUB_SESSION);
            assertThat(atRunStart).isGreaterThanOrEqualTo(before);

            tick();

            assertThat(clockAt(SUB_SESSION)).isEqualTo(atRunStart);
            assertThat(tracker.isRunning(SUB_SESSION)).isTrue();
        } finally {
            handle.dispose();
        }
    }

    /** 缺失 sessionId 时（非会话上下文）钩子必须空放行，不得写入任何时钟条目。 */
    @Test
    void phaseHooks_withoutSessionId_areNoOps() throws Exception {
        RuntimeContext noSession = RuntimeContext.builder().userId("user-1").build();

        assertThatCode(() -> middleware.onReasoning(
                null, noSession, new ReasoningInput(List.of(), null, null),
                in -> Flux.empty()).blockLast()).doesNotThrowAnyException();
        assertThatCode(() -> middleware.onActing(
                null, noSession, new ActingInput(List.of()),
                in -> Flux.empty()).blockLast()).doesNotThrowAnyException();

        assertThat(clock.snapshot()).isEmpty();
    }
}
