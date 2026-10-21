package com.tipdm.agentplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.config.FileSystemAgentLoader;
import com.tipdm.agentplatform.domain.AgentConfig;
import com.tipdm.agentplatform.domain.AgentStatus;
import com.tipdm.agentplatform.repository.AgentConfigRepository;
import com.tipdm.agentplatform.repository.FileAgentDraftRepository;
import com.tipdm.agentplatform.repository.ModelCredentialRepository;
import com.tipdm.agentplatform.repository.PlatformModelDefaultRepository;
import com.tipdm.agentplatform.repository.SkillRepository;
import com.tipdm.agentplatform.repository.ToolRepository;
import com.tipdm.agentplatform.service.credential.PlatformModelDefaultService;
import com.tipdm.agentplatform.web.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 删除数据库型Agent 时「DB 行」与「工作区目录」必须同进退。
 *
 * <p>背景（2026-10-10，K8s 测试环境实证）：{@code delete} 原实现先删 DB 行再删目录，
 * 且清盘失败被 {@code catch (IOException)} 静默吞掉，于是行没了、目录留着。残留的
 * UUID 目录会被 {@link FileSystemAgentLoader} 当成文件驱动智能体重新登记，在列表里
 * 表现为「UUID 名字 + 发布中 + 无描述 + 0 技能」的假智能体——实测累计 72 个。</p>
 *
 * <p>本测试固化三条契约：<b>①</b> 删除成功后目录必须消失；<b>②</b> 清盘失败必须抛错
 * （静默降级会让用户以为删干净了）；<b>③</b> {@code workspace_path} 为空的历史行
 * 不做目录清理——{@code resolveWorkspace} 对空值会回落到共享的 {@code agents/_default}，
 * 递归删它等于跨 Agent 误删。</p>
 */
class AgentConfigServiceDeleteWorkspaceTest {

    private final AgentConfigRepository repository = mock(AgentConfigRepository.class);
    private final com.tipdm.agentplatform.repository.AgentConfigRevisionRepository revisionRepository =
            mock(com.tipdm.agentplatform.repository.AgentConfigRevisionRepository.class);
    private final SkillRepository skillRepository = mock(SkillRepository.class);
    private final ToolRepository toolRepository = mock(ToolRepository.class);
    private final WorkspaceGenerator workspaceGenerator = mock(WorkspaceGenerator.class);
    private final AgentAssemblyService assemblyService = mock(AgentAssemblyService.class);
    private final AguiRegistryManager aguiRegistryManager = mock(AguiRegistryManager.class);
    private final FileSystemAgentLoader fileSystemAgentLoader = mock(FileSystemAgentLoader.class);
    private final SessionWorkspaceManager sessionWorkspaceManager = mock(SessionWorkspaceManager.class);
    private final TagService tagService = mock(TagService.class);

    private AgentConfigService service() {
        return new AgentConfigService(repository, revisionRepository, skillRepository, toolRepository,
                workspaceGenerator, assemblyService, aguiRegistryManager, fileSystemAgentLoader,
                sessionWorkspaceManager, tagService,
                new PlatformModelDefaultService(mock(PlatformModelDefaultRepository.class),
                        mock(ModelCredentialRepository.class)),
                new ObjectMapper(), new AgentDebugDraftStore(),
                mock(FileAgentDraftRepository.class),
                "dashscope:qwen3.8-flash");
    }

    /** 数据库型 Agent 行：有 id、有工作区路径、非文件驱动。 */
    private static AgentConfig dbAgent(UUID id, String workspacePath) {
        AgentConfig config = new AgentConfig();
        config.setId(id);
        config.setName("案例开发工具");
        config.setModel("dashscope:qwen3.8-flash");
        config.setStatus(AgentStatus.active);
        config.setWorkspacePath(workspacePath);
        config.setFileBased(false);
        return config;
    }

