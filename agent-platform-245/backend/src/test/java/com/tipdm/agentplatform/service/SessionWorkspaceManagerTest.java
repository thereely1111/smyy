package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.web.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行态工作区管理器的路径安全与目录托管行为验证。
 */
class SessionWorkspaceManagerTest {

    @TempDir
    Path tempDir;

    private SessionWorkspaceManager newManager() {
        return new SessionWorkspaceManager(tempDir.toString());
    }

    @Test
    void ensureSessionDir_createsAgentSessionLayers() throws IOException {
        SessionWorkspaceManager manager = newManager();
        Path dir = manager.ensureSessionDir("agent-1", "wb-session-1");

        assertTrue(Files.isDirectory(dir));
        assertEquals(tempDir.resolve("runtimes/agent-1/wb-session-1"), dir);
        // 二次调用必须幂等
        assertEquals(dir, manager.ensureSessionDir("agent-1", "wb-session-1"));
    }

    @Test
    void resolveRuntimeRoot_rejectsTraversal() {
        SessionWorkspaceManager manager = newManager();
        assertThrows(ApiException.class, () -> manager.resolveRuntimeRoot(".."));
        assertThrows(ApiException.class, () -> manager.resolveRuntimeRoot("../evil"));
        assertThrows(ApiException.class, () -> manager.resolveRuntimeRoot("/abs/path"));
        assertThrows(ApiException.class, () -> manager.resolveRuntimeRoot("a/b"));
    }

    @Test
    void resolveSessionDir_rejectsTraversalInSession() {
        SessionWorkspaceManager manager = newManager();
        assertThrows(ApiException.class, () -> manager.resolveSessionDir("agent-1", "../s"));
        assertThrows(ApiException.class, () -> manager.resolveSessionDir("agent-1", "s/../escape"));
    }

    @Test
    void resolveAgentDefinitionDir_supportsRelativeAndAbsolute() {
        SessionWorkspaceManager manager = newManager();
        Path relative = manager.resolveAgentDefinitionDir("agents/abc");
        assertEquals(tempDir.resolve("agents/abc"), relative);

        Path abs = tempDir.resolve("agents/xyz").toAbsolutePath();
        assertEquals(abs, manager.resolveAgentDefinitionDir(abs.toString()));
    }

    @Test
    void resolveAgentDefinitionDir_rejectsEscape() {
        SessionWorkspaceManager manager = newManager();
        assertThrows(ApiException.class, () -> manager.resolveAgentDefinitionDir("../outside"));
        assertThrows(ApiException.class, () -> manager.resolveAgentDefinitionDir("/etc/passwd"));
    }

    @Test
    void deleteSessionDir_removesSessionOnly() throws IOException {
        SessionWorkspaceManager manager = newManager();
        Path s1 = manager.ensureSessionDir("agent-1", "wb-s1");
        Path s2 = manager.ensureSessionDir("agent-1", "wb-s2");
        Files.writeString(s1.resolve("产物.md"), "x");

        manager.deleteSessionDir("agent-1", "user-1", "wb-s1");

        assertFalse(Files.exists(s1));
        assertTrue(Files.isDirectory(s2));
    }

    /** 子会话目录的归属来自框架 spawn registry：只清理本会话派生的子目录，兄弟会话不受影响。 */
    @Test
    void deleteSessionDir_removesSubagentWorkspacesBoundToParentSession() throws IOException {
        SessionLineageService lineage = Mockito.mock(SessionLineageService.class);
        Mockito.when(lineage.subSessionsOf("user-1", "wb-parent"))
                .thenReturn(List.of("sub-child-1"));
        SessionWorkspaceManager manager = new SessionWorkspaceManager(tempDir.toString(), lineage);
        manager.ensureSessionDir("agent-1", "wb-parent");
        Path sub = tempDir.resolve("runtimes/agent-1/sub-child-1");
        Files.createDirectories(sub.resolve("全局DB/书/组件"));
        Files.writeString(sub.resolve("全局DB/书/组件/知识准备_v1.0.md"), "# x");
        Path unrelated = tempDir.resolve("runtimes/agent-1/sub-child-2");
        Files.createDirectories(unrelated);
        Files.writeString(unrelated.resolve("note.txt"), "别的会话的产物");

        manager.deleteSessionDir("agent-1", "user-1", "wb-parent");

        assertFalse(Files.exists(sub));
        assertTrue(Files.isDirectory(unrelated));
    }

    /** 没有血缘服务（纯目录操作）时只删本会话目录，不抛异常。 */
    @Test
    void deleteSessionDir_withoutLineage_removesSessionOnly() throws IOException {
        SessionWorkspaceManager manager = newManager();
        Path dir = manager.ensureSessionDir("agent-1", "wb-lone");
        Files.createDirectories(tempDir.resolve("runtimes/agent-1/sub-orphan"));

        manager.deleteSessionDir("agent-1", "user-1", "wb-lone");

        assertFalse(Files.exists(dir));
        assertTrue(Files.isDirectory(tempDir.resolve("runtimes/agent-1/sub-orphan")));
    }

    // ---------- 删除会话时的 wakeup 队列清理（2026-10-09）----------

