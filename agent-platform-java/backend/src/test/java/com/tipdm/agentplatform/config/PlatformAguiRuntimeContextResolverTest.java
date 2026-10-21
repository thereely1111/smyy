package com.tipdm.agentplatform.config;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import com.tipdm.agentplatform.harness.SessionAgentRegistry;
import com.tipdm.agentplatform.service.JwtService;
import com.tipdm.agentplatform.service.SessionKnowledgeService;
import com.tipdm.agentplatform.service.SessionOwnerService;
import com.tipdm.agentplatform.service.SessionSkillInvocation;
import com.tipdm.agentplatform.service.SessionWorkspaceManager;
import com.tipdm.agentplatform.service.SkillService;
import com.tipdm.agentplatform.web.auth.JwtInterceptor;
import com.tipdm.agentplatform.web.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AG-UI 运行时上下文解析器单元测试。
 *
 * <p>守护：{@code sessionId → agentId} 注册、RuntimeContext 携带 {@code agentId}、
 * 「不允许匿名使用」（401），以及消息级技能调用
 * {@code forwardedProps.skill_invocations} 的解析与可见性重校验（T03 补测）。</p>
 */
class PlatformAguiRuntimeContextResolverTest {

    private JwtService jwtService;
    private SessionWorkspaceManager workspaceManager;
    private SessionOwnerService sessionOwnerService;
    private SessionAgentRegistry registry;
    private com.tipdm.agentplatform.service.AgentAssemblyService assemblyService;
    private com.tipdm.agentplatform.harness.SessionPermissionReconciler permissionReconciler;
    private SkillService skillService;
    private PlatformAguiRuntimeContextResolver resolver;

    private final String agentId = "631f97d0-c074-3069-8196-e850831bbee0";
    private final String threadId = "wb-0692e7c9-37da-4f51-a54d-bbf03355951d";

