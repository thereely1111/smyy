package com.tipdm.agentplatform.harness;

import com.tipdm.agentplatform.service.SessionOwnerService;
import io.agentscope.harness.agent.bus.MessageBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 迭代上限截断续跑的投递契约。
 *
 * <p>覆盖三件事：截断时确实投出 inbox hint 并请求唤醒；滑动窗口内超出上限后
 * 停止自动续跑；关闭开关或缺归属时不投递。</p>
 */
class TruncationResumeNudgerTest {

    private static final String SESSION = "session-1";
    private static final String AGENT = "agent-1";
    private static final String USER = "user-1";

    private MessageBus messageBus;
    private SessionOwnerService sessionOwnerService;
    private TruncationResumeNudger nudger;

    @BeforeEach
    void setUp() {
        messageBus = mock(MessageBus.class);
        sessionOwnerService = mock(SessionOwnerService.class);
        when(messageBus.inboxPush(anyString(), anyMap())).thenReturn(Mono.empty());
        when(messageBus.enqueueWakeup(anyString(), anyString(), anyString())).thenReturn(Mono.empty());
        when(sessionOwnerService.agentBySessionId(SESSION)).thenReturn(Optional.of(AGENT));
        when(sessionOwnerService.owner(AGENT, SESSION)).thenReturn(Optional.of(USER));
        nudger = new TruncationResumeNudger(messageBus, sessionOwnerService,
                true, 0L, 3, 30L);
    }

    @Test
    void pushesResumeHintAndRequestsWakeup() {
        nudger.onTruncated(SESSION, 10, 10);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(messageBus).inboxPush(eq(SESSION), payload.capture());
        assertThat(payload.getValue()).containsEntry("source", TruncationResumeNudger.HINT_SOURCE);
        assertThat(payload.getValue().get("hint").toString())
                .contains("maxIters=10")
                .contains(TruncationResumeNudger.HINT_SOURCE);
        // 唤醒请求延迟发出（此处 delay=0），故用 timeout 等待异步投递。
        verify(messageBus, timeout(2000)).enqueueWakeup(USER, SESSION, AGENT);
    }

    @Test
    void capsRepeatedResumesWithinWindow() {
        for (int i = 0; i < 5; i++) {
            nudger.onTruncated(SESSION, 10, 10);
        }
        // 前 3 次投递，第 4/5 次超限静默（唤醒请求是异步投递，此处只断言 hint 侧）。
        verify(messageBus, times(3)).inboxPush(eq(SESSION), anyMap());
    }

    @Test
    void staysSilentWhenDisabled() {
        TruncationResumeNudger disabled = new TruncationResumeNudger(messageBus, sessionOwnerService,
                false, 0L, 3, 30L);
        disabled.onTruncated(SESSION, 10, 10);
        verify(messageBus, never()).inboxPush(anyString(), anyMap());
        verify(messageBus, never()).enqueueWakeup(anyString(), anyString(), anyString());
    }

    @Test
    void skipsWhenNoOwnerAgent() {
        when(sessionOwnerService.agentBySessionId(SESSION)).thenReturn(Optional.empty());
        nudger.onTruncated(SESSION, 10, 10);
        verify(messageBus, never()).inboxPush(anyString(), anyMap());
        verify(messageBus, never()).enqueueWakeup(anyString(), anyString(), anyString());
    }
}
