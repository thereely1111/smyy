package com.tipdm.agentplatform.harness;

import com.tipdm.agentplatform.service.SessionSkillInvocation;
import com.tipdm.agentplatform.service.SessionWorkspaceManager;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SessionSkillInvocationMiddleware 单测（T03 补测）：
 * 有快照 → 系统提示注入 SKILL.md 全文 + 技能文件幂等落位 .harness/skills/&lt;name&gt;/；
 * 无快照 / 无 agentId → 原样透传或只注入不落位，绝不抛错阻断 run。
 */
class SessionSkillInvocationMiddlewareTest {

    private static final String AGENT_ID = "agent-1";
    private static final String SESSION_ID = "wb-session-1";

    @TempDir
    Path tempDir;

    private SessionWorkspaceManager workspaceManager;
    private SessionSkillInvocationMiddleware middleware;

    @BeforeEach
    void setUp() {
        workspaceManager = mock(SessionWorkspaceManager.class);
        when(workspaceManager.resolveRuntimeRoot(AGENT_ID)).thenReturn(tempDir);
        middleware = new SessionSkillInvocationMiddleware(workspaceManager);
    }

    private static SessionSkillInvocation.Invocation invocation(String name) {
        return new SessionSkillInvocation.Invocation(
                UUID.randomUUID(),
                name,
                "# 数据准备\n严格按以下步骤执行。",
                Map.of("scripts/run.py", "print('hi')"),
                null);
    }

    private RuntimeContext ctxWithAgentId() {
        return RuntimeContext.builder()
                .userId("user-1")
                .sessionId(SESSION_ID)
                .put("agentId", AGENT_ID)
                .build();
    }

    @Test
    void emptySnapshot_promptPassedThroughUnchanged() {
        String out = middleware
                .onSystemPrompt(null, ctxWithAgentId(), "系统提示原文")
                .block();
        assertThat(out).isEqualTo("系统提示原文");
    }

    @Test
    void nullContext_promptPassedThroughUnchanged() {
        String out = middleware.onSystemPrompt(null, null, "系统提示原文").block();
        assertThat(out).isEqualTo("系统提示原文");
    }

    @Test
    void invocation_injectsSkillBodyAndPreferenceDirective() {
        String out = middleware
                .onSystemPrompt(null, ctxWithAgentId(),
                        "系统提示原文")
                .block();
        // 无快照时不受下面构造的 invocation 影响（防御性：先确认透传基线）
        assertThat(out).isEqualTo("系统提示原文");

        RuntimeContext ctx = ctxWithAgentId();
        ctx.put(SessionSkillInvocation.class,
                new SessionSkillInvocation(List.of(invocation("数据准备"))));
        String injected = middleware.onSystemPrompt(null, ctx, "系统提示原文").block();

        assertThat(injected).startsWith("系统提示原文");
        assertThat(injected).contains("/数据准备");
        assertThat(injected).contains("# 数据准备\n严格按以下步骤执行。");
        assertThat(injected).contains("请优先遵循该技能的流程与约束");
    }

    @Test
    void invocation_filesLandedUnderHarnessSkills() {
        RuntimeContext ctx = ctxWithAgentId();
        ctx.put(SessionSkillInvocation.class,
                new SessionSkillInvocation(List.of(invocation("数据准备"))));

        middleware.onSystemPrompt(null, ctx, "base").block();

        Path skillDir = tempDir.resolve(".harness").resolve("skills").resolve("数据准备");
        assertThat(skillDir.resolve("SKILL.md")).exists();
        assertThat(skillDir.resolve("scripts").resolve("run.py")).exists();
    }

    @Test
    void invocation_resourcePathTraversalSkipped() {
        RuntimeContext ctx = ctxWithAgentId();
        SessionSkillInvocation.Invocation evil = new SessionSkillInvocation.Invocation(
                UUID.randomUUID(), "evil", "body",
                Map.of("../../escape.txt", "pwned"), null);
        ctx.put(SessionSkillInvocation.class, new SessionSkillInvocation(List.of(evil)));

        middleware.onSystemPrompt(null, ctx, "base").block();

        assertThat(tempDir.resolve("escape.txt")).doesNotExist();
    }

    @Test
    void invocation_cjkAndSpecialCharsSanitizedToSafeDirName() {
        RuntimeContext ctx = ctxWithAgentId();
        ctx.put(SessionSkillInvocation.class,
                new SessionSkillInvocation(List.of(invocation("a/b c"))));

        middleware.onSystemPrompt(null, ctx, "base").block();

        // 非法字符被替换为下划线，不会逃出 .harness/skills/
        assertThat(tempDir.resolve(".harness").resolve("skills").resolve("a_b_c").resolve("SKILL.md"))
                .exists();
    }

    @Test
    void invocation_withoutAgentId_injectsTextWithoutFileLanding() {
        // 子代理等无 agentId 场景：只注入指令，不落文件、不抛错
        RuntimeContext ctx = RuntimeContext.builder()
                .userId("user-1").sessionId(SESSION_ID).build();
        ctx.put(SessionSkillInvocation.class,
                new SessionSkillInvocation(List.of(invocation("数据准备"))));

        String out = middleware.onSystemPrompt(null, ctx, "base").block();

        assertThat(out).contains("# 数据准备");
        assertThat(tempDir.resolve(".harness")).doesNotExist();
    }

    @Test
    void invocation_blankContent_fallbackNoticeInjected() {
        RuntimeContext ctx = ctxWithAgentId();
        SessionSkillInvocation.Invocation empty = new SessionSkillInvocation.Invocation(
                UUID.randomUUID(), "no-body", "", Map.of(), null);
        ctx.put(SessionSkillInvocation.class, new SessionSkillInvocation(List.of(empty)));

        String out = middleware.onSystemPrompt(null, ctx, "base").block();

        assertThat(out).contains("未提供正文指令");
    }
}
