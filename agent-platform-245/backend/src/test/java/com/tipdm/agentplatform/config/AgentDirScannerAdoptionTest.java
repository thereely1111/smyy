package com.tipdm.agentplatform.config;

import com.tipdm.agentplatform.domain.AgentConfig;
import com.tipdm.agentplatform.repository.AgentConfigRepository;
import com.tipdm.agentplatform.util.AgentDirId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 智能体目录收编为配置记录（迁移 M3 自愈）。
 *
 * <p><b>本测试钉住迁移最关键的不变量：id 不得漂移。</b>智能体目录名决定 agent id
 * （{@link AgentDirId}），而会话按 id 分区、版本存档与标签关联都挂在 id 上。id 一旦
 * 对不上，历史会话会串到别的智能体上，且这种错误在界面上完全不可见——只能靠测试拦。
 *
 * <p>另一条边界：<b>UUID 形态目录不得被收编</b>。UUID 目录 + DB 无行是「已删除 agent 的
 * 残留」（K8s 实测 76 个目录里 72 个是这类），收编它们会让用户看到一堆点进去 404 的
 * 「智能体」。只有人取名的目录才是真正的智能体目录智能体。
 */
class AgentDirScannerAdoptionTest {

    private final AgentConfigRepository repository = mock(AgentConfigRepository.class);

    private AgentDirScanner loader(Path workspaceRoot) {
        return new AgentDirScanner(workspaceRoot.toString(),
                "dashscope:qwen3.8-flash", false, false, "_default,agents", repository);
    }

    @Test
    void adoptedRowKeepsDirectoryDerivedId(@TempDir Path root) throws IOException {
        Path dir = Files.createDirectories(root.resolve("agents/旅行规划师"));
        Files.writeString(dir.resolve("AGENTS.md"), "# 旅行规划师\n", StandardCharsets.UTF_8);
        when(repository.findById(any())).thenReturn(Optional.empty());

        loader(root).loadAll();

        ArgumentCaptor<AgentConfig> captor = ArgumentCaptor.forClass(AgentConfig.class);
        verify(repository).save(captor.capture());
        AgentConfig saved = captor.getValue();
        assertEquals(AgentDirId.of("旅行规划师"), saved.getId(),
                "补行必须沿用目录名的 v3 哈希——重新生成会让历史会话失联");
        assertEquals("agents/旅行规划师", saved.getWorkspacePath());
        assertEquals("旅行规划师", saved.getName());
    }

    @Test
    void adoptionIdMatchesImportServiceDerivation(@TempDir Path root) throws IOException {
        // 与导入服务必须算出同一个 id：同名的「导入的 agent」与「文件型 agent」
        // 若算出两个 id，收编后会变成两个智能体
        Path dir = Files.createDirectories(root.resolve("agents/数据分析助手"));
        Files.writeString(dir.resolve("AGENTS.md"), "# 数据分析助手\n", StandardCharsets.UTF_8);
        when(repository.findById(any())).thenReturn(Optional.empty());

        List<AgentConfig> loaded = loader(root).loadAll();

        assertEquals(1, loaded.size());
        assertEquals(AgentDirId.of("数据分析助手"), loaded.get(0).getId());
    }

    @Test
    void existingRowIsNotOverwritten(@TempDir Path root) throws IOException {
        // 已有行 ⇒ 只在路径不一致时对齐 workspace_path，不覆盖用户在配置侧做过的修改
        UUID id = AgentDirId.of("会议纪要助手");
        Path dir = Files.createDirectories(root.resolve("agents/会议纪要助手"));
        Files.writeString(dir.resolve("AGENTS.md"), "# 会议纪要助手\n", StandardCharsets.UTF_8);

        AgentConfig row = new AgentConfig();
        row.setId(id);
        row.setName("我的会议纪要助手");       // 用户改过的展示名
        row.setDescription("用户写过的描述");
        row.setWorkspacePath("agents/会议纪要助手"); // 路径一致 ⇒ 不需对齐
        when(repository.findById(id)).thenReturn(Optional.of(row));

        loader(root).loadAll();

        verify(repository, never()).save(any());
        assertEquals("我的会议纪要助手", row.getName(), "补行不得覆盖用户改过的配置");
    }

