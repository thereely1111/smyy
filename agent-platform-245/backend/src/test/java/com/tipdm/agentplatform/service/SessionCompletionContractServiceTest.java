package com.tipdm.agentplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 完成契约核验（3.13）：「是否真完成」的判定基准从文本猜测换成产出核验。
 *
 * <p>锁四条规则：① 标记 + 全部交付件指纹通过 = VERIFIED（确定性终态）；
 * ② 交付件缺失/低于下限/sha 失配 = INVALID，且失败明细可直接拼进提示词；
 * ③ 落件后被修改而未重新声明 ⇒ 指纹失配（不存在陈旧的完成状态）；
 * ④ 立过计划（plan 目录）但无标记 = MISSING + workEvidence（补醒依据），
 * 纯问答会话（无计划无标记）= MISSING + 无 workEvidence（回落文本兜底）。</p>
 */
class SessionCompletionContractServiceTest {

    @TempDir
    Path workspace;

    private SessionWorkspaceManager workspaceManager;
    private SessionCompletionContractService service;

    @BeforeEach
    void setUp() {
        workspaceManager = mock(SessionWorkspaceManager.class);
        when(workspaceManager.resolveSessionDir("agent-1", "session-1"))
                .thenReturn(workspace);
        service = new SessionCompletionContractService(
                workspaceManager, new ObjectMapper(),
                SessionCompletionContractService.DEFAULT_MARKER_FILE);
    }

    private String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private Path writeMarker(String deliverablesJson) throws IOException {
        Path marker = workspace.resolve("session-complete.json");
        Files.writeString(marker, "{\"summary\":\"done\",\"deliverables\":"
                + deliverablesJson + ",\"leftovers\":[]}");
        return marker;
    }

    @Test
    void verifiedWhenAllDeliverablesMatch() throws Exception {
        Path artifact = workspace.resolve("artifacts");
        Files.createDirectories(artifact);
        Path doc = artifact.resolve("前言.md");
        Files.writeString(doc, "前言正文内容");
        writeMarker("[{\"path\":\"artifacts/前言.md\",\"sha256\":\"" + sha256(doc)
                + "\",\"minBytes\":5}]");

        var check = service.check("agent-1", "session-1");
        assertEquals(SessionCompletionContractService.Status.VERIFIED, check.status());
        assertTrue(check.failures().isEmpty());
        assertTrue(check.workEvidence());
    }

    @Test
    void invalidWhenDeliverableMissingOrEmpty() throws Exception {
        Files.createDirectories(workspace.resolve("artifacts"));
        writeMarker("[{\"path\":\"artifacts/缺失.md\"},"
                + "{\"path\":\"artifacts/空.md\",\"minBytes\":100}]");
        Files.writeString(workspace.resolve("artifacts/空.md"), "短");

        var check = service.check("agent-1", "session-1");
        assertEquals(SessionCompletionContractService.Status.INVALID, check.status());
        assertEquals(2, check.failures().size());
        assertTrue(check.failures().get(0).contains("不存在"));
        assertTrue(check.failures().get(1).contains("低于声明下限"));
    }

    @Test
    void staleShaInvalidatesContract() throws Exception {
        Path doc = workspace.resolve("交付.md");
        Files.writeString(doc, "初稿");
        writeMarker("[{\"path\":\"交付.md\",\"sha256\":\"" + sha256(doc) + "\"}]");
        assertEquals(SessionCompletionContractService.Status.VERIFIED,
                service.check("agent-1", "session-1").status());

        // 落件后被修改而未重新声明 ⇒ 指纹失配，陈旧的完成状态自动失效
        Files.writeString(doc, "返工后的内容");
        var check = service.check("agent-1", "session-1");
        assertEquals(SessionCompletionContractService.Status.INVALID, check.status());
        assertTrue(check.failures().get(0).contains("sha256 不匹配"));
    }

    @Test
    void missingMarkerWithPlanIsWorkEvidence() throws Exception {
        Files.createDirectories(workspace.resolve("plan"));
        Files.writeString(workspace.resolve("plan/PLAN.md"), "# 计划");

        var check = service.check("agent-1", "session-1");
        assertEquals(SessionCompletionContractService.Status.MISSING, check.status());
        assertTrue(check.failures().isEmpty());
        assertTrue(check.workEvidence());
    }

    @Test
    void plainQaSessionHasNoWorkEvidence() {
        var check = service.check("agent-1", "session-1");
        assertEquals(SessionCompletionContractService.Status.MISSING, check.status());
        assertFalse(check.workEvidence());
    }

    @Test
    void malformedMarkerIsInvalid() throws Exception {
        Files.writeString(workspace.resolve("session-complete.json"), "{不是json");
        var check = service.check("agent-1", "session-1");
        assertEquals(SessionCompletionContractService.Status.INVALID, check.status());
        assertTrue(check.failures().get(0).contains("解析失败"));
    }
}
