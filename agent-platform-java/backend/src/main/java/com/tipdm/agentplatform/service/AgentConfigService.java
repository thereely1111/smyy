package com.tipdm.agentplatform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.config.FileSystemAgentLoader;
import com.tipdm.agentplatform.domain.AgentConfig;
import com.tipdm.agentplatform.domain.FileAgentDraft;
import com.tipdm.agentplatform.domain.AgentConfigRevision;
import com.tipdm.agentplatform.domain.AgentStatus;
import com.tipdm.agentplatform.domain.Subagent;
import com.tipdm.agentplatform.domain.Skill;
import com.tipdm.agentplatform.domain.Tool;
import com.tipdm.agentplatform.repository.AgentConfigRepository;
import com.tipdm.agentplatform.repository.FileAgentDraftRepository;
import com.tipdm.agentplatform.repository.AgentConfigRevisionRepository;
import com.tipdm.agentplatform.repository.SkillRepository;
import com.tipdm.agentplatform.repository.ToolRepository;
import com.tipdm.agentplatform.web.dto.AgentDtos.AgentConfigRequest;
import com.tipdm.agentplatform.web.dto.AgentDtos.AgentConfigResponse;
import com.tipdm.agentplatform.web.dto.AgentDtos.AssembleResponse;
import com.tipdm.agentplatform.web.dto.AgentDtos.FileDefinition;
import com.tipdm.agentplatform.web.dto.AgentDtos.SubagentInline;
import com.tipdm.agentplatform.web.dto.CommonDtos.PageResponse;
import com.tipdm.agentplatform.web.dto.AgentDtos;
import com.tipdm.agentplatform.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.tipdm.agentplatform.web.dto.TagDtos.TagView;