    @Test
    void existingRowWithStalePathIsAligned(@TempDir Path root) throws IOException {
        // 目录被重命名/迁入：路径不一致时对齐，否则 resolveAgentDefinitionDir
        // 会指向不存在的目录
        UUID id = AgentDirId.of("新名字");
        Path dir = Files.createDirectories(root.resolve("agents/新名字"));
        Files.writeString(dir.resolve("AGENTS.md"), "# 新名字\n", StandardCharsets.UTF_8);

        AgentConfig row = new AgentConfig();
        row.setId(id);
        row.setName("保留的展示名");
        row.setWorkspacePath("agents/旧名字");
        when(repository.findById(id)).thenReturn(Optional.of(row));

        loader(root).loadAll();

        assertEquals("agents/新名字", row.getWorkspacePath(), "过期路径必须对齐");
        assertEquals("保留的展示名", row.getName(), "对齐路径不得顺带改展示名");
    }

    @Test
    void orphanedUuidDirectoryIsNotAdopted(@TempDir Path root) throws IOException {
        // 智能体配置已删除的残留目录：即使 AGENTS.md 完整也不得补行
        UUID orphan = UUID.randomUUID();
        Path dir = Files.createDirectories(root.resolve("agents/" + orphan));
        Files.writeString(dir.resolve("AGENTS.md"), "# 残留\n", StandardCharsets.UTF_8);
        when(repository.existsById(orphan)).thenReturn(false);
        when(repository.findById(any())).thenReturn(Optional.empty());

        List<AgentConfig> loaded = loader(root).loadAll();

        assertEquals(0, loaded.size(), "UUID 残留目录不是智能体");
        verify(repository, never()).save(any());
    }

    @Test
    void directoryWithoutAgentsMdIsNotAdopted(@TempDir Path root) throws IOException {
        // 半成品目录（写盘中途失败）不得被补行
        Files.createDirectories(root.resolve("agents/半成品/skills"));
        when(repository.findById(any())).thenReturn(Optional.empty());

        List<AgentConfig> loaded = loader(root).loadAll();

        assertEquals(0, loaded.size());
        verify(repository, never()).save(any());
    }

    @Test
    void adoptionFailureDoesNotBlockScanning(@TempDir Path root) throws IOException {
        // 补行失败不能中断扫描：一个目录的写冲突不该让所有智能体目录智能体都加载不出来
        Path dir = Files.createDirectories(root.resolve("agents/教材开发小助手"));
        Files.writeString(dir.resolve("AGENTS.md"),
                "---\ntags: [教育培训]\n---\n\n# 编排智能体\n", StandardCharsets.UTF_8);
        when(repository.findById(any())).thenThrow(new IllegalStateException("写库失败"));

        List<AgentConfig> loaded = loader(root).loadAll();

        assertEquals(1, loaded.size(), "补行失败时该目录仍应能以只读形态加载");
        assertEquals("教材开发小助手", loaded.get(0).getName());
        assertEquals(List.of("教育培训"), loaded.get(0).getFileTagNames(),
                "frontmatter 标签仍应解析（M1 要把它写进 agent_tags）");
    }

    @Test
    void existingAgentRowIsNotAdoptedTwice(@TempDir Path root) throws IOException {
        // 表单创建的智能体装载后不得被补登记——配置行已存在，
        // 再 save 一次会撞主键，且可能把它自己的名称/字段覆盖成目录名。
        UUID id = UUID.randomUUID();
        Path dir = Files.createDirectories(root.resolve("agents/" + id));
        Files.writeString(dir.resolve("AGENTS.md"), "# 编程助手\n", StandardCharsets.UTF_8);
        when(repository.existsById(id)).thenReturn(true);
        AgentConfig row = new AgentConfig();
        row.setId(id);
        row.setName("编程助手");
        row.setWorkspacePath("agents/" + id);
        when(repository.findById(id)).thenReturn(Optional.of(row));

        List<AgentConfig> loaded = loader(root).loadAll();

        assertEquals(1, loaded.size());
        verify(repository, never()).save(any());
    }

    @Test
    void frontmatterTagsParsedBeforeAdoption(@TempDir Path root) throws IOException {
        // M1 要把 frontmatter 标签写进 agent_tags，因此解析必须早于补行
        Path dir = Files.createDirectories(root.resolve("agents/旅行规划师"));
        Files.writeString(dir.resolve("AGENTS.md"),
                "---\ntags: [生活助手]\n---\n\n# 旅行规划师\n", StandardCharsets.UTF_8);
        when(repository.findById(any())).thenReturn(Optional.empty());

        List<AgentConfig> loaded = loader(root).loadAll();

        assertFalse(loaded.isEmpty());
        assertEquals(List.of("生活助手"), loaded.get(0).getFileTagNames());
        assertTrue(loaded.get(0).getFileTagNames() != null
                && !loaded.get(0).getFileTagNames().isEmpty(),
                "标签必须在补行前解析出来，否则 M1 无从写入 agent_tags");
    }
}