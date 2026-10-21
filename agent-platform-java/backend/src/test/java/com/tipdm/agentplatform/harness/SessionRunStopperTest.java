package com.tipdm.agentplatform.harness;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;

/**
 * 删会话前的「停止在跑 run」回归。
 *
 * <p>事故背景（2026-09-11 12:29 实测）：清会话删掉状态行后，仍在跑的 run 结束时会用
 * CAS 回写状态（{@code getVersioned(agent_state)}）→ 行已删 → 未处理异常、接口 500
 * {@code Failed to get versioned state}。</p>
 */
class SessionRunStopperTest {

    private static final String USER = "user-1";
    private static final String SESSION = "main-1";

    private final RunSubscriptionRegistry registry = new RunSubscriptionRegistry();

    @Test
    void stopIfRunning_interruptsAndWaitsUntilIdle() {
        SessionActivityTracker tracker = new SessionActivityTracker();
        tracker.begin(SESSION); // run 在跑
        HarnessAgent agent = mock(HarnessAgent.class);
        // 中断触发后 run 很快收尾
        doAnswer(inv -> {
            tracker.end(SESSION);
            return null;
        }).when(agent).interrupt(anyString(), anyString());

        assertTrue(SessionRunStopper.stopIfRunning(agent, tracker, registry, USER, SESSION));
        verify(agent).interrupt(USER, SESSION);
        assertFalse(tracker.isRunning(SESSION));
    }

    @Test
    void stopIfRunning_noRunningRun_doesNotInterrupt() {
        SessionActivityTracker tracker = new SessionActivityTracker();
        HarnessAgent agent = mock(HarnessAgent.class);

        assertFalse(SessionRunStopper.stopIfRunning(agent, tracker, registry, USER, SESSION));
        verify(agent, never()).interrupt(anyString(), anyString());
    }

    /** 中断本身报错时不得抛出：删会话是用户意图，停止只是尽力而为。 */
    @Test
    void stopIfRunning_interruptFailure_isSwallowed() {
        SessionActivityTracker tracker = new SessionActivityTracker();
        tracker.begin(SESSION);
        HarnessAgent agent = mock(HarnessAgent.class);
        doThrow(new IllegalStateException("no active run")).when(agent).interrupt(anyString(), anyString());

        assertFalse(SessionRunStopper.stopIfRunning(agent, tracker, registry, USER, SESSION));
    }

    @Test
    void stopIfRunning_blankSession_isNoop() {
        SessionActivityTracker tracker = new SessionActivityTracker();
        HarnessAgent agent = mock(HarnessAgent.class);
        assertFalse(SessionRunStopper.stopIfRunning(agent, tracker, registry, USER, "  "));
        verify(agent, never()).interrupt(anyString(), anyString());
    }

    /**
     * 计数泄漏（{@code isRunning} 为false）但订阅仍登记着 run 时，<b>必须</b>仍发出中断。
     *
     * <p>这是「用户点停止没反应」的根因回归：原实现首行
     * {@code if (!tracker.isRunning(...)) return false;} 会把中断请求静默丢弃，
     * 而接口照样返回 202。订阅登记表（{@code doOnSubscribe} 写入）与活跃计数
     * <b>不同源</b>，因此能在计数失真时充当第二判据。</p>
     */
    @Test
    void stopIfRunning_leakedCountButSubscribed_stillInterrupts() {
        SessionActivityTracker tracker = new SessionActivityTracker();
        // 计数从未 begin（模拟 begin/end 不配对导致的计数丢失），但订阅已登记。
        registry.register(SESSION, mock(org.reactivestreams.Subscription.class));
        HarnessAgent agent = mock(HarnessAgent.class);

        assertTrue(SessionRunStopper.stopIfRunning(agent, tracker, registry, USER, SESSION),
                "计数说空闲但有登记订阅时必须仍尝试中断，否则用户点停止无效");
        verify(agent).interrupt(USER, SESSION);
    }

    /** 对照：两个来源都为假（无计数、无订阅）时才是真的无需中断。 */
    @Test
    void stopIfRunning_noCountNoSubscription_doesNotInterrupt() {
        SessionActivityTracker tracker = new SessionActivityTracker();
        HarnessAgent agent = mock(HarnessAgent.class);

        assertFalse(SessionRunStopper.stopIfRunning(agent, tracker, registry, USER, SESSION));
        verify(agent, never()).interrupt(anyString(), anyString());
    }

    /** 订阅登记被dispose 时必须发出真取消（Reactor cancel ⇒ 上游 HTTP abort）。 */
    @Test
    void stopIfRunning_disposesSubscriptionBeforeInterrupt() {
        SessionActivityTracker tracker = new SessionActivityTracker();
        tracker.begin(SESSION);
        org.reactivestreams.Subscription sub = mock(org.reactivestreams.Subscription.class);
        registry.register(SESSION, sub);
        HarnessAgent agent = mock(HarnessAgent.class);
        doAnswer(inv -> {
            tracker.end(SESSION);
            return null;
        }).when(agent).interrupt(anyString(), anyString());

        assertTrue(SessionRunStopper.stopIfRunning(agent, tracker, registry, USER, SESSION));
        verify(sub).cancel();
    }
}
