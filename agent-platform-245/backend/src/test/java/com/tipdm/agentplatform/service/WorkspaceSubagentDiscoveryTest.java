package com.tipdm.agentplatform.service;

import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 框架「工作区根 → subagents/ 自动发现」能力的契约验证。
 *
 * <p>背景：平台把 {@code builder.workspace(...)} 指向 {@code agents/{agentId}/}（智能体目录）而非
 * {@code runtimes/{agentId}/.harness}。框架的 {@code DynamicSubagentsMiddleware} 分两层加载子
 * agent声明：Layer 2 直接读 {@code mainWorkspace.resolve("subagents")}（本地磁盘），Layer 1 走
 * {@code filesystem.glob}（OverlayFilesystem，只读下层兜底）。本测试锁定 Layer 2 这一条——它决定
 * 「在线装配落盘的 subagents/*.md 能否被框架发现」，是智能体目录落盘方案的地基。
 *
 * <p>另锁定 frontmatter 字段（tools / steps / workspace.mode）能被解析：丢失 {@code tools} 意味着
 * 子智能体工具白名单失效，属于权限面变宽，须有回归防护。
 */
class WorkspaceSubagentDiscoveryTest {

    @TempDir
    Path workspaceRoot;

    private Path writeSubagent(String fileName, String frontmatter) throws IOException {
        Path dir = workspaceRoot.resolve("subagents");
        Files.createDirectories(dir);
        Path file = dir.resolve(fileName);
        Files.writeString(file, frontmatter, java.nio.charset.StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void loadFromDirectory_readsRealTravelPlannerSubagent() throws IOException {
        // 用仓库内真实文件型智能体的子智能体样本（含 steps / workspace.mode / tools）。
        // 不做静默跳过：样本缺失时直接失败，否则该用例会在夹具丢失时悄悄空跑。
        // 定位不依赖运行目录（Maven 在 backend/ 下跑、手工 javac 在仓库根下跑）。
        Path real = realSampleSubagentsDir();
        assertTrue(Files.isDirectory(real), "真实样本必须存在：" + real);

        List<SubagentDeclaration> decls =
                AgentSpecLoader.loadFromDirectory(real, real.getParent());

        assertFalse(decls.isEmpty(), "真实子智能体样本应被解析出声明");
        SubagentDeclaration first = decls.get(0);
        assertNotNull(first.getName());
        assertNotNull(first.getDescription());
        assertFalse(first.getDescription().isBlank(), "description 是模型决定是否委派的关键依据");
    }

    /** 从当前工作目录向上找仓库根，定位 workspace/agents/旅行规划师/subagents。 */
    private Path realSampleSubagentsDir() {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && cur != null; i++) {
            Path candidate = cur.resolve("workspace/agents/旅行规划师/subagents");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            cur = cur.getParent();
        }
        return Path.of("workspace/agents/旅行规划师/subagents").toAbsolutePath();
    }

    @Test
    void loadFromDirectory_derivesAgentIdFromFileNameNotFrontmatter() throws IOException {
        // 框架契约：文件名（去掉 .md）就是 agent_id，frontmatter 里不再写 name
        writeSubagent(
                "行程规划子智能体.md",
                """
                ---
                description: 旅行行程草案的生成者。
                mode: subagent
                steps: 20
                workspace:
                  mode: shared
                tools: [read_file, write_file, web_search]
                ---

                # 行程规划子智能体

                你是行程规划子智能体，只遵循本文件定义。
                """);

        List<SubagentDeclaration> decls =
                AgentSpecLoader.loadFromDirectory(workspaceRoot.resolve("subagents"), workspaceRoot);

        assertEquals(1, decls.size());
        SubagentDeclaration d = decls.get(0);
        assertEquals("行程规划子智能体", d.getName(), "agent_id 必须来自文件名");
    }

    @Test
    void loadFromDirectory_preservesToolsWhitelist() throws IOException {
        // tools 是子智能体的工具白名单：丢失等于放开全部工具，属权限面变宽
        writeSubagent(
                "reviewer.md",
                """
                ---
                description: 代码审查专家。
                tools: [read_file, grep_files]
                ---

                你是代码评审子智能体。
                """);

        List<SubagentDeclaration> decls =
                AgentSpecLoader.loadFromDirectory(workspaceRoot.resolve("subagents"), workspaceRoot);

        assertEquals(1, decls.size());
        assertNotNull(decls.get(0).getTools(), "tools 白名单必须被解析");
        assertTrue(
                decls.get(0).getTools().contains("read_file"),
                "tools 白名单必须保留读工具");
    }

    @Test
    void loadFromDirectory_scansMultipleSubagentsSortedByFileName() throws IOException {
        writeSubagent("beta.md", "---\ndescription: B 子智能体。\n---\n\nB 正文。");
        writeSubagent("alpha.md", "---\ndescription: A 子智能体。\n---\n\nA 正文。");

        List<SubagentDeclaration> decls =
                AgentSpecLoader.loadFromDirectory(workspaceRoot.resolve("subagents"), workspaceRoot);

        assertEquals(2, decls.size());
        assertEquals("alpha", decls.get(0).getName());
        assertEquals("beta", decls.get(1).getName());
    }

    @Test
    void loadFromDirectory_returnsEmptyWhenSubagentsDirAbsent() {
        // 现状：workspace 根指向 .harness 时该目录不存在，Layer 2 直接返回空 —— 这正是步骤 0
        // 要消除的路径错位（子智能体只能靠 Layer 1 兜底被发现）
        List<SubagentDeclaration> decls =
                AgentSpecLoader.loadFromDirectory(workspaceRoot.resolve("does-not-exist"), workspaceRoot);

        assertTrue(decls.isEmpty(), "subagents/ 不存在时返回空列表而非抛异常");
    }

    @Test
    void loadFromDirectory_ignoresNonMarkdownFiles() throws IOException {
        writeSubagent("valid.md", "---\ndescription: 有效子智能体。\n---\n\n正文。");
        Files.writeString(workspaceRoot.resolve("subagents").resolve("notes.txt"), "不是声明");

        List<SubagentDeclaration> decls =
                AgentSpecLoader.loadFromDirectory(workspaceRoot.resolve("subagents"), workspaceRoot);

        assertEquals(1, decls.size(), "只扫 .md，非 markdown 应被忽略");
    }
}
