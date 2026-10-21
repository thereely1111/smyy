package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.util.AgentDirId;
import com.tipdm.agentplatform.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 智能体智能体目录导入：把外部 zip 包或多文件目录落成 {@code workspace/agents/{名称}/}。
 *
 * <p><b>这是本项目唯一的新增解压入口</b>，此前全库只有打包方向（{@code ArtifactZipService}
 * 的 {@code ZipOutputStream}），因此<b>不存在可复用的 zip slip 防护</b>——必须在此新建。
 * 防护要点（缺一即漏洞）：
 * <ol>
 *   <li>拒绝 {@code ..} 段（含编码形式）、绝对路径、盘符前缀</li>
 *   <li>拒绝符号链接条目（zip 可携带 unix 符号链接，落盘会指向智能体目录外的任意路径）</li>
 *   <li>限制解压总字节数（防 zip 炸弹）与条目数</li>
 *   <li>逐条落盘后再做一次 {@code normalize()} + {@code startsWith()} 复检（防软链接与竞态）</li>
 *   <li>根目录必须存在 {@code AGENTS.md}（框架 {@code WorkspaceManager.validate()} 缺失
 *       只 warn，但管理端会因此拿不到可用对象）</li>
 * </ol>
 *
 * <p>落盘结果是智能体定义的唯一真相源，与在线装配、上传文件夹两条入口同构——
 * <b>入口不参与后续分流</b>。
 */
