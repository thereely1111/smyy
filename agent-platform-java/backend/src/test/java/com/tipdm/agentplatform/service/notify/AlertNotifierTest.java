package com.tipdm.agentplatform.service.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.config.AlertProperties;
import com.tipdm.agentplatform.domain.PlatformNotification;
import com.tipdm.agentplatform.repository.PlatformNotificationRepository;
import com.tipdm.agentplatform.repository.SessionAlertPushRepository;
import com.tipdm.agentplatform.service.channel.AlertChannelDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
/**
 * 告警发布：去重与投递语义。
 *
 * <p>去重是这类告警最容易出错也最要命的地方——周期性看护 60s 一轮，
 * 一旦抑制失效，一个停摆会话一夜之间能刷出上千条通知，告警本身变成噪音。</p>
 */
class AlertNotifierTest {

    private PlatformNotificationRepository repository;
    private NotifyStreamRegistry registry;
    private AlertProperties props;
    private AlertChannelDispatcher channelDispatcher;
    private SessionAlertPushRepository pushRepository;
    private AlertNotifier notifier;

    @BeforeEach
    void setUp() {
        repository = mock(PlatformNotificationRepository.class);
        registry = mock(NotifyStreamRegistry.class);
        props = new AlertProperties();
        props.setEnabled(true);
        props.setDedupCooldownMinutes(60);
        channelDispatcher = mock(AlertChannelDispatcher.class);
        pushRepository = mock(SessionAlertPushRepository.class);
        when(pushRepository.findByUserIdAndSessionId(anyString(), anyString()))
                .thenReturn(java.util.Optional.empty());
        notifier = new AlertNotifier(repository, registry, props, new ObjectMapper(),
                channelDispatcher, new SessionAlertPushService(pushRepository));
        // 模拟持久化副作用：真实 JPA 由 @PrePersist 生成 id，mock 必须自己补，
        // 否则 publish 返回的 id 恒为 null（BaseEntity 不经过实体生命周期回调）。
        when(repository.save(any(PlatformNotification.class)))
                .thenAnswer(inv -> {
                    PlatformNotification n = inv.getArgument(0);
                    n.setId(UUID.randomUUID());
                    return n;
                });
    }

    private AlertNotifier.AlertRequest stallAlert(String userId, String sessionId) {
        return AlertNotifier.AlertRequest.of(
                AlertNotifier.LEVEL_ERROR, "会话已空闲 40 分钟，可能已停摆",
                "详情", userId, sessionId, "stall:" + sessionId);
    }

    /**
     * 扇出已移出调用线程（独立线程池），断言前必须显式等它排空：
     * 否则 {@code verify(..., never())} 会在任务尚未执行时"假通过"。
     */
    private void drainFanOut() {
        assertThat(notifier.awaitFanOutIdle(2_000))
                .as("异步扇出应在 2 秒内跑完")
                .isTrue();
    }