    /** wakeup 队列目录名按 userId 聚合（hashKey(user)），一个目录里混着该用户所有会话的条目。 */
    private static final String WAKEUP_PREFIX = "agentscope_wakeups_";

    /** 真实 wakeup 条目的形状（见 AgentScopeWakeupConfig#seedFromWakeupFile）。 */
    private static String wakeupEntry(String sessionId) {
        return "{\"userId\":\"user-1\",\"sessionId\":\"" + sessionId
                + "\",\"agentId\":\"agent-1\"}";
    }

    private Path wakeupQueueDir() throws IOException {
        Path dir = tempDir.resolve(".agentscope/bus/queues/" + WAKEUP_PREFIX + "hash1");
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * 删除会话时清掉<b>属于它</b>的 wakeup 条目。
     *
     * <p>回归场景：只清 inbox 队列不清理up 队列，已删会话的 pending wakeup
     * 会在下次启动时被 WakeupDispatcherBootstrap drain 并拉起一个已删会话的 run。</p>
     */
    @Test
    void deleteSessionWakeupEntries_removesEntryOfThatSessionOnly() throws IOException {
        SessionWorkspaceManager manager = newManager();
        Path q = wakeupQueueDir();
        Path mine = q.resolve("e-mine.json");
        Path other = q.resolve("e-other.json");
        Files.writeString(mine, wakeupEntry("wb-target"));
        Files.writeString(other, wakeupEntry("wb-other"));

        manager.deleteSessionWakeupEntries("wb-target");

        assertFalse(Files.exists(mine), "目标会话的 wakeup 条目应被删除");
        assertTrue(Files.exists(other), "同用户其他会话的 wakeup 条目必须保留");
    }

    /**
     * <b>本修复最容易埋雷的点</b>：wakeup 队列目录名只含 userId 哈希、<b>不含 sessionId</b>，
     * 同一用户的多个会话共用一个目录。若按目录名/前缀匹配删除（inbox 那种做法），
     * 会把该用户所有会话的唤醒请求一并删掉——别的事务跑得好好的却被误伤。
     */
    @Test
    void deleteSessionWakeupEntries_neverTouchesSiblingSessionsOfSameUser() throws IOException {
        SessionWorkspaceManager manager = newManager();
        Path q = wakeupQueueDir();
        // 同用户三个会话的条目混在同一目录
        Path a = q.resolve("a.json");
        Path b = q.resolve("b.json");
        Path c = q.resolve("c.json");
        Files.writeString(a, wakeupEntry("wb-a"));
        Files.writeString(b, wakeupEntry("wb-b"));
        Files.writeString(c, wakeupEntry("wb-c"));

        manager.deleteSessionWakeupEntries("wb-b");

        assertTrue(Files.exists(a), "兄弟会话 a 的条目必须保留");
        assertFalse(Files.exists(b), "目标会话 b 的条目应被删");
        assertTrue(Files.exists(c), "兄弟会话 c 的条目必须保留");
    }

    /**
     * 无法解析的条目（{@code .keep} 元文件、损坏 JSON）一律保留。
     *
     * <p>宁留一条无害条目，也不可误删——唤醒侧对无归属/无内容的条目本就会跳过，
     * 而误删的代价是另一个会话丢工作、永久不再被唤醒。</p>
     */
    @Test
    void deleteSessionWakeupEntries_keepsUnparsableEntries() throws IOException {
        SessionWorkspaceManager manager = newManager();
        Path q = wakeupQueueDir();
        Path keep = q.resolve(".keep");
        Path broken = q.resolve("broken.json");
        Path empty = q.resolve("empty.json");
        Files.writeString(keep, "");
        Files.writeString(broken, "{ not valid json");
        Files.writeString(empty, "{}");

        manager.deleteSessionWakeupEntries("wb-target");

        assertTrue(Files.exists(keep), ".keep 等元文件必须保留");
        assertTrue(Files.exists(broken), "损坏条目必须保留（宁留勿误删）");
        assertTrue(Files.exists(empty), "无 sessionId 字段的条目必须保留");
    }

    /** deleteSessionDir 必须联动清理 wakeup 条目（否则删除路径仍有残留唤醒源）。 */
    @Test
    void deleteSessionDir_alsoClearsWakeupEntries() throws IOException {
        SessionWorkspaceManager manager = newManager();
        Path q = wakeupQueueDir();
        Path inbox = tempDir.resolve(".agentscope/bus/queues/agentscope_inbox_wb-w_abc123");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("hint.json"), "{}");
        Path wake = q.resolve("w.json");
        Files.writeString(wake, wakeupEntry("wb-w"));
        manager.ensureSessionDir("agent-1", "wb-w");

        manager.deleteSessionDir("agent-1", "user-1", "wb-w");

        assertFalse(Files.exists(inbox), "inbox 队列应被清");
        assertFalse(Files.exists(wake), "wakeup 条目也应被清（删除不留残留唤醒源）");
    }

    /** 队列根不存在时静默返回（部署初期/清理后重复调用都属正常）。 */
    @Test
    void deleteSessionWakeupEntries_missingQueueRoot_isSilent() {
        SessionWorkspaceManager manager = newManager(); // 临时目录里从未建过 queues
        manager.deleteSessionWakeupEntries("wb-nonexistent");
    }
}
