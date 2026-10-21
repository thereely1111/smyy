package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.service.MarkdownDocxRenderer;
import com.tipdm.agentplatform.service.CumcmPaperDocxRenderer;
import com.tipdm.agentplatform.service.SessionLineageService;
import com.tipdm.agentplatform.web.dto.RuntimeDtos.ExportRequest;
import com.tipdm.agentplatform.web.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 产物导出：把会话里的 Markdown 产物渲染成 Word（docx）交给前端。
 *
 * <ul>
 *   <li>{@code POST /api/agents/{agentId}/sessions/{sessionId}/export} — 出 docx 字节流</li>
 * </ul>
 *
 * <p>用途是站点工作台那条「分析跑完 → 把分析结果整理成可下载的文档」。动画产物不在
 * 这条路径上：它们是 HTML，本来就能在浏览器里直接看，转成 Word 反而丢失交互。</p>
 *
 * <p><strong>与产物读写接口的关系：</strong>定位规则完整复用
 * {@link SessionArtifactController}（同一套 {@code validateRelPath} 与
 * {@code resolveArtifactFile}，因此同样受「只能 artifacts/ 之内 + 子会话血缘白名单 +
 * 符号链接穿透防护」约束）；这里只多两件事——渲染，以及把结果落到会话目录的
 * {@code exports/}。</p>
 *
 * <p><strong>为什么既落盘又回字节流：</strong>落盘是为了「会话空间独立、成果可追溯」
 * （导出物留在该会话目录里，删会话即随之清理）；回字节流是为了前端一次请求就能拿到文件
 * 直接下载，不必再补一个二进制下载端点。{@code exports/} 在 {@code artifacts/} 之外，
 * 因此不会出现在产物树里污染智能体的产物清单。</p>
 */
@RestController
@RequestMapping("/api/agents/{agentId}/sessions/{sessionId}/export")
public class SessionExportController {

    private static final Logger log = LoggerFactory.getLogger(SessionExportController.class);

    /** 导出物落地目录（会话目录下，与 artifacts/ 平级） */
    private static final String EXPORT_DIR = "exports";

    /** 源 Markdown 大小上限：与产物预览同一口径（5MB），再大也不像一个分析报告 */
    private static final int MAX_SOURCE_SIZE = 5 * 1024 * 1024;

    /** 唯一支持的导出格式。留成参数是为了后续加 pdf 时不必改契约。 */
    private static final String FORMAT_DOCX = "docx";

    private final SessionLineageService sessionLineageService;
    private final SessionAccessGuard accessGuard;

    /** 产物目录名（{@code agent-platform.artifacts-dir}），与产物接口同源 */
    @org.springframework.beans.factory.annotation.Value("${agent-platform.artifacts-dir:artifacts}")
    private String artifactsDirName;

    public SessionExportController(SessionLineageService sessionLineageService,
                                   SessionAccessGuard accessGuard) {
        this.sessionLineageService = sessionLineageService;
        this.accessGuard = accessGuard;
    }

