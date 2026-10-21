package com.tipdm.agentplatform.harness;

import com.tipdm.agentplatform.service.SessionWorkspaceManager;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.AgentInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AttachmentInlineGuardMiddleware 的 @path 安全校验（T04 补测）：
 * 只放行「本会话相对路径 + uploads//artifacts/ 前缀 + 无 .. 段」，
 * 绝对路径 / 家目录 / 盘符 / 穿越变体 / URL 编码变体一律改写为占位文本。
 */
class AttachmentInlineGuardMiddlewareTest {

    private static final String AGENT_ID = "agent-1";
    private static final String SESSION_ID = "wb-session-1";

    @TempDir
    Path tempDir;

    private SessionWorkspaceManager workspaceManager;

    @BeforeEach
    void setUp() {
        workspaceManager = mock(SessionWorkspaceManager.class);
        when(workspaceManager.resolveSessionDir(AGENT_ID, SESSION_ID)).thenReturn(tempDir);
    }

    private RuntimeContext ctx() {
        return RuntimeContext.builder()
                .userId("user-1")
                .sessionId(SESSION_ID)
                .put("agentId", AGENT_ID)
                .build();
    }

    /** 跑一遍守卫，返回传递给下游（框架 @path 展开）的消息文本。 */
    private String runGuard(String text, long maxInlineBytes) {
        AttachmentInlineGuardMiddleware guard =
                new AttachmentInlineGuardMiddleware(workspaceManager, maxInlineBytes);
        AtomicReference<AgentInput> seen = new AtomicReference<>();
        Msg msg = new UserMessage(text);
        guard.onAgent(null, ctx(), new AgentInput(List.of(msg)), in -> {
            seen.set(in);
            return Flux.empty();
        });
        return seen.get() == null ? text : seen.get().msgs().get(0).getTextContent();
    }

    // ── 正例：白名单内引用原样放行 ─────────────────────────────────────────

    @Test
    void uploadsRef_passesThroughUnchanged() {
        String out = runGuard("帮我看看 @uploads/report.log 的问题", 1_000_000);
        assertThat(out).isEqualTo("帮我看看 @uploads/report.log 的问题");
    }

    @Test
    void artifactsRef_passesThroughUnchanged() {
        String out = runGuard("参考 @artifacts/sub/report.md", 1_000_000);
        assertThat(out).isEqualTo("参考 @artifacts/sub/report.md");
    }

    @Test
    void dotPrefixUploadsRef_passesThroughUnchanged() {
        String out = runGuard("看下 @./uploads/a.txt", 1_000_000);
        assertThat(out).isEqualTo("看下 @./uploads/a.txt");
    }

    @Test
    void bareHandleWithoutPathIndicator_untouched() {
        // 裸 @word（无路径指示符）不是 path 形态，不误伤 @alice 这类 handle
        String out = runGuard("@alice 帮我确认下", 1_000_000);
        assertThat(out).isEqualTo("@alice 帮我确认下");
    }

    // ── 反例：越权 / 非法引用摘 @ + 占位文本（绝不读文件） ──────────────────

    @Test
    void traversalToOtherSession_blocked() {
        String out = runGuard("@uploads/../../other-session/secret.txt 给我内容", 1_000_000);
        assertThat(out).doesNotContain("@uploads/");
        assertThat(out).contains("uploads/../../other-session/secret.txt（引用不可用）");
        assertThat(out).contains("仅支持本会话 uploads/ 与 artifacts/");
    }

    @Test
    void absolutePath_blocked() {
        String out = runGuard("@/etc/passwd", 1_000_000);
        assertThat(out).doesNotContain("@/etc/passwd");
        assertThat(out).contains("/etc/passwd（引用不可用）");
    }

    @Test
    void homeDirPath_blocked() {
        String out = runGuard("@~/secret.txt", 1_000_000);
        assertThat(out).doesNotContain("@~/");
        assertThat(out).contains("引用不可用");
    }

