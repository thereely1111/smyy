package com.tipdm.agentplatform.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 子智能体汇报块解析：模型输出不保证百分百合规，解析必须"尽力而为 + 不误判为完成"。
 */
class SubagentReportParserTest {

    private static final String FENCED = """
            本轮已完成知识图谱。

            ```yaml
            【TASK_RESULT】
            status: COMPLETED
            stage: 一
            completed: [知识图谱.md, 知识图谱.json]
            in_progress: []
            remaining: []
            artifacts:
              - sub-1a2b/全局DB/Python/章节目录/知识图谱.md
              - sub-1a2b/全局DB/Python/章节目录/知识图谱.json
            private: []
            missing_artifacts: []
            next_action: 进入目录审核
            resumable: false
            summary: 知识图谱及结构化文件已写入并核实
            ```
            """;

    @Test
    void parsesFencedBlockWithBlockListArtifacts() {
        SubagentReportParser.Report r = SubagentReportParser.parse(FENCED);
        assertNotNull(r);
        assertEquals("COMPLETED", r.status());
        assertEquals("一", r.stage());
        assertEquals(2, r.artifacts().size());
        assertEquals("sub-1a2b/全局DB/Python/章节目录/知识图谱.json", r.artifacts().get(1));
        assertEquals("进入目录审核", r.nextAction());
        assertFalse(r.resumable());
        assertEquals(2, r.completed().size());
        assertEquals("知识图谱及结构化文件已写入并核实", r.summary());
    }

    @Test
    void parsesInlineArraysWithoutFence() {
        SubagentReportParser.Report r = SubagentReportParser.parse("""
                【TASK_RESULT】
                status: PARTIAL
                completed: [第1章, 第2章]
                remaining: [第3章]
                artifacts: [sub-9f/全局DB/书/第1章/第1章_v1.0.docx]
                summary: 前两章完成
                """);
        assertNotNull(r);
        assertEquals("PARTIAL", r.status());
        assertEquals(2, r.completed().size());
        assertEquals(1, r.remaining().size());
        assertEquals("sub-9f/全局DB/书/第1章/第1章_v1.0.docx", r.artifacts().get(0));
    }

    @Test
    void keepsNestedMissingArtifactsAsText() {
        SubagentReportParser.Report r = SubagentReportParser.parse("""
                【TASK_RESULT】
                status: PARTIAL
                remaining: [第4章]
                missing_artifacts:
                  - chapter: 第4章
                    artifacts: ["第4章_标题_v1.0.docx"]
                summary: 第4章缺 Word
                """);
        assertNotNull(r);
        assertEquals("PARTIAL", r.status());
        assertFalse(r.missingArtifacts().isEmpty());
        assertTrue(r.missingArtifacts().get(0).contains("第4章"), r.missingArtifacts().toString());
    }

    /** 状态缺失时必须退化为 PARTIAL——绝不能因为解析不到 status 就当作已完成。 */
    @Test
    void missingStatusFallsBackToPartial() {
        SubagentReportParser.Report r = SubagentReportParser.parse("""
                【TASK_RESULT】
                artifacts: [sub-1/全局DB/x.md]
                summary: 只有产物清单
                """);
        assertNotNull(r);
        assertEquals("PARTIAL", r.status());
    }

    @Test
    void illegalStatusFallsBackToPartial() {
        SubagentReportParser.Report r = SubagentReportParser.parse("""
                【TASK_RESULT】
                status: 基本完成
                summary: x
                """);
        assertNotNull(r);
        assertEquals("PARTIAL", r.status());
    }

    @Test
    void noBlockReturnsNull() {
        assertNull(SubagentReportParser.parse("我完成了知识图谱，没有结构化汇报。"));
        assertNull(SubagentReportParser.parse(""));
        assertNull(SubagentReportParser.parse(null));
    }

    /** 模型偶尔重复输出：以最后一个块为准。 */
    @Test
    void lastBlockWins() {
        SubagentReportParser.Report r = SubagentReportParser.parse("""
                【TASK_RESULT】
                status: PARTIAL
                summary: 中途

                【TASK_RESULT】
                status: COMPLETED
                artifacts: [sub-1/全局DB/x.md]
                summary: 最终
                """);
        assertNotNull(r);
        assertEquals("COMPLETED", r.status());
        assertEquals("最终", r.summary());
    }

    @Test
    void emptyListAndQuotedItems() {
        SubagentReportParser.Report r = SubagentReportParser.parse("""
                【TASK_RESULT】
                status: COMPLETED
                artifacts: ["sub-1/全局DB/a b.md", 'sub-1/全局DB/c.md']
                remaining: []
                summary: x
                """);
        assertNotNull(r);
        assertEquals(2, r.artifacts().size());
        assertEquals("sub-1/全局DB/a b.md", r.artifacts().get(0));
        assertTrue(r.remaining().isEmpty());
    }
}
