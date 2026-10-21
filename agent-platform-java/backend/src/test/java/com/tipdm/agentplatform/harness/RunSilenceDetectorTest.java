package com.tipdm.agentplatform.harness;

import com.tipdm.agentplatform.repository.AgentSessionOwnerRepository;
import com.tipdm.agentplatform.service.notify.AlertNotifier;
import io.agentscope.harness.agent.bus.MessageBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 静默探测器的分档行为。
 *
 * <p>核心是两条边界：① 子智能体会话（{@code sub-*}）的细粒度事件被转发到父会话流，其
 * 「时钟在但长期无事件」是结构性失真，不得据此判死（实测误报时该子会话仍在写产物）；
 * ② 但 {@code clockMissing}（在跑却无时钟条目）是收尾账本真丢了，对父子会话同为真缺陷，
 * 不能被子会话豁免一起放行——漏掉这条会把计数泄漏重新变成盲区。</p>
 */
class RunSilenceDetectorTest {

    private static final String SUB_SESSION = "sub-a14a76bd-a543-4cc5-ad1c-fc14a6fd09d0";
    private static final String MAIN_SESSION = "main-9b94883c-f21d-4f48-b952-1dc2ac7c30d4";

    private final SessionEventClock clock = new SessionEventClock();
    private final SessionActivityTracker tracker = new SessionActivityTracker();

    private RunLeaseStore leaseStore;
    private MessageBus messageBus;
    private AlertNotifier alertNotifier;
    private RunSubscriptionRegistry subscriptions;

    @BeforeEach
    void setUp() {
        leaseStore = mock(RunLeaseStore.class);
        messageBus = mock(MessageBus.class);
        alertNotifier = mock(AlertNotifier.class);
        subscriptions = mock(RunSubscriptionRegistry.class);
        when(leaseStore.instanceId()).thenReturn("test-instance");
        when(leaseStore.findAll()).thenReturn(List.of());
        when(subscriptions.dispose(anyString())).thenReturn(false);
        when(messageBus.inboxPush(anyString(), any())).thenReturn(Mono.empty());
    }

    /** 阈值取 0 分钟 ⇒ 下一次巡检立刻落到该档，不必真等 20/60 分钟。 */
    private RunSilenceDetector detector(long detectMinutes, long recoverMinutes) {
        return new RunSilenceDetector(clock, tracker, leaseStore, messageBus,
                mock(AgentSessionOwnerRepository.class), alertNotifier, subscriptions,
                detectMinutes, recoverMinutes);
    }

    private static void tick() throws InterruptedException {
        Thread.sleep(15);
    }

    // ── 判死档：子会话豁免，主会话照旧 ────────────────────────────────────

    @Test
    void subagentSession_staleClock_isNotDeclaredDead() throws Exception {
        clock.begin(SUB_SESSION);
        tracker.begin(SUB_SESSION);
        tick();

        detector(0, 0).sweep();

        assertThat(tracker.isRunning(SUB_SESSION)).isTrue();
        verify(subscriptions, never()).dispose(anyString());
        verify(messageBus, never()).inboxPush(anyString(), any());
        verify(leaseStore, never()).delete(anyString());
    }

    @Test
    void mainSession_staleClock_isStillDeclaredDead() throws Exception {
        clock.begin(MAIN_SESSION);
        tracker.begin(MAIN_SESSION);
        tick();

        detector(0, 0).sweep();

        assertThat(tracker.isRunning(MAIN_SESSION)).isFalse();
        verify(messageBus, times(1)).inboxPush(anyString(), any());
        verify(leaseStore, times(1)).delete(MAIN_SESSION);
    }

    /**
     * 豁免只针对「时钟在但无事件」这一形态。计数泄漏（只登记了活跃计数、收尾没写时钟条目）
     * 是真实缺陷，对子会话同样要恢复——否则 2026-09-27「停滞闸门被永久关死」的那条盲区会回来。
     *
     * <p>泄漏档的静默起点是「首次被观测到在跑」，单测里没有等 10 分钟的手段，故首轮巡检
     * 登记锚点后把锚点回拨到阈值之外。</p>
     */
    @Test
    void subagentSession_leakedCount_isStillRecovered() throws Exception {
        tracker.begin(SUB_SESSION);
        RunSilenceDetector detector = detector(0, 600);
        detector.sweep();
        backdateLeakAnchor(detector, SUB_SESSION, 11);
        detector.sweep();

        assertThat(tracker.isRunning(SUB_SESSION)).isFalse();
        verify(messageBus, times(1)).inboxPush(anyString(), any());
        verify(leaseStore, times(1)).delete(SUB_SESSION);
    }

    // ── 报警档：子会话不投递用户告警（且文案不得把人引向「上游流停滞」）──────

    @Test
    void subagentSession_silenceAlert_doesNotPublishUserNotification() throws Exception {
        clock.begin(SUB_SESSION);
        tracker.begin(SUB_SESSION);
        tick();

        detector(0, 600).sweep();

        verify(alertNotifier, never()).publish(any());
        // 只留痕、不动账本：时钟条目与活跃计数都还在
        assertThat(clock.snapshot()).containsKey(SUB_SESSION);
        assertThat(tracker.isRunning(SUB_SESSION)).isTrue();
    }

    @Test
    void mainSession_silenceAlert_publishesUserNotification() throws Exception {
        clock.begin(MAIN_SESSION);
        tracker.begin(MAIN_SESSION);
        tick();

        detector(0, 600).sweep();

        verify(alertNotifier, times(1)).publish(any());
        assertThat(tracker.isRunning(MAIN_SESSION)).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static void backdateLeakAnchor(
            RunSilenceDetector detector, String sessionId, long minutes) throws Exception {
        java.lang.reflect.Field field =
                RunSilenceDetector.class.getDeclaredField("firstSeenNoClock");
        field.setAccessible(true);
        Map<String, Long> anchors = (Map<String, Long>) field.get(detector);
        anchors.put(sessionId, System.currentTimeMillis() - minutes * 60_000L);
    }
}
