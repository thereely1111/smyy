package com.tipdm.agentplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.config.AgentDirScanner;
import com.tipdm.agentplatform.domain.AgentConfig;
import com.tipdm.agentplatform.domain.AgentStatus;
import com.tipdm.agentplatform.repository.AgentConfigRepository;
import com.tipdm.agentplatform.repository.AgentConfigRevisionRepository;
import com.tipdm.agentplatform.repository.DirAgentDraftRepository;
import com.tipdm.agentplatform.repository.ModelCredentialRepository;
import com.tipdm.agentplatform.repository.PlatformModelDefaultRepository;
import com.tipdm.agentplatform.repository.SkillRepository;
import com.tipdm.agentplatform.repository.ToolRepository;
import com.tipdm.agentplatform.service.credential.PlatformModelDefaultService;
import com.tipdm.agentplatform.web.dto.AgentDtos.AgentConfigRequest;
import com.tipdm.agentplatform.web.dto.AgentDtos.AgentConfigResponse;
import com.tipdm.agentplatform.web.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件驱动 Agent 在管理端只读。
 *
 * <p>背景（2026-09-17）：文件型 Agent（{@code workspace/agents/<名称>/}）没有智能体配置，
 * 详情接口此前只查库 → 404，而前端编辑页缺少 .catch，把「加载失败」渲染成了一张空表单，
 * 看起来像配置被清空；同时列表页的启停/删除按钮点了也是 404。</p>
 *
 * <p>本测试固化两点契约：<b>能看</b>（详情接口走 DB → 文件兜底，并回传智能体目录快照）、
 * <b>不能改</b>（update / updateStatus / delete 一律 409，且绝不落库、绝不生成工作区目录
 * ——否则会在 {@code agents/<uuid>/} 下凭空多出一份与智能体目录并存的配置）。</p>
 */
class AgentConfigServiceFileBasedGuardTest {

    /** 与 AgentDirScanner 一致的 id 推导：目录名的 UUIDv3。 */
    private static final UUID FILE_AGENT_ID =
            UUID.nameUUIDFromBytes("教材开发小助手".getBytes(StandardCharsets.UTF_8));

    private static final String DEFINITION_PATH = "agents/教材开发小助手";

    private final AgentConfigRepository repository = mock(AgentConfigRepository.class);
    private final SkillRepository skillRepository = mock(SkillRepository.class);
    private final ToolRepository toolRepository = mock(ToolRepository.class);
    private final WorkspaceGenerator workspaceGenerator = mock(WorkspaceGenerator.class);
    private final AgentAssemblyService assemblyService = mock(AgentAssemblyService.class);
    private final AguiRegistryManager aguiRegistryManager = mock(AguiRegistryManager.class);
    private final AgentDirScanner fileSystemAgentLoader = mock(AgentDirScanner.class);
    private final SessionWorkspaceManager sessionWorkspaceManager = mock(SessionWorkspaceManager.class);
    private final TagService tagService =
            mock(TagService.class);

    private AgentConfigService service() {
        return service(null);
    }

    /**
     * 带真实 workspace-root 的工厂。
     *
     * <p>智能体目录快照现在对所有 agent 都读（不再按类型跳过），且与装配侧共用
     * {@code SessionWorkspaceManager.resolveAgentDefinitionDir}——因此需要能解析到真实
     * 目录的 manager，共享 mock 满足不了。传 null 时退回共享 mock（适用于不触碰
     * 智能体目录快照的用例）。
     */
    private AgentConfigService service(Path root) {
        SessionWorkspaceManager manager = root == null
                ? sessionWorkspaceManager
                : new SessionWorkspaceManager(root.toString());
        return new AgentConfigService(repository,
                // 版本存档仓库（V58 发布快照模型）：文件型 Agent 走不到写路径，空 mock 即可
                Mockito.mock(AgentConfigRevisionRepository.class),
                skillRepository, toolRepository, workspaceGenerator,
                assemblyService, aguiRegistryManager, fileSystemAgentLoader, manager,
                tagService,
                // 空仓库 mock → get() 返回 empty → effectiveModelString 回落配置兜底值
                new PlatformModelDefaultService(
                        Mockito.mock(PlatformModelDefaultRepository.class),
                        Mockito.mock(ModelCredentialRepository.class)),
                new ObjectMapper(),
                new AgentDebugDraftStore(),
                Mockito.mock(DirAgentDraftRepository.class),
                "dashscope:qwen3.8-flash");
    }

