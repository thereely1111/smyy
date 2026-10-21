package com.tipdm.agentplatform.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 产物面板的**artifacts 合并树**回归（2026-09-18 改版）。
 *
 * <p>背景：平台按会话隔离，子智能体产物落在各自的 {@code runtimes/{agentId}/sub-…}（与父会话
 * 目录同级）；按「产出统一写入 {@code artifacts/}」契约，产物树以 {@code artifacts} 为根，
 * 合并本会话与白名单子会话的 {@code artifacts/}，artifacts 之外的内容一律不展示。</p>
 */
class SessionArtifactControllerTest {

    @TempDir
    Path runtimeRoot;

    private Path sessionRoot;

    @BeforeEach
    void setUp() throws IOException {
        sessionRoot = runtimeRoot.resolve("agent-1/main-abc");
        // 本会话 artifacts：书/章节目录/目录结构.json
        Files.createDirectories(sessionRoot.resolve("artifacts/书/章节目录"));
        Files.writeString(sessionRoot.resolve("artifacts/书/章节目录/目录结构.json"), "{}");
        // 本会话的子会话：artifacts 下正常出产物
        Files.createDirectories(runtimeRoot.resolve("agent-1/sub-1111aaaa/artifacts/书/组件"));
        Files.writeString(runtimeRoot.resolve(
                "agent-1/sub-1111aaaa/artifacts/书/组件/知识准备_v1.0.md"), "# x");
        // 兄弟会话（不属于本会话）：绝不能被读到
        Files.createDirectories(runtimeRoot.resolve("agent-1/sub-2222bbbb/artifacts/书/组件"));
        Files.writeString(runtimeRoot.resolve(
                "agent-1/sub-2222bbbb/artifacts/书/组件/secret.md"), "secret");
    }

    // -----------------------------------------------------------------
    //  合并树（mergedArtifactRoots）
    // -----------------------------------------------------------------

    /** 父 + 子的 artifacts 合并成一棵以 artifacts 为根的树；同名目录按名 union。 */
    @Test
    void mergesParentAndSubArtifactsUnderArtifactsRoot() throws IOException {
        var own = SessionArtifactController.collectChildren(
                sessionRoot.resolve("artifacts"), "artifacts/", 6);
        var subs = new LinkedHashMap<String,
                List<com.tipdm.agentplatform.web.dto.RuntimeDtos.ArtifactNode>>();
        subs.put("sub-1111aaaa", SessionArtifactController.collectChildren(
                runtimeRoot.resolve("agent-1/sub-1111aaaa/artifacts"),
                "sub-1111aaaa/artifacts/", 6));

        List<com.tipdm.agentplatform.web.dto.RuntimeDtos.ArtifactNode> roots =
                SessionArtifactController.mergedArtifactRoots("artifacts", own, subs);

        assertEquals(1, roots.size());
        var root = roots.get(0);
        assertEquals("artifacts", root.name());
        assertEquals("dir", root.type());
        // 根下只有「书」一个目录（父的 章节目录 与子的 组件 都 union 进去）
        assertEquals(1, root.children().size());
        var book = root.children().get(0);
        assertEquals("书", book.name());
        assertEquals(2, book.children().size());
        List<String> childNames = book.children().stream()
                .map(com.tipdm.agentplatform.web.dto.RuntimeDtos.ArtifactNode::name)
                .sorted().toList();
        assertEquals(List.of("章节目录", "组件"), childNames);
        // 子会话文件的 path 仍带子会话前缀（content 接口据此路由到子会话目录）
        assertTrue(root.children().toString().contains("sub-1111aaaa/artifacts/书/组件/知识准备_v1.0.md"));
    }

    /** 父子同名文件并存：子会话文件展示名追加短标，真实 path 互不相同。 */
    @Test
    void tagsCollidingSubFileDisplayName() {
        var own = List.of(node("报告.md", "artifacts/报告.md"));
        var subs = new LinkedHashMap<String,
                List<com.tipdm.agentplatform.web.dto.RuntimeDtos.ArtifactNode>>();
        subs.put("sub-1a2b3c4d-9999", List.of(node("报告.md", "sub-1a2b3c4d-9999/artifacts/报告.md")));

        var root = SessionArtifactController.mergedArtifactRoots("artifacts", own, subs).get(0);
        assertEquals(2, root.children().size());
        List<String> names = root.children().stream()
                .map(com.tipdm.agentplatform.web.dto.RuntimeDtos.ArtifactNode::name).toList();
        assertTrue(names.contains("报告.md"), names.toString());
        assertTrue(names.contains("报告.md · 1a2b3c4d"), names.toString());
        // 展示名变了，path 不变（内容回取仍走原会话）
        assertTrue(root.children().stream()
                .anyMatch(n -> n.name().contains("·") && n.path().equals("sub-1a2b3c4d-9999/artifacts/报告.md")));
    }