    /** 平台默认模型服务（空仓库 mock → get() 为 empty → 走 clear/配置兜底分支）。 */
    private static com.tipdm.agentplatform.service.credential.PlatformModelDefaultService platformModelDefaultService() {
        return new com.tipdm.agentplatform.service.credential.PlatformModelDefaultService(
                org.mockito.Mockito.mock(com.tipdm.agentplatform.repository.PlatformModelDefaultRepository.class),
                org.mockito.Mockito.mock(com.tipdm.agentplatform.repository.ModelCredentialRepository.class));
    }

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        workspaceManager = mock(SessionWorkspaceManager.class);
        sessionOwnerService = mock(SessionOwnerService.class);
        registry = new SessionAgentRegistry();
        assemblyService = mock(com.tipdm.agentplatform.service.AgentAssemblyService.class);
        permissionReconciler = mock(com.tipdm.agentplatform.harness.SessionPermissionReconciler.class);
        skillService = mock(SkillService.class);
        resolver = new PlatformAguiRuntimeContextResolver(
                jwtService, workspaceManager, sessionOwnerService, registry,
                assemblyService, permissionReconciler, mock(SessionKnowledgeService.class),
                skillService,
                mock(com.tipdm.agentplatform.harness.RunLeaseStore.class),
                new com.tipdm.agentplatform.harness.SessionActivityTracker(),
                mock(com.tipdm.agentplatform.service.credential.ModelCredentialService.class),
                platformModelDefaultService(),
                mock(com.tipdm.agentplatform.service.SessionProgressService.class),
                emptyRegistrarProvider());
    }

    @Test
    void resolve_whenSessionAlreadyRunning_throwsConflict() {
        com.tipdm.agentplatform.harness.SessionActivityTracker tracker =
                new com.tipdm.agentplatform.harness.SessionActivityTracker();
        tracker.begin(threadId);
        PlatformAguiRuntimeContextResolver guarded = new PlatformAguiRuntimeContextResolver(
                jwtService, workspaceManager, sessionOwnerService, registry,
                assemblyService, permissionReconciler, mock(SessionKnowledgeService.class),
                skillService,
                mock(com.tipdm.agentplatform.harness.RunLeaseStore.class), tracker,
                mock(com.tipdm.agentplatform.service.credential.ModelCredentialService.class),
                platformModelDefaultService(),
                mock(com.tipdm.agentplatform.service.SessionProgressService.class),
                emptyRegistrarProvider());
        when(jwtService.decode("valid-token"))
                .thenReturn(Optional.of(new JwtService.TokenPayload(
                        "user-1", "u", "admin", "access", Instant.now().plusSeconds(600))));
        AguiRuntimeContextRequest request =
                requestWithHeader(JwtInterceptor.AUTHORIZATION_HEADER, "Bearer valid-token",
                        agentId, threadId);

        assertThatThrownBy(() -> guarded.resolve(request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(409));

        // forceEnd 释放活跃计数后放行（静默探测器判死僵尸 run 的路径）
        tracker.forceEnd(threadId);
        RuntimeContext ctx = guarded.resolve(request);
        assertThat(ctx.getSessionId()).isEqualTo(threadId);
    }

    @Test
    void resolve_withValidJwt_usesJwtUserAndInjectsAgentId() {
        when(jwtService.decode("valid-token"))
                .thenReturn(Optional.of(new JwtService.TokenPayload(
                        "user-1", "u", "admin", "access", Instant.now().plusSeconds(600))));
        AguiRuntimeContextRequest request =
                requestWithHeader(JwtInterceptor.AUTHORIZATION_HEADER, "Bearer valid-token",
                        agentId, threadId);

        RuntimeContext ctx = resolver.resolve(request);

        assertThat(ctx.getUserId()).isEqualTo("user-1");
        assertThat(ctx.getSessionId()).isEqualTo(threadId);
        // wakeup payload 需要的 agentId 已注入
        assertThat((String) ctx.get("agentId")).isEqualTo(agentId);
        // 会话归属已注册（Wakeup 目标据此解析父智能体）
        assertThat(registry.agentFor(threadId)).isEqualTo(agentId);
        // 会话产物目录幂等托管（真实用户，不再匿名）+ 归属记录
        verify(workspaceManager).ensureSessionDir(agentId, threadId);
        verify(sessionOwnerService).record(agentId, threadId, "user-1");
    }

    @Test
    void resolve_ignoresXUserHeader_whenNoJwt() {
        // 身份只认 JWT：X-User-ID 兜底已移除，只带该头必须按未登录处理（401）。
        AguiRuntimeContextRequest request =
                requestWithHeader("X-User-ID", "user-1", agentId, threadId);

        assertThatThrownBy(() -> resolver.resolve(request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(401));
    }

    @Test
    void resolve_withoutIdentity_throwsUnauthorized() {
        AguiRuntimeContextRequest request = AguiRuntimeContextRequest.builder()
                .input(RunAgentInput.builder().threadId(threadId).runId("run-1").build())
                .pathAgentId(agentId)
                .headers(java.util.Map.of())
                .build();

        assertThatThrownBy(() -> resolver.resolve(request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(401));
    }

    @Test
    void resolve_withInvalidJwt_throwsUnauthorized() {
        when(jwtService.decode(anyString())).thenReturn(Optional.empty());
        AguiRuntimeContextRequest request =
                requestWithHeader(JwtInterceptor.AUTHORIZATION_HEADER, "Bearer bad-token",
                        agentId, threadId);

        assertThatThrownBy(() -> resolver.resolve(request))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(401));
    }

    private static AguiRuntimeContextRequest requestWithHeader(
            String header, String value, String pathAgentId, String threadId) {
        return AguiRuntimeContextRequest.builder()
                .input(RunAgentInput.builder().threadId(threadId).runId("run-1").build())
                .pathAgentId(pathAgentId)
                .headers(java.util.Map.of(header, java.util.List.of(value)))
                .build();
    }

    // ── 消息级技能调用（forwardedProps.skill_invocations） ───────────────────

    private static final String SKILL_USER = "11111111-1111-1111-1111-111111111111";

    /** 构造携带 forwardedProps 的 run 请求（JWT 固定 "skill-token" → SKILL_USER）。 */
    private static AguiRuntimeContextRequest requestWithProps(
            String pathAgentId, String threadId, java.util.Map<String, Object> forwardedProps) {
        return AguiRuntimeContextRequest.builder()
                .input(RunAgentInput.builder()
                        .threadId(threadId).runId("run-skill")
                        .forwardedProps(forwardedProps)
                        .build())
                .pathAgentId(pathAgentId)
                .headers(java.util.Map.of(
                        JwtInterceptor.AUTHORIZATION_HEADER,
                        List.of("Bearer skill-token")))
                .build();
    }

    private void stubSkillUser() {
        when(jwtService.decode("skill-token"))
                .thenReturn(Optional.of(new JwtService.TokenPayload(
                        SKILL_USER, "u", "user", "access", Instant.now().plusSeconds(600))));
    }

    @Test
    void resolve_withoutSkillInvocations_injectsEmptySnapshotAndSkipsValidation() {
        stubSkillUser();
        RuntimeContext ctx = resolver.resolve(requestWithProps(agentId, threadId, java.util.Map.of()));

        assertThat(ctx.get(SessionSkillInvocation.class).isEmpty()).isTrue();
        // 无该字段不触发可见性校验（防伪造兜底：裸 /xxx 文本绝不注入技能指令）
        verifyNoInteractions(skillService);
    }

    @Test
    void resolve_skillInvocations_visibleSkillsResolvedViaLoginUser() {
        stubSkillUser();
        UUID skillId = UUID.randomUUID();
        com.tipdm.agentplatform.domain.Skill skill = new com.tipdm.agentplatform.domain.Skill();
        skill.setId(skillId);
        skill.setName("data-prep");
        skill.setContent("# 步骤");
        skill.setResources(java.util.Map.of("scripts/run.py", "print('hi')"));
        when(skillService.resolveVisible(List.of(skillId), UUID.fromString(SKILL_USER)))
                .thenReturn(List.of(skill));
        // 正文快照来自 readSkillBody（resolver 不直接读 skill.getContent()）
        when(skillService.readSkillBody(skill)).thenReturn("# 步骤");

        RuntimeContext ctx = resolver.resolve(requestWithProps(agentId, threadId,
                java.util.Map.of("skill_invocations",
                        List.of(java.util.Map.of("skill_id", skillId.toString(), "name", "data-prep")))));

        SessionSkillInvocation snapshot = ctx.get(SessionSkillInvocation.class);
        assertThat(snapshot.invocations()).hasSize(1);
        SessionSkillInvocation.Invocation inv = snapshot.invocations().get(0);
        assertThat(inv.skillId()).isEqualTo(skillId);
        assertThat(inv.name()).isEqualTo("data-prep");
        assertThat(inv.content()).isEqualTo("# 步骤");
        assertThat(inv.resources()).containsEntry("scripts/run.py", "print('hi')");
    }

    @Test
    void resolve_skillInvocations_invisibleSkillDroppedNotFailed() {
        // resolveVisible 返回空 = 他人个人库/停用平台技能/不存在——run 不 fail，快照为空
        stubSkillUser();
        UUID skillId = UUID.randomUUID();
        when(skillService.resolveVisible(List.of(skillId), UUID.fromString(SKILL_USER)))
                .thenReturn(List.of());

        RuntimeContext ctx = resolver.resolve(requestWithProps(agentId, threadId,
                java.util.Map.of("skill_invocations",
                        List.of(java.util.Map.of("skill_id", skillId.toString(), "name", "ghost")))));

        assertThat(ctx.get(SessionSkillInvocation.class).isEmpty()).isTrue();
    }

    @Test
    void resolve_skillInvocations_malformedIdIgnored_validOnesStillResolved() {
        stubSkillUser();
        UUID skillId = UUID.randomUUID();
        com.tipdm.agentplatform.domain.Skill skill = new com.tipdm.agentplatform.domain.Skill();
        skill.setId(skillId);
        skill.setName("ok-skill");
        when(skillService.resolveVisible(anyList(), any(UUID.class))).thenReturn(List.of(skill));

        RuntimeContext ctx = resolver.resolve(requestWithProps(agentId, threadId,
                java.util.Map.of("skill_invocations", List.of(
                        java.util.Map.of("skill_id", "../../etc/passwd", "name", "evil"),
                        java.util.Map.of("name", "no-id"),
                        java.util.Map.of("skill_id", skillId.toString(), "name", "ok-skill")))));

        // 只有合法 UUID 进入可见性校验
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Collection<UUID>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(skillService).resolveVisible(captor.capture(), any(UUID.class));
        assertThat(captor.getValue()).containsExactly(skillId);
        assertThat(ctx.get(SessionSkillInvocation.class).invocations()).hasSize(1);
    }

    @Test
    void resolve_skillInvocations_nonUuidUserSkipsInjection() {
        // 历史账号体系（非 UUID userId）：宁可跳过注入也不放大权限
        when(jwtService.decode("skill-token"))
                .thenReturn(Optional.of(new JwtService.TokenPayload(
                        "user-1", "u", "user", "access", Instant.now().plusSeconds(600))));
        RuntimeContext ctx = resolver.resolve(requestWithProps(agentId, threadId,
                java.util.Map.of("skill_invocations",
                        List.of(java.util.Map.of("skill_id", UUID.randomUUID().toString(), "name", "x")))));

        assertThat(ctx.get(SessionSkillInvocation.class).isEmpty()).isTrue();
        verifyNoInteractions(skillService);
    }

    @Test
    void resolve_skillInvocations_cappedAtTenPerMessage() {
        stubSkillUser();
        java.util.List<com.tipdm.agentplatform.domain.Skill> many = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            com.tipdm.agentplatform.domain.Skill s = new com.tipdm.agentplatform.domain.Skill();
            s.setId(UUID.randomUUID());
            s.setName("skill-" + i);
            many.add(s);
        }
        when(skillService.resolveVisible(anyList(), any(UUID.class))).thenReturn(many);

        RuntimeContext ctx = resolver.resolve(requestWithProps(agentId, threadId,
                java.util.Map.of("skill_invocations",
                        List.of(java.util.Map.of("skill_id", many.get(0).getId().toString(), "name", "x")))));

        assertThat(ctx.get(SessionSkillInvocation.class).invocations()).hasSize(10);
    }

    /**
     * resume 契约诊断用的 registrar 供给者替身：getIfAvailable() 返回 null，
     * 对应「反射快照不可用」的降级路径——诊断是纯旁路，缺失不得影响 run。
     */
    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<
            com.tipdm.agentplatform.harness.WakeupInterruptRegistrar> emptyRegistrarProvider() {
        org.springframework.beans.factory.ObjectProvider<
                com.tipdm.agentplatform.harness.WakeupInterruptRegistrar> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

}
