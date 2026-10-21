package com.tipdm.agentplatform.config;

import com.tipdm.agentplatform.domain.AgentConfig;
import com.tipdm.agentplatform.domain.AgentStatus;
import com.tipdm.agentplatform.repository.AgentConfigRepository;
import com.tipdm.agentplatform.util.AgentDirId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class AgentDirScanner {

    private static final Logger log = LoggerFactory.getLogger(AgentDirScanner.class);

    private final Path rootDir;

    /**
     * 默认模型（目录型 Agent 不配置 model 时使用；热更新路径必须保留，不能置空）。
     * 2026-09-22 整改：与主智能体同源——只来自 agent-platform.model 配置，
     * 代码不再内置任何兜底模型名（缺配置启动即失败）。
     */
    private final String defaultModel;

    /**
     * Plan 模式
     */
    private final boolean defaultPlanEnabled;

    /**
     * 技能沉淀
     */
    private final boolean defaultSkillSedimentation;

    /**
     * 排除的文件
     */
    private final List<String> excludeFolders;

    private final AgentConfigRepository configRepository;

    // 缓存所有已加载的目录型 Agent（内存）
    private final Map<UUID, AgentConfig> cache = new ConcurrentHashMap<>();

    public AgentDirScanner(
            @Value("${agent-platform.workspace-root}") String root,
            @Value("${agent-platform.model}") String defaultModel,
            @Value("${agent-platform.plan-enabled:false}") boolean defaultPlanEnabled,
            @Value("${agent-platform.skill-sedimentation:false}") boolean defaultSkillSedimentation,
            @Value("${agent-platform.exclude-folders:}") String excludeFoldersCsv,
            AgentConfigRepository configRepository) {
        this.rootDir = Paths.get(root).resolve("agents").toAbsolutePath().normalize();
        this.defaultModel = defaultModel;
        this.defaultPlanEnabled = defaultPlanEnabled;
        this.defaultSkillSedimentation = defaultSkillSedimentation;
        this.excludeFolders = parseExcludeFolders(excludeFoldersCsv);
        this.configRepository = configRepository;
        this.initializeRootDirectory();
    }

    private List<String> parseExcludeFolders(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 改为普通初始化方法，由 AguiIntegrationConfiguration 在启动时调用一次。
     * 同时也负责创建根目录。
     */
    public void initializeRootDirectory() {
        if (!Files.exists(rootDir)) {
            try {
                Files.createDirectories(rootDir);
                log.info("Created file-agent root directory: {}", rootDir);
            } catch (IOException e) {
                log.error("Created file-agent root directory: {}. err", rootDir, e);
            }
        }
    }

    /**
     * 全量扫描（不递归），每个含 AGENTS.md 的子目录视为一个 Agent。
     * 自动过滤：排除列表中的文件夹（如 .git, node_modules）、以及缺 AGENTS.md 的半成品目录。
     *
     * <p>目录名是 uuid 形态的目录同样会被装载——表单创建的智能体目录名就是它自己的 id，
     * 而它的 AGENTS.md 是同样要能在「定义内容」里编辑的。id 与展示名的取法见
     * {@link #resolveAgentId} / {@link #resolveDisplayName}。
     */
    public List<AgentConfig> loadAll() throws IOException {
        Map<UUID, AgentConfig> newCache = new HashMap<>();
        try (var stream = Files.list(rootDir)) {
            stream.filter(this::isValidFileAgentDirectory).forEach(dir -> {
                String dirName = dir.getFileName().toString();
                // id 来源两分：目录名是 uuid → 那是表单创建时自己的 id；否则由目录名派生
                UUID id = resolveAgentId(dirName);
                Path agentsMd = dir.resolve("AGENTS.md");

                AgentConfig config = new AgentConfig();
                config.setId(id);
                // 展示名：uuid 目录名不给人看，沿用数据库里已登记的名称
                config.setName(resolveDisplayName(dirName, id));
                // 统一为相对 workspace-root 的相对路径（与其他智能体一致），
                // 避免绝对路径导致运行态根解析分叉
                config.setWorkspacePath("agents/" + dirName);
                config.setStatus(AgentStatus.active);


                config.setModel(this.defaultModel);
                config.setPlanEnabled(defaultPlanEnabled);
                config.setSkillSedimentation(defaultSkillSedimentation);
                // 智能体目录来源的智能体同样需要一条配置记录（workspace_path 指向自己的
                // 目录），否则配置侧查不到、无法编辑。补行是 M3 自愈：写操作放这里而
                // 不写一次性脚本，新放入的目录也会自动被登记。
                // frontmatter 标签要在补配置记录之前解析出来：M1 要把它写进
                // agent_tags（frontmatter 标签本身随 M5 一并废弃）。
                config.setFileTagNames(parseFrontmatterTags(agentsMd));
                adoptIntoDatabase(config, dirName);

                newCache.put(id, config);
                log.debug("Loaded file-agent: {} (id={})", dirName, id);
            });
        }

        this.cache.clear();
        this.cache.putAll(newCache);
        log.info("Loaded {} file-based agent(s) from {} (excluded: {})",
                newCache.size(), rootDir, excludeFolders);
        return new ArrayList<>(newCache.values());
    }

    /**
     * 把智能体目录登记为一条配置记录（迁移 M3 自愈）。
     *
     * <p>重构后每个智能体都必有一行 {@code agent_configs}：定义内容在目录里，
     * 配置侧则需要行来保存名称、启停、工具勾选等元数据。目录型目录此前只存在于
     * 磁盘，这里补上行即可让两条管线对它们一致生效。
     *
     * <p><b>id 沿用目录名的 v3 哈希</b>，不可重新生成——会话按 id 分区，id 变了
     * 历史会话会失联（且这种错误界面上完全不可见）。
     *
     * <p>已存在则只对齐 {@code workspace_path}，不覆盖用户在配置侧做过的修改。
     */
    private void adoptIntoDatabase(AgentConfig config, String dirName) {
        try {
            Optional<AgentConfig> existing = configRepository.findById(config.getId());
            if (existing.isPresent()) {
                // 目录可能刚被重命名/迁入：仅在路径不一致时对齐
                AgentConfig row = existing.get();
                if (!("agents/" + dirName).equals(row.getWorkspacePath())) {
                    row.setWorkspacePath("agents/" + dirName);
                    configRepository.save(row);
                    log.info("Adopted definition dir into existing agent row: {} -> agents/{}", dirName, dirName);
                }
                return;
            }
            configRepository.save(config);
            log.info("Adopted definition dir as new agent row: {} (id={})", dirName, config.getId());
        } catch (RuntimeException e) {
            // 补行失败不能阻断扫描：否则一个目录的写冲突会让所有智能体目录智能体都加载
            // 不出来。记 warn 并继续——该目录仍能以「只读」形态使用。
            log.warn("Failed to adopt definition dir {} into agent_configs: {}", dirName, e.getMessage());
        }
    }

    /**
     * 判断一个目录是否应该被当作智能体加载。
     * 满足以下条件才加载：
     * 1. 是目录
     * 2. 不在用户配置的排除列表中
     * 3. 目录里有 {@code AGENTS.md}——缺定义文件说明它不是可运行的智能体，而是
     *    「配置行已删除、工作区残留」或写盘失败的半成品目录。此类目录若照旧登记，
     *    会以「uuid 名字 + 发布中 + 无描述 + 0 技能」的形式在列表里冒充智能体
     *    （删除流程清盘失败、或创建事务回滚后都会留下这种目录）。
     *
     * <p>目录名是不是 uuid <b>不</b>参与判断：它只是 id 与展示名的取法差异
     * （见 {@link #resolveAgentId}），不是「这类智能体归谁管」的判据。
     */
    private boolean isValidFileAgentDirectory(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }

        String dirName = dir.getFileName().toString();

        // 1. 检查是否在排除列表中
        if (excludeFolders.contains(dirName)) {
            log.trace("Skipping excluded folder: {}", dirName);
            return false;
        }

        // 2. 缺少定义文件 → 不认作智能体（写盘失败的半成品）
        if (!Files.isRegularFile(dir.resolve("AGENTS.md"))) {
            log.warn("Skipping directory without AGENTS.md (not a runnable agent): {}", dirName);
            return false;
        }

        // 3. uuid 形态且配置行已不存在 = 已删除智能体残留的目录。
        //    放过它就会以「uuid 名字 + 发布中 + 无描述」在列表里冒充智能体。
        //    反之配置行还在的**要装载**：表单创建的智能体目录名就是它自己的 id，
        //    而它的 AGENTS.md 与其他人设一样要在「定义内容」里编辑。
        try {
            UUID id = UUID.fromString(dirName);
            if (!configRepository.existsById(id)) {
                log.warn("Skipping orphaned agent workspace (config row gone, no longer an agent): {}", dirName);
                return false;
            }
        } catch (IllegalArgumentException e) {
            // 目录名不是 uuid ⇒ 目录名本身是身份，不存在「残留」问题
        }

        return true;
    }

    /**
     * 该目录对应的智能体 id。
     *
     * <p>目录名是 uuid 形态时，**以数据库记录的 id 为准**——表单创建的智能体目录名
     * 就是它自己的 id（见 WorkspaceGenerator），此时从目录名重新派生会得到另一个
     * uuid，导致「定义内容」等按 id 查询的功能永远查不到它。
     *
     * <p>其余情况（导入 / 手工放入的目录）才由目录名派生，与 AgentDirId 同一套规则。
     */
    private UUID resolveAgentId(String dirName) {
        try {
            return UUID.fromString(dirName);
        } catch (IllegalArgumentException e) {
            return AgentDirId.of(dirName);
        }
    }

    /**
     * 展示名。uuid 形态的目录名是内部 id，不该出现在界面上，
     * 因此沿用数据库里已登记的名称；查不到才退回目录名。
     */
    private String resolveDisplayName(String dirName, UUID id) {
        try {
            return configRepository.findById(id).map(AgentConfig::getName).orElse(dirName);
        } catch (RuntimeException e) {
            return dirName;
        }
    }

    /**
     * 获取所有文件 Agent（供列表接口使用）
     *
     * @return
     */
    public List<AgentConfig> getAll() {
        return new ArrayList<>(cache.values());
    }

    /**
     * 按 ID 获取缓存中的文件 Agent
     *
     * @param id
     * @return
     */
    public Optional<AgentConfig> get(UUID id) {
        return Optional.ofNullable(cache.get(id));
    }

    private AgentConfig buildConfigFromDir(String dirName) {
        UUID id = resolveAgentId(dirName);
        AgentConfig config = new AgentConfig();
        config.setId(id);
        config.setName(resolveDisplayName(dirName, id));
        // 统一为相对 workspace-root 的相对路径（与其他智能体一致）
        config.setWorkspacePath("agents/" + dirName);
        config.setStatus(AgentStatus.active);
        config.setModel(this.defaultModel);
        config.setPlanEnabled(defaultPlanEnabled);
        config.setSkillSedimentation(defaultSkillSedimentation);
        config.setFileTagNames(parseFrontmatterTags(rootDir.resolve(dirName).resolve("AGENTS.md")));
        return config;
    }

    /**
     * 解析 AGENTS.md YAML frontmatter 里的 {@code tags:}（2026-09-20 标签系统）。
     *
     * <p>支持两种写法：</p>
     * <pre>{@code
     * tags: [数据分析, 可视化]     // 行内数组
     * tags:                        // 块列表
     *   - 数据分析
     *   - 可视化
     * }</pre>
     *
     * <p>不做完整 YAML 解析——frontmatter 里只需要这一个键；格式非法或无 frontmatter
     * 返回 null（= 无标签），不影响加载。只与平台已有标签按名匹配（TagRepository
     * findByNameIgnoreCase 同样忽略大小写），frontmatter 里的未知标签名静默跳过，
     * 不自动建标签（封闭集合原则，只有管理员能建）。</p>
     */
    static List<String> parseFrontmatterTags(Path agentsMd) {
        if (!Files.isRegularFile(agentsMd)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(agentsMd, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).trim().equals("---")) {
                return null;
            }
            List<String> result = new ArrayList<>();
            boolean inTags = false;
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                if (trimmed.equals("---")) {
                    break;
                }
                if (inTags) {
                    if (trimmed.startsWith("- ")) {
                        String name = trimmed.substring(2).trim();
                        if (!name.isEmpty()) {
                            result.add(stripQuotes(name));
                        }
                        continue;
                    }
                    inTags = false;
                }
                if (trimmed.startsWith("tags:")) {
                    String value = trimmed.substring("tags:".length()).trim();
                    if (value.startsWith("[") && value.endsWith("]")) {
                        for (String item : value.substring(1, value.length() - 1).split(",")) {
                            String name = stripQuotes(item.trim());
                            if (!name.isEmpty()) {
                                result.add(name);
                            }
                        }
                        break;
                    }
                    if (value.isEmpty()) {
                        inTags = true;
                        continue;
                    }
                    // 单值写法 tags: 数据分析
                    result.add(stripQuotes(value));
                    break;
                }
            }
            return result.isEmpty() ? null : result;
        } catch (IOException e) {
            return null;
        }
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1).trim();
        }
        return s;
    }
}
