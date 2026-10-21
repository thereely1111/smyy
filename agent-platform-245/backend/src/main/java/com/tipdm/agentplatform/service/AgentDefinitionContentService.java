package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.AgentConfig;
import com.tipdm.agentplatform.domain.Skill;
import com.tipdm.agentplatform.domain.Tool;
import com.tipdm.agentplatform.repository.AgentConfigRepository;
import com.tipdm.agentplatform.repository.ToolRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 智能体定义内容的增删改：私有技能目录与工具启用项。
 *
 * <p>这两类内容都由「修改定义内容」这条管线直接管理，落点明确：
 * <ul>
 *   <li><b>私有技能</b>：{@code agents/{workspacePath}/skills/{技能名}/}。技能必然位于
 *       本智能体的 {@code skills} 目录内，不进平台技能库、不与其他智能体共享，
 *       运行时由子智能体按路径加载。目录内必须含 {@code SKILL.md}，可另带
 *       {@code scripts/} 等资源。</li>
 *   <li><b>工具</b>：真相源是 {@code agent_configs.tool_ids}（装配期
 *       {@code builder.enableSkills/tools} 直接读它），启用清单转写到 AGENTS.md 的工具段。</li>
 * </ul>
 *
 * <p><b>不提供「覆盖同名技能」</b>：已存在时拒绝并报错——私有技能可能是别人精心写的，
 * 静默覆盖等于丢数据。
 *
 * <p><b>删除前查引用</b>：技能/工具被 {@code subagents/*.md} 的 {@code tools} frontmatter
 * 引用时，删除会让那些子智能体在运行时缺工具失败，因此默认拒绝并在错误里带出引用者。
 */
@Service
public class AgentDefinitionContentService {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinitionContentService.class);

    /** 单文件上限，与智能体目录导入一致。 */
    private static final long MAX_FILE_BYTES = 256 * 1024L;

    /** 解压总字节上限：防 zip 炸弹。 */
    private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;

    /** 条目数上限：防海量小文件耗尽 inode。 */
    private static final int MAX_ENTRIES = 2000;

    /** 技能目录名单段、不含分隔符与穿越；中文合法（私有技能名普遍是中文）。 */
    private static final Pattern SKILL_DIR_NAME = Pattern.compile("^[\\w\\-\\u4e00-\\u9fa5]{1,96}$");

    /** 技能定义文件：框架按约定从 skills/{name}/SKILL.md 发现技能。 */
    private static final String SKILL_MANIFEST = "SKILL.md";

    private static final String SKILLS_DIR = "skills";

    private static final String AGENTS_MD = "AGENTS.md";

    private static final String SUBAGENTS_DIR = "subagents";

    private final Path workspaceRoot;
    private final AgentConfigRepository repository;
    private final ToolRepository toolRepository;
    private final SkillService skillService;

    public AgentDefinitionContentService(
            @Value("${agent-platform.workspace-root}") String workspaceRoot,
            AgentConfigRepository repository,
            ToolRepository toolRepository,
            SkillService skillService) {
        this.workspaceRoot = Paths.get(workspaceRoot).toAbsolutePath().normalize();
        this.repository = repository;
        this.toolRepository = toolRepository;
        this.skillService = skillService;
    }

    /** 上传结果：技能名、落盘相对路径、文件数。 */
    public record SkillUploadResult(String skillName, String workspacePath, int fileCount) {
    }

    /** 删除结果：实际删除的路径。 */
    public record RemovalResult(String name, String workspacePath) {
    }

    // ==================== 私有技能 ====================

    /**
     * 上传一个私有技能目录（zip 包）。包内可多包一层目录，但剥开后必须含 {@code SKILL.md}。
     *
     * @param agentId  目标智能体
     * @param zip      技能包
     * @return 落盘结果
     */
    public SkillUploadResult uploadSkillFromZip(UUID agentId, InputStream zip) throws IOException {
        Path staging = Files.createTempDirectory("agent-skill-");
        try {
            Path root = extractSkillZip(zip, staging);
            if (!Files.isRegularFile(root.resolve(SKILL_MANIFEST))) {
                throw ApiException.badRequest(
                        "技能包内必须包含 " + SKILL_MANIFEST + "（技能名以目录名为准）");
            }
            String skillName = root.getFileName().toString();
            Path skillsRoot = skillsDirOf(agentId);
            Path target = skillsRoot.resolve(skillName).normalize();
            if (!target.startsWith(skillsRoot)) {
                throw ApiException.badRequest("非法技能目录名: " + skillName);
            }
            if (Files.exists(target)) {
                // 不覆盖：已有技能可能是用户手写的，静默覆盖等于丢数据
                throw ApiException.conflict("技能「" + skillName + "」已存在，请先移除再上传");
            }
            copyTree(root, target);
            int files = countFiles(target);
            log.info("Uploaded private skill '{}' into agent {} ({} files)",
                    skillName, agentId, files);
            return new SkillUploadResult(skillName,
                    workspaceRoot.relativize(target).toString(), files);
        } finally {
            deleteRecursively(staging);
        }
    }

    /**
     * 从平台/个人技能库复制一个技能到该智能体的 {@code skills/} 下。
     *
     * <p>写入 SKILL.md 正文（DB content 优先，否则回退 originDir 下的 SKILL.md）
     * 以及 skills 表记录的文本资源。同名已存在时拒绝（与 zip 上传保持一致）。
     */
    public SkillUploadResult copySkillFromLibrary(UUID agentId, Skill skill) throws IOException {
        String skillName = skill.getName();
        validateSkillName(skillName);
        Path skillsRoot = skillsDirOf(agentId);
        Path target = skillsRoot.resolve(skillName).normalize();
        if (!target.startsWith(skillsRoot)) {
            throw ApiException.badRequest("非法技能目录名: " + skillName);
        }
        if (Files.exists(target)) {
            throw ApiException.conflict("技能「" + skillName + "」已存在，请先移除再添加");
        }

        String body = skillService.readSkillBody(skill);
        if (body == null || body.isBlank()) {
            throw ApiException.badRequest("技能「" + skillName + "」没有可写入的正文");
        }

        Files.createDirectories(target);
        Path manifest = target.resolve(SKILL_MANIFEST);
        Files.write(manifest, body.getBytes(StandardCharsets.UTF_8));
        int files = 1;

        Map<String, String> resources = skill.getResources();
        if (resources != null && !resources.isEmpty()) {
            for (Map.Entry<String, String> entry : resources.entrySet()) {
                String relPath = entry.getKey();
                String content = entry.getValue();
                if (content == null || relPath == null || relPath.isBlank()) {
                    continue;
                }
                rejectUnsafeEntryName(relPath);
                Path resTarget = safeResolve(target, relPath);
                Files.createDirectories(resTarget.getParent());
                Files.write(resTarget, content.getBytes(StandardCharsets.UTF_8));
                files++;
            }
        }

        log.info("Copied skill '{}' from library into agent {} ({} files)", skillName, agentId, files);
        return new SkillUploadResult(skillName, workspaceRoot.relativize(target).toString(), files);
    }

    /**
     * 移除一个私有技能目录（含其下全部文件，如 {@code scripts/}）。
     *
     * <p>被 {@code subagents/*.md} 引用时拒绝，并带出引用者供用户判断。
     */
    public RemovalResult removeSkill(UUID agentId, String skillName) throws IOException {
        validateSkillName(skillName);
        Path skillsRoot = skillsDirOf(agentId);
        Path target = skillsRoot.resolve(skillName).normalize();
        if (!target.startsWith(skillsRoot)) {
            throw ApiException.badRequest("非法技能目录名: " + skillName);
        }
        if (!Files.isDirectory(target)) {
            throw ApiException.notFound("技能「" + skillName + "」不存在");
        }
        List<String> referrers = subagentsReferencing(agentId, skillName);
        if (!referrers.isEmpty()) {
            throw ApiException.conflict("技能「" + skillName + "」正被以下子智能体引用，"
                    + "移除后它们将在运行时失败：" + String.join("、", referrers));
        }
        deleteRecursively(target);
        log.info("Removed private skill '{}' from agent {}", skillName, agentId);
        return new RemovalResult(skillName, workspaceRoot.relativize(target).toString());
    }

    /** 列出该智能体 skills/ 下的技能目录名（不含 _shared 之类的约定目录时由调用方决定）。 */
    public List<String> listSkillNames(UUID agentId) throws IOException {
        Path skillsRoot = skillsDirOf(agentId);
        if (!Files.isDirectory(skillsRoot)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(skillsRoot)) {
            stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.startsWith("."))
                    .sorted()
                    .forEach(names::add);
        }
        return names;
    }

    /**
     * 列出某个技能目录下的文件树（相对该技能根的一层，含子目录）。
     *
     * <p>只展开一层：调用方逐层请求。真实结构可能是
     * {@code SKILL.md + scripts/*.py + references/<子目录>/*.md}，一次全量返回
     * 会让响应体随技能数与深度膨胀，而文件树 UI 本来就是「点开才看」。
     *
     * <p>路径安全：技能名先过 {@link #validateSkillName}（禁路径分隔符与 {@code ..}），
     * 解析后再 {@code normalize} 并确认仍在技能根之内——否则一个名为 {@code ../x}
     * 的条目能读到目录之外。
     */
    public List<SkillFileNode> listSkillFiles(UUID agentId, String skillName) throws IOException {
        Path skillsRoot = skillsDirOf(agentId).normalize();
        // 逐段校验：path 是相对 skills/ 的路径（含斜杠，如 `jc-0/scripts`），
        // 不能直接交给 validateSkillName（它只接受单段技能名）。
        Path skillRoot = skillsRoot;
        for (String seg : skillName.split("/")) {
            if (seg.isBlank() || seg.equals(".") || seg.equals("..")) {
                return List.of();
            }
            skillRoot = skillRoot.resolve(seg);
        }
        skillRoot = skillRoot.normalize();
        if (!skillRoot.startsWith(skillsRoot) || !Files.isDirectory(skillRoot)) {
            return List.of();
        }
        return listOneLevel(skillRoot);
    }

    private static long sizeOfQuietly(Path p) {
        try {
            return Files.size(p);
        } catch (IOException ignored) {
            return 0L;
        }
    }

    /**
     * 技能目录下的一个条目。目录优先、同类按名排序，让 UI 的顺序稳定可预期。
     *
     * @param directory 是否为子目录（决定前端能否再展开一层）
     * @param sizeBytes 文件字节数；目录为 0
     */
    public record SkillFileNode(String name, boolean directory, long sizeBytes) {
        /** 目录在前，其余按名称自然序（{@code String#compareTo} 够用，目录名短）。 */
        public static final java.util.Comparator<SkillFileNode> ORDER =
                java.util.Comparator.comparing(SkillFileNode::directory).reversed()
                        .thenComparing(SkillFileNode::name);
    }

    // ==================== 技能目录内的文件操作 ====================
    //
    // 全部以「相对 skills/ 的路径」为参数，逐段校验后 normalize，
    // 再确认仍在 skills/ 之内——路径穿越（../）在这一层被挡掉，不依赖调用方。

    /** 把相对路径解析为绝对路径，并确保它落在该智能体的 skills/ 内。 */
    private Path resolveInSkills(UUID agentId, String relPath) throws IOException {
        rejectUnsafeEntryName(relPath);
        Path skillsRoot = skillsDirOf(agentId).normalize();
        Path target = safeResolve(skillsRoot, relPath);
        if (!target.startsWith(skillsRoot)) {
            throw ApiException.badRequest("路径越界: " + relPath);
        }
        return target;
    }

    /**
     * 把相对路径解析为绝对路径，并确保它落在定义目录内、首段不是受管条目。
     *
     * <p>受管条目（AGENTS.md / subagents / skills）各有自己的管理入口与保护逻辑
     * （草稿模型、引用检查、发布流程），从这里改等于绕过它们——在解析层整体拒绝，
     * 不给任何一条操作留口子。
     */
    private Path resolveInAgentDir(UUID agentId, String relPath) throws IOException {
        rejectUnsafeEntryName(relPath);
        String first = relPath.replace('\\', '/').split("/")[0];
        if (isManagedRootName(first)) {
            throw ApiException.badRequest(
                    "受管条目请走对应入口（AGENTS.md 与 subagents 走草稿编辑，skills 走技能接口）: " + relPath);
        }
        Path agentDir = agentDirOf(agentId);
        Path target = agentDir.resolve(relPath.replace('\\', '/')).normalize();
        if (!target.startsWith(agentDir) || target.equals(agentDir)) {
            throw ApiException.badRequest("路径越界: " + relPath);
        }
        return target;
    }

    /**
     * 读取技能目录下的文本文件。
     *
     * <p>只给文本：二进制（.pyc、图片、docx、字体）原样返回会毁掉前端与日志，
     * 且没有任何用途。判据是「能否按 UTF-8 解码且不含控制字符」，
     * 与扩展名无关——用户可能给脚本起任何名字。
     *
     * @throws ApiException 路径不存在 / 是目录 / 是二进制
     */
    public SkillFileContent readSkillFile(UUID agentId, String relPath) throws IOException {
        return readFileAt(relPath, resolveInSkills(agentId, relPath));
    }

    /**
     * 严格 UTF-8 解码；含 NUL 或替换字符则判为二进制。
     *
     * <p>不用 {@code CharsetDecoder} 的 REPLACE 模式——它对任何字节序列都返回字符串，
     * 会把 PNG 也「成功」解成乱码。
     */
    private static String decodeUtf8Strict(byte[] raw) {
        java.nio.charset.CharsetDecoder dec = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        try {
            String s = dec.decode(java.nio.ByteBuffer.wrap(raw)).toString();
            return s.indexOf('\0') >= 0 || s.indexOf("\uFFFD") >= 0 ? null : s;
        } catch (java.nio.charset.CharacterCodingException e) {
            return null;
        }
    }

    /**
     * 覆写技能目录下的文本文件（不存在则创建）。
     *
     * <p>只接字符串：技能目录里的 .py/.md 会被框架读取执行，前端若塞二进制进来，
     * 后续运行期会出现难以定位的解析错误。与其事后排查，不如在入口拦住。
     */
    public SkillFileSaved writeSkillFile(UUID agentId, String relPath, String content) throws IOException {
        return writeFileAt(relPath, resolveInSkills(agentId, relPath), content);
    }

    /**
     * 在技能目录下新建文件或目录。
     *
     * <p>目录用结尾的 {@code /} 表示（与文件树一致）；已存在时拒绝，不覆盖——
     * 覆盖是「写文件」的语义，混在一起会让误操作直接毁掉内容。
     */
    public SkillFileSaved createSkillEntry(UUID agentId, String relPath, boolean directory) throws IOException {
        return createEntryAt(relPath, resolveInSkills(agentId, relPath), directory);
    }

    /**
     * 删除技能目录下的文件或子目录。
     *
     * <p>技能根本身不可删——删它等于 {@code removeSkill}，会让引用检查与回滚提示失效，
     * 用户看不出差别却在别处踩坑。要删整个技能请走移除技能的接口。
     */
    public RemovalResult deleteSkillEntry(UUID agentId, String relPath) throws IOException {
        Path target = resolveInSkills(agentId, relPath);
        Path skillsRoot = skillsDirOf(agentId).normalize();
        if (target.getParent() == null || target.getParent().equals(skillsRoot)) {
            throw ApiException.badRequest("不能删除技能目录本身，请使用「移除技能」");
        }
        return deleteEntryAt(relPath, target, skillsRoot);
    }

    /**
     * 重命名（移动）技能目录下的条目。
     *
     * <p>跨目录移动会一并创建中间目录；目标是目录而源是文件（或反之）时拒绝——
     * 那意味着用户想改的是类型，应走新建/删除两条路径，而不是在这里隐式转换。
     */
    public SkillFileSaved renameSkillEntry(UUID agentId, String from, String to) throws IOException {
        Path src = resolveInSkills(agentId, from);
        Path dst = resolveInSkills(agentId, to);
        Path skillsRoot = skillsDirOf(agentId).normalize();
        if (src.getParent() != null && src.getParent().equals(skillsRoot)) {
            throw ApiException.badRequest("不能重命名技能目录本身，请使用「移除技能」+ 上传");
        }
        return renameEntryAt(from, src, to, dst);
    }

    // ==================== 定义目录根级附加文件 ====================
    //
    // AGENTS.md、subagents/、skills/ 之外的文件与目录：导入包里自带的参考资料、
    // 用户手工放的笔记等。与技能文件同一条「落盘直写」路线，不进草稿模型。
    //
    // 首段是受管条目的路径在这里整体拒绝——AGENTS.md / subagents 归草稿模型管、
    // skills 归技能接口管，从这里改等于绕过各自的引用检查与发布流程。

    /** 根级受管条目：不进本组接口的可见范围，也不可经本组接口读写。 */
    private static boolean isManagedRootName(String seg) {
        return AGENTS_MD.equalsIgnoreCase(seg) || SUBAGENTS_DIR.equals(seg) || SKILLS_DIR.equals(seg);
    }

    /**
     * 列出定义目录下某一层的附加条目。
     *
     * @param relPath 空 = 根级（自动隐藏受管条目与点文件）；非空 = 某个附加目录下的子层
     */
    public List<SkillFileNode> listRootEntries(UUID agentId, String relPath) throws IOException {
        if (relPath == null || relPath.isBlank()) {
            Path agentDir = agentDirOf(agentId);
            if (!Files.isDirectory(agentDir)) {
                return List.of();
            }
            try (var stream = Files.list(agentDir)) {
                return stream
                        .map(p -> {
                            String name = p.getFileName().toString();
                            if (name.startsWith(".") || isManagedRootName(name)) {
                                return null;
                            }
                            boolean dir = Files.isDirectory(p);
                            return new SkillFileNode(name, dir, dir ? 0L : sizeOfQuietly(p));
                        })
                        .filter(java.util.Objects::nonNull)
                        .sorted(SkillFileNode.ORDER)
                        .toList();
            }
        }
        Path target = resolveInAgentDir(agentId, relPath);
        if (!Files.isDirectory(target)) {
            return List.of();
        }
        return listOneLevel(target);
    }

    /** 读取定义目录下的附加文本文件。 */
    public SkillFileContent readRootFile(UUID agentId, String relPath) throws IOException {
        return readFileAt(relPath, resolveInAgentDir(agentId, relPath));
    }

    /** 覆写（不存在则创建）定义目录下的附加文本文件。 */
    public SkillFileSaved writeRootFile(UUID agentId, String relPath, String content) throws IOException {
        return writeFileAt(relPath, resolveInAgentDir(agentId, relPath), content);
    }

    /** 在定义目录下新建附加文件或目录（已存在时拒绝，不覆盖）。 */
    public SkillFileSaved createRootEntry(UUID agentId, String relPath, boolean directory) throws IOException {
        return createEntryAt(relPath, resolveInAgentDir(agentId, relPath), directory);
    }

    /** 删除定义目录下的附加文件或子目录。受管条目在 {@link #resolveInAgentDir} 已被挡下。 */
    public RemovalResult deleteRootEntry(UUID agentId, String relPath) throws IOException {
        Path target = resolveInAgentDir(agentId, relPath);
        return deleteEntryAt(relPath, target, agentDirOf(agentId));
    }

    /** 重命名（移动）定义目录下的附加条目。 */
    public SkillFileSaved renameRootEntry(UUID agentId, String from, String to) throws IOException {
        return renameEntryAt(from, resolveInAgentDir(agentId, from), to, resolveInAgentDir(agentId, to));
    }

    /** 读取结果。 */
    public record SkillFileContent(String path, String content, long sizeBytes) {
    }

    /** 写入/新建/重命名结果。 */
    public record SkillFileSaved(String path, long sizeBytes) {
    }

    // ==================== 文件操作核心（skills 与根级附加文件共用） ====================
    //
    // 两个入口的唯一差别是「路径怎么解析、哪些条目受保护」；解析之后读写删改的
    // 语义必须只有一份——同一判据写两处必然漂移。

    /** 读一个已解析的文本文件（二进制拒绝、大小受限，语义见 {@link #readSkillFile}）。 */
    private SkillFileContent readFileAt(String relPath, Path target) throws IOException {
        if (!Files.isRegularFile(target)) {
            throw ApiException.notFound("文件不存在: " + relPath);
        }
        long size = Files.size(target);
        if (size > MAX_FILE_BYTES) {
            throw ApiException.badRequest("文件过大（上限 " + (MAX_FILE_BYTES / 1024) + "KB）: " + relPath);
        }
        byte[] raw = Files.readAllBytes(target);
        String text = decodeUtf8Strict(raw);
        if (text == null) {
            throw ApiException.badRequest("不支持预览二进制文件: " + relPath);
        }
        return new SkillFileContent(relPath, text, size);
    }

    /** 覆写一个已解析的文本文件（不存在则创建；只接字符串，拒绝二进制混入）。 */
    private SkillFileSaved writeFileAt(String relPath, Path target, String content) throws IOException {
        if (content == null) {
            throw ApiException.badRequest("文件内容为空");
        }
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw ApiException.badRequest("文件过大（上限 " + (MAX_FILE_BYTES / 1024) + "KB）: " + relPath);
        }
        if (Files.isDirectory(target)) {
            throw ApiException.badRequest("目标是目录: " + relPath);
        }
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        return new SkillFileSaved(relPath, bytes.length);
    }

    /** 在已解析位置新建文件或目录（已存在时拒绝，不覆盖）。 */
    private SkillFileSaved createEntryAt(String relPath, Path target, boolean directory) throws IOException {
        if (Files.exists(target)) {
            throw ApiException.conflict("已存在: " + relPath);
        }
        if (directory) {
            Files.createDirectories(target);
        } else {
            Path name = target.getFileName();
            if (name == null) {
                throw ApiException.badRequest("路径缺少文件名: " + relPath);
            }
            rejectUnsafeEntryName(name.toString());
            // 不允许通过新建把目录顶成文件（同名目录已存在时上面已拦住）
            Files.createDirectories(target.getParent());
            Files.write(target, new byte[0]);
        }
        return new SkillFileSaved(relPath, 0);
    }

    /** 删除一个已解析的条目；目录级联删除。 */
    private RemovalResult deleteEntryAt(String relPath, Path target, Path jailRoot) throws IOException {
        if (!Files.exists(target)) {
            throw ApiException.notFound("文件不存在: " + relPath);
        }
        String rel = jailRoot.relativize(target).toString();
        if (Files.isDirectory(target)) {
            deleteRecursively(target);
        } else {
            Files.delete(target);
        }
        return new RemovalResult(rel, jailRoot.getParent().relativize(target).toString());
    }

    /** 重命名（移动）一个已解析的条目；目标父目录不存在则一并创建。 */
    private SkillFileSaved renameEntryAt(String from, Path src, String to, Path dst) throws IOException {
        if (!Files.exists(src)) {
            throw ApiException.notFound("文件不存在: " + from);
        }
        if (Files.exists(dst)) {
            throw ApiException.conflict("目标已存在: " + to);
        }
        // 目标的父目录交给 createDirectories 兜住：它不存在就建，存在但不是目录则抛 IOException。
        // （此前这里拿 isDirectory(src) 去比 dst.getParent()——那是目标所在目录，本就该是目录，
        //    同目录改名因此被误判为「目标位置不是目录」。）
        Files.createDirectories(dst.getParent());
        Files.move(src, dst);
        return new SkillFileSaved(to, sizeOfQuietly(dst));
    }

    /** 列出某目录一层的条目（隐藏点文件；目录在前、同类按名排序）。 */
    private static List<SkillFileNode> listOneLevel(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream
                    .map(p -> {
                        String name = p.getFileName().toString();
                        if (name.startsWith(".")) {
                            return null; // .DS_Store 之类
                        }
                        boolean dirFlag = Files.isDirectory(p);
                        return new SkillFileNode(name, dirFlag, dirFlag ? 0L : sizeOfQuietly(p));
                    })
                    .filter(java.util.Objects::nonNull)
                    .sorted(SkillFileNode.ORDER)
                    .toList();
        }
    }

    // ==================== 工具启用 ====================

    /**
     * 启用一个平台工具（按名称）。真相源是 {@code agent_configs.tool_ids}。
     *
     * @return 更新后的启用工具名列表
     */
    public List<String> enableTool(UUID agentId, String toolName) {
        AgentConfig config = requireEditableConfig(agentId);
        Tool tool = toolRepository.findAll().stream()
                .filter(t -> toolName.equals(t.getName()))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("平台工具库中不存在: " + toolName));
        List<UUID> ids = new ArrayList<>(config.getToolIds());
        if (!ids.contains(tool.getId())) {
            ids.add(tool.getId());
            config.setToolIds(ids);
            repository.save(config);
        }
        return enabledToolNames(config);
    }

    /**
     * 停用一个工具。被 {@code subagents/*.md} 引用时拒绝并带出引用者。
     *
     * @return 更新后的启用工具名列表
     */
    public List<String> disableTool(UUID agentId, String toolName) throws IOException {
        AgentConfig config = requireEditableConfig(agentId);
        Tool tool = toolRepository.findAll().stream()
                .filter(t -> toolName.equals(t.getName()))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("平台工具库中不存在: " + toolName));
        List<String> referrers = subagentsReferencing(agentId, toolName);
        if (!referrers.isEmpty()) {
            throw ApiException.conflict("工具「" + toolName + "」正被以下子智能体引用，"
                    + "停用后它们将在运行时失败：" + String.join("、", referrers));
        }
        List<UUID> ids = new ArrayList<>(config.getToolIds());
        if (ids.remove(tool.getId())) {
            config.setToolIds(ids);
            repository.save(config);
        }
        return enabledToolNames(config);
    }

    // ==================== 内部 ====================

    private AgentConfig requireEditableConfig(UUID agentId) {
        AgentConfig config = repository.findById(agentId)
                .orElseThrow(() -> ApiException.notFound("智能体不存在: " + agentId));
        return config;
    }

    private List<String> enabledToolNames(AgentConfig config) {
        return toolRepository.findAllById(config.getToolIds()).stream()
                .map(t -> t.getName())
                .sorted()
                .toList();
    }

    private UUID toolIdOf(String toolName) {
        return toolRepository.findAll().stream()
                .filter(t -> toolName.equals(t.getName()))
                .map(t -> t.getId())
                .findFirst()
                .orElse(null);
    }

    /** 该智能体的 skills/ 目录；不存在则创建。 */
    private Path skillsDirOf(UUID agentId) throws IOException {
        Path agentDir = agentDirOf(agentId);
        Path skills = agentDir.resolve(SKILLS_DIR);
        Files.createDirectories(skills);
        return skills;
    }

    /** 智能体目录 = workspace-root + 该智能体登记的 workspace_path。 */
    private Path agentDirOf(UUID agentId) {
        AgentConfig config = repository.findById(agentId)
                .orElseThrow(() -> ApiException.notFound("智能体不存在: " + agentId));
        String registered = config.getWorkspacePath();
        Path base = (registered == null || registered.isBlank())
                ? workspaceRoot.resolve("agents").resolve(agentId.toString())
                : workspaceRoot.resolve(registered);
        Path dir = base.toAbsolutePath().normalize();
        if (!dir.startsWith(workspaceRoot)) {
            throw ApiException.badRequest("非法工作区路径: " + registered);
        }
        return dir;
    }

    /**
     * 哪些子智能体的 {@code tools} frontmatter 引用了给定名字。
     *
     * <p>只做子串匹配：frontmatter 是自由文本，精确解析 YAML 会让「工具名恰好是另一个
     * 工具名的子串」这类误判变成漏报。这里宁可多报——多报只是让用户多点一次确认，
     * 漏报会让子智能体在运行时缺工具失败。
     */
    private List<String> subagentsReferencing(UUID agentId, String name) throws IOException {
        Path agentDir = agentDirOf(agentId);
        Path subagents = agentDir.resolve(SUBAGENTS_DIR);
        if (!Files.isDirectory(subagents)) {
            return List.of();
        }
        List<String> referrers = new ArrayList<>();
        try (var stream = Files.list(subagents)) {
            for (Path spec : stream.filter(p -> p.getFileName().toString().endsWith(".md")).toList()) {
                String text = Files.readString(spec, StandardCharsets.UTF_8);
                String frontmatter = text.startsWith("---") && text.indexOf("---", 3) > 0
                        ? text.substring(0, text.indexOf("---", 3))
                        : "";
                // tools: [a, b, c] —— 取中括号内的内容再按逗号切，避免正文里出现工具名就误判
                int open = frontmatter.indexOf("tools:[");
                if (open < 0) {
                    open = frontmatter.indexOf("tools: [");
                }
                if (open < 0) {
                    continue;
                }
                int close = frontmatter.indexOf(']', open);
                if (close < 0) {
                    continue;
                }
                String list = frontmatter.substring(open, close)
                        .replace("tools:[", "").replace("tools: [", "").trim();
                boolean hit = java.util.Arrays.stream(list.split(","))
                        .map(AgentDefinitionContentService::unquoteYamlScalar)
                        .anyMatch(t -> t.equals(name));
                if (hit) {
                    referrers.add(stripMdSuffix(spec.getFileName().toString()));
                }
            }
        }
        return referrers;
    }

    private static String stripMdSuffix(String fileName) {
        return fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
    }

    /**
     * 剥掉 YAML 标量的成对引号。
     *
     * <p>YAML 里含空格或特殊字符的标量必须加引号（{@code tools: ["Web 搜索"]}），
     * 而 {@code tools} 白名单的语义是工具<b>名字</b>，加载侧按裸名比对。
     * 不剥引号会让带空格的工具永远匹配不上 ⇒ 引用检查对它完全失效，
     * 而带空格的名字恰是最容易被写成引号形式的那批。
     */
    private static String unquoteYamlScalar(String raw) {
        String v = raw.trim();
        if (v.length() >= 2
                && ((v.startsWith("\"") && v.endsWith("\""))
                || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static void validateSkillName(String skillName) {
        if (skillName == null || !SKILL_DIR_NAME.matcher(skillName).matches()) {
            throw ApiException.badRequest("技能名不可用（仅允许字母/数字/汉字/中划线/下划线，"
                    + "且不含路径分隔符）: " + skillName);
        }
    }

    /** 解压技能包，返回技能根目录（剥掉可能存在的包装层）。 */
    private Path extractSkillZip(InputStream in, Path staging) throws IOException {
        Path unpacked = staging.resolve("unpacked");
        Files.createDirectories(unpacked);
        long total = 0;
        int entries = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw ApiException.badRequest("技能包条目过多（上限 " + MAX_ENTRIES + " 条）");
                }
                String rel = entry.getName().replace('\\', '/');
                rejectUnsafeEntryName(rel);
                Path target = safeResolve(unpacked, rel);
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (var out = Files.newOutputStream(target)) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        total += read;
                        if (total > MAX_TOTAL_BYTES) {
                            throw ApiException.badRequest("技能包解压后过大（上限 64MB）");
                        }
                        if (read > MAX_FILE_BYTES) {
                            throw ApiException.badRequest("技能包内单个文件超过 256KB: " + rel);
                        }
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
        return stripWrapper(unpacked);
    }

    /**
     * 剥掉可能存在的包装层，返回技能根目录。
     *
     * <p>现实中大量 zip 会多包一层项目名目录，有的还包两层（如 {@code 项目名/jc-2-数据准备/SKILL.md}）。
     * 判据是「当前层只有一个子项且不是文件」就再往里剥一层，直到剥到真正含
     * {@code SKILL.md} 的那层，或剥不动为止。是否含 {@code SKILL.md} 由调用方校验。
     */
    private Path stripWrapper(Path unpacked) throws IOException {
        Path current = unpacked;
        // 上限 3 层：足够覆盖「项目名/分类/技能名」这类真实结构，又不至于无限剥
        for (int depth = 0; depth < 3; depth++) {
            List<Path> children;
            try (var stream = Files.list(current)) {
                children = stream.toList();
            }
            if (children.size() != 1 || !Files.isDirectory(children.get(0))) {
                break;
            }
            current = children.get(0);
            if (Files.isRegularFile(current.resolve(SKILL_MANIFEST))) {
                return current;
            }
        }
        return current;
    }

    private static void rejectUnsafeEntryName(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("技能包含空条目名");
        }
        String v = name.replace('\\', '/');
        if (v.startsWith("/") || (v.length() >= 2 && v.charAt(1) == ':') || v.startsWith("//")) {
            throw ApiException.badRequest("技能包条目名非法: " + name);
        }
        for (String seg : v.split("/")) {
            if (seg.equals("..")) {
                throw ApiException.badRequest("技能包条目越界: " + name);
            }
        }
    }

    private static Path safeResolve(Path root, String relPath) {
        Path target = root.resolve(relPath.replace('\\', '/')).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw ApiException.badRequest("技能包条目越界: " + relPath);
        }
        return target;
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        try (var walk = Files.walk(src)) {
            for (Path p : walk.toList()) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
    }

    private static int countFiles(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            return (int) walk.filter(Files::isRegularFile).count();
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** 供测试与诊断：技能名是否合法。 */
    static boolean isValidSkillName(String name) {
        return name != null && SKILL_DIR_NAME.matcher(name).matches();
    }
}
