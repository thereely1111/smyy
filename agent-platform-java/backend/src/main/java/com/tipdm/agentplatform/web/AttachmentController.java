package com.tipdm.agentplatform.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.web.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 会话附件接口（需求 1：添加本地文件到对话）。
 *
 * <ul>
 *   <li>{@code POST   /api/agents/{agentId}/sessions/{sessionId}/attachments} — 上传文件</li>
 *   <li>{@code GET    /api/agents/{agentId}/sessions/{sessionId}/attachments} — 列出已上传附件</li>
 *   <li>{@code DELETE /api/agents/{agentId}/sessions/{sessionId}/attachments/{fileId}} — 删除附件</li>
 * </ul>
 *
 * <p>文件落盘到 {@code runtimes/{agentId}/{sessionId}/uploads/}，文件名形如
 * {@code {uuid}-{清洗后原名}}。模型侧不接触文件字节：用户消息里携带的是
 * {@code @uploads/{文件名}} 引用（返回的 {@code ref}），由框架
 * {@code AtPathExpansionMiddleware} 展开进上下文，或用 read_file / grep / execute
 * 按同一相对路径读取（SESSION 命名空间锚定到本会话目录）。</p>
 *
 * <p>归属校验复用 {@link SessionAccessGuard}（owner 或 admin），与产物面板一致。
 * 单文件 ≤ 20MB、扩展名白名单（与前端 attachments.ts 同步）。</p>
 */
@RestController
@RequestMapping("/api/agents/{agentId}/sessions/{sessionId}/attachments")
public class AttachmentController {

    private static final Logger log = LoggerFactory.getLogger(AttachmentController.class);

    /** 单文件大小上限（字节）。全局 multipart 上限见 application.yml（25MB）。 */
    public static final long MAX_ATTACH_BYTES = 20L * 1024 * 1024;

    /** 文件名清洗后最大长度（保留扩展名空间）。 */
    private static final int MAX_FILE_NAME_LENGTH = 120;

    /** 上传文件存放的会话内目录名（相对路径即模型可见路径）。 */
    public static final String UPLOADS_DIR = "uploads";

    /**
     * 原始文件名 sidecar（{@code uploads/.names.json}）：落盘名 → 上传时的原始文件名。
     *
     * <p>落盘名必须 ASCII 化（见 {@link #toAsciiFileName}），纯中文等非 ASCII 名会
     * 兜底成 {@code file.md}——若不持久化原名，刷新后列表/@ 面板只能从落盘名反推
     * 展示名，用户看到的就是一排 "file.md"（2026-09-22 实测）。点开头文件不进
     * 附件列表、不被模型当附件消费，副作用可控。</p>
     */
    private static final String NAMES_SIDECAR = ".names.json";

    /** 扩展名白名单：模型可用文件工具直接消费的常见类型。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "json", "csv", "tsv",
            "py", "java", "js", "ts", "tsx", "jsx", "html", "css", "sql", "xml", "yaml", "yml", "sh",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "png", "jpg", "jpeg");

    private final SessionAccessGuard accessGuard;
    private final ObjectMapper objectMapper;
    /** sidecar 读写锁：并发上传同会话时避免互相覆盖丢条目（controller 单例，实例锁即可）。 */
    private final Object namesLock = new Object();

    public AttachmentController(SessionAccessGuard accessGuard, ObjectMapper objectMapper) {
        this.accessGuard = accessGuard;
        this.objectMapper = objectMapper;
    }

    /**
     * 上传附件。返回的 {@code ref} 即用户消息里应携带的引用文本。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse upload(
            @PathVariable UUID agentId,
            @PathVariable String sessionId,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        Path sessionRoot = accessGuard.requireOwnedSessionRoot(agentId, sessionId, request);

        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("上传文件不能为空");
        }
        String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String cleanName = sanitizeFileName(originalName);
        if (cleanName.isBlank()) {
            throw ApiException.badRequest("文件名非法");
        }
        if (!isAllowedExtension(cleanName)) {
            throw ApiException.badRequest("不支持的文件类型：" + cleanName
                    + "（允许 " + String.join(" / ", ALLOWED_EXTENSIONS.stream().limit(12).toList()) + " 等）");
        }
        if (file.getSize() > MAX_ATTACH_BYTES) {
            throw ApiException.badRequest("文件超过 "
                    + (MAX_ATTACH_BYTES / 1024 / 1024) + "MB 限制");
        }

        String storedName = UUID.randomUUID() + "-" + toAsciiFileName(cleanName);
        Path uploadsDir = sessionRoot.resolve(UPLOADS_DIR);
        Path target;
        try {
            Files.createDirectories(uploadsDir);
            // 只按文件名解析：uuid 前缀保证唯一，防覆盖与路径穿越
            target = uploadsDir.resolve(storedName).normalize();
            if (!target.startsWith(uploadsDir)) {
                throw ApiException.badRequest("文件名非法");
            }
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            persistOriginalName(uploadsDir, storedName, cleanName);
        } catch (IOException e) {
            log.error("Failed to store attachment agent={} session={}: {}", agentId, sessionId, e.getMessage());
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "附件保存失败: " + e.getMessage());
        }

        log.info("Attachment uploaded agent={} session={} name={} size={}",
                agentId, sessionId, cleanName, file.getSize());
        return new AttachmentResponse(
                cleanName,
                "@" + UPLOADS_DIR + "/" + storedName,
                file.getSize(),
                file.getContentType() == null ? "" : file.getContentType(),
                Instant.now());
    }

    /**
     * 列出会话已上传的附件（按上传时间倒序）。
     */
    @GetMapping
    public ListAttachmentsResponse list(
            @PathVariable UUID agentId,
            @PathVariable String sessionId,
            HttpServletRequest request) {
        Path sessionRoot = accessGuard.requireOwnedSessionRoot(agentId, sessionId, request);
        Path uploadsDir = sessionRoot.resolve(UPLOADS_DIR);
        if (!Files.isDirectory(uploadsDir)) {
            return new ListAttachmentsResponse(List.of(), 0);
        }
        List<AttachmentView> items = new ArrayList<>();
        Map<String, String> originalNames = readOriginalNames(uploadsDir);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(uploadsDir)) {
            for (Path p : ds) {
                String stored = p.getFileName().toString();
                // 跳过 sidecar 等点开头文件：它们不是附件
                if (stored.startsWith(".") || !Files.isRegularFile(p)) {
                    continue;
                }
                try {
                    long size = Files.size(p);
                    items.add(new AttachmentView(
                            stored,
                            "@" + UPLOADS_DIR + "/" + stored,
                            // 优先返回持久化的原始文件名；历史文件无记录时退回落盘名反推
                            originalNames.getOrDefault(stored, displayName(stored)),
                            size,
                            Files.getLastModifiedTime(p).toInstant()));
                } catch (IOException ignored) {
                    // 文件被并发删除：跳过
                }
            }
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "读取附件列表失败");
        }
        items.sort(Comparator.comparing(AttachmentView::createdAt).reversed());
        return new ListAttachmentsResponse(items, items.size());
    }

    /**
     * 删除已上传的附件（如发送前取消暂存）。
     */
    @DeleteMapping("/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable UUID agentId,
            @PathVariable String sessionId,
            @PathVariable String fileId,
            HttpServletRequest request) {
        Path sessionRoot = accessGuard.requireOwnedSessionRoot(agentId, sessionId, request);
        if (fileId == null || fileId.isBlank() || fileId.contains("/") || fileId.contains("\\")
                || fileId.contains("..")) {
            throw ApiException.badRequest("附件名非法");
        }
        Path uploadsDir = sessionRoot.resolve(UPLOADS_DIR);
        Path target = uploadsDir.resolve(fileId).normalize();
        if (!target.startsWith(uploadsDir) || !Files.isRegularFile(target)) {
            throw ApiException.notFound("附件不存在");
        }
        try {
            Files.deleteIfExists(target);
            // 同步清理原名映射（文件已不存在则无需保留）
            synchronized (namesLock) {
                Map<String, String> names = readOriginalNames(uploadsDir);
                if (names.remove(fileId) != null) {
                    writeOriginalNames(uploadsDir, names);
                }
            }
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "删除附件失败");
        }
    }

    // ---- 原始文件名 sidecar ----

    private Map<String, String> readOriginalNames(Path uploadsDir) {
        Path sidecar = uploadsDir.resolve(NAMES_SIDECAR);
        if (!Files.isRegularFile(sidecar)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(
                    sidecar.toFile(), new TypeReference<Map<String, String>>() {
                    });
        } catch (IOException e) {
            // sidecar 损坏不阻塞主流程：列表退回落盘名反推
            log.warn("Failed to read attachment name sidecar: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void writeOriginalNames(Path uploadsDir, Map<String, String> names) throws IOException {
        Path sidecar = uploadsDir.resolve(NAMES_SIDECAR);
        objectMapper.writeValue(sidecar.toFile(), names);
    }

    /** 登记原始文件名（上传成功后调用；失败仅告警，不影响上传结果）。 */
    private void persistOriginalName(Path uploadsDir, String storedName, String originalName) {
        synchronized (namesLock) {
            try {
                Map<String, String> names = readOriginalNames(uploadsDir);
                names.put(storedName, originalName);
                writeOriginalNames(uploadsDir, names);
            } catch (IOException e) {
                log.warn("Failed to persist original attachment name for {}: {}",
                        storedName, e.getMessage());
            }
        }
    }

    // ---- 命名与校验 ----

    /** 清洗文件名：去路径分隔符与控制字符、限长、保扩展名。 */
    static String sanitizeFileName(String name) {
        if (name == null) {
            return "";
        }
        String cleaned = name.replace('\\', '/');
        if (cleaned.contains("/")) {
            cleaned = cleaned.substring(cleaned.lastIndexOf('/') + 1);
        }
        // 去掉控制字符与 URL/路径保留字符，保留中文等 Unicode 字母
        cleaned = cleaned.chars()
                .filter(c -> c >= 0x20 && !isReservedChar(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        while (cleaned.startsWith(".") || cleaned.startsWith(" ")) {
            cleaned = cleaned.substring(1);
        }
        while (cleaned.endsWith(".") || cleaned.endsWith(" ")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.length() > MAX_FILE_NAME_LENGTH) {
            int dot = cleaned.lastIndexOf('.');
            if (dot > 0 && cleaned.length() - dot <= 16) {
                cleaned = cleaned.substring(0, MAX_FILE_NAME_LENGTH - (cleaned.length() - dot))
                        + cleaned.substring(dot);
            } else {
                cleaned = cleaned.substring(0, MAX_FILE_NAME_LENGTH);
            }
        }
        return cleaned;
    }

    private static boolean isReservedChar(int c) {
        return switch (c) {
            case '/', '\\', ':', '*', '?', '"', '<', '>', '|' -> true;
            default -> false;
        };
    }

    /**
     * 存储用文件名：仅保留 ASCII 安全字符（[A-Za-z0-9._-]），非 ASCII 段替换为下划线。
     *
     * <p>框架 {@code AtPathExpansionMiddleware} 的 {@code @path} 正则只匹配 ASCII
     * 路径片段，中文文件名会让展开在首个非 ASCII 字符处截断、silently 失效
     * （2026-09-12 端到端实测：模型拿不到 <attached_file> 内容，被迫 glob 兜底）。
     * 文件名落盘与 ref 均用 ASCII 形态：混合名保留 ASCII 片段（"K8s灾备演练" →
     * "K8s"），纯中文名兜底为 "file"（uuid 前缀保证唯一）。原始文件名持久化在
     * sidecar（{@link #NAMES_SIDECAR}），列表接口优先返回原名。</p>
     */
    private static String toAsciiFileName(String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        String asciiBase = base.chars()
                .map(c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '-' || c == '_'
                        ? c : '_')
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString()
                // 压缩连续下划线（中文段）并去掉首尾下划线
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (asciiBase.isEmpty()) {
            asciiBase = "file";
        }
        return asciiBase + ext;
    }

    private static boolean isAllowedExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        return ALLOWED_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** 去掉 uuid 前缀得到展示名（uuid 固定 36 字符 + 分隔连字符，与前端规则一致）。 */
    private static String displayName(String storedName) {
        return storedName.length() > 37 ? storedName.substring(37) : storedName;
    }

    // ---- 响应体 ----

    public record AttachmentResponse(
            String fileName,
            String ref,
            long size,
            String mimeType,
            Instant createdAt) {
    }

    public record AttachmentView(
            String fileId,
            String ref,
            String fileName,
            long size,
            Instant createdAt) {
    }

    public record ListAttachmentsResponse(
            List<AttachmentView> items,
            long total) {
    }
}