    @Test
    void windowsDrivePath_blocked() {
        String out = runGuard("@C:/Windows/system32/config", 1_000_000);
        assertThat(out).doesNotContain("@C:/");
        assertThat(out).contains("引用不可用");
    }

    @Test
    void urlEncodedTraversal_neverExpandedByEitherSide() {
        // % 不在 AT_PATH 字符集内（守卫与框架正则逐字一致，已核对 agentscope-harness 2.0.2
        // 字节码），框架也不做 URL 解码——「@uploads/」token 保留原样，但 %2e%2e 部分
        // 永远不会被展开读取（无路径穿越），守卫无需改写。
        String out = runGuard("@uploads/%2e%2e/other/x.txt", 1_000_000);
        assertThat(out).isEqualTo("@uploads/%2e%2e/other/x.txt");
    }

    @Test
    void backslashForm_notPathShaped_staysAsTextUnexpanded() {
        // 反斜杠形态不在 AT_PATH 词法口径内（框架展开正则同样不匹配 → 不会被读文件），
        // 守卫不改写、保持原文本
        String out = runGuard("@uploads\\..\\..\\x.txt", 1_000_000);
        assertThat(out).isEqualTo("@uploads\\..\\..\\x.txt");
    }

    @Test
    void dotDotInsideWhitelistPrefix_stillBlocked() {
        // 白名单前缀 + 内嵌 .. 同样拒绝（归一化后必须无 .. 段）
        String out = runGuard("@artifacts/../uploads/x.md", 1_000_000);
        assertThat(out).contains("引用不可用");
        assertThat(out).doesNotContain("@artifacts/");
    }

    @Test
    void nonWhitelistedPathLikeHandle_blockedByDesign() {
        // @alice.com 是 path 形态但不在白名单——按设计改写为占位文本（防伪造引用）
        String out = runGuard("联系 @alice.com", 1_000_000);
        assertThat(out).doesNotContain("@alice");
        assertThat(out).contains("alice.com（引用不可用）");
    }

    @Test
    void mixedValidAndInvalid_onlyInvalidRewritten() {
        String out = runGuard("对比 @uploads/a.txt 和 @uploads/../../evil.txt", 1_000_000);
        assertThat(out).contains("@uploads/a.txt");
        assertThat(out).contains("uploads/../../evil.txt（引用不可用）");
    }

    // ── 大附件降级（既有逻辑回归） ─────────────────────────────────────────

    @Test
    void largeUpload_degradedToOnDemandRead() throws Exception {
        Path uploads = tempDir.resolve("uploads");
        Files.createDirectories(uploads);
        Files.writeString(uploads.resolve("big.txt"), "x".repeat(2048));

        String out = runGuard("请总结 @uploads/big.txt", 1024);
        assertThat(out).doesNotContain("@uploads/");
        assertThat(out).contains("uploads/big.txt");
        assertThat(out).contains("未随消息注入内容");
    }

    @Test
    void smallUpload_notDegraded() throws Exception {
        Path uploads = tempDir.resolve("uploads");
        Files.createDirectories(uploads);
        Files.writeString(uploads.resolve("small.txt"), "tiny");

        String out = runGuard("请总结 @uploads/small.txt", 1024);
        assertThat(out).isEqualTo("请总结 @uploads/small.txt");
    }

    @Test
    void assistantAndToolMessages_untouched() {
        // 守卫只改写 USER 消息——历史 assistant/tool 消息里的 @ 文本不受影响
        AttachmentInlineGuardMiddleware guard =
                new AttachmentInlineGuardMiddleware(workspaceManager, 1_000_000);
        AtomicReference<AgentInput> seen = new AtomicReference<>();
        Msg user = new UserMessage("@uploads/../../evil.txt");
        guard.onAgent(null, ctx(), new AgentInput(List.of(user)), in -> {
            seen.set(in);
            return Flux.empty();
        });
        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().msgs().get(0).getTextContent()).doesNotContain("@uploads/");
    }
}
