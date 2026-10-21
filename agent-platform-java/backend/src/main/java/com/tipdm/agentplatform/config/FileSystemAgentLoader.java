package com.tipdm.agentplatform.config;

import com.tipdm.agentplatform.domain.AgentConfig;
import com.tipdm.agentplatform.domain.AgentStatus;
import com.tipdm.agentplatform.repository.AgentConfigRepository;
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
public class FileSystemAgentLoader {

    private static final Logger log = LoggerFactory.getLogger(FileSystemAgentLoader.class);

    private final Path rootDir;

    /**
     * 默认模型（文件型 Agent 不配置 model 时使用；热更新路径必须保留，不能置空）。
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

    // 缓存所有已加载的文件型 Agent（内存）
    private final Map<UUID, AgentConfig> cache = new ConcurrentHashMap<>();

    public FileSystemAgentLoader(
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
     * 全量扫描（不递归），每个子目录视为一个 Agent。
     * 自动过滤：
     * 1. 排除列表中的文件夹（如 .git, node_modules）
     * 2. 数据库已存在的 Agent（文件夹名是数据库 UUID）
     */
    public List<AgentConfig> loadAll() throws IOException {
        Map<UUID, AgentConfig> newCache = new HashMap<>();
        try (var stream = Files.list(rootDir)) {
            stream.filter(this::isValidFileAgentDirectory).forEach(dir -> {
                String dirName = dir.getFileName().toString();
                // 使用目录名的 UUID（稳定唯一）
                UUID id = UUID.nameUUIDFromBytes(dirName.getBytes(StandardCharsets.UTF_8));
                Path agentsMd = dir.resolve("AGENTS.md");

                AgentConfig config = new AgentConfig();
                config.setId(id);
                config.setName(dirName);
                // 统一为相对 workspace-root 的相对路径（与数据库 Agent 一致），
                // 避免绝对路径导致运行态根解析分叉
                config.setWorkspacePath("agents/" + dirName);
                config.setStatus(AgentStatus.active);

                // 标记为文件类型
                config.setFileBased(true);

                config.setModel(this.defaultModel);
                config.setSystemPrompt(null);
                config.setPlanEnabled(defaultPlanEnabled);
                config.setSkillSedimentation(defaultSkillSedimentation);
                config.setFileTagNames(parseFrontmatterTags(agentsMd));

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
     * 判断一个目录是否应该被当作文件型 Agent 加载。
     * 满足以下条件才加载：
     * 1. 是目录
     * 2. 不在用户配置的排除列表中
     * 3. 不是数据库 Agent 的工作区（数据库 Agent 的文件夹名是有效的 UUID 且存在于 DB）
     * 4. 目录里有 {@code AGENTS.md}——缺定义文件说明它不是可运行的智能体，而是
     *    「数据库行已删除、工作区残留」或写盘失败的半成品目录。此类目录若照旧登记，
     *    会以「UUID 名字 + 发布中 + 无描述 + 0 技能」的形式在列表里冒充智能体
     *    （删除流程清盘失败、或创建事务回滚后都会留下这种目录）。
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

        // 2. 检查是否是数据库 Agent 的工作区
        try {
            UUID id = UUID.fromString(dirName);
            if (configRepository.existsById(id)) {
                log.trace("Skipping database-agent workspace: {} (exists in DB)", dirName);
                return false;
            }
            // UUID 形态却查不到对应 DB 行＝该数据库智能体已被删除（或创建事务回滚），
            // 目录是未同步清掉的残留。不再登记为智能体，只记一条 warn 便于对账清理。
            log.warn("Skipping orphaned agent workspace (no DB row, no longer loaded as agent): {}", dirName);
            return false;
        } catch (IllegalArgumentException e) {
            // 不是有效的 UUID 格式，说明是纯粹的文件型 Agent，允许通过
        }

        // 3. 缺少定义文件 → 不认作智能体（残留目录 / 写盘失败的半成品）
        if (!Files.isRegularFile(dir.resolve("AGENTS.md"))) {
            log.warn("Skipping directory without AGENTS.md (not a runnable file agent): {}", dirName);
            return false;
        }

        return true;
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
        UUID id = UUID.nameUUIDFromBytes(dirName.getBytes(StandardCharsets.UTF_8));
        AgentConfig config = new AgentConfig();
        config.setId(id);
        config.setName(dirName);
        // 统一为相对 workspace-root 的相对路径（与数据库 Agent 一致）
        config.setWorkspacePath("agents/" + dirName);
        config.setStatus(AgentStatus.active);
        config.setModel(this.defaultModel);
        config.setSystemPrompt(null);
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