@Service
public class AgentDefinitionImportService {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinitionImportService.class);

    /** 根目录必需文件：框架据此注入人格与行为约定。 */
    static final String REQUIRED_ROOT_FILE = "AGENTS.md";

    /** 单文件上限（字节）：技能脚本、参考资料都远小于此。 */
    private static final long MAX_FILE_BYTES = 256 * 1024L;

    /** 解压总字节上限：防 zip 炸弹（压缩比可达千倍）。 */
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;

    /** 条目数上限：防海量小文件耗尽 inode。 */
    private static final int MAX_ENTRIES = 2000;

    /** 目录名单段：与既有 agent 目录同名规则一致（中文名是既有资产）。 */
    private static final int MAX_DIR_NAME_LENGTH = 96;

    /** 逐字节单位换算（用于把字节上限渲染成人可读的错误文案）。 */
    private static final int BYTES_PER_KB = 1024;
    private static final int BYTES_PER_MB = 1024 * 1024;

    /** 流式读取块大小。 */
    private static final int COPY_CHUNK_BYTES = 8192;

    /** 盘符判定取前两个字符。 */
    private static final int DRIVE_PREFIX_LEN = 2;

    /**
     * Info-ZIP 扩展字段：id {@code 0x756e}（"un"）。JDK 21 无
     * {@code ZipEntry.getExternalAttributes()}，unix mode 只能从这里取。
     */
    private static final int INFOZIP_UNIX_EXTRA_ID = 0x756e;

    /** 该扩展的 payload 最小长度：version 1 字节 + mode 2 字节。 */
    private static final int UNIX_MODE_EXTRA_PAYLOAD = 3;

    /** payload 首字节：0x01 表示后面跟着 unix mode。 */
    private static final int UNIX_MODE_EXTRA_VERSION = 0x01;

    /** S_IFMT 位掩码与 S_IFLNK 取值（判定文件类型用）。 */
    private static final int UNIX_MODE_SYMLINK = 0120000;

    private final Path workspaceRoot;
    private final Path agentsRoot;

    public AgentDefinitionImportService(@Value("${agent-platform.workspace-root}") String workspaceRoot) {
        this.workspaceRoot = Paths.get(workspaceRoot).toAbsolutePath().normalize();
        this.agentsRoot = this.workspaceRoot.resolve("agents").normalize();
    }

    /**
     * 导入结果。
     *
     * @param workspacePath 相对 workspace-root 的智能体目录路径（{@code agents/{名称}}）
     * @param agentId       智能体目录对应的稳定 id（目录名的 v3 哈希，与目录型一致）
     * @param fileCount     落盘文件数
     */
    public record ImportResult(String workspacePath, String agentId, int fileCount) {
    }

    /**
     * 从 zip 包导入智能体目录。
     *
     * @param zip     zip 字节流
     * @param rawName 用户填写的智能体名称（可含中文）；为空时取包内 AGENTS.md 所在目录名
     * @return 落盘结果
     */
    public ImportResult importFromZip(InputStream zip, String rawName) throws IOException {
        Path staging = createStagingDir();
        try {
            int count = extractZip(zip, staging);
            Path definitionRoot = resolveDefinitionRoot(staging);
            return commit(definitionRoot, rawName, count);
        } finally {
            deleteRecursively(staging);
        }
    }

    /**
     * 从多文件（浏览器文件夹上传）导入智能体目录。
     *
     * <p>每项的相对路径以 {@code /} 分隔（前端 {@code webkitRelativePath} 去掉了顶层目录后
     * 传上来），同样走 zip 落盘那套安全校验。
     *
     * @param entries 相对路径 → 内容
     * @param rawName 用户填写的智能体名称
     * @return 落盘结果
     */
    public ImportResult importFromFiles(List<PathContent> entries, String rawName) throws IOException {
        if (entries == null || entries.isEmpty()) {
            throw ApiException.badRequest("未收到任何文件");
        }
        Path staging = createStagingDir();
        try {
            int count = 0;
            long total = 0;
            for (PathContent entry : entries) {
                if (++count > MAX_ENTRIES) {
                    throw ApiException.badRequest("文件数超限（>" + MAX_ENTRIES + "）");
                }
                String rel = entry.relativePath();
                if (rel == null || rel.isBlank()) {
                    throw ApiException.badRequest("文件缺少相对路径");
                }
                byte[] content = entry.content() == null ? new byte[0] : entry.content();
                total += content.length;
                if (total > MAX_TOTAL_BYTES) {
                    throw ApiException.badRequest("解压总大小超限（>" + (MAX_TOTAL_BYTES / BYTES_PER_MB) + "MB）");
                }
                if (content.length > MAX_FILE_BYTES) {
                    throw ApiException.badRequest("单文件超限（>" + (MAX_FILE_BYTES / BYTES_PER_KB) + "KB）：" + rel);
                }
                writeEntry(staging, rel, content);
            }
            Path definitionRoot = resolveDefinitionRoot(staging);
            return commit(definitionRoot, rawName, count);
        } finally {
            deleteRecursively(staging);
        }
    }

    /**
     * 单个上传文件。
     *
     * @param relativePath 相对智能体目录根的路径，用 {@code /} 分隔
     * @param content      文件内容
     */
    public record PathContent(String relativePath, byte[] content) {
    }

    // ======================== zip 解压 ========================

    /**
     * 解压到暂存目录，逐条做路径与体积校验。
     *
     * @param zip     zip 输入流
     * @param staging 暂存目录
     * @return 实际落盘的条目数
     */
    private int extractZip(InputStream zip, Path staging) throws IOException {
        int count = 0;
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(zip, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) {
                    throw ApiException.badRequest("压缩包条目数超限（>" + MAX_ENTRIES + "）");
                }
                String name = entry.getName();
                rejectUnsafeEntryName(name);
                // 符号链接：外部属性最高 16 位是 unix mode，0120000 即 symlink。
                // 落盘会让一个条目指向智能体目录外的任意路径，读取时即可越权。
                if (isSymlinkEntry(entry)) {
                    throw ApiException.badRequest("压缩包含符号链接条目，拒绝导入: " + name);
                }
                if (entry.isDirectory()) {
                    safeTarget(staging, name);
                    continue;
                }
                // 逐段限制单文件体积：不读完整个条目就能拒绝超限文件，
                // 避免「声明很小、实际很大」的条目把磁盘写满
                byte[] content = readEntryBytes(in, MAX_FILE_BYTES, name);
                total += content.length;
                if (total > MAX_TOTAL_BYTES) {
                    throw ApiException.badRequest(
                            "解压总大小超限（>" + (MAX_TOTAL_BYTES / BYTES_PER_MB) + "MB）");
                }
                writeEntry(staging, name, content);
            }
        }
        if (count == 0) {
            throw ApiException.badRequest("压缩包为空");
        }
        return count;
    }

    /**
     * 读取单个条目内容，超过上限即中断（防「声明很小、实际很大」的条目）。
     *
     * @param in    条目流
     * @param limit 单文件字节上限
     * @param name  条目名（用于错误文案）
     * @return 条目内容
     * @throws IOException 读取失败
     */
    private byte[] readEntryBytes(InputStream in, long limit, String name) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[COPY_CHUNK_BYTES];
        int read;
        while ((read = in.read(chunk)) > 0) {
            if (buf.size() + (long) read > limit) {
                throw ApiException.badRequest("单文件超限（>" + (limit / BYTES_PER_KB) + "KB）：" + name);
            }
            buf.write(chunk, 0, read);
        }
        return buf.toByteArray();
    }

    /**
     * 拒绝不安全的条目名：{@code ..} 段、绝对路径、盘符前缀、UNC 前缀。
     *
     * <p>必须对<b>原始名</b>判，不能先 {@code normalize()}——那样
     * {@code a/../../x} 会被规整成看似合法的结果。
     *
     * @param name zip 条目原始名
     */
    private void rejectUnsafeEntryName(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("压缩包含空条目名");
        }
        String v = name.replace('\\', '/');
        if (v.startsWith("/")) {
            throw ApiException.badRequest("条目名是绝对路径: " + name);
        }
        // Windows 盘符（c:foo）与 UNC（//host/share）
        if (v.length() >= DRIVE_PREFIX_LEN && v.charAt(1) == ':') {
            throw ApiException.badRequest("条目名含盘符前缀: " + name);
        }
        if (v.startsWith("//")) {
            throw ApiException.badRequest("条目名是 UNC 路径: " + name);
        }
        for (String seg : v.split("/")) {
            if (seg.equals("..")) {
                throw ApiException.badRequest("条目名含路径穿越: " + name);
            }
        }
    }

    /**
     * 判断条目是否为符号链接。
     *
     * <p>JDK 21 的 {@code ZipEntry} <b>没有</b> {@code getExternalAttributes()}（该方法在
     * 早期版本存在但未公开保留），因此不能用「读外部属性的 unix mode」这条路。实际可用的
     * 判据是 <b>Info-ZIP 扩展字段</b>：extra 中 id 为 {@code 0x756e}（"un"）的块，当
     * {@code TSize==1} 且首字节为 {@code 0x01} 时，后面跟着 2 字节的 unix mode；mode 的
     * {@code 0120000} 位即 S_IFLNK。
     *
     * <p>读不到 mode 时返回 false（不拦），因为 symlink 落盘后最坏结果是文件内容不可用，
     * 而真正的越权防线是 {@link #safeTarget} 的前缀校验——它对符号链接同样生效。
     *
     * @param entry zip 条目
     * @return 是符号链接时为 {@code true}
     */
    private boolean isSymlinkEntry(ZipEntry entry) {
        byte[] extra = entry.getExtra();
        if (extra == null) {
            return false;
        }
        int p = 0;
        while (p + 4 <= extra.length) {
            int id = ((extra[p] & 0xFF) << 8) | (extra[p + 1] & 0xFF);
            int size = ((extra[p + 2] & 0xFF) << 8) | (extra[p + 3] & 0xFF);
            if (p + 4 + size > extra.length) {
                break;
            }
            if (id == INFOZIP_UNIX_EXTRA_ID && size >= UNIX_MODE_EXTRA_PAYLOAD
                    && (extra[p + 4] & 0xFF) == UNIX_MODE_EXTRA_VERSION) {
                int mode = ((extra[p + 5] & 0xFF) << 8) | (extra[p + 6] & 0xFF);
                return (mode & UNIX_MODE_SYMLINK) == UNIX_MODE_SYMLINK;
            }
            p += 4 + size;
        }
        return false;
    }

    /**
     * 把一个条目写到暂存目录，路径越界即拒。
     *
     * <p>这里是<b>最后一道</b>防线：{@code rejectUnsafeEntryName} 已按原始名判过，
     * 这里再基于实际解析出的目标做一次前缀校验——两者不可互相替代。
     *
     * @param staging  暂存目录
     * @param relPath  条目相对路径
     * @param content  条目内容
     * @throws IOException 写入失败
     */
    private void writeEntry(Path staging, String relPath, byte[] content) throws IOException {
        Path target = safeTarget(staging, relPath);
        if (Files.exists(target) && Files.isDirectory(target)) {
            throw ApiException.badRequest("条目与目录同名: " + relPath);
        }
        Files.createDirectories(target.getParent());
        Files.write(target, content);
    }

    private Path safeTarget(Path staging, String relPath) {
        Path target = staging.resolve(relPath.replace('\\', '/')).normalize();
        if (!target.startsWith(staging)) {
            throw ApiException.badRequest("条目越界暂存目录: " + relPath);
        }
        if (target.equals(staging)) {
            throw ApiException.badRequest("非法条目路径: " + relPath);
        }
        return target;
    }

    // ======================== 智能体目录定位与提交 ========================

    /**
     * 定位智能体目录根：优先「暂存目录本身即根」（AGENTS.md 在顶层），否则剥掉一层包装目录。
     *
     * <p>现实中大量 zip 会多包一层项目名目录，两种形态都要认。
     */
    private Path resolveDefinitionRoot(Path staging) throws IOException {
        if (Files.isRegularFile(staging.resolve(REQUIRED_ROOT_FILE))) {
            return staging;
        }
        try (var children = Files.list(staging)) {
            List<Path> dirs = children.filter(Files::isDirectory).toList();
            if (dirs.size() == 1 && Files.isRegularFile(dirs.get(0).resolve(REQUIRED_ROOT_FILE))) {
                return dirs.get(0);
            }
        }
        throw ApiException.badRequest(
                "压缩包根目录必须存在 " + REQUIRED_ROOT_FILE + "（若已多包一层目录，该层内必须有它）");
    }

    /**
     * 把暂存智能体目录提交为正式智能体目录。
     *
     * <p>目录名 = 用户填的名称（缺省取根目录所在目录名），<b>与目录型 agent 同构</b>：
     * 目录名决定 id（v3 哈希），因此重名会被拒——否则两个 agent 会算出同一个 id，
     * 造成会话串号。
     */
    private ImportResult commit(Path definitionRoot, String rawName, int fileCount) throws IOException {
        String dirName = resolveDirName(rawName, definitionRoot, stagingNameOf(definitionRoot));
        Path target = agentsRoot.resolve(dirName).normalize();
        if (!target.startsWith(agentsRoot)) {
            throw ApiException.badRequest("非法智能体名称: " + dirName);
        }
        if (Files.exists(target)) {
            throw ApiException.conflict("同名智能体已存在: " + dirName);
        }
        Files.createDirectories(agentsRoot);
        copyTree(definitionRoot, target);
        String rel = workspaceRoot.relativize(target).toString();
        // id 由目录名派生，与目录型 agent 同一套规则（AgentDirId）。**不新建第二份
        // 实现**——两处各算一次迟早漂移，而 id 一旦对不上，会话会串到别的 agent 上。
        String agentId = AgentDirId.of(dirName).toString();
        log.info("Imported agent definition: {} ({} files) -> {}", dirName, fileCount, rel);
        return new ImportResult(rel, agentId, fileCount);
    }

    private String stagingNameOf(Path definitionRoot) {
        Path parent = definitionRoot.getParent();
        return parent == null ? definitionRoot.getFileName().toString() : parent.getFileName().toString();
    }

    /** 目录名只允许普通名字：单段、不得含分隔符或穿越，长度受限。 */
    private String resolveDirName(String rawName, Path definitionRoot, String fallback) {
        String v = rawName == null ? "" : rawName.trim();
        if (v.isBlank()) {
            v = fallback == null ? "" : fallback.trim();
        }
        // 剥掉用户在 zip 路径里可能带的层级
        int slash = Math.max(v.lastIndexOf('/'), v.lastIndexOf('\\'));
        if (slash >= 0 && slash < v.length() - 1) {
            v = v.substring(slash + 1);
        }
        if (v.isBlank() || v.equals(".") || v.equals("..")) {
            throw ApiException.badRequest("无法确定智能体名称，请显式填写");
        }
        if (v.length() > MAX_DIR_NAME_LENGTH) {
            v = v.substring(0, MAX_DIR_NAME_LENGTH);
        }
        if (!isSafeDirName(v)) {
            throw ApiException.badRequest("智能体名称不可用: " + v);
        }
        return v;
    }

    private boolean isSafeDirName(String v) {
        if (v.contains("/") || v.contains("\\") || v.contains("..") || v.contains(":")) {
            return false;
        }
        return !v.toLowerCase(Locale.ROOT).startsWith("."); // 隐藏目录会干扰 loader 排除逻辑
    }

    private Path createStagingDir() throws IOException {
        Path staging = Files.createTempDirectory("agent-import-");
        return staging;
    }

    private void copyTree(Path src, Path dst) throws IOException {
        try (var walk = Files.walk(src)) {
            for (Path p : walk.toList()) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("Failed to clean import staging dir {}: {}", dir, e.getMessage());
        }
    }
}
