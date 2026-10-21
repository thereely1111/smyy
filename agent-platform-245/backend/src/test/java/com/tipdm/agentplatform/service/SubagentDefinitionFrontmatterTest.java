package com.tipdm.agentplatform.service;

import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 文件型子智能体定义（{@code subagents/*.md}）必须能被框架自己的加载器正确解析。
 *
 * <p>为什么需要这层验证：文件型智能体不经过平台声明（{@code AgentAssemblyService} 对
 * {@code fileBased} 走 {@code List.of()}），子智能体的 frontmatter 就是<b>唯一</b>的运行时契约。
 * 两处踩过的坑：</p>
 * <ul>
 *   <li>{@code steps} 默认只有 10——实测子智能体在写完第二个产物前就耗尽迭代
 *       （2026-09-11 自检：目录结构.json 因迭代上限未写出）。平台侧的
 *       {@code agent-platform.max-iters} 只作用于主智能体，传不到文件型子智能体。</li>
 *   <li>{@code workspace.mode: shared} 是子智能体与父共用同一后端（同根、各自会话命名空间）
 *       的前提；漏写会退化成 ISOLATED，产物落进 {@code .harness/agents/<name>/workspace}。</li>
 * </ul>
 */
class SubagentDefinitionFrontmatterTest {

    private static Path subagentsDir() {
        Path root = Path.of(System.getProperty("user.dir"))
                .resolve("../workspace/agents/教材开发小助手/subagents").normalize();
        return Files.isDirectory(root) ? root : null;
    }

    @Test
    void everySubagentDeclarationParsesWithPlatformBudgets() throws IOException {
        Path dir = subagentsDir();
        assumeTrue(dir != null, "样例智能体目录不存在，跳过");

        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .toList();
        }
        assertFalse(files.isEmpty(), "未找到任何子智能体定义");

        for (Path file : files) {
            SubagentDeclaration decl = AgentSpecLoader.loadFromFile(file, null);
            String name = file.getFileName().toString().replace(".md", "");
            assertTrue(decl.getSteps() >= 50,
                    name + " 的 steps=" + decl.getSteps() + " 偏小，子任务会在产物写完前耗尽迭代");
            assertEquals(WorkspaceMode.SHARED, decl.getWorkspaceMode(),
                    name + " 必须是 shared：与父共用后端、各自会话命名空间");
            assertTrue(decl.getTools() != null && !decl.getTools().isEmpty(),
                    name + " 未声明工具白名单");
            assertFalse(decl.getTools().contains("agent_spawn"),
                    name + " 不应拥有 agent_spawn（子智能体不得再派生）");
        }
    }
}