    @Test
    void publishesAndPersistsWhenNoDuplicate() {
        when(repository.existsByUserIdAndAlertKeyAndReadAtIsNull("u1", "stall:s1"))
                .thenReturn(false);
        when(repository.findLatestAlertAt(eq("u1"), eq("stall:s1"), any(Instant.class)))
                .thenReturn(null);

        assertThat(notifier.publish(stallAlert("u1", "s1"))).isNotNull();

        ArgumentCaptor<PlatformNotification> captor =
                ArgumentCaptor.forClass(PlatformNotification.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo("u1");
        assertThat(captor.getValue().getSessionId()).isEqualTo("s1");
        assertThat(captor.getValue().getLevel()).isEqualTo("error");
        assertThat(captor.getValue().getAlertKey()).isEqualTo("stall:s1");
        // 必须推 SSE，否则在线用户收不到即时提醒
        verify(registry).send(eq("u1"), eq("session-alert"), any());
    }

    /**
     * 同键已有<b>时效内</b>的未读 → 抑制。这是「提醒一次，直到你看过」的核心语义
     * （时效上限见 unreadAlertOlderThanSuppressWindowIsNotSuppressed）。
     */
    @Test
    void suppressedWhenUnreadAlertExists() {
        when(repository.countUnreadSince(eq("u1"), eq("stall:s1"), any(Instant.class)))
                .thenReturn(1L);

        assertThat(notifier.publish(stallAlert("u1", "s1"))).isNull();

        verify(repository, never()).save(any(PlatformNotification.class));
        verify(registry, never()).send(anyString(), anyString(), any());
    }

    /**
     * 未读抑制有 24 小时时效：用户一直不读铃铛时，同一问题三天后再现必须能再提醒一次，
     * 而不是被"永久未读抑制"吃掉（冷却窗口否则永远走不到）。
     */
    @Test
    void unreadAlertOlderThanSuppressWindowIsNotSuppressed() {
        when(repository.countUnreadSince(eq("u1"), eq("stall:s1"), any(Instant.class)))
                .thenReturn(0L); // 时效内没有未读（更早的未读不算）
        when(repository.findLatestAlertAt(eq("u1"), eq("stall:s1"), any(Instant.class)))
                .thenReturn(null);

        assertThat(notifier.publish(stallAlert("u1", "s1"))).isNotNull();

        verify(repository).save(any(PlatformNotification.class));
    }

    /** 全部已读后又被命中：靠时间冷却兜底，避免用户读完立刻被同一事件刷屏。 */
    @Test
    void suppressedByTimeCooldownAfterRead() {
        when(repository.existsByUserIdAndAlertKeyAndReadAtIsNull("u1", "stall:s1"))
                .thenReturn(false);
        when(repository.findLatestAlertAt(eq("u1"), eq("stall:s1"), any(Instant.class)))
                .thenReturn(Instant.now().minus(java.time.Duration.ofMinutes(10)));

        assertThat(notifier.publish(stallAlert("u1", "s1"))).isNull();
        verify(repository, never()).save(any(PlatformNotification.class));
    }

    /** 无 alertKey 的告警不去重——调用方自行保证低频。 */
    @Test
    void alertWithoutKeyIsNeverSuppressed() {
        AlertNotifier.AlertRequest req = AlertNotifier.AlertRequest.of(
                AlertNotifier.LEVEL_WARN, "无键告警", "详情", "u1", "s1", null);

        assertThat(notifier.publish(req)).isNotNull();
        verify(repository).save(any(PlatformNotification.class));
    }

    @Test
    void anonymousUserIsIgnored() {
        assertThat(notifier.publish(stallAlert("anonymous", "s1"))).isNull();
        verify(repository, never()).save(any(PlatformNotification.class));
    }

    @Test
    void disabledAlertIsSkipped() {
        props.setEnabled(false);
        assertThat(notifier.publish(stallAlert("u1", "s1"))).isNull();
        verify(repository, never()).save(any(PlatformNotification.class));
    }

    /** 级别兜底：未知级别按 info，避免前端 LEVEL_DOT 取不到色点。 */
    @Test
    void unknownLevelFallsBackToInfo() {
        when(repository.existsByUserIdAndAlertKeyAndReadAtIsNull(anyString(), anyString()))
                .thenReturn(false);
        when(repository.findLatestAlertAt(anyString(), anyString(), any(Instant.class)))
                .thenReturn(null);

        notifier.publish(AlertNotifier.AlertRequest.of(
                "fatal", "未知级别", null, "u1", "s1", "k1"));

        ArgumentCaptor<PlatformNotification> captor =
                ArgumentCaptor.forClass(PlatformNotification.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getLevel()).isEqualTo("info");
    }

    /** 发布成功后必须扇出到用户自建渠道（第四条投递路径）。 */
    @Test
    void fansOutToUserChannelsOnPublish() {
        when(repository.existsByUserIdAndAlertKeyAndReadAtIsNull(anyString(), anyString()))
                .thenReturn(false);
        when(repository.findLatestAlertAt(anyString(), anyString(), any(Instant.class)))
                .thenReturn(null);
        // 订阅开启且勾选了渠道 → 只扇出白名单内的渠道
        com.tipdm.agentplatform.domain.SessionAlertPush on =
                new com.tipdm.agentplatform.domain.SessionAlertPush();
        on.setUserId("u1");
        on.setSessionId("s1");
        on.setEnabled(true);
        UUID picked = UUID.randomUUID();
        on.setChannelIds(picked + ",");
        when(pushRepository.findByUserIdAndSessionId("u1", "s1"))
                .thenReturn(java.util.Optional.of(on));
        when(channelDispatcher.dispatch(any(PlatformNotification.class), any())).thenReturn(2);

        assertThat(notifier.publish(stallAlert("u1", "s1"))).isNotNull();
        drainFanOut();

        org.mockito.ArgumentCaptor<java.util.Collection<UUID>> ids =
                org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(channelDispatcher).dispatch(any(PlatformNotification.class), ids.capture());
        assertThat(ids.getValue()).containsExactly(picked);
    }

    /** 渠道扇出抛异常不得影响告警主流程（落库/SSE 已完成的成果不能回滚）。 */
    @Test
    void channelFanOutFailureDoesNotBreakPublish() {
        when(repository.existsByUserIdAndAlertKeyAndReadAtIsNull(anyString(), anyString()))
                .thenReturn(false);
        when(repository.findLatestAlertAt(anyString(), anyString(), any(Instant.class)))
                .thenReturn(null);
        // 必须真的勾选渠道，否则扇出会被订阅校验提前跳过，测不到"抛异常"这条路径
        com.tipdm.agentplatform.domain.SessionAlertPush on =
                new com.tipdm.agentplatform.domain.SessionAlertPush();
        on.setUserId("u1");
        on.setSessionId("s1");
        on.setEnabled(true);
        on.setChannelIds(UUID.randomUUID() + ",");
        when(pushRepository.findByUserIdAndSessionId("u1", "s1"))
                .thenReturn(java.util.Optional.of(on));
        when(channelDispatcher.dispatch(any(PlatformNotification.class), any()))
                .thenThrow(new IllegalStateException("boom"));

        assertThat(notifier.publish(stallAlert("u1", "s1"))).isNotNull();
        // 扇出线程里抛出的异常必须被吞掉，不能反过来污染 publish 的结果
        drainFanOut();
        verify(channelDispatcher).dispatch(any(PlatformNotification.class), any());
    }

    /** 会话级 opt-out：订阅关闭时告警仍落库/SSE，但不扇出到 IM 渠道。 */
    @Test
    void sessionSubscriptionOffSkipsChannelFanOut() {
        when(repository.existsByUserIdAndAlertKeyAndReadAtIsNull(anyString(), anyString()))
                .thenReturn(false);
        when(repository.findLatestAlertAt(anyString(), anyString(), any(Instant.class)))
                .thenReturn(null);
        com.tipdm.agentplatform.domain.SessionAlertPush off =
                new com.tipdm.agentplatform.domain.SessionAlertPush();
        off.setUserId("u1");
        off.setSessionId("s1");
        off.setEnabled(false);
        when(pushRepository.findByUserIdAndSessionId("u1", "s1"))
                .thenReturn(java.util.Optional.of(off));

        assertThat(notifier.publish(stallAlert("u1", "s1"))).isNotNull();
        drainFanOut();

        verify(channelDispatcher, never()).dispatch(any(PlatformNotification.class), any());
        verify(repository).save(any(PlatformNotification.class));
    }

    /** opt-in：无订阅记录的会话默认不扇出（推不推、推到哪由用户主动勾选）。 */
    @Test
    void sessionWithoutSubscriptionDoesNotFanOut() {
        when(repository.existsByUserIdAndAlertKeyAndReadAtIsNull(anyString(), anyString()))
                .thenReturn(false);
        when(repository.findLatestAlertAt(anyString(), anyString(), any(Instant.class)))
                .thenReturn(null);

        assertThat(notifier.publish(stallAlert("u1", "s1"))).isNotNull();
        drainFanOut();

        verify(channelDispatcher, never()).dispatch(any(PlatformNotification.class), any());
        verify(repository).save(any(PlatformNotification.class));
    }

    /** 订阅开启但没勾任何渠道 → 同样不外发。 */
    @Test
    void subscriptionWithoutPickedChannelsDoesNotFanOut() {
        when(repository.existsByUserIdAndAlertKeyAndReadAtIsNull(anyString(), anyString()))
                .thenReturn(false);
        when(repository.findLatestAlertAt(anyString(), anyString(), any(Instant.class)))
                .thenReturn(null);
        com.tipdm.agentplatform.domain.SessionAlertPush empty =
                new com.tipdm.agentplatform.domain.SessionAlertPush();
        empty.setUserId("u1");
        empty.setSessionId("s1");
        empty.setEnabled(true);
        when(pushRepository.findByUserIdAndSessionId("u1", "s1"))
                .thenReturn(java.util.Optional.of(empty));

        assertThat(notifier.publish(stallAlert("u1", "s1"))).isNotNull();
        drainFanOut();

        verify(channelDispatcher, never()).dispatch(any(PlatformNotification.class), any());
    }

    /** 全局告警（无 sessionId）不受会话白名单约束，按用户全部启用渠道扇出。 */
    @Test
    void globalAlertFansOutToAllChannels() {
        when(repository.existsByUserIdAndAlertKeyAndReadAtIsNull(anyString(), anyString()))
                .thenReturn(false);
        when(repository.findLatestAlertAt(anyString(), anyString(), any(Instant.class)))
                .thenReturn(null);
        when(channelDispatcher.dispatch(any(PlatformNotification.class),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(3);

        AlertNotifier.AlertRequest global = AlertNotifier.AlertRequest.of(
                AlertNotifier.LEVEL_ERROR, "系统级告警", "详情", "u1", null, "global:k1");
        assertThat(notifier.publish(global)).isNotNull();
        drainFanOut();

        verify(channelDispatcher).dispatch(any(PlatformNotification.class),
                org.mockito.ArgumentMatchers.isNull());
    }
}
