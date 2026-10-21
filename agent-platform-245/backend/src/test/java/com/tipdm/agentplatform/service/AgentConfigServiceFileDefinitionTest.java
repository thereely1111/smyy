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
import com.tipdm.agentplatform.web.dto.AgentDtos.FileAgentEditView;
import com.tipdm.agentplatform.web.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 定义内容编辑入口对**本次进程内新建**的智能体同样可解析。
 *
 * <p>回归（2026-10-18）：在线配置新建智能体后立刻进编辑页，「定义内容」整块渲染成
 * 「智能体目录加载失败，请刷新重试」。
 *
 * <p>根因不在磁盘——配置行与 {@code agents/{id}/AGENTS.md} 都已在创建时就绪——而在
 * 解析入口选了<b>只查目录扫描缓存</b>的路径。该缓存只在启动与显式重扫时建立，本次进程内
 * 新建的智能体必然不在其中，于是被判定为「不存在」返回 404；而同一智能体的配置侧详情
 * （走库优先的解析）一切正常，症状看起来就像「目录丢了」。因此本测试钉住两条：
 * <ul>
 *   <li>库里有行、缓存里没有 ⇒ 读得到（新建后立即可改，不必重启）；</li>
 *   <li>库里没有、缓存里也没有 ⇒ 仍按 404 拒绝（不要把未知 id 当成空模板放行，
 *       否则前端会把「不存在」渲染成一张可保存的空定义）。</li>
 * </ul>
 */
class AgentConfigServiceFileDefinitionTest {

    private static final UUID AGENT_ID = UUID.fromString("afa2a32e-18ea-42d7-9561-64881e7da539");
    private static final String DEFINITION_PATH = "agents/" + AGENT_ID;

    private final AgentConfigRepository repository = mock(AgentConfigRepository.class);
    private final AgentConfigRevisionRepository revisionRepository =
            mock(AgentConfigRevisionRepository.class);
    private final DirAgentDraftRepository draftRepository = mock(DirAgentDraftRepository.class);
    private final AgentDirScanner scanner = mock(AgentDirScanner.class);

    private AgentConfigService service(Path workspaceRoot) {
        return new AgentConfigService(repository,
                revisionRepository,
                mock(SkillRepository.class),
                mock(ToolRepository.class),
                mock(WorkspaceGenerator.class),
                mock(AgentAssemblyService.class),
                mock(AguiRegistryManager.class),
                scanner,
                // 真实 resolver：断言读到的就是 agents/{id}/ 这一处，与装配侧同一口径
                new SessionWorkspaceManager(workspaceRoot.toString()),
                mock(TagService.class),
                new PlatformModelDefaultService(
                        mock(PlatformModelDefaultRepository.class),
                        mock(ModelCredentialRepository.class)),
                new ObjectMapper(),
                new AgentDebugDraftStore(),
                draftRepository,
                "dashscope:qwen3.8-flash");
    }

    /** 刚由在线配置创建出来的智能体：库里已有行，目录已落盘，但扫描缓存尚未重扫。 */
    private static AgentConfig justCreated() {
        AgentConfig config = new AgentConfig();
        config.setId(AGENT_ID);
        config.setName("测试");
        config.setWorkspacePath(DEFINITION_PATH);
        config.setModel("dashscope:qwen3.8-flash");
        config.setStatus(AgentStatus.draft);
        return config;
    }

    @Test
    void readsDefinitionForAgentCreatedInThisProcess(@TempDir Path workspaceRoot) throws IOException {
        Path dir = Files.createDirectories(workspaceRoot.resolve(DEFINITION_PATH));
        Files.writeString(dir.resolve("AGENTS.md"), "# 测试\n\n神神叨叨\n", StandardCharsets.UTF_8);
        Files.createDirectories(dir.resolve("subagents"));
        Files.writeString(dir.resolve("subagents/审稿.md"), "---\nmode: subagent\n---\n",
                StandardCharsets.UTF_8);

        when(repository.findById(AGENT_ID)).thenReturn(Optional.of(justCreated()));
        // 关键前提：缓存里没有它（创建不触发重扫，缓存仍是启动时那份）
        when(scanner.get(AGENT_ID)).thenReturn(Optional.empty());

        FileAgentEditView view = service(workspaceRoot).readFileForEdit(AGENT_ID);

        assertEquals("# 测试\n\n神神叨叨\n", view.agentsMd(),
                "新建后立刻进编辑页必须读到磁盘上的 AGENTS.md，而不是 404");
        assertEquals(1, view.subagents().size());
        assertTrue(view.subagents().containsKey("审稿.md"));
        assertFalse(view.hasDraft(), "刚创建、未保存过草稿");
    }

    @Test
    void rejectsUnknownAgentWithNotFound(@TempDir Path workspaceRoot) {
        when(repository.findById(AGENT_ID)).thenReturn(Optional.empty());
        when(scanner.get(AGENT_ID)).thenReturn(Optional.empty());

        ApiException e = assertThrows(ApiException.class,
                () -> service(workspaceRoot).readFileForEdit(AGENT_ID));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatus(),
                "两个来源都没有就是不存在——不能放行成空模板（用户会以为定义被清空）");
    }
}
