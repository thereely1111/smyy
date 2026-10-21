package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.config.FileSystemAgentLoader;
import com.tipdm.agentplatform.domain.AgentConfig;
import com.tipdm.agentplatform.domain.AgentStatus;
import com.tipdm.agentplatform.repository.AgentConfigRepository;
import com.tipdm.agentplatform.repository.SkillRepository;
import com.tipdm.agentplatform.repository.ToolRepository;
import com.tipdm.agentplatform.web.dto.AgentDtos.AgentConfigRequest;
import com.tipdm.agentplatform.web.dto.AgentDtos.AgentConfigResponse;
import com.tipdm.agentplatform.web.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * <p>背景（2026-09-17）：文件型 Agent（{@code workspace/agents/<名称>/}）没有数据库配置行，
 * 详情接口此前只查库 → 404，而前端编辑页缺少 .catch，把「加载失败」渲染成了一张空表单，
 * 看起来像配置被清空；同时列表页的启停/删除按钮点了也是 404。</p>
 *
 * <p>本测试固化两点契约：<b>能看</b>（详情接口走 DB → 文件兜底，并回传定义区快照）、
 * <b>不能改</b>（update / updateStatus / delete 一律 409，且绝不落库、绝不生成工作区目录
 * ——否则会在 {@code agents/<uuid>/} 下凭空多出一份与定义区并存的配置）。</p>
 */
class AgentConfigServiceFileBasedGuardTest {

    /** 与 FileSystemAgentLoader 一致的 id 推导：目录名的 UUIDv3。 */
    private static final UUID FILE_AGENT_ID =
            UUID.nameUUIDFromBytes("教材开发小助手".getBytes(StandardCharsets.UTF_8));

    private static final String DEFINITION_PATH = "agents/教材开发小助手";

    private final AgentConfigRepository repository = mock(AgentConfigRepository.class);
    private final SkillRepository skillRepository = mock(SkillRepository.class);
    private final ToolRepository toolRepository = mock(ToolRepository.class);
    private final WorkspaceGenerator workspaceGenerator = mock(WorkspaceGenerator.class);
    private final AgentAssemblyService assemblyService = mock(AgentAssemblyService.class);
    private final AguiRegistryManager aguiRegistryManager = mock(AguiRegistryManager.class);
    private final FileSystemAgentLoader fileSystemAgentLoader = mock(FileSystemAgentLoader.class);
    private final SessionWorkspaceManager sessionWorkspaceManager = mock(SessionWorkspaceManager.class);
    private final com.tipdm.agentplatform.service.TagService tagService =
            mock(com.tipdm.agentplatform.service.TagService.class);

    private AgentConfigService service() {
        return new AgentConfigService(repository,
                // 版本存档仓库（V58 发布快照模型）：文件型 Agent 走不到写路径，空 mock 即可
                org.mockito.Mockito.mock(com.tipdm.agentplatform.repository.AgentConfigRevisionRepository.class),
                skillRepository, toolRepository, workspaceGenerator,
                assemblyService, aguiRegistryManager, fileSystemAgentLoader, sessionWorkspaceManager,
                tagService,
                // 空仓库 mock → get() 返回 empty → effectiveModelString 回落配置兜底值
                new com.tipdm.agentplatform.service.credential.PlatformModelDefaultService(
                        org.mockito.Mockito.mock(com.tipdm.agentplatform.repository.PlatformModelDefaultRepository.class),
                        org.mockito.Mockito.mock(com.tipdm.agentplatform.repository.ModelCredentialRepository.class)),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new AgentDebugDraftStore(),
                org.mockito.Mockito.mock(com.tipdm.agentplatform.repository.FileAgentDraftRepository.class),
                "dashscope:qwen3.8-flash");
    }

    /** 内存中的文件型 Agent（模拟 FileSystemAgentLoader 扫描目录的产物）。 */
    private static AgentConfig fileAgent() {
        AgentConfig config = new AgentConfig();
        config.setId(FILE_AGENT_ID);
        config.setName("教材开发小助手");
        config.setWorkspacePath(DEFINITION_PATH);
        config.setModel("dashscope:qwen3.8-flash");
        config.setStatus(AgentStatus.active);
        config.setFileBased(true);
        return config;
    }

    /** 请求 DTO 已无 model 字段（2026-09-22 主智能体模型平台化）。 */
    private static AgentConfigRequest request(String name) {
        return new AgentConfigRequest(name, null, null, AgentStatus.active,
                List.of(), List.of(), List.of(), List.of(), false, false, null, null, null, null);
    }

    /** 在临时根下铺一个定义区：2 个技能目录 + 2 个子智能体 + AGENTS.md。 */
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
        Path dir = prepareDefinition(workspaceRoot);
        when(repository.findById(FILE_AGENT_ID)).thenReturn(Optional.empty());
        when(fileSystemAgentLoader.get(FILE_AGENT_ID)).thenReturn(Optional.of(fileAgent()));
        when(workspaceGenerator.resolveWorkspace(DEFINITION_PATH)).thenReturn(dir);

        AgentConfigResponse response = service().get(FILE_AGENT_ID);

        assertEquals("教材开发小助手", response.name());
        assertTrue(response.fileBased(), "文件型 Agent 必须带 fileBased 标记，前端据此切只读视图");
        assertNotNull(response.definition(), "文件型详情必须回传定义区快照，否则只读页无从展示");
        assertEquals(DEFINITION_PATH, response.definition().path());
        assertEquals(2, response.definition().skillsCount());
        assertEquals(2, response.definition().subagentsCount(), "subagents 只数 .md，.DS_Store 不计");
        assertTrue(response.definition().agentsMdBytes() > 0);
        assertNotNull(response.definition().agentsMdUpdatedAt());
    }

    @Test
    void definitionSnapshotIsOmittedForDatabaseAgent() {
        UUID id = UUID.randomUUID();
        AgentConfig config = new AgentConfig();
        config.setId(id);
        config.setName("图书创作助手");
        config.setWorkspacePath("agents/" + id);
        config.setModel("dashscope:qwen-plus");
        when(repository.findById(id)).thenReturn(Optional.of(config));

        AgentConfigResponse response = service().get(id);

        assertNotNull(response, "数据库 Agent 仍走原路径");
        assertNull(response.definition(), "数据库 Agent 不返回定义区快照");
    }

    @Test
    void writeOperationsOnFileAgentAreRejectedAndTouchNothing() {
        when(repository.findById(FILE_AGENT_ID)).thenReturn(Optional.empty());
        when(fileSystemAgentLoader.get(FILE_AGENT_ID)).thenReturn(Optional.of(fileAgent()));
        AgentConfigService service = service();

        ApiException onUpdate = assertThrows(ApiException.class,
                () -> service.update(FILE_AGENT_ID, request("改个名"), "tester"));
        assertEquals(HttpStatus.CONFLICT, onUpdate.getStatus());
        assertTrue(onUpdate.getMessage().contains("文件驱动"), "错误文案要指向真正该改的地方");

        ApiException onStatus = assertThrows(ApiException.class,
                () -> service.updateStatus(FILE_AGENT_ID, AgentStatus.draft, "tester"));
        assertEquals(HttpStatus.CONFLICT, onStatus.getStatus());

        ApiException onDelete = assertThrows(ApiException.class, () -> service.delete(FILE_AGENT_ID));
        assertEquals(HttpStatus.CONFLICT, onDelete.getStatus());

        verify(repository, never()).save(any());
        verify(repository, never()).delete(any());
        // 关键：不能生成 agents/{uuid}/，否则定义区与生成区两份配置并存
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
}
