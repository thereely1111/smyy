package com.tipdm.agentplatform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tipdm.agentplatform.web.error.ApiException;
import org.junit.jupiter.api.Test;

import java.util.Set;

/**
 * 导出接口（{@code POST .../export}）的边界回归。
 *
 * <p>这一层不启动 Spring：导出逻辑真正会出事的地方是**路径与文件名**——源文件能不能读
 * （白名单复用了产物接口那套）、导出名会不会因为模型产出的怪名字跳出目录。渲染本身
 * 由 {@link com.tipdm.agentplatform.service.MarkdownDocxRendererTest} 覆盖。</p>
 */
class SessionExportControllerTest {

    // -----------------------------------------------------------------
    //  源路径白名单（与产物「保存」同一套判定）
    // -----------------------------------------------------------------

    @Test
    void allowsExportingFromOwnArtifacts() {
        assertTrue(SessionArtifactController.isWritableArtifact(
                "artifacts", Set.of("sub-1111aaaa"), "artifacts/analysis/analysis_v1.0.md"));
    }

    @Test
    void allowsExportingFromOwnSubSessionArtifacts() {
        assertTrue(SessionArtifactController.isWritableArtifact(
                "artifacts", Set.of("sub-1111aaaa"),
                "sub-1111aaaa/artifacts/analysis/analysis_v1.0.md"));
    }

    /** 附件目录可读但不可导出：导出物应当是智能体的产物，不是用户刚传上来的原题 */
    @Test
    void refusesExportingUploads() {
        assertFalse(SessionArtifactController.isWritableArtifact(
                "artifacts", Set.of(), "uploads/题目.pdf"));
    }

    /** 上一次的导出物（exports/）不能再作为导出源 —— 避免 docx 套 docx */
    @Test
    void refusesExportingPreviousExports() {
        assertFalse(SessionArtifactController.isWritableArtifact(
                "artifacts", Set.of(), "exports/analysis_v1.0.docx"));
    }

    @Test
    void refusesExportingSiblingSession() {
        assertFalse(SessionArtifactController.isWritableArtifact(
                "artifacts", Set.of("sub-1111aaaa"),
                "sub-2222bbbb/artifacts/analysis/analysis_v1.0.md"));
    }

    // -----------------------------------------------------------------
    //  相对路径校验（导出与读取共用）
    // -----------------------------------------------------------------

    @Test
    void normalizesBackslashesAndLeadingSlash() {
        assertEquals("artifacts/a.md", SessionArtifactController.validateRelPath("\\artifacts\\a.md"));
        assertEquals("artifacts/a.md", SessionArtifactController.validateRelPath("/artifacts/a.md/"));
    }

    @Test
    void rejectsTraversalAndBlankPaths() {
        assertThrows(ApiException.class, () -> SessionArtifactController.validateRelPath("artifacts/../secret.md"));
        assertThrows(ApiException.class, () -> SessionArtifactController.validateRelPath(""));
        assertThrows(ApiException.class, () -> SessionArtifactController.validateRelPath("artifacts/./a.md"));
    }

    // -----------------------------------------------------------------
    //  导出文件名清洗
    // -----------------------------------------------------------------

    @Test
    void stripsSourceExtension() {
        assertEquals("analysis_v1.0", SessionExportController.safeBaseName("analysis_v1.0.md"));
        assertEquals("预期结果", SessionExportController.safeBaseName("预期结果.json"));
    }

    /** 产物名来自模型，可能带路径分隔符或空格 —— 不能直接拼进磁盘路径 */
    @Test
    void sanitizesPathSeparatorsAndSpaces() {
        assertEquals("a_b_c", SessionExportController.safeBaseName("a/b\\c.md"));
        assertEquals("报告_汇总", SessionExportController.safeBaseName("报告 汇总.md"));
        assertEquals("奇怪_名字", SessionExportController.safeBaseName("奇怪?名字.md"));
    }

    @Test
    void fallsBackForEmptyName() {
        assertEquals("export", SessionExportController.safeBaseName(null));
        assertEquals("export", SessionExportController.safeBaseName(""));
        assertEquals("export", SessionExportController.safeBaseName("/"));
    }

    @Test
    void truncatesOverlongName() {
        String longName = "x".repeat(200) + ".md";
        String cleaned = SessionExportController.safeBaseName(longName);
        assertEquals(80, cleaned.length());
    }

    // -----------------------------------------------------------------
    //  响应头里的文件名（只能放 ASCII）
    // -----------------------------------------------------------------

    /** 中文名要百分号编码 —— HTTP 头值放不下中文，浏览器会判非法头值 */
    @Test
    void headerPathIsAsciiOnly() {
        String encoded = SessionExportController.percentEncodePathSegment("分析报告.docx");
        encoded.chars().forEach(c -> assertTrue(c < 0x80, "头值必须是 ASCII，实际: " + encoded));
        assertEquals("%E5%88%86%E6%9E%90%E6%8A%A5%E5%91%8A.docx", encoded);
    }

    /** 纯 ASCII 名原样保留（前端 decodeURIComponent 是无损的） */
    @Test
    void headerPathKeepsAsciiAsIs() {
        assertEquals("analysis_v1.0.docx", SessionExportController.percentEncodePathSegment("analysis_v1.0.docx"));
    }

    /** 名字里本来就有 % 时必须连同它一起编码，否则前端会解成别的东西 */
    @Test
    void headerPathEscapesPercentItself() {
        assertEquals("100%25.docx", SessionExportController.percentEncodePathSegment("100%.docx"));
    }
}
