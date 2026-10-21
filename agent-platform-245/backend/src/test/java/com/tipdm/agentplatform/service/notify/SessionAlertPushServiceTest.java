package com.tipdm.agentplatform.service.notify;

import com.tipdm.agentplatform.domain.SessionAlertPush;
import com.tipdm.agentplatform.repository.SessionAlertPushRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话告警推送白名单语义（V44：opt-in + 显式选渠道）。
 *
 * <p>锁三条规则：① 无记录/关闭 = 不扇出（推不推由用户主动选，不再默认全推）；
 * ② 开启但未勾渠道 = 不扇出；③ 白名单解析只认合法 UUID，脏数据跳过不整体失败。
 * 另锁 CSV 归一化：去重保序、去空白、非法 id 丢弃。</p>
 */
class SessionAlertPushServiceTest {

    private SessionAlertPushRepository repository;
    private SessionAlertPushService service;

    @BeforeEach
    void setUp() {
        repository = mock(SessionAlertPushRepository.class);
        service = new SessionAlertPushService(repository);
        when(repository.findByUserIdAndSessionId(anyString(), anyString()))
                .thenReturn(Optional.empty());
    }

    private SessionAlertPush row(boolean enabled, String channelIds) {
        SessionAlertPush p = new SessionAlertPush();
        p.setUserId("u1");
        p.setSessionId("s1");
        p.setEnabled(enabled);
        p.setChannelIds(channelIds);
        return p;
    }

    @Test
    void noRecordMeansNoFanout() {
        assertThat(service.resolveFanoutChannelIds("u1", "s1")).isEmpty();
        assertThat(service.effectiveEnabled("u1", "s1")).isFalse();
    }

    @Test
    void disabledRecordMeansNoFanout() {
        when(repository.findByUserIdAndSessionId("u1", "s1"))
                .thenReturn(Optional.of(row(false, UUID.randomUUID().toString())));
        assertThat(service.resolveFanoutChannelIds("u1", "s1")).isEmpty();
    }

    @Test
    void enabledWithoutPickedChannelsMeansNoFanout() {
        when(repository.findByUserIdAndSessionId("u1", "s1"))
                .thenReturn(Optional.of(row(true, "")));
        assertThat(service.resolveFanoutChannelIds("u1", "s1")).isEmpty();
    }

    @Test
    void picksOnlyWhitelistedChannels() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(repository.findByUserIdAndSessionId("u1", "s1"))
                .thenReturn(Optional.of(row(true, a + "," + b)));
        assertThat(service.resolveFanoutChannelIds("u1", "s1")).containsExactly(a, b);
    }

    @Test
    void globalAlertReturnsNullConstraint() {
        assertThat(service.resolveFanoutChannelIds("u1", null)).isNull();
        assertThat(service.resolveFanoutChannelIds("u1", "  ")).isNull();
    }

    @Test
    void dirtyCsvEntriesAreSkipped() {
        UUID a = UUID.randomUUID();
        when(repository.findByUserIdAndSessionId("u1", "s1"))
                .thenReturn(Optional.of(row(true, "not-a-uuid,," + a)));
        assertThat(service.resolveFanoutChannelIds("u1", "s1")).containsExactly(a);
    }

    @Test
    void normalizeDedupesKeepsOrderAndDropsInvalid() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        String csv = SessionAlertPushService.normalizeChannelIds(
                List.of(b.toString(), a.toString(), b.toString(), "  ", "junk", a.toString()));
        String[] parts = csv.split(",");
        assertThat(parts).containsExactly(b.toString(), a.toString());
    }

    @Test
    void setKeepsChannelsWhenIdsNull() {
        UUID a = UUID.randomUUID();
        when(repository.findByUserIdAndSessionId("u1", "s1"))
                .thenReturn(Optional.of(row(true, a.toString())));
        service.set("u1", "s1", false, null);
        ArgumentCaptor<SessionAlertPush> captor =
                ArgumentCaptor.forClass(SessionAlertPush.class);
        Mockito.verify(repository).save(captor.capture());
        assertThat(captor.getValue().isEnabled()).isFalse();
        assertThat(captor.getValue().getChannelIds()).isEqualTo(a.toString());
    }
}