    /** 父与子都没有 artifacts：返回空表，前端显示空态。 */
    @Test
    void returnsEmptyWhenNoArtifactsAnywhere() throws IOException {
        Path bare = runtimeRoot.resolve("agent-1/main-bare");
        Files.createDirectories(bare);
        var own = SessionArtifactController.collectChildren(
                bare.resolve("artifacts"), "artifacts/", 6);
        assertTrue(SessionArtifactController.mergedArtifactRoots(
                "artifacts", own, Map.of()).isEmpty());
    }

    /** artifacts 之外的内容（含运行态内部目录、历史散落文件）不进产物树。 */
    @Test
    void onlyArtifactsContentIsCollected() throws IOException {
        Files.createDirectories(sessionRoot.resolve("memory"));
        Files.writeString(sessionRoot.resolve("memory/2026-09-18.md"), "x");
        Files.writeString(sessionRoot.resolve("散落的旧产物.md"), "x");

        var own = SessionArtifactController.collectChildren(
                sessionRoot.resolve("artifacts"), "artifacts/", 6);
        String json = own.toString();
        assertTrue(json.contains("章节目录"), json);
        assertFalse(json.contains("memory"), json);
        assertFalse(json.contains("散落的旧产物"), json);
    }

    private static com.tipdm.agentplatform.web.dto.RuntimeDtos.ArtifactNode node(
            String name, String path) {
        return new com.tipdm.agentplatform.web.dto.RuntimeDtos.ArtifactNode(
                name, path, "file", 1L, null, null);
    }

    // -----------------------------------------------------------------
    //  content 接口的路径解析（子会话白名单路由，行为不变）
    // -----------------------------------------------------------------

    @Test
    void resolvesFileInOwnSession() {
        Path p = SessionArtifactController.resolveArtifactFile(
                "agent-1", "main-abc", sessionRoot, Set.of("sub-1111aaaa"),
                "artifacts/书/章节目录/目录结构.json");
        assertTrue(p != null && p.endsWith("目录结构.json"), String.valueOf(p));
    }

    @Test
    void resolvesFileInOwnSubSession() {
        Path p = SessionArtifactController.resolveArtifactFile(
                "agent-1", "main-abc", sessionRoot, Set.of("sub-1111aaaa"),
                "sub-1111aaaa/artifacts/书/组件/知识准备_v1.0.md");
        assertTrue(p != null && p.toString().contains("sub-1111aaaa"), String.valueOf(p));
    }

    /** 非本会话的子会话（白名单外）不得读到——避免越权。 */
    @Test
    void refusesSiblingSessionPath() {
        assertNull(SessionArtifactController.resolveArtifactFile(
                "agent-1", "main-abc", sessionRoot, Set.of("sub-1111aaaa"),
                "sub-2222bbbb/artifacts/书/组件/secret.md"));
    }

    /** 子会话前缀 + 目录穿越：规范化后必须仍在子会话目录内。 */
    @Test
    void refusesTraversalViaSubSessionPrefix() {
        assertNull(SessionArtifactController.resolveArtifactFile(
                "agent-1", "main-abc", sessionRoot, Set.of("sub-1111aaaa"),
                "sub-1111aaaa/../../sub-2222bbbb/artifacts/书/组件/secret.md"));
    }

    /** 隐藏文件（dotfile）与运行态内部目录（agents/default/memory/plan/MEMORY.md）不进产物树。 */
    @Test
    void hidesRuntimeInternalEntries() throws IOException {
        Files.createDirectories(sessionRoot.resolve("artifacts/memory"));
        Files.writeString(sessionRoot.resolve("artifacts/memory/2026-09-11.md"), "x");
        Files.writeString(sessionRoot.resolve("artifacts/MEMORY.md"), "x");
        Files.writeString(sessionRoot.resolve("artifacts/.hidden"), "x");

        List<com.tipdm.agentplatform.web.dto.RuntimeDtos.ArtifactNode> nodes =
                SessionArtifactController.collectChildren(sessionRoot.resolve("artifacts"), "", 6);
        List<String> names = nodes.stream().map(
                com.tipdm.agentplatform.web.dto.RuntimeDtos.ArtifactNode::name).toList();
        assertTrue(names.contains("书"), names.toString());
        assertFalse(names.contains("memory"), names.toString());
        assertFalse(names.contains("MEMORY.md"), names.toString());
        assertFalse(names.contains(".hidden"), names.toString());
    }

    @Test
    void missingFileReturnsNull() {
        assertNull(SessionArtifactController.resolveArtifactFile(
                "agent-1", "main-abc", sessionRoot, Set.of(), "artifacts/不存在.md"));
    }

    @Test
    void blankSessionReturnsEmptyTree() {
        assertTrue(SessionArtifactController.collectChildren(
                runtimeRoot.resolve("agent-1/nope"), "", 6).isEmpty());
        assertEquals(0, SessionArtifactController.collectChildren(sessionRoot, "", 0).size());
    }
}