@Service
public class AgentConfigService {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigService.class);

    /** 文件驱动 Agent 的写操作统一拒绝文案（管理端只读，真相源在工作区文件）。 */
    private static final String FILE_BASED_READONLY_MESSAGE =
            "该智能体由工作区文件定义（文件驱动），管理端不支持修改；"
                    + "请直接编辑其定义目录下的 AGENTS.md / skills / subagents 后重启生效。";

    private final AgentConfigRepository repository;
    private final AgentConfigRevisionRepository revisionRepository;
    private final SkillRepository skillRepository;
    private final ToolRepository toolRepository;
    private final WorkspaceGenerator workspaceGenerator;
    private final AgentAssemblyService assemblyService;
    private final AguiRegistryManager aguiRegistryManager;
    private final FileSystemAgentLoader fileSystemAgentLoader;
    private final SessionWorkspaceManager sessionWorkspaceManager;
    private final TagService tagService;
    /** 主智能体平台默认模型（agent-platform.model）：动态默认未设置时的配置兜底值。 */
    private final String platformModel;
    /** 动态平台默认模型（凭证+启用模型，2026-09-22）：存在时优先于配置兜底值。 */
    private final com.tipdm.agentplatform.service.credential.PlatformModelDefaultService platformModelDefaultService;
    /** 在线调试：试运行草稿内存仓库（编辑页「试运行」写入，装配期覆盖发布配置）。 */
    private final AgentDebugDraftStore debugDraftStore;
    /** 文件驱动智能体在线编辑：草稿持久仓库（2026-09-24）。 */
    private final FileAgentDraftRepository fileAgentDraftRepository;
    /** 请求/实体 ↔ 草稿与发布快照（Map）的互转；Spring Boot 上下文提供的共享实例。 */
    private final ObjectMapper objectMapper;


    public AgentConfigService(
            AgentConfigRepository repository,
            AgentConfigRevisionRepository revisionRepository,
            SkillRepository skillRepository,
            ToolRepository toolRepository,
            WorkspaceGenerator workspaceGenerator,
            AgentAssemblyService assemblyService,
            AguiRegistryManager aguiRegistryManager,
            FileSystemAgentLoader fileSystemAgentLoader,
            SessionWorkspaceManager sessionWorkspaceManager,
            TagService tagService,
            com.tipdm.agentplatform.service.credential.PlatformModelDefaultService platformModelDefaultService,
            ObjectMapper objectMapper,
            AgentDebugDraftStore debugDraftStore,
            FileAgentDraftRepository fileAgentDraftRepository,
            @org.springframework.beans.factory.annotation.Value("${agent-platform.model}")
            String platformModel) {
        this.repository = repository;
        this.revisionRepository = revisionRepository;
        this.skillRepository = skillRepository;
        this.toolRepository = toolRepository;
        this.workspaceGenerator = workspaceGenerator;
        this.assemblyService = assemblyService;
        this.aguiRegistryManager = aguiRegistryManager;
        this.fileSystemAgentLoader = fileSystemAgentLoader;
        this.sessionWorkspaceManager = sessionWorkspaceManager;
        this.tagService = tagService;
        this.platformModelDefaultService = platformModelDefaultService;
        this.objectMapper = objectMapper;
        this.debugDraftStore = debugDraftStore;
        this.fileAgentDraftRepository = fileAgentDraftRepository;
        this.platformModel = platformModel;
    }

    /** 未指定 run 级模型时的生效模型（动态默认优先，回落 agent-platform.model 配置）。 */
    private String effectivePlatformModel() {
        return platformModelDefaultService.effectiveModelString(platformModel);
    }

    // 在 AgentConfigService 中修改 list 方法
    public PageResponse<AgentConfigResponse> list(String search, UUID tag, int page, int size) {
        return list(search, tag, page, size, true);
    }

    /**
     * 智能体列表（含草稿可见性口径）。
     *
     * <p>广场 / 对话选择器 / 侧边栏走 {@code includeDrafts=false}：只返回发布态
     * （status=active）的数据库 Agent——「广场只能看到发布状态」。草稿与待发布
     * 草稿只对管理端可见（管理端列表显式传 true）。文件驱动 Agent 无草稿概念，
     * 两种口径下都返回。</p>
     */
    public PageResponse<AgentConfigResponse> list(
            String search, UUID tag, int page, int size, boolean includeDrafts) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        List<AgentConfig> pagedContent;
        int total;

        if (tag != null) {
            // 按标签筛选：关联表里只有数据库 Agent，文件驱动 Agent 按 frontmatter 标签名匹配。
            // 筛选态需要全量匹配后再手动分页（标签命中集与普通分页窗口不一致）。
            List<AgentConfig> matched = new ArrayList<>();
            Set<UUID> taggedIds = tagService.agentIdsByTag(tag);
            if (!taggedIds.isEmpty()) {
                repository.findAllById(taggedIds).stream()
                        .filter(c -> includeDrafts || c.getStatus() == AgentStatus.active)
                        .filter(c -> matchesSearch(c, search))
                        .forEach(matched::add);
            }
            String tagName = tagService.tagName(tag);
            if (tagName != null) {
                fileSystemAgentLoader.getAll().stream()
                        .filter(c -> matchesSearch(c, search))
                        .filter(c -> c.getFileTagNames() != null
                                && c.getFileTagNames().stream()
                                        .anyMatch(n -> n.equalsIgnoreCase(tagName)))
                        .forEach(matched::add);
            }
            // 统一按更新时间倒序，保证筛选结果顺序稳定
            matched.sort(java.util.Comparator.comparing(
                    AgentConfig::getUpdatedAt,
                    java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
            total = matched.size();
            int start = Math.min(Math.max(page, 0) * Math.max(size, 1), matched.size());
            int end = Math.min(start + Math.max(size, 1), matched.size());
            pagedContent = matched.subList(start, end);
        } else {
            // 1. 数据库分页查询（includeDrafts=false 时只取发布态，广场口径）
            Page<AgentConfig> dbResult;
            if (StringUtils.hasText(search)) {
                dbResult = includeDrafts
                        ? repository.findByNameContainingIgnoreCase(search.trim(), pageable)
                        : repository.findByNameContainingIgnoreCaseAndStatus(
                                search.trim(), AgentStatus.active, pageable);
            } else {
                dbResult = includeDrafts
                        ? repository.findAll(pageable)
                        : repository.findByStatus(AgentStatus.active, pageable);
            }
            List<AgentConfig> dbList = dbResult.getContent();

            // 2. 获取所有文件 Agent（内存）
            List<AgentConfig> fileList = fileSystemAgentLoader.getAll();
            // 搜索过滤（按名称）
            if (StringUtils.hasText(search)) {
                fileList = fileList.stream()
                        .filter(c -> c.getName().toLowerCase().contains(search.trim().toLowerCase()))
                        .toList();
            }

            // 3. 合并：数据库 + 文件
            List<AgentConfig> merged = new ArrayList<>(dbList);
            merged.addAll(fileList);

            // 4. 重新分页（因为文件 Agent 不在数据库 Page 内）
            total = (int) (dbResult.getTotalElements() + fileList.size());
            int start = Math.min(page * size, merged.size());
            int end = Math.min(start + size, merged.size());
            pagedContent = merged.subList(start, end);
        }

        // 5. 批量拼标签（一次查关联表，避免 N+1）
        Map<UUID, List<TagView>> tagsByAgent = tagService.tagsByAgentIds(
                pagedContent.stream().map(AgentConfig::getId).toList());

        return new PageResponse<>(
                pagedContent.stream().map(c -> toResponse(c, tagsByAgent)).toList(),
                page,
                size,
                total,
                (int) Math.ceil((double) total / size)
        );
    }

    /** 名称搜索匹配（与无标签路径的 contains 口径一致）；无搜索词恒真。 */
    private static boolean matchesSearch(AgentConfig config, String search) {
        if (!StringUtils.hasText(search)) {
            return true;
        }
        return config.getName() != null
                && config.getName().toLowerCase().contains(search.trim().toLowerCase());
    }

    /**
     * 详情：数据库 Agent 与文件驱动 Agent 都可解析。
     *
     * <p>文件驱动 Agent 没有数据库配置行，此前这里只查库 → 404，而前端编辑页
     * 没有 .catch，把「加载失败」渲染成了一张空表单（用户会以为配置被清空了）。
     * 改为复用 {@link #requireConfigEntity(UUID)} 的文件兜底，并附带定义区快照，
     * 让只读视图能如实呈现「这个智能体现在是什么样」。</p>
     */
    public AgentConfigResponse get(UUID id) {
        AgentConfig config = requireConfigEntity(id);
        return toResponse(config, config.isFileBased() ? readFileDefinition(config) : null);
    }

    @Transactional
    public AgentConfigResponse create(AgentConfigRequest request, String publishedBy) {
        requireNameAvailable(request.name(), null);
        AgentConfig config = new AgentConfig();
        apply(config, request);
        // model 列 NOT NULL（历史遗留），而请求 DTO 已不携带 model（2026-09-22 整改）：
        // 新建时必须兜底平台默认模型，否则 INSERT 违反非空约束，被全局兜底翻译成
        // 409「数据冲突（如名称已存在）」，与真实原因完全无关（2026-10-10 实证）。
        if (config.getModel() == null || config.getModel().isBlank()) {
            config.setModel(effectivePlatformModel());
        }
        config.setVersion(1);
        repository.save(config);
        regenerateWorkspace(config);
        registerRollbackWorkspaceCleanup(config);
        // 新增同步
        aguiRegistryManager.register(config);
        if (request.tagIds() != null) {
            tagService.setAgentTags(config.getId(), request.tagIds());
        }
        // 创建即发布（request.status=active）记为 v1 发布，进版本存档
        if (config.getStatus() == AgentStatus.active) {
            archiveRevision(config, publishedBy);
        }
        return toResponse(config);
    }

    /**
     * 创建流程的失败补偿：工作区已写盘，但{@code create} 后续步骤（AG-UI 注册、标签写入、
     * 版本存档）任一抛错都会让事务回滚——数据库行消失，<b>文件系统不参与回滚</b>，
     * {@code agents/{id}/} 会留成孤儿目录，并在下次启动时被文件驱动加载器登记成
     * 「UUID 名字 + 发布中 + 无描述」的假智能体（K8s 测试环境 2026-10-10 实测到72 个）。
     *
     * <p>挂在 {@code afterCompletion} 而非 try/catch：只有事务真的回滚才删目录，
     * 提交成功或回滚后重试成功都不会误删已落地的智能体。</p>
     */
    private void registerRollbackWorkspaceCleanup(AgentConfig config) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager
                .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != org.springframework.transaction.support
                                .TransactionSynchronization.STATUS_ROLLED_BACK) {
                            return;
                        }
                        String workspacePath = config.getWorkspacePath();
                        if (workspacePath == null || workspacePath.isBlank()) {
                            return;
                        }
                        try {
                            java.nio.file.Path dir = workspaceGenerator.resolveWorkspace(workspacePath);
                            if (java.nio.file.Files.exists(dir)) {
                                try (java.util.stream.Stream<java.nio.file.Path> walk =
                                             java.nio.file.Files.walk(dir)) {
                                    for (java.nio.file.Path p : walk.sorted(
                                            java.util.Comparator.reverseOrder()).toList()) {
                                        java.nio.file.Files.deleteIfExists(p);
                                    }
                                }
                                log.warn("Rolled back create for agent {}, removed orphan workspace: {}",
                                        config.getId(), dir);
                            }
                        } catch (java.io.IOException | RuntimeException e) {
                            // 补偿失败只记日志：不能因清理问题掩盖原始异常
                            log.warn("Failed to remove orphan workspace {} after rollback: {}",
                                    workspacePath, e.getMessage());
                        }
                    }
                });
    }

    /**
     * 更新配置。按「是否发布过」分流为两套语义：
     *
     * <ul>
     *   <li>从未发布（无版本存档）：编辑直写主行（现状语义）；request.status=active
     *       即首次发布，记为 v1 存档。</li>
     *   <li>已发布：request.status=active 为「发布」——编辑内容直接成为新发布版本
     *       （version+1，广场立即切换，旧版本已在其发布时存档）；否则为「仅保存草稿」
     *       ——编辑内容进 {@code draft_snapshot}，主行（对外发布内容）不动，广场继续
     *       展示当前发布版本。</li>
     * </ul>
     */
    @Transactional
    public AgentConfigResponse update(UUID id, AgentConfigRequest request, String publishedBy) {
        AgentConfig config = requireEditable(id);
        // 草稿快照里也含 name，发布时会回写主行——三个分支统一在入口拦重名
        requireNameAvailable(request.name(), id);
        boolean publish = request.status() == AgentStatus.active;
        if (!revisionRepository.existsByAgentId(id)) {
            // 从未发布：编辑直写主行
            assemblyService.evict(id);
            apply(config, request);
            repository.save(config);
            regenerateWorkspace(config);
            // 刷新 AG-UI 注册
            aguiRegistryManager.refresh(config);
            // 标签全量替换（null = 本次不碰；空数组 = 清空）
            if (request.tagIds() != null) {
                tagService.setAgentTags(config.getId(), request.tagIds());
            }
            if (publish) {
                archiveRevision(config, publishedBy);
            }
            return toResponse(config);
        }
        if (publish) {
            // 发布：编辑内容成为新发布版本（version+1），草稿快照清空
            assemblyService.evict(id);
            apply(config, request);
            config.setVersion(config.getVersion() + 1);
            config.setStatus(AgentStatus.active);
            config.setDraftSnapshot(null);
            repository.save(config);
            regenerateWorkspace(config);
            aguiRegistryManager.refresh(config);
            if (request.tagIds() != null) {
                tagService.setAgentTags(config.getId(), request.tagIds());
            }
            archiveRevision(config, publishedBy);
            return toResponse(config);
        }
        // 已发布智能体「仅保存草稿」：只进草稿快照；主行与装配缓存都不动，
        // 广场与运行链路继续使用当前发布版本
        config.setDraftSnapshot(objectMapper.convertValue(
                request, new TypeReference<Map<String, Object>>() { }));
        repository.save(config);
        return toResponse(config);
    }

    /**
     * 状态变更（发布 / 下架）。
     *
     * <p>→ active（发布）：从未发布过 = 首次发布（v1 存档）；有草稿快照 = 草稿内容
     * 成为新发布版本（version+1）；无草稿 = 重新发布已下架智能体（version+1）。</p>
     *
     * <p>→ draft（下架）：广场不再展示；版本存档保留，重新发布即产生新版本。</p>
     */
    @Transactional
    public AgentConfigResponse updateStatus(UUID id, AgentStatus status, String publishedBy) {
        AgentConfig config = requireEditable(id);
        boolean hasPublished = revisionRepository.existsByAgentId(id);
        assemblyService.evict(id);
        if (status == AgentStatus.active) {
            if (!hasPublished) {
                config.setStatus(AgentStatus.active);
                repository.save(config);
            } else if (config.getDraftSnapshot() != null) {
                // 发布已存草稿：草稿内容覆盖主行成为新发布版本
                AgentConfigRequest draft = objectMapper.convertValue(
                        config.getDraftSnapshot(), AgentConfigRequest.class);
                apply(config, draft);
                config.setVersion(config.getVersion() + 1);
                config.setStatus(AgentStatus.active);
                config.setDraftSnapshot(null);
                repository.save(config);
                regenerateWorkspace(config);
                if (draft.tagIds() != null) {
                    tagService.setAgentTags(config.getId(), draft.tagIds());
                }
            } else {
                // 重新发布已下架智能体（无草稿修改）：每次发布都是新版本
                config.setVersion(config.getVersion() + 1);
                config.setStatus(AgentStatus.active);
                repository.save(config);
            }
        } else {
            config.setStatus(AgentStatus.draft);
            repository.save(config);
        }
        // 状态变更后同步注册表
        aguiRegistryManager.refresh(config);
        if (status == AgentStatus.active) {
            archiveRevision(config, publishedBy);
        }
        return toResponse(config);
    }

    @Transactional
    public void delete(UUID id) {
        AgentConfig config = requireEditable(id);
        // 先注销 AG-UI
        aguiRegistryManager.unregister(id);
        assemblyService.evict(id);
        debugDraftStore.evictAgent(id);
        // 联动清理运行态产物（runtimes/{agentId}，含全部用户会话目录）
        sessionWorkspaceManager.deleteRuntimeRoot(config.getId().toString());
        // 工作区先删、DB 行后删：顺序反了会留下「行没了目录还在」的孤儿目录，
        // 而孤儿目录会被 FileSystemAgentLoader 当成文件驱动智能体重新登记
        // （名字=目录名 UUID、状态恒 active），在列表里凭空多出一个智能体。
        // 删除失败不再静默吞掉（此前 catch 后留空注释），直接抛错让事务回滚，
        // 用户能立刻看到「智能体未删除」而不是面对一个查不到行的残留目录。
        deleteWorkspaceOrFail(config);
        revisionRepository.deleteByAgentId(id);
        repository.delete(config);
    }

    /**
     * 删除数据库型 Agent 的工作区目录；失败即抛错（回滚事务），不做静默降级。
     *
     * <p>{@code workspace_path} 为空的历史行（V1 建表即可空，V12/V14/V19 迁移插入的
     * demo 行都没写这一列）<b>不做目录删除</b>：{@link WorkspaceGenerator#resolveWorkspace}
     * 对空值会回落到 {@code agents/_default}，那是所有未配置工作区的 Agent 共用的目录，
     * 递归删它等于跨 Agent 误删。空路径只清运行态产物 + 删 DB 行即可。</p>
     */
    private void deleteWorkspaceOrFail(AgentConfig config) {
        String workspacePath = config.getWorkspacePath();
        if (workspacePath == null || workspacePath.isBlank()) {
            log.info("Agent {} has no workspace_path, skip definition dir cleanup "
                    + "(shared agents/_default must not be deleted)", config.getId());
            return;
        }
        java.nio.file.Path dir = workspaceGenerator.resolveWorkspace(workspacePath);
        if (!java.nio.file.Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(dir)) {
            for (java.nio.file.Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.deleteIfExists(p);
            }
        } catch (java.io.IOException e) {
            log.error("Failed to delete workspace dir {} for agent {}, aborting delete: {}",
                    dir, config.getId(), e.getMessage(), e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "workspace_delete_failed",
                    "工作区目录清理失败，智能体未删除（残留目录会被误识别为文件驱动智能体），请联系管理员: "
                            + e.getMessage());
        }
    }

    public AssembleResponse assemble(UUID id) {
        AgentConfig config = requireConfig(id);
        io.agentscope.harness.agent.HarnessAgent agent = assemblyService.requireAssembled(config);
        List<Tool> tools = config.getToolIds().isEmpty() ? List.of() : toolRepository.findAllById(config.getToolIds());
        List<Skill> skills = config.getSkillIds().isEmpty()
                ? List.of() : skillRepository.findAllById(config.getSkillIds());
        return new AssembleResponse(
                config.getId(),
                config.getName(),
                effectivePlatformModel(),
                config.getWorkspacePath(),
                tools.stream().map(Tool::getName).toList(),
                (config.getInlineSubagents() == null ? List.<Subagent>of() : config.getInlineSubagents())
                        .stream().map(Subagent::name).toList(),
                skills.stream().map(Skill::getName).toList(),
                "postgres");
    }

    /** 发布版本历史（倒序），仅数据库 Agent；文件驱动 Agent 无版本概念。 */
    @Transactional(readOnly = true)
    public List<com.tipdm.agentplatform.web.dto.AgentDtos.AgentRevisionView> listRevisions(UUID id) {
        requireConfig(id);
        return revisionRepository.findByAgentIdOrderByVersionDesc(id).stream()
                .map(r -> new com.tipdm.agentplatform.web.dto.AgentDtos.AgentRevisionView(
                        r.getAgentId(), r.getVersion(), r.getCreatedAt(), r.getPublishedBy()))
                .toList();
    }

    /**
     * 回滚到历史发布版本：<b>恢复为待发布草稿</b>（写入 draft_snapshot），不直接
     * 改动对外发布内容——与「修改已发布智能体」同一验证路径，确认无误后再发布
     * （发布即产生 version+1 的新版本）。标签不参与回滚（展示属性，保持现状）。
     */
    @Transactional
    public AgentConfigResponse rollback(UUID id, int version) {
        if (repository.findById(id).isEmpty()) {
            // 文件驱动智能体：回滚 = 把历史快照恢复为待发布草稿（与 DB 智能体同语义）
            AgentConfig fileConfig = requireFileAgent(id);
            rollbackFileAgent(id, version);
            return toResponse(fileConfig);
        }
        AgentConfig config = requireConfig(id);
        AgentConfigRevision rev = revisionRepository.findByAgentIdAndVersion(id, version)
                .orElseThrow(() -> ApiException.notFound("该智能体不存在版本 v" + version));
        config.setDraftSnapshot(rev.getSnapshot());
        repository.save(config);
        return toResponse(config);
    }

    /** 发布存档：同一 (agent_id, version) 重复发布时覆盖旧快照（幂等，防并发双写炸唯一约束）。 */
    private void archiveRevision(AgentConfig config, String publishedBy) {
        AgentConfigRevision rev = revisionRepository
                .findByAgentIdAndVersion(config.getId(), config.getVersion())
                .orElseGet(AgentConfigRevision::new);
        rev.setAgentId(config.getId());
        rev.setVersion(config.getVersion());
        rev.setSnapshot(snapshotOf(config));
        rev.setPublishedBy(publishedBy);
        revisionRepository.save(rev);
    }

    /** 配置实体 → 发布快照（AgentConfigRequest 字段形状；status/tagIds 不进快照）。 */
    private Map<String, Object> snapshotOf(AgentConfig config) {
        AgentConfigRequest request = new AgentConfigRequest(
                config.getName(),
                config.getDescription(),
                config.getSystemPrompt(),
                null,
                config.getSkillIds(),
                config.getToolIds(),
                (config.getInlineSubagents() == null ? List.<Subagent>of() : config.getInlineSubagents())
                        .stream()
                        .map(s -> new com.tipdm.agentplatform.web.dto.AgentDtos.SubagentInline(
                                s.name(), s.description(), s.prompt(), s.model(),
                                s.tools() == null || s.tools().isEmpty() ? null : s.tools(), s.steps()))
                        .toList(),
                config.getLaunchTemplates(),
                config.isPlanEnabled(),
                config.isSkillSedimentation(),
                config.getWelcomeForm(),
                config.getForm(),
                config.getFormTrigger(),
                null);
        return objectMapper.convertValue(request, new TypeReference<Map<String, Object>>() { });
    }

    /**
     * 解析运行时接口（会话列表 / 转录 / 清理）使用的配置：数据库 Agent 优先，
     * 其次文件型 Agent（workspace/agents 目录声明，内存缓存）。
     *
     * <p>运行时端点需要同时服务两类智能体——文件型 Agent 由 AG-UI 路径持久化会话，
     * 管理端只读接口若只能解析数据库配置，会对文件型 Agent 返回 404，
     * 导致 Space 对话刷新后无法恢复历史。</p>
     */
    public AgentConfig requireConfigEntity(UUID id) {
        return repository.findById(id)
                .or(() -> fileSystemAgentLoader.get(id))
                .orElseThrow(() -> ApiException.notFound("Agent 配置不存在: " + id));
    }

    /**
     * 重建工作区定义文件。子 Agent 现配存数据库（agent_configs.inline_subagents，随 apply 落库），
     * 工作区只重建 AGENTS.md / skills/。
     */
    private void regenerateWorkspace(AgentConfig config) {
        List<Skill> skills = config.getSkillIds().isEmpty()
                ? List.of() : skillRepository.findAllById(config.getSkillIds());
        List<Tool> tools = config.getToolIds().isEmpty() ? List.of() : toolRepository.findAllById(config.getToolIds());
        workspaceGenerator.generate(config, skills, tools);
        repository.save(config);
    }

    private AgentConfig requireConfig(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("Agent 配置不存在: " + id));
    }

    /**
     * 解析「可编辑」配置：只有数据库 Agent 可写。
     *
     * <p>文件驱动 Agent 的真相源是 workspace/agents/{目录名}/ 下的定义文件，数据库里
     * 没有它的行——若放行写操作，要么 404，要么在 agents/{uuid}/ 下凭空生成一份与定义区
     * 并存的副本，两个源同时"生效"，是最难排查的一类脏数据（{@link WorkspaceGenerator}
     * 按 id 建目录，而文件型的 id 是目录名哈希，二者并非同一目录）。因此这里显式拒绝，
     * 并把"去哪里改"写进错误文案。</p>
     */
    private AgentConfig requireEditable(UUID id) {
        AgentConfig config = repository.findById(id).orElse(null);
        if (config == null) {
            if (fileSystemAgentLoader.get(id).isPresent()) {
                throw ApiException.conflict(FILE_BASED_READONLY_MESSAGE);
            }
            throw ApiException.notFound("Agent 配置不存在: " + id);
        }
        if (config.isFileBased()) {
            throw ApiException.conflict(FILE_BASED_READONLY_MESSAGE);
        }
        return config;
    }

    /**
     * 读取文件驱动 Agent 的定义区快照（AGENTS.md 字节数/修改时间 + skills/subagents 计数）。
     * 仅供详情接口的只读展示；目录缺失或读取失败返回 null，不阻塞详情。
     */
    private FileDefinition readFileDefinition(AgentConfig config) {
        if (config.getWorkspacePath() == null || config.getWorkspacePath().isBlank()) {
            return null;
        }
        try {
            Path dir = workspaceGenerator.resolveWorkspace(config.getWorkspacePath());
            Path agentsMd = dir.resolve("AGENTS.md");
            boolean hasAgentsMd = Files.isRegularFile(agentsMd);
            return new FileDefinition(
                    config.getWorkspacePath(),
                    hasAgentsMd ? Files.size(agentsMd) : 0L,
                    hasAgentsMd ? Files.getLastModifiedTime(agentsMd).toInstant() : null,
                    countSubdirectories(dir.resolve("skills")),
                    countMarkdownFiles(dir.resolve("subagents")));
        } catch (IOException | ApiException e) {
            log.warn("Failed to read file-agent definition for {}: {}", config.getName(), e.getMessage());
            return null;
        }
    }

    private static int countSubdirectories(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var stream = Files.list(dir)) {
            return (int) stream.filter(Files::isDirectory).count();
        }
    }

    private static int countMarkdownFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var stream = Files.list(dir)) {
            return (int) stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
                    .count();
        }
    }

    /** 字段映射统一走 {@link AgentConfigApplier}（试运行草稿克隆共用，防漂移）。 */
    private void apply(AgentConfig config, AgentConfigRequest request) {
        AgentConfigApplier.apply(config, request);
    }

    /**
     * 名称唯一性守卫：agent_configs.name 从建表起就没有 DB 层 UNIQUE 约束
     * （V1 里带 UNIQUE 的是 users/tools 等表），create/update 也从未查重，
     * 重名会静默落库。ignore-case 比对，忽略与自身（selfId）重名。
     */
    private void requireNameAvailable(String rawName, UUID selfId) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            return; // 空名由 Controller 层 @NotBlank 拦截
        }
        boolean taken = selfId == null
                ? repository.existsByNameIgnoreCase(name)
                : repository.existsByNameIgnoreCaseAndIdNot(name, selfId);
        if (taken) {
            throw ApiException.conflict("智能体名称已存在: " + name);
        }
    }

    // ── 在线调试：试运行草稿（2026-09-24） ──────────────────────────────

    /**
     * 登记试运行草稿（编辑页「试运行」按钮）：把表单当前内容存入内存仓库，
     * 其后该用户对该智能体携带 {@code forwardedProps.debug=true} 的 run 在装配期
     * 用这份草稿覆盖发布配置——验证的是编辑器内容，广场与既有会话不受影响。
     * 文件驱动智能体无表单配置（真相源在 workspace 文件），沿用 requireEditable 409。
     */
    public void saveDebugDraft(UUID id, AgentConfigRequest request, String userId) {
        requireEditable(id);
        debugDraftStore.put(userId, id, request);
    }

    /** 清除本人对该智能体的试运行草稿。 */
    public void clearDebugDraft(UUID id, String userId) {
        debugDraftStore.evict(userId, id);
    }

    // ── 文件驱动智能体在线编辑（2026-09-24） ──────────────────────────────
    // 真相源仍是 workspace/agents/{名}/ 定义目录；本组方法承载「草稿（DB）→
    // 试运行（装配临时目录）→ 发布（落盘 + 存档版本）」三态，与 DB 智能体的
    // 发布快照模型对齐。skills/ 技能树不在在线编辑范围（走磁盘 + reload-files）。

    private static final int FILE_AGENTS_MD_MAX_CHARS = 512 * 1024;
    private static final int FILE_SUBAGENT_FILE_MAX_CHARS = 256 * 1024;
    private static final int FILE_SUBAGENT_MAX_FILES = 64;
    /** subagents 文件名白名单：单段、.md 结尾，防路径穿越。 */
    private static final java.util.regex.Pattern FILE_SUBAGENT_NAME =
            java.util.regex.Pattern.compile("^[\\w\\-\\u4e00-\\u9fa5]{1,96}\\.md$");

    /** 编辑视图：有草稿回草稿，无草稿读磁盘定义区（AGENTS.md + subagents/*.md）。 */
    @Transactional(readOnly = true)
    public AgentDtos.FileAgentEditView readFileForEdit(UUID id) {
        AgentConfig config = requireFileAgent(id);
        Integer version = currentFileVersion(id);
        FileAgentDraft draft = fileAgentDraftRepository.findByAgentId(id).orElse(null);
        if (draft != null) {
            return new AgentDtos.FileAgentEditView(draft.getAgentsMd(),
                    draft.getSubagents() == null ? java.util.Map.of() : draft.getSubagents(),
                    true, version, draft.getUpdatedBy(), draft.getUpdatedAt());
        }
        Path defDir = sessionWorkspaceManager.resolveAgentDefinitionDir(config.getWorkspacePath());
        String agentsMd = readTextFileQuietly(defDir.resolve("AGENTS.md"));
        java.util.Map<String, String> subs = new java.util.TreeMap<>();
        Path subDir = defDir.resolve("subagents");
        if (Files.isDirectory(subDir)) {
            try (var stream = Files.list(subDir)) {
                for (Path f : stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".md"))
                        .sorted(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                        .toList()) {
                    subs.put(f.getFileName().toString(), readTextFileQuietly(f));
                }
            } catch (IOException e) {
                log.warn("Read file-agent subagents failed: {}", e.getMessage());
            }
        }
        return new AgentDtos.FileAgentEditView(agentsMd, subs, false, version, null, null);
    }

    /** 保存草稿（后写覆盖；每个智能体至多一条草稿）。 */
    @Transactional
    public AgentDtos.FileAgentEditView saveFileDraft(UUID id, AgentDtos.FileDraftRequest request,
                                                     String userId) {
        requireFileAgent(id);
        validateFileDraft(request);
        FileAgentDraft draft = fileAgentDraftRepository.findByAgentId(id).orElseGet(FileAgentDraft::new);
        if (draft.getAgentId() == null) {
            draft.setAgentId(id);
        }
        draft.setAgentsMd(request.agentsMd());
        draft.setSubagents(request.subagents() == null ? java.util.Map.of() : request.subagents());
        draft.setDraftSeq(draft.getDraftSeq() + 1);
        draft.setUpdatedBy(userId);
        fileAgentDraftRepository.save(draft);
        return new AgentDtos.FileAgentEditView(draft.getAgentsMd(), draft.getSubagents(),
                true, currentFileVersion(id), draft.getUpdatedBy(), draft.getUpdatedAt());
    }

    /** 放弃草稿：回到磁盘现状。 */
    @Transactional
    public void discardFileDraft(UUID id) {
        requireFileAgent(id);
        fileAgentDraftRepository.deleteByAgentId(id);
    }

    /**
     * 发布：草稿 → 校验 → 落盘定义目录（AGENTS.md 全量覆盖、subagents/*.md 按全量
     * 期望状态同步）→ 重扫缓存 → 存档版本（version = 历史最大 + 1）→ 删草稿。
     * 发布后旧版本自动下线（文件即对外内容），版本历史可用于回滚。
     */
    @Transactional
    public AgentDtos.FilePublishResponse publishFileAgent(UUID id, String userId) {
        AgentConfig config = requireFileAgent(id);
        FileAgentDraft draft = fileAgentDraftRepository.findByAgentId(id)
                .orElseThrow(() -> ApiException.notFound("没有待发布的草稿"));
        validateFileDraft(new AgentDtos.FileDraftRequest(draft.getAgentsMd(), draft.getSubagents()));
        Path defDir = sessionWorkspaceManager.resolveAgentDefinitionDir(config.getWorkspacePath());
        try {
            Files.writeString(defDir.resolve("AGENTS.md"), draft.getAgentsMd(),
                    java.nio.charset.StandardCharsets.UTF_8);
            syncSubagentsDir(defDir.resolve("subagents"),
                    draft.getSubagents() == null ? java.util.Map.of() : draft.getSubagents());
        } catch (IOException e) {
            log.error("Publish file agent {} failed", id, e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "publish_failed",
                    "写入定义目录失败：" + e.getMessage());
        }
        Integer current = currentFileVersion(id);
        int nextVersion = current == null ? 1 : current + 1;
        AgentConfigRevision rev = new AgentConfigRevision();
        rev.setAgentId(id);
        rev.setVersion(nextVersion);
        rev.setSnapshot(java.util.Map.of(
                "agents_md", draft.getAgentsMd(),
                "subagents", draft.getSubagents() == null ? java.util.Map.of() : draft.getSubagents()));
        rev.setPublishedBy(userId);
        revisionRepository.save(rev);
        fileAgentDraftRepository.delete(draft);
        // 重扫缓存（frontmatter 标签等元数据同步）+ 清装配缓存 + 刷新注册表
        List<AgentConfig> reloaded;
        try {
            reloaded = fileSystemAgentLoader.loadAll();
        } catch (IOException e) {
            log.error("Reload file agents after publish failed", e);
            reloaded = List.of(config);
        }
        AgentConfig fresh = reloaded.stream().filter(c -> c.getId().equals(id))
                .findFirst().orElse(config);
        assemblyService.evict(id);
        aguiRegistryManager.refresh(fresh);
        log.info("File agent {} published: v{} (by {})", id, nextVersion, userId);
        return new AgentDtos.FilePublishResponse(nextVersion);
    }

    /** 回滚文件智能体到历史版本：恢复为待发布草稿（不直接落盘）。 */
    @Transactional
    public void rollbackFileAgent(UUID id, int version) {
        requireFileAgent(id);
        AgentConfigRevision rev = revisionRepository.findByAgentIdAndVersion(id, version)
                .orElseThrow(() -> ApiException.notFound("该智能体不存在版本 v" + version));
        String agentsMd = String.valueOf(rev.getSnapshot().getOrDefault("agents_md", ""));
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> subs = (java.util.Map<String, String>) rev.getSnapshot()
                .getOrDefault("subagents", java.util.Map.of());
        FileAgentDraft draft = fileAgentDraftRepository.findByAgentId(id).orElseGet(FileAgentDraft::new);
        if (draft.getAgentId() == null) {
            draft.setAgentId(id);
        }
        draft.setAgentsMd(agentsMd);
        draft.setSubagents(subs);
        draft.setDraftSeq(draft.getDraftSeq() + 1);
        draft.setUpdatedBy(rev.getPublishedBy());
        fileAgentDraftRepository.save(draft);
    }

    private AgentConfig requireFileAgent(UUID id) {
        AgentConfig config = fileSystemAgentLoader.get(id)
                .orElseThrow(() -> ApiException.notFound("Agent 配置不存在: " + id));
        if (!config.isFileBased()) {
            throw ApiException.conflict("该智能体不是文件驱动类型，请走常规编辑");
        }
        return config;
    }

    /** 当前发布版本号 = 版本存档最大 version；从未发布过为 null。 */
    private Integer currentFileVersion(UUID id) {
        return revisionRepository.findByAgentIdOrderByVersionDesc(id).stream()
                .findFirst().map(AgentConfigRevision::getVersion).orElse(null);
    }

    private void validateFileDraft(AgentDtos.FileDraftRequest request) {
        if (request.agentsMd() == null || request.agentsMd().isBlank()) {
            throw ApiException.badRequest("AGENTS.md 内容不能为空");
        }
        if (request.agentsMd().length() > FILE_AGENTS_MD_MAX_CHARS) {
            throw ApiException.badRequest("AGENTS.md 超过大小限制（512KB）");
        }
        java.util.Map<String, String> subs = request.subagents();
        if (subs == null) {
            return;
        }
        if (subs.size() > FILE_SUBAGENT_MAX_FILES) {
            throw ApiException.badRequest("子智能体文件数超过限制（" + FILE_SUBAGENT_MAX_FILES + "）");
        }
        for (java.util.Map.Entry<String, String> e : subs.entrySet()) {
            if (e.getKey() == null || !FILE_SUBAGENT_NAME.matcher(e.getKey()).matches()) {
                throw ApiException.badRequest("子智能体文件名非法（仅允许字母/数字/汉字/中划线/下划线，.md 结尾）: "
                        + e.getKey());
            }
            if (e.getValue() != null && e.getValue().length() > FILE_SUBAGENT_FILE_MAX_CHARS) {
                throw ApiException.badRequest("子智能体文件过大（256KB）: " + e.getKey());
            }
        }
    }

    /** subagents 目录全量同步：写期望文件、删除不在期望状态里的顶层 .md（非 .md 不动）。 */
    private void syncSubagentsDir(Path subDir, java.util.Map<String, String> expected) throws IOException {
        if (expected.isEmpty()) {
            if (Files.isDirectory(subDir)) {
                try (var stream = Files.list(subDir)) {
                    for (Path f : stream.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".md")).toList()) {
                        Files.deleteIfExists(f);
                    }
                }
            }
            return;
        }
        Files.createDirectories(subDir);
        java.util.Set<String> names = expected.keySet();
        try (var stream = Files.list(subDir)) {
            for (Path f : stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !names.contains(p.getFileName().toString())).toList()) {
                Files.deleteIfExists(f);
            }
        }
        for (java.util.Map.Entry<String, String> e : expected.entrySet()) {
            Files.writeString(subDir.resolve(e.getKey()),
                    e.getValue() == null ? "" : e.getValue(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private String readTextFileQuietly(Path file) {
        try {
            return Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 文件驱动智能体免重载（在线调试 2026-09-24）：重扫 workspace/agents 并对账——
     * 新增目录注册 AG-UI 工厂、移除目录注销工厂并清装配缓存；全部文件智能体统一
     * evict + refresh（AGENTS.md/skills/subagents 可能在进程外被编辑，evict 后下个
     * run 重新装配读盘；refresh 让工厂闭包持有重扫出的新配置实例，frontmatter
     * 标签等元数据同步更新）。无需重启进程。
     *
     * @return 重扫结果计数（total=本次扫描到的文件智能体数）
     */
    public AgentDtos.FileReloadResponse reloadFileAgents() {
        Set<UUID> before = fileSystemAgentLoader.getAll().stream()
                .map(AgentConfig::getId).collect(Collectors.toSet());
        List<AgentConfig> reloaded;
        try {
            reloaded = fileSystemAgentLoader.loadAll();
        } catch (IOException e) {
            log.error("Reload file agents failed", e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "reload_failed",
                    "扫描文件智能体失败：" + e.getMessage());
        }
        Set<UUID> after = reloaded.stream()
                .map(AgentConfig::getId).collect(Collectors.toSet());
        Set<UUID> added = new HashSet<>(after);
        added.removeAll(before);
        Set<UUID> removed = new HashSet<>(before);
        removed.removeAll(after);
        for (UUID id : removed) {
            aguiRegistryManager.unregister(id);
            assemblyService.evict(id);
        }
        for (AgentConfig config : reloaded) {
            aguiRegistryManager.refresh(config);
            assemblyService.evict(config.getId());
        }
        log.info("File agents reloaded: total={}, added={}, removed={}",
                reloaded.size(), added.size(), removed.size());
        return new AgentDtos.FileReloadResponse(reloaded.size(), added.size(), removed.size());
    }

    private AgentConfigResponse toResponse(AgentConfig config) {
        return toResponse(config, null,
                tagService.tagsByAgentIds(List.of(config.getId())));
    }

    /** 列表路径：标签由调用方批量查出，避免逐行 N+1。 */
    private AgentConfigResponse toResponse(AgentConfig config, Map<UUID, List<TagView>> tagsByAgent) {
        // 文件驱动 Agent 附带定义区快照：列表卡片的能力统计（子智能体/技能数）
        // 真相源在磁盘（subagents/*.md 与 skills/ 目录），DB 字段恒空会让卡片显示 0/0。
        return toResponse(config, config.isFileBased() ? readFileDefinition(config) : null, tagsByAgent);
    }

    /** @param definition 文件驱动 Agent 的定义区快照；数据库 Agent 传 null。 */
    private AgentConfigResponse toResponse(AgentConfig config, FileDefinition definition) {
        return toResponse(config, definition,
                tagService.tagsByAgentIds(List.of(config.getId())));
    }

    private AgentConfigResponse toResponse(AgentConfig config, FileDefinition definition,
                                           Map<UUID, List<TagView>> tagsByAgent) {
        // 子 Agent 现配从数据库回显（inline_subagents → DTO）
        List<SubagentInline> subagents = config.getInlineSubagents() == null ? List.of()
                : config.getInlineSubagents().stream()
                        .map(s -> new SubagentInline(
                                s.name(), s.description(), s.prompt(), s.model(),
                                s.tools() == null || s.tools().isEmpty() ? null : s.tools(), s.steps()))
                        .toList();
        // 标签：数据库 Agent 走关联表；文件驱动 Agent 的标签名真相源是 AGENTS.md frontmatter，
        // 按名与平台标签池匹配（忽略大小写，未知/已删标签名静默跳过）。
        List<TagView> tags = config.isFileBased()
                ? resolveFileTags(config)
                : tagsByAgent.getOrDefault(config.getId(), List.of());
        return new AgentConfigResponse(
                config.getId(),
                config.getName(),
                config.getDescription(),
                config.getSystemPrompt(),
                // 主智能体模型已平台化（2026-09-22）：回显「未指定 run 级模型时的生效
                // 模型」= 动态平台默认（凭证+启用模型）优先，回落 agent-platform.model；
                // DB 列的遗留值不再对外呈现。
                effectivePlatformModel(),
                config.getStatus(),
                config.getSkillIds(),
                config.getToolIds(),
                subagents,
                config.getLaunchTemplates(),
                config.isPlanEnabled(),
                config.isSkillSedimentation(),
                config.getWorkspacePath(),
                config.isFileBased(),
                definition,
                config.getWelcomeForm(),
                config.getForm(),
                config.getFormTrigger(),
                tags,
                config.getCreatedAt(),
                config.getUpdatedAt(),
                config.getVersion(),
                config.getDraftSnapshot() != null,
                config.getDraftSnapshot());
    }

    /** 文件驱动 Agent 的标签解析：frontmatter 名单 → 平台标签池匹配（含停用标签，保持展示一致）。 */
    private List<TagView> resolveFileTags(AgentConfig config) {
        List<String> names = config.getFileTagNames();
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        return tagService.findViewsByNames(names);
    }
}
