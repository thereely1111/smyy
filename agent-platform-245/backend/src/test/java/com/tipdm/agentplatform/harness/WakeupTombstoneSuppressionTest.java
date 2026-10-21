package com.tipdm.agentplatform.harness;

import com.tipdm.agentplatform.service.AgentAssemblyService;
import com.tipdm.agentplatform.service.PendingFormInterruptStore;
import com.tipdm.agentplatform.service.SessionKnowledgeService;
import com.tipdm.agentplatform.service.SessionOwnerService;
import com.tipdm.agentplatform.service.SessionProgressService;
import com.tipdm.agentplatform.service.SessionWorkspaceManager;
import com.tipdm.agentplatform.service.notify.SessionNotifyPublisher;
import io.agentscope.harness.agent.bus.MessageBus;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

/**
 * 删除会话后不得再被后台唤醒（2026-10-13 用户报障：删除会话后仍在后台运行并生成产物）。
 *
 * <p><b>缺陷机制</b>：删除会话原先是纯删除，把唤醒链路的抑制判据载体一并清掉——
 * {@code agent_session_progress} 行（stop_requested / awaiting_human_gate 的载体）
 * 与 {@code agent_session_owners} 行（archived 判据的载体）。载体消失后
 * {@code findBySessionId().orElse(false)} 把「已删除」读成「未归档 ⇒ 放行」，
 * {@code AguiWakeupTarget} 随即 {@code ensureSessionDir} + {@code record}
 * 重建产物目录与归属行，会话在列表里复活并继续产出产物。
 * 修复：删除改为置 {@code deleted_at} 墓碑，唤醒入口统一先读墓碑。</p>
 *
 * <p>本测试锁死「墓碑命中即静默、且不产生任何副作用」这条契约：
 * 抑制检查必须发生在 {@code ensureSessionDir} / {@code record} 之前。</p>
 */
class WakeupTombstoneSuppressionTest {

    private AguiWakeupTarget newTarget(SessionOwnerService owners) {
        return new AguiWakeupTarget(
                new SessionAgentRegistry(),
                new SessionActivityTracker(),
                mock(AgentAssemblyService.class),
                mock(SessionWorkspaceManager.class),
                owners,
                mock(MessageBus.class),
                mock(PendingFormInterruptStore.class),
                mock(SessionPermissionReconciler.class),
                mock(WakeupInterruptRegistrar.class),
                mock(ServerSideRunLauncher.class),
                mock(SessionNotifyPublisher.class),
                mock(SessionKnowledgeService.class),
                mock(SessionProgressService.class));
    }

    /**
     * 墓碑命中 → 返回空 Mono（不发起 run）。
     */
    @Test
    void deletedSessionIsNotWokenUp() {
        SessionOwnerService owners = mock(SessionOwnerService.class);
        when(owners.isDeletedBySession("main-gone")).thenReturn(true);

        Mono<?> result = newTarget(owners).runWakeup("u1", "main-gone");

        assertNull(result.block(), "deleted session must not produce a run result");
    }

    /**
     * 墓碑命中时<b>不解析 agent</b>——这决定了副作用能否发生：
     * {@code doWakeup} 里的 {@code ensureSessionDir} + {@code record} 都在解析成功之后，
     * 抑制检查必须早于解析（与归档判据同一位置）。
     */
    @Test
    void deletedSessionSkipsAgentResolution() {
        SessionOwnerService owners = mock(SessionOwnerService.class);
        when(owners.isDeletedBySession(anyString())).thenReturn(true);
        var assembly = mock(AgentAssemblyService.class);
        AguiWakeupTarget target = new AguiWakeupTarget(
                new SessionAgentRegistry(),
                new SessionActivityTracker(),
                assembly,
                mock(SessionWorkspaceManager.class),
                owners,
                mock(MessageBus.class),
                mock(PendingFormInterruptStore.class),
                mock(SessionPermissionReconciler.class),
                mock(WakeupInterruptRegistrar.class),
                mock(ServerSideRunLauncher.class),
                mock(SessionNotifyPublisher.class),
                mock(SessionKnowledgeService.class),
                mock(SessionProgressService.class));

        target.runWakeup("u1", "main-gone").block();

        verify(assembly, never()).requireAssembledById(anyString());
    }

    /**
     * 未删除的会话不得被墓碑判据误伤：判据为 false 时流程继续往下走
     * （此处会因无装配智能体而返回空，但<b>不是</b>被墓碑拦下的）。
     */
    @Test
    void liveSessionIsNotBlockedByTombstoneCheck() {
        SessionOwnerService owners = mock(SessionOwnerService.class);
        when(owners.isDeletedBySession("main-live")).thenReturn(false);
        when(owners.isArchivedBySession("main-live")).thenReturn(false);
        when(owners.agentBySessionId("main-live")).thenReturn(Optional.empty());

        newTarget(owners).runWakeup("u1", "main-live").block();

        // 走到了 agent 回退解析（说明墓碑没拦住它）
        verify(owners).agentBySessionId("main-live");
    }
}