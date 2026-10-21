package com.tipdm.agentplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.config.LibraryProperties;
import com.tipdm.agentplatform.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 资料库正文存储（doc 的 markdown、drive 的文件字节）。
 *
 * <p>目录约定：
 * <ul>
 *   <li>doc：{@code root/{spaceId}/{nodeId}.md}——用节点 id 命名，天然唯一、无需防重名；</li>
 *   <li>drive：{@code root/{spaceId}/files/{uuid}-{ascii名}}——落盘名强制 ASCII，
 *       原名（含中文）写sidecar {@code files/.names.json}。</li>
 * </ul>
 *
 * <p><b>为什么 drive 落盘名要 ASCII 化</b>：框架的 {@code @path} 展开正则只匹配 ASCII，
 * 含中文的路径会静默失效（见 {@code AttachmentController} 同款处理与注释）。
 * 且同名覆盖风险由 uuid 前缀消除。</p>
 *
 * <p>DB 只存相对路径（{@code LibraryNode.contentRel}），所有路径解析均做
 * {@code startsWith(root)} 防穿越——与 {@code KnowledgeStorageService} 同一套纪律。</p>
 */
@Service
public class LibraryBlobStore {

    private static final Logger log = LoggerFactory.getLogger(LibraryBlobStore.class);

    /** drive 文件子目录。 */
    private static final String FILES_DIR = "files";
    /** drive 原名 sidecar（相对 FILES_DIR）。 */
    private static final String NAMES_SIDECAR = ".names.json";

    private final Path root;

    public LibraryBlobStore(LibraryProperties properties) {
        this.root = properties.root();
    }

    /** 空间绝对目录 root/{spaceId}，带防穿越守卫。 */
    public Path spaceDir(UUID spaceId) {
        Path dir = root.resolve(spaceId.toString()).normalize();
        if (!dir.startsWith(root)) {
            throw ApiException.badRequest("非法资料库路径");
        }
        return dir;
    }