    /** 内存中的文件型 Agent（模拟 AgentDirScanner 扫描目录的产物）。 */
    private static AgentConfig fileAgent() {
        AgentConfig config = new AgentConfig();
        config.setId(FILE_AGENT_ID);
        config.setName("教材开发小助手");
        config.setWorkspacePath(DEFINITION_PATH);
        config.setModel("dashscope:qwen3.8-flash");
        config.setStatus(AgentStatus.active);
        return config;
    }

    /** 请求 DTO 已无 model 字段（2026-09-22 主智能体模型平台化）。 */
    private static AgentConfigRequest request(String name) {
        return new AgentConfigRequest(name, null, null, AgentStatus.active,
                List.of(), List.of(), List.of(), null, List.of(), false, false, null, null, null, null);
    }

    /** 在临时根下铺一个智能体目录：2 个技能目录 + 2 个子智能体 + AGENTS.md。 */
    private static Path prepareDefinition(@TempDir Path workspaceRoot) throws IOException {
        Path dir = workspaceRoot.resolve(DEFINITION_PATH);
        Files.createDirectories(dir.resolve("skills/jc-0-路径工具"));
        Files.createDirectories(dir.resolve("skills/jc-1-目录生成"));
        Files.createDirectories(dir.resolve("subagents"));
        Files.writeString(dir.resolve("AGENTS.md"), "# 编排智能体\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("subagents/审核智能体.md"), "---\nmode: subagent\n---\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("subagents/章节生成智能体.md"), "---\nmode: subagent\n---\n", StandardCharsets.UTF_8);
        // 干扰项：非 .md 文件不计入子智能体数
        Files.writeString(dir.resolve("subagents/.DS_Store"), "x", StandardCharsets.UTF_8);
        return dir;
    }

    @Test
    void detailResolvesFileAgentAndReturnsDefinitionSnapshot(@TempDir Path workspaceRoot) throws IOException {
        prepareDefinition(workspaceRoot);
        when(repository.findById(FILE_AGENT_ID)).thenReturn(Optional.empty());
        when(fileSystemAgentLoader.get(FILE_AGENT_ID)).thenReturn(Optional.of(fileAgent()));

        // 走真实的 SessionWorkspaceManager（与装配侧同一个 resolver）：断言「读快照」
        // 与「装配读智能体目录」指向同一处，而不是各 mock 各的。
        AgentConfigResponse response = service(workspaceRoot).get(FILE_AGENT_ID);

        assertEquals("教材开发小助手", response.name());
        assertFalse(response.configEditable(),
                "仅由智能体目录扫描得来的智能体在配置侧没有行，前端据此切只读视图"
                        + "（字段语义已从「来源类型」改为「配置侧是否有配置记录」，"
                        + "file_based 列随重构删除）");
        assertNotNull(response.definition(), "文件型详情必须回传智能体目录快照，否则只读页无从展示");
        assertEquals(DEFINITION_PATH, response.definition().path());
        assertEquals(2, response.definition().skillsCount());
        assertEquals(2, response.definition().subagentsCount(), "subagents 只数 .md，.DS_Store 不计");
        assertTrue(response.definition().agentsMdBytes() > 0);
        assertNotNull(response.definition().agentsMdUpdatedAt());
    }

    /**
     * 数据库 Agent 同样有智能体目录（{@code workspacePath} 指向 {@code agents/{id}}），
     * 因此<b>也要</b>回传智能体目录快照——能力统计（子智能体 / 技能数）的真相源在磁盘，
     * 只看 DB 字段会显示 0/0。此前的「DB agent 不带快照」语义属类型二分的残留，
     * 重构后不再成立。
     */
    @Test
    void definitionSnapshotIsAlsoReturnedForDatabaseAgent(@TempDir Path root) throws IOException {
        UUID id = UUID.randomUUID();
        Path dir = Files.createDirectories(root.resolve("agents/" + id));
        Files.writeString(dir.resolve("AGENTS.md"), "# 图书创作助手\n", StandardCharsets.UTF_8);
        Files.createDirectories(dir.resolve("subagents"));
        Files.writeString(dir.resolve("subagents").resolve("审稿.md"), "---\ndescription: 审稿。\n---\n\n正文。",
                StandardCharsets.UTF_8);

        AgentConfig config = new AgentConfig();
        config.setId(id);
        config.setName("图书创作助手");
        config.setWorkspacePath("agents/" + id);
        config.setModel("dashscope:qwen-plus");
        when(repository.findById(id)).thenReturn(Optional.of(config));

        AgentConfigResponse response = service(root).get(id);

        assertNotNull(response, "数据库 Agent 仍走原路径");
        assertNotNull(response.definition(), "数据库 Agent 同样要回传智能体目录快照");
        assertEquals(1, response.definition().subagentsCount(),
                "智能体目录的子智能体数量必须如实反映，不能因有 智能体配置而被忽略");
    }

