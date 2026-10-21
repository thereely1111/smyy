package com.tipdm.agentplatform.config;

import com.tipdm.agentplatform.harness.RunLeaseStore;
import com.tipdm.agentplatform.harness.SessionActivityTracker;
import com.tipdm.agentplatform.harness.SessionAgentRegistry;
import com.tipdm.agentplatform.harness.SessionPermissionReconciler;
import com.tipdm.agentplatform.service.AgentAssemblyService;
import com.tipdm.agentplatform.service.JwtService;
import com.tipdm.agentplatform.service.SessionKnowledgeService;
import com.tipdm.agentplatform.service.SessionOwnerService;
import com.tipdm.agentplatform.service.SessionProgressService;
import com.tipdm.agentplatform.service.SessionWorkspaceManager;
import com.tipdm.agentplatform.service.SkillService;
import com.tipdm.agentplatform.service.credential.ModelCredentialService;
import com.tipdm.agentplatform.service.credential.PlatformModelDefaultService;
import com.tipdm.agentplatform.web.error.ApiException;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 删除即终态：已删除的 sessionId 不得再被 AG-UI 入口接受（2026-10-13）。
 *
 * <p><b>为什么入口必须拒绝</b>：删除清空了状态行与产物目录，但老 URL 可能被收藏 /
 * 分享 / 浏览器回退重新打开。若入口放行，resolver 第 3 步会
 * {@code ensureSessionDir} 重建目录并 {@code record} 刷新归属行，
 * 用户在一个后端已清空的会话上继续对话——上下文为空、产物重新生成，
 * 而它又因 {@code deleted_at IS NULL} 过滤而不出现在任何列表里。</p>
 *
 * <p><b>为什么不能只靠「不复位墓碑」</b>：那只能保证列表侧看不见，run 照跑。
 * 终态必须由入口显式拒绝才算成立。</p>
 */
class DeletedSessionRunRejectedTest {

    private AguiRuntimeContextRequest requestFor(String sessionId) {
        RunAgentInput input = new RunAgentInput.Builder()
                .threadId(sessionId)
                .runId("run-1")
                .build();
        return AguiRuntimeContextRequest.builder()
                .input(input)
                .pathAgentId("agent-1")
                .transport(AguiRuntimeContextRequest.Transport.MVC)
                .method("POST")
                .path("/agui/run/agent-1")
                // 鉴权是 resolve() 的第一步（步骤 1），必须先过它才能测到步骤 2.2。
                // 身份只认 JWT Bearer，不带这个头会直接 401，墓碑检查压根走不到。
                .headers(Map.of("Authorization", List.of("Bearer test-token")))
                .build();
    }

    private PlatformAguiRuntimeContextResolver newResolver(SessionOwnerService owners,
                                                            SessionWorkspaceManager workspace) {
        JwtService jwt =
                mock(JwtService.class);
        when(jwt.decode("test-token")).thenReturn(Optional.of(
                new JwtService.TokenPayload(
                        "u1", "alice", "USER", "access", Instant.now().plusSeconds(600))));
        return new PlatformAguiRuntimeContextResolver(
                jwt,
                workspace,
                owners,
                mock(SessionAgentRegistry.class),
                mock(AgentAssemblyService.class),
                mock(SessionPermissionReconciler.class),
                mock(SessionKnowledgeService.class),
                mock(SkillService.class),
                mock(RunLeaseStore.class),
                mock(SessionActivityTracker.class),
                mock(ModelCredentialService.class),
                mock(PlatformModelDefaultService.class),
                mock(SessionProgressService.class),
                mock(ObjectProvider.class));
    }

    /**
     * 已删除会话的 run 请求被拒，且拒绝发生在任何副作用之前——
     * {@code ensureSessionDir} 会重建已删会话的产物目录，必须一次都不发生。
     */
    @Test
    void deletedSessionRunIsRejectedWithoutSideEffects() {
        SessionOwnerService owners = mock(SessionOwnerService.class);
        when(owners.isDeletedBySession("main-gone")).thenReturn(true);
        SessionWorkspaceManager workspace = mock(SessionWorkspaceManager.class);

        ApiException thrown = assertThrows(ApiException.class,
                () -> newResolver(owners, workspace).resolve(requestFor("main-gone")));

        assertEquals("GONE", thrown.getCode(), "deleted session must be rejected as 410 Gone");
        verify(workspace, never()).ensureSessionDir(anyString(), anyString());
        verify(owners, never()).record(anyString(), anyString(), anyString());
    }

    /**
     * 墓碑判据只按 sessionId 查（不带 agentId）：拒绝必须发生在 agentId 解析之前，
     * 否则解析失败时已删会话反而能漏过去。
     */
    @Test
    void tombstoneCheckRunsBeforeAgentResolution() {
        SessionOwnerService owners = mock(SessionOwnerService.class);
        when(owners.isDeletedBySession("main-gone")).thenReturn(true);

        assertThrows(ApiException.class,
                () -> newResolver(owners, mock(SessionWorkspaceManager.class))
                        .resolve(requestFor("main-gone")));

        verify(owners).isDeletedBySession("main-gone");
        verify(owners, never()).agentBySessionId(anyString());
    }

    /**
     * 未删除的会话不被墓碑判据误伤：判据为 false 时流程<b>继续往下走</b>，
     * 证据是后续的产物目录托管被调用了（本测试用副作用而非异常类型来区分，
     * 因为下游步骤失败的原因与本判据无关，会让断言变得脆弱）。
     */
    @Test
    void liveSessionIsNotBlockedByTombstoneCheck() {
        SessionOwnerService owners = mock(SessionOwnerService.class);
        when(owners.isDeletedBySession(anyString())).thenReturn(false);
        SessionWorkspaceManager workspace = mock(SessionWorkspaceManager.class);

        try {
            newResolver(owners, workspace).resolve(requestFor("main-live"));
        } catch (Throwable ignored) {
            // 下游（权限对账 / 装配）失败与本判据无关，此处只关心它有没有被走到。
        }

        verify(owners).isDeletedBySession("main-live");
        verify(workspace).ensureSessionDir(anyString(), anyString());
    }
}