    /** 铺一个含 AGENTS.md 与 skills/ 的工作区目录。 */
    private static Path prepareWorkspace(@TempDir Path root, String relative) throws IOException {
        Path dir = root.resolve(relative);
        Files.createDirectories(dir.resolve("skills"));
        Files.writeString(dir.resolve("AGENTS.md"), "# 案例开发工具\n\n## 系统提示词\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("skills/s1.md"), "# s1\n", StandardCharsets.UTF_8);
        return dir;
    }

    @Test
    void deleteRemovesWorkspaceDirectoryAlongWithDbRow(@TempDir Path root) throws IOException {
        UUID id = UUID.randomUUID();
        String relative = "agents/" + id;
        Path dir = prepareWorkspace(root, relative);
        when(repository.findById(id)).thenReturn(Optional.of(dbAgent(id, relative)));
        when(workspaceGenerator.resolveWorkspace(relative)).thenReturn(dir);

        service().delete(id);

        assertTrue(Files.notExists(dir), "删除智能体后工作区目录必须一并消失，否则残留目录会被当成文件驱动智能体");
        verify(sessionWorkspaceManager).deleteRuntimeRoot(id.toString());
        verify(repository).delete(org.mockito.ArgumentMatchers.any(AgentConfig.class));
    }

    @Test
    void deleteFailsLoudlyWhenWorkspaceCleanupFails(@TempDir Path root) throws IOException {
        // 目录内放一个非空子目录冒充「删不掉的残留」：用只读父目录制造删除失败
        UUID id = UUID.randomUUID();
        String relative = "agents/" + id;
        Path dir = prepareWorkspace(root, relative);
        Path locked = Files.createDirectories(dir.resolve("locked"));
        Files.writeString(locked.resolve("x.txt"), "x", StandardCharsets.UTF_8);
        assertTrue(locked.toFile().setWritable(false), "需要制造一个删不掉的目录");

        when(repository.findById(id)).thenReturn(Optional.of(dbAgent(id, relative)));
        when(workspaceGenerator.resolveWorkspace(relative)).thenReturn(dir);

        try {
            //清盘失败必须让调用方看到「智能体未删除」，而不是静默留残留
            assertThrows(ApiException.class, () -> service().delete(id));
            verify(repository, never()).delete(org.mockito.ArgumentMatchers.any(AgentConfig.class));
        } finally {
            // 恢复权限便于 @TempDir 清理（macOS/Windows 语义差异，不假设一定成功）
            locked.toFile().setWritable(true);
            dir.toFile().setWritable(true);
            root.toFile().setWritable(true);
        }
    }

    @Test
    void deleteSkipsCleanupWhenWorkspacePathBlank(@TempDir Path root) {
        // V1 建表 workspace_path 可空、V12/V14/V19 迁移插入的 demo 行未写该列；
        // resolveWorkspace 对空值会回落到共享目录 agents/_default，绝不能去删它
        UUID id = UUID.randomUUID();
        AgentConfig config = dbAgent(id, null);
        when(repository.findById(id)).thenReturn(Optional.of(config));

        service().delete(id);

        verify(workspaceGenerator, never()).resolveWorkspace(org.mockito.ArgumentMatchers.anyString());
        verify(workspaceGenerator, never())
                .resolveWorkspace(org.mockito.ArgumentMatchers.isNull());
        verify(repository).delete(config);
        verify(sessionWorkspaceManager).deleteRuntimeRoot(id.toString());
    }

    @Test
    void fileAgentDeleteStillRejectedBeforeAnyFilesystemWork(@TempDir Path root) {
        // 回归护栏：文件驱动 Agent 没有 DB 行，delete 必须 409 且不碰磁盘
        UUID id = UUID.nameUUIDFromBytes("教材开发小助手".getBytes(StandardCharsets.UTF_8));
        when(repository.findById(id)).thenReturn(Optional.empty());
        when(fileSystemAgentLoader.get(id)).thenReturn(Optional.of(dbAgent(id, "agents/教材开发小助手")));

        ApiException e = assertThrows(ApiException.class, () -> service().delete(id));
        assertEquals(409, e.getStatus().value());
        verify(sessionWorkspaceManager, never())
                .deleteRuntimeRoot(org.mockito.ArgumentMatchers.anyString());
    }
}