    /** 解析相对路径到绝对路径，带防穿越守卫。 */
    public Path resolve(UUID spaceId, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw ApiException.badRequest("正文路径为空");
        }
        Path p = spaceDir(spaceId).resolve(relativePath).normalize();
        if (!p.startsWith(root)) {
            throw ApiException.badRequest("非法正文路径");
        }
        return p;
    }

    // ── doc：markdown 正文 ──────────────────────────────────────────

    /**
     * 写 doc 正文（markdown），返回相对路径 {@code {nodeId}.md}。
     * 原子写：先写临时文件再 move，避免读到半截内容。
     */
    public String writeDoc(UUID spaceId, UUID nodeId, String markdown) {
        Path dir = spaceDir(spaceId);
        String rel = nodeId + ".md";
        Path target = dir.resolve(rel);
        Path tmp = dir.resolve(rel + ".tmp");
        try {
            Files.createDirectories(dir);
            Files.writeString(tmp, markdown == null ? "" : markdown, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return rel;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL", "文档写入失败: " + e.getMessage());
        }
    }

    /** 读 doc 正文；文件不存在返回空串（新建节点尚未落盘的正常情形）。 */
    public String readDoc(UUID spaceId, String relativePath) {
        Path p = resolve(spaceId, relativePath);
        try {
            return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL", "文档读取失败: " + e.getMessage());
        }
    }

    // ── drive：文件字节 ────────────────────────────────────────────

    /**
     * 写 drive 文件，返回相对路径 {@code files/{uuid}-{ascii名}}。
     * 原名写入 sidecar，供下载时还原 {@code Content-Disposition} 文件名。
     */
    public String writeDriveFile(UUID spaceId, String originalName, InputStream content) {
        String cleanName = sanitize(originalName);
        String stored = UUID.randomUUID() + "-" + toAsciiFileName(cleanName);
        Path dir = spaceDir(spaceId).resolve(FILES_DIR);
        Path target = dir.resolve(stored);
        Path tmp = dir.resolve(stored + ".tmp");
        try {
            Files.createDirectories(dir);
            Files.copy(content, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            rememberOriginalName(dir, stored, cleanName);
            return FILES_DIR + "/" + stored;
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL", "文件保存失败: " + e.getMessage());
        }
    }

    /** 读 drive 文件字节；不存在抛 404（引用已失效）。 */
    public byte[] readDriveFile(UUID spaceId, String relativePath) {
        Path p = resolve(spaceId, relativePath);
        if (!Files.exists(p)) {
            throw ApiException.notFound("文件不存在");
        }
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL", "文件读取失败: " + e.getMessage());
        }
    }

    /** 读 drive 文件大小（字节）；不存在返回 0。 */
    public long driveFileSize(UUID spaceId, String relativePath) {
        Path p = resolve(spaceId, relativePath);
        try {
            return Files.exists(p) ? Files.size(p) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    /** 取 drive 文件的原名（从 sidecar 读回）；读不到则退回落盘名。 */
    public String driveOriginalName(UUID spaceId, String relativePath) {
        String stored = relativePath.contains("/")
                ? relativePath.substring(relativePath.lastIndexOf('/') + 1)
                : relativePath;
        Path sidecar = spaceDir(spaceId).resolve(FILES_DIR).resolve(NAMES_SIDECAR);
        if (!Files.exists(sidecar)) {
            return stored;
        }
        try {
            String original = parseNames(Files.readString(sidecar, StandardCharsets.UTF_8))
                    .get(stored);
            return original == null || original.isBlank() ? stored : original;
        } catch (IOException e) {
            return stored;
        }
    }

    // ── 删除 ─────────────────────────────────────────────────────────

    /** 删单个正文文件（doc 与 drive 通用）。 */
    public void delete(UUID spaceId, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        Path p;
        try {
            p = resolve(spaceId, relativePath);
        } catch (ApiException e) {
            // 路径已非法（数据被改坏）时不该阻断删除流程，记日志放过
            log.warn("跳过非法正文路径 space={} rel={}", spaceId, relativePath);
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("删除正文文件失败 {}: {}", p, e.getMessage());
        }
    }

    /** 删整个空间的正文目录（节点已软删时异步清理用）。 */
    public void deleteSpace(UUID spaceId) {
        Path dir = spaceDir(spaceId);
        try {
            if (Files.exists(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 删不掉的（可能被占用）留待下次
                        }
                    });
                }
            }
        } catch (IOException e) {
            log.warn("删除资料库目录失败 {}: {}", dir, e.getMessage());
        }
    }

    // ── 内部 ─────────────────────────────────────────────────────────

    /**
     * 记原名到 sidecar {@code files/.names.json}。
     *
     * <p>刻意不引JSON 库：sidecar 是"落盘名 → 原名"的单向映射，读写都在本类内完成，
     * 每次上传全量重写（条目数等于空间内文件数，量级可控）。
     * 手拼字符串易错，故用 {@link StringBuilder} 逐条追加而非正则剔除。</p>
     */
    private void rememberOriginalName(Path filesDir, String storedName, String original)
            throws IOException {
        Path sidecar = filesDir.resolve(NAMES_SIDECAR);
        Map<String, String> names = new LinkedHashMap<>();
        if (Files.exists(sidecar)) {
            names.putAll(parseNames(Files.readString(sidecar, StandardCharsets.UTF_8)));
        }
        names.put(storedName, original);

        StringBuilder sb = new StringBuilder(names.size() * 64 + 2).append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : names.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(quoteJson(e.getKey())).append(':').append(quoteJson(e.getValue()));
            first = false;
        }
        sb.append('}');

        Path tmp = filesDir.resolve(NAMES_SIDECAR + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, sidecar, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 解析 sidecar 为 map；格式不符时返回空 map（不抛异常——sidecar 丢失不该阻断上传）。 */
    private static Map<String, String> parseNames(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<?, ?> m = mapper.readValue(json, Map.class);
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        } catch (Exception e) {
            log.warn("解析资料库原名 sidecar 失败，按空处理: {}", e.getMessage());
        }
        return out;
    }

    /** JSON 字符串转义：仅需处理反斜杠与双引号（键值已被 sanitize 去掉控制字符）。 */
    private static String quoteJson(String raw) {
        String v = raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
        return '"' + v + '"';
    }

    /** 去掉文件名中的路径分隔符，防目录穿越。 */
    private static String sanitize(String filename) {
        if (filename == null || filename.isBlank()) {
            return "file";
        }
        String n = filename.replaceAll("[/\\\\]", "_").trim();
        return n.isBlank() ? "file" : n;
    }

    /**
     * 落盘名 ASCII 化：中文段整体替换为下划线并压缩。
     * 与 {@code AttachmentController.toAsciiFileName} 同款——框架 {@code @path}
     * 展开只认 ASCII，含中文会静默失效。
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
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (asciiBase.isEmpty()) {
            asciiBase = "file";
        }
        // 扩展名也过滤：可能含非 ASCII。
        // ⚠️ ext 是「点 + 后缀」（如 ".docx"），校验必须**跳过首字符的点**——
        //    早前实现用 allMatch 逐字符判定，而 '.' 既非字母也非数字，
        //    导致所有扩展名被判为非法而整体丢弃，落盘变成 "uuid-file"，
        //    下游（如预览服务）无法从文件名判断类型。
        String asciiExt = isAsciiExtension(ext) ? ext : "";
        return asciiBase + asciiExt;
    }

    /**
     * 判断扩展名是否为纯 ASCII。
     *
     * <p>约定：首字符必须是小数点，其余字符限 ASCII 字母与数字
     * （不含点，避免 ".." 这类构造）。空串（无扩展名）视为合法，由调用方原样拼接。</p>
     */
    private static boolean isAsciiExtension(String ext) {
        if (ext.isEmpty()) {
            return true;
        }
        if (ext.charAt(0) != '.') {
            return false;
        }
        for (int i = 1; i < ext.length(); i++) {
            char c = ext.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
