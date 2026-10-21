package com.tipdm.agentplatform.config;

import com.tipdm.agentplatform.domain.AgentConfig;
import com.tipdm.agentplatform.repository.AgentConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 文件驱动智能体扫描器的准入判据。
 *
 * <p>背景（2026-10-10，K8s 测试环境实证）：原判据只看「目录名是 UUID 形态且 DB 无对应行」
 * 就登记为文件驱动智能体，而 DB 行缺失恰恰是<b>删除流程清盘失败</b>或<b>创建事务回滚</b>
 * 留下的孤儿目录——两者都会在没有 AGENTS.md 定义、或只剩半成品定义的情况下被当成智能体，
 * 以「UUID 名字 + 发布中 + 无描述 + 0 技能」的形式混进列表（实测 76 个目录里72 个是这类残留）。</p>
 *
 * <p>本测试固化三条契约：<b>①</b> UUID 形态目录 + DB 无行 → 是被删除/回滚留下的孤儿
 * 目录，拒绝加载（哪怕 AGENTS.md 完整、首行还留着当年智能体名）；<b>②</b> 人取名的目录
 * 缺 {@code AGENTS.md} → 拒绝加载；<b>③</b> DB 有行的目录 → 跳过（不得重复登记为文件驱动），
 * 且人取名目录的展示名仍是目录名（不得被 AGENTS.md 的 H1 覆盖）。</p>
 */
class FileSystemAgentLoaderAdmissionTest {

    private final AgentConfigRepository repository = mock(AgentConfigRepository.class);

    private FileSystemAgentLoader loader(Path workspaceRoot) {
        return new FileSystemAgentLoader(workspaceRoot.toString(),
                "dashscope:qwen3.8-flash", false, false, "_default,agents", repository);
    }

    private static AgentConfig byName(List<AgentConfig> configs, String name) {
        return configs.stream().filter(c -> name.equals(c.getName())).findFirst().orElse(null);
    }

    @Test
    void orphanUuidDirectoryWithoutDbRowIsNotLoaded(@TempDir Path root) throws IOException {
        // DB 行已删除、工作区残留：没有任何 AGENTS.md 也不该被当成智能体
        UUID orphan = UUID.randomUUID();
        Files.createDirectories(root.resolve("agents/" + orphan));
        when(repository.existsById(any())).thenReturn(false);

        List<AgentConfig> loaded = loader(root).loadAll();

        assertEquals(0, loaded.size(), "DB 无行的 UUID 残留目录不得登记为智能体");
    }

    @Test
    void directoryWithoutAgentsMdIsNotLoaded(@TempDir Path root) throws IOException {
        // 半成品目录：写了 skills/ 但没写 AGENTS.md（写盘中途失败的形态）
        Path half = Files.createDirectories(root.resolve("agents/半成品"));
        Files.createDirectories(half.resolve("skills"));
        when(repository.existsById(any())).thenReturn(false);

        List<AgentConfig> loaded = loader(root).loadAll();

        assertEquals(0, loaded.size(), "缺 AGENTS.md 的目录不是可运行智能体");
    }

    @Test
    void uuidDirectoryWithCompleteAgentsMdIsStillRejectedWhenDbRowGone(@TempDir Path root) throws IOException {
        // 最典型的残留形态（2026-10-10 K8s 实测 72 个全是这种）：AGENTS.md 完整、
        // 首行还留着当年的智能体名，但 DB 行已删除。它是废弃数据，不是智能体，
        // 加载它只会让用户在列表里看到一个点进去就 404 的「智能体」。
        UUID orphan = UUID.randomUUID();
        Path dir = Files.createDirectories(root.resolve("agents/" + orphan));
        Files.writeString(dir.resolve("AGENTS.md"),
                "# 案例开发工具\n\n## 系统提示词\n\n提示词\n", StandardCharsets.UTF_8);
        when(repository.existsById(any())).thenReturn(false);

        List<AgentConfig> loaded = loader(root).loadAll();

        assertEquals(0, loaded.size(), "DB 行已删除的工作区是废弃数据，即使定义文件完整也不该登记为智能体");
    }

    @Test
    void humanNamedDirectoryKeepsDirectoryNameAsDisplayName(@TempDir Path root) throws IOException {
        // 真文件型智能体：目录名才是身份，不能被AGENTS.md 里的 H1 覆盖
        // （实测「教材开发小助手」目录的 H1 写的是「编排智能体」）
        Path dir = Files.createDirectories(root.resolve("agents/教材开发小助手"));
        Files.writeString(dir.resolve("AGENTS.md"),
                "---\ntags: [教育培训]\n---\n\n# 编排智能体\n", StandardCharsets.UTF_8);
        when(repository.existsById(any())).thenReturn(false);

        List<AgentConfig> loaded = loader(root).loadAll();

        assertTrue(byName(loaded, "教材开发小助手") != null, "人取名的目录必须仍以目录名展示");
        assertTrue(byName(loaded, "编排智能体") == null, "不得用 H1 覆盖人取名目录的展示名");
        assertEquals(List.of("教育培训"), byName(loaded, "教材开发小助手").getFileTagNames(),
                "frontmatter 标签解析不应受影响");
    }

    @Test
    void databaseAgentWorkspaceIsSkippedSilently(@TempDir Path root) throws IOException {
        // 正常 DB 智能体：目录存在且 DB 有行 → 不作为文件驱动加载
        UUID id = UUID.randomUUID();
        Path dir = Files.createDirectories(root.resolve("agents/" + id));
        Files.writeString(dir.resolve("AGENTS.md"), "# 编程助手\n", StandardCharsets.UTF_8);
        when(repository.existsById(id)).thenReturn(true);

        List<AgentConfig> loaded = loader(root).loadAll();

        assertEquals(0, loaded.size(), "DB 智能体的工作区不得被重复登记为文件驱动智能体");
    }
}