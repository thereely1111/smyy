package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.web.dto.RuntimeDtos.SubagentReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 子智能体汇报合成：血缘（框架 spawn registry）+ 汇报块 + **产出存在性核对**。
 */
class SubagentReportServiceTest {

    @TempDir
    Path workspaceRoot;

    private static final String PARENT = "main-parent";

    private SubagentReportService service(SessionLineageService lineage, Map<String, String> replies) {
        SessionWorkspaceManager workspace = new SessionWorkspaceManager(workspaceRoot.toString());
        return new SubagentReportService(lineage, workspace, (userId, sessionId) -> {
            String text = replies.get(sessionId);
            return text == null ? List.of() : List.of(text);
        });
    }

    private SessionLineageService lineageOf(List<SessionLineageService.SubSession> subs) {
        SessionLineageService lineage = mock(SessionLineageService.class);
        when(lineage.subSessions("user-1", PARENT)).thenReturn(subs);
        return lineage;
    }

    @Test
    void reportIncludesArtifactExistenceChecks() throws IOException {
        Path subDir = workspaceRoot.resolve("runtimes/agent-1/sub-1111");
        Files.createDirectories(subDir.resolve("全局DB/书/章节目录"));
        Files.writeString(subDir.resolve("全局DB/书/章节目录/知识图谱.md"), "# kg");

        SessionLineageService lineage = lineageOf(List.of(
                new SessionLineageService.SubSession("sub-1111", "目录生成智能体", "catalog", 1)));
        SubagentReportService service = service(lineage, Map.of("sub-1111", """
                【TASK_RESULT】
                status: COMPLETED
                artifacts:
                  - sub-1111/全局DB/书/章节目录/知识图谱.md
                  - sub-1111/全局DB/书/章节目录/知识图谱.json
                summary: 已写入
                """));

        List<SubagentReport> reports = service.reportsFor("agent-1", "user-1", PARENT);
        assertEquals(1, reports.size());
        SubagentReport report = reports.get(0);
        assertEquals("目录生成智能体", report.agentId());
        assertEquals("COMPLETED", report.status());
        assertEquals(2, report.artifacts().size());
        assertTrue(report.artifacts().get(0).exists());
        assertFalse(report.artifacts().get(1).exists());
        // 不存在的产出必须出现在 missing 里，模型自报的缺失项也一样
        assertTrue(report.missing().contains("sub-1111/全局DB/书/章节目录/知识图谱.json"),
                report.missing().toString());
    }

    @Test
    void acceptsRelativePathWithoutSessionPrefix() throws IOException {
        Path subDir = workspaceRoot.resolve("runtimes/agent-1/sub-2222");
        Files.createDirectories(subDir.resolve("全局DB/书"));
        Files.writeString(subDir.resolve("全局DB/书/目录.md"), "# toc");

        SessionLineageService lineage = lineageOf(List.of(
                new SessionLineageService.SubSession("sub-2222", "目录生成智能体", null, 1)));
        SubagentReportService service = service(lineage, Map.of("sub-2222", """
                【TASK_RESULT】
                status: COMPLETED
                artifacts: [全局DB/书/目录.md]
                summary: x
                """));

        SubagentReport report = service.reportsFor("agent-1", "user-1", PARENT).get(0);
        assertTrue(report.artifacts().get(0).exists());
        assertEquals("全局DB/书/目录.md", report.artifacts().get(0).relativePath());
        assertTrue(report.missing().isEmpty(), report.missing().toString());
    }

    /** 未产出汇报（状态读不到）的子会话仍要出现在列表里：派发过就必须可见。 */
    @Test
    void subSessionWithoutReportStillListed() {
        SessionLineageService lineage = lineageOf(List.of(
                new SessionLineageService.SubSession("sub-3333", "审核智能体", "review", 1)));
        SubagentReportService service = service(lineage, Map.of());

        List<SubagentReport> reports = service.reportsFor("agent-1", "user-1", PARENT);
        assertEquals(1, reports.size());
        assertEquals("sub-3333", reports.get(0).sessionId());
        assertEquals(null, reports.get(0).status());
        assertTrue(reports.get(0).artifacts().isEmpty());
    }

    /** 越界路径（穿越到会话目录之外）不得被判为存在。 */
    @Test
    void refusesTraversalOutsideSessionDir() throws IOException {
        Path subDir = workspaceRoot.resolve("runtimes/agent-1/sub-4444");
        Files.createDirectories(subDir);
        Files.writeString(workspaceRoot.resolve("runtimes/agent-1/secret.md"), "secret");

        SessionLineageService lineage = lineageOf(List.of(
                new SessionLineageService.SubSession("sub-4444", "目录生成智能体", null, 1)));
        SubagentReportService service = service(lineage, Map.of("sub-4444", """
                【TASK_RESULT】
                status: COMPLETED
                artifacts: [sub-4444/../secret.md]
                summary: x
                """));

        SubagentReport report = service.reportsFor("agent-1", "user-1", PARENT).get(0);
        assertFalse(report.artifacts().get(0).exists());
    }

    /** 最后一条助手文本不含汇报块时，必须继续往前找（模型常在写完文件后再追加过程说明）。 */
    @Test
    void findsReportInEarlierAssistantText() {
        SessionLineageService lineage = lineageOf(List.of(
                new SessionLineageService.SubSession("sub-5555", "目录生成智能体", null, 1)));
        SessionWorkspaceManager workspace = new SessionWorkspaceManager(workspaceRoot.toString());
        SessionLineageService ignored = lineage;
        SubagentReportService service = new SubagentReportService(lineage, workspace, (userId, sessionId) ->
                List.of(
                        "【TASK_RESULT】\nstatus: COMPLETED\nartifacts: [sub-5555/全局DB/a.md]\nsummary: 最终",
                        "现在我继续核对一下文件…（过程说明，没有汇报块）"));

        SubagentReport report = service.reportsFor("agent-1", "user-1", PARENT).get(0);
        assertEquals("COMPLETED", report.status());
        assertEquals("最终", report.summary());
    }

    @Test
    void noLineageReturnsEmptyList() {
        SubagentReportService service = service(lineageOf(List.of()), Map.of());
        assertTrue(service.reportsFor("agent-1", "user-1", PARENT).isEmpty());
    }
}