    /**
     * 没有智能体目录的 智能体配置（{@code workspacePath} 为空）不返回快照，也不报错——
     * 存量数据里存在这种行，读取路径不得因此抛异常。
     */
    @Test
    void definitionSnapshotIsNullWhenWorkspacePathMissing(@TempDir Path root) {
        UUID id = UUID.randomUUID();
        AgentConfig config = new AgentConfig();
        config.setId(id);
        config.setName("未绑定目录的老配置");
        config.setModel("dashscope:qwen-plus");
        when(repository.findById(id)).thenReturn(Optional.of(config));

        AgentConfigResponse response = service(root).get(id);

        assertNotNull(response);
        assertNull(response.definition());
    }

    @Test
    void writeOperationsOnDirOnlyAgentAreRejectedAndTouchNothing() {
        when(repository.findById(FILE_AGENT_ID)).thenReturn(Optional.empty());
        when(fileSystemAgentLoader.get(FILE_AGENT_ID)).thenReturn(Optional.of(fileAgent()));
        AgentConfigService service = service();

        ApiException onUpdate = assertThrows(ApiException.class,
                () -> service.update(FILE_AGENT_ID, request("改个名"), "tester"));
        assertEquals(HttpStatus.CONFLICT, onUpdate.getStatus());
        assertTrue(onUpdate.getMessage().contains("智能体目录"), "错误文案要指向真正该改的地方");

        ApiException onStatus = assertThrows(ApiException.class,
                () -> service.updateStatus(FILE_AGENT_ID, AgentStatus.draft, "tester"));
        assertEquals(HttpStatus.CONFLICT, onStatus.getStatus());

        ApiException onDelete = assertThrows(ApiException.class, () -> service.delete(FILE_AGENT_ID));
        assertEquals(HttpStatus.CONFLICT, onDelete.getStatus());

        verify(repository, never()).save(any());
        verify(repository, never()).delete(any());
        // 关键：不能生成 agents/{uuid}/，否则智能体目录与生成区两份配置并存
        verify(workspaceGenerator, never()).generate(any(), any(), any());
    }

    @Test
    void unknownIdStillReportsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        when(fileSystemAgentLoader.get(id)).thenReturn(Optional.empty());

        ApiException e = assertThrows(ApiException.class, () -> service().get(id));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus(), "真不存在的 id 不能被 409 掩盖");
    }

    @Test
    void databaseAgentRemainsEditable() {
        UUID id = UUID.randomUUID();
        AgentConfig config = new AgentConfig();
        config.setId(id);
        config.setName("图书创作助手");
        config.setWorkspacePath("agents/" + id);
        config.setModel("dashscope:qwen-plus");
        when(repository.findById(id)).thenReturn(Optional.of(config));
        when(workspaceGenerator.generate(any(), any(), any())).thenReturn("agents/" + id);

        AgentConfigResponse response = service().update(id, request("新的名字"), "tester");

        assertEquals("新的名字", response.name());
        verify(repository, atLeastOnce()).save(any());
    }

    @Test
    void detailAndListAgreeOnConfigEditableForDirectoryAgentWithDatabaseRow() {
        // 缺陷形态：智能体目录缺失自愈会给每个目录补一条 agent_configs 记录，
        // 于是「DB 里有没有这一行」不再能区分两类智能体。若详情路径据此判定可编辑、
        // 列表路径按「在目录扫描结果里」判定不可编辑，同一智能体在两处得到相反答案，
        // 且两处各自自洽、不报任何错。
        when(fileSystemAgentLoader.get(FILE_AGENT_ID)).thenReturn(Optional.of(fileAgent()));
        when(fileSystemAgentLoader.getAll()).thenReturn(List.of(fileAgent()));
        // ⚠️ 必须让 existsById 也返回 true —— 缺陷就出在「有 DB 行」这个前提下
        when(repository.existsById(FILE_AGENT_ID)).thenReturn(true);
        when(repository.findById(FILE_AGENT_ID)).thenReturn(Optional.of(fileAgent()));
        when(repository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(fileAgent()), org.springframework.data.domain.Pageable.ofSize(10), 1));

        boolean editableByDetail = service().get(FILE_AGENT_ID).configEditable();
        boolean editableByList = service()
                .list(null, null, 0, 10)
                .content().get(0).configEditable();

        assertFalse(editableByDetail, "由智能体目录定义的智能体，详情接口不应判为可编辑");
        assertEquals(editableByList, editableByDetail,
                "列表与详情对同一智能体的 configEditable 必须一致");
    }
}
