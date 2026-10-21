package com.tipdm.agentplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.config.ProgressWatchdogProperties;
import com.tipdm.agentplatform.service.notify.AlertNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * HITL 等待作为独立通知类型的告警路由（maybeAlertStall 拆分）。
 *
 * <p>锁三条规则：① 会话在等人工输入时走 hitl-wait 独立去重键（与 stall 键互不
 * 抑制，否则「未读抑制」会让两条通知互相吞掉）；② 阈值是 hitlAlertMinMinutes
 * （分钟级可见），不是停滞告警的 30 分钟；③ 不等人工的会话仍走原 stall 键、
 * error 级，行为不变。</p>
 */
class SessionProgressHitlAlertTest {

    private AlertNotifier alertNotifier;
    private SessionProgressService service;

    @BeforeEach
    void setUp() {
        alertNotifier = mock(AlertNotifier.class);
        // maybeAlertStall 只消费 watchdogProps 与 alertNotifier，其余依赖可空
        //（末位 SessionKnowledgeService 为后续新增依赖，同样可空）
        service = new SessionProgressService(
                null, null, null, null, null,
                new ProgressWatchdogProperties(), null, alertNotifier, null, null, null, null,
                // maybeAlertStall 不消费契约探针，传空壳即可
                new SessionCompletionContractService(
                        mock(SessionWorkspaceManager.class), new ObjectMapper(), "x.json"));
    }

    private void alert(int idleMinutes, boolean waitingHuman) {
        alert(idleMinutes, waitingHuman, SessionProgressService.NEXT_WAIT_HITL, false);
    }

    private void alert(int idleMinutes, boolean waitingHuman, String nextAction,
                       boolean finished) {
        service.maybeAlertStall("agent-1", "session-1", "user-1",
                new SessionProgressService.NextActionDecision("初稿", nextAction),
                Instant.now().minusSeconds(idleMinutes * 60L),
                0, false, waitingHuman, finished);
    }

    @Test
    void hitlWaitGoesToDedicatedKeyAtLowThreshold() {
        alert(5, true);
        ArgumentCaptor<AlertNotifier.AlertRequest> captor =
                ArgumentCaptor.forClass(AlertNotifier.AlertRequest.class);
        verify(alertNotifier).publish(captor.capture());
        AlertNotifier.AlertRequest req = captor.getValue();
        assertEquals("hitl-wait:session-1", req.alertKey());
        assertEquals(AlertNotifier.LEVEL_WARN, req.level());
        assertEquals("session-1", req.sessionId());
    }

    @Test
    void hitlWaitBelowThresholdIsSilent() {
        alert(2, true); // 低于默认 3 分钟阈值
        verify(alertNotifier, never()).publish(ArgumentMatchers.any());
    }

    @Test
    void hitlWaitNeverFallsThroughToStallKey() {
        // 即使空闲 60 分钟，等人工的会话也只发 hitl-wait，不再叠加 stall 告警
        alert(60, true);
        ArgumentCaptor<AlertNotifier.AlertRequest> captor =
                ArgumentCaptor.forClass(AlertNotifier.AlertRequest.class);
        verify(alertNotifier).publish(captor.capture());
        assertEquals("hitl-wait:session-1", captor.getValue().alertKey());
    }

    @Test
    void stallWithoutHumanUsesGenericKeyAndErrorLevel() {
        alert(35, false);
        ArgumentCaptor<AlertNotifier.AlertRequest> captor =
                ArgumentCaptor.forClass(AlertNotifier.AlertRequest.class);
        verify(alertNotifier).publish(captor.capture());
        AlertNotifier.AlertRequest req = captor.getValue();
        assertEquals("stall:session-1", req.alertKey());
        assertEquals(AlertNotifier.LEVEL_ERROR, req.level());
        assertTrue(req.title().contains("可能已停摆"));
    }

    @Test
    void doneNeverAlerts() {
        // 真完成（契约 VERIFIED 或无工作痕迹的文本 DONE，finished=true）不告警
        service.maybeAlertStall("agent-1", "session-1", "user-1",
                new SessionProgressService.NextActionDecision(null, SessionProgressService.NEXT_DONE),
                Instant.now().minusSeconds(3600), 0, false, true, true);
        verify(alertNotifier, never()).publish(ArgumentMatchers.any());
    }

    @Test
    void doneWithUnverifiedContractStillAlerts() {
        // 3.13：DONE 文本 + 立过计划但完成契约未核验 ⇒ finished=false，
        // 不被 DONE 分支静默吞掉——空闲超阈值时走 stall 告警，把停滞暴露给人
        alert(35, false, SessionProgressService.NEXT_DONE, false);
        ArgumentCaptor<AlertNotifier.AlertRequest> captor =
                ArgumentCaptor.forClass(AlertNotifier.AlertRequest.class);
        verify(alertNotifier).publish(captor.capture());
        AlertNotifier.AlertRequest req = captor.getValue();
        assertEquals("stall:session-1", req.alertKey());
        assertEquals(AlertNotifier.LEVEL_ERROR, req.level());
    }

    @Test
    void unverifiedContractBelowThresholdIsSilent() {
        alert(10, false, SessionProgressService.NEXT_DONE, false);
        verify(alertNotifier, never()).publish(ArgumentMatchers.any());
    }
}