    @PostMapping({"", "/paper"})
    public ResponseEntity<byte[]> export(
            @PathVariable UUID agentId,
            @PathVariable String sessionId,
            @RequestBody ExportRequest body,
            HttpServletRequest request) throws IOException {
        String userId = accessGuard.requireUserId(request);
        if (body == null || body.path() == null || body.path().isBlank()) {
            throw ApiException.badRequest("path 不能为空");
        }
        String format = body.format() == null || body.format().isBlank()
                ? FORMAT_DOCX : body.format().trim().toLowerCase(Locale.ROOT);
        if (!FORMAT_DOCX.equals(format)) {
            throw ApiException.badRequest("暂只支持 " + FORMAT_DOCX + " 导出，收到: " + format);
        }

        String rel = SessionArtifactController.validateRelPath(body.path());
        boolean paper = request.getServletPath() != null
                && request.getServletPath().endsWith("/export/paper");
        if (paper && !rel.replace("\\", "/").matches("(?s).*artifacts/[^/]+/paper/paper_v[0-9]+(?:\\.[0-9]+)?\\.md")) {
            throw ApiException.badRequest("论文导出只接受本案例 paper/paper_v*.md");
        }
        Path root = accessGuard.requireOwnedSessionRoot(agentId, userId, sessionId, request);
        Set<String> subSessions = new LinkedHashSet<>(
                sessionLineageService.subSessionsOf(userId, sessionId));
        if (!SessionArtifactController.isWritableArtifact(artifactsDirName, subSessions, rel)) {
            throw ApiException.badRequest(
                    "只能导出产物目录（" + artifactsDirName + "/）内的文件: " + rel);
        }
        Path source = SessionArtifactController.resolveArtifactFile(
                agentId.toString(), sessionId, root, subSessions, rel);
        if (source == null) {
            throw ApiException.notFound("产物文件不存在: " + rel);
        }

        long size = Files.size(source);
        if (size > MAX_SOURCE_SIZE) {
            throw ApiException.badRequest("源文件过大（" + size + " 字节），超过 "
                    + (MAX_SOURCE_SIZE / 1024 / 1024) + "MB 导出上限");
        }
        String markdown;
        try {
            markdown = Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // MalformedInputException（不是 UTF-8 文本）也走这里
            log.warn("导出源文件读取失败 {}: {}", source, e.getMessage());
            throw ApiException.badRequest("源文件不是 UTF-8 文本，无法导出: " + rel);
        }

        String base = safeBaseName(source.getFileName().toString());
        String fileName = base + ".docx";
        // 原文首行本身就是一级标题时不另加标题，base 只在没有标题的产物上兜底
        byte[] docx = paper ? CumcmPaperDocxRenderer.render(markdown)
                : MarkdownDocxRenderer.render(markdown, base);
        Path target = writeToExports(root, fileName, docx);

        log.info("产物已导出: session={} src={} -> {} bytes={}",
                sessionId, rel, target.getFileName(), docx.length);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        headers.setContentLength(docx.length);
        // 文件名可能含中文，必须走 RFC 5987 的 filename*，否则中文名在下载时丢字
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8).build());
        // 文件名可能含中文 → 这个头只能放 ASCII，否则浏览器侧直接判非法头值
        // （前端 `res.headers.get(...)` 那一步就会抛，整个下载失败）。用百分号编码，
        // 前端 decodeURIComponent 还原（见 src/modules/analysis/api/exportDoc.ts）。
        headers.add("X-Export-Path", EXPORT_DIR + "/" + percentEncodePathSegment(fileName));
        return new ResponseEntity<>(docx, headers, HttpStatus.OK);
    }

    /**
     * 头值安全的文件名：非 ASCII 字符百分号编码，其余原样。
     *
     * <p>HTTP 头值只能是字节串（≤0xFF），中文直接放进去在浏览器侧是**非法头值** —
     * 不是显示成乱码那么轻，是 `new Response(...)` / 读头那一步直接抛。真实后端上
     * 表现为产物名叫「预期结果.md」时导出整体失败。</p>
     */
    static String percentEncodePathSegment(String name) {
        StringBuilder sb = new StringBuilder();
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            // 只编码非 ASCII 与 % 本身（% 不编码会让前端解错），路径分隔符等一律放行
            if (c < 0x80 && c != '%') {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    /**
     * 把导出的 docx 落到会话目录 {@code exports/}。
     *
     * <p>同名直接覆盖：同一份源导出两次应得到同一个文件，而不是攒出一堆
     * {@code report(1).docx}。文件名已由 {@link #safeBaseName} 洗掉路径分隔符，
     * 这里再校验一次归一化结果仍在 {@code exports/} 之内。</p>
     */
    private static Path writeToExports(Path sessionRoot, String fileName, byte[] docx) throws IOException {
        Path dir = sessionRoot.resolve(EXPORT_DIR).normalize();
        Path target = dir.resolve(fileName).normalize();
        if (!target.startsWith(dir)) {
            throw ApiException.badRequest("非法导出文件名: " + fileName);
        }
        Files.createDirectories(dir);
        Files.write(target, docx);
        return target;
    }

    /**
     * 源文件名 → 导出文件名主干：去掉原扩展名，把路径分隔符与不常见字符换成下划线。
     *
     * <p>产物名来自模型（可能是任意字符串），不能直接拼进磁盘路径。</p>
     */
    static String safeBaseName(String fileName) {
        String name = fileName == null ? "" : fileName;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_").trim();
        cleaned = cleaned.replaceAll("^[._]+", "");
        if (cleaned.isEmpty()) {
            cleaned = "export";
        }
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }
}
