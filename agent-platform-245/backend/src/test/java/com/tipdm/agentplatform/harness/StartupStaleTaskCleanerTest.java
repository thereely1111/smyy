package com.tipdm.agentplatform.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.service.SessionOwnerService;
import io.agentscope.harness.agent.bus.MessageBus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

/**
 * 启动清理器回归：2.0.3 的 async-tools 布局可被扫描、RUNNING 被标记 CANCELLED
 * （3.7 终态语义：被中断 ≠ 子智能体自身失败）、受影响会话收到带产物清单的
 * 任务中断通知并触发 wakeup。
 */
class StartupStaleTaskCleanerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temp;

    private StartupStaleTaskCleaner cleaner(
            SessionOwnerService owner, MessageBus bus) {
        return new StartupStaleTaskCleaner(null, owner, bus);
    }

    @Test
    void marksStaleAsyncToolRecordCancelled() throws Exception {
        // runtimes/{agent}/.agentscope/bus/async-tools/{session}/call_x.json
        Path record = temp.resolve("agent-1/.agentscope/bus/async-tools/sess-1/call_x.json");
        Files.createDirectories(record.getParent());
        MAPPER.writeValue(record.toFile(), Map.of(
                "id", "call_x", "status", "RUNNING", "sessionId", "sess-1", "toolName", "agent_spawn"));

        SessionOwnerService owner = mock(SessionOwnerService.class);
        MessageBus bus = mock(MessageBus.class);
        when(bus.inboxPush(any(), any())).thenReturn(Mono.empty());
        when(bus.enqueueWakeup(any(), any(), any())).thenReturn(Mono.empty());
        when(owner.owner("agent-1", "sess-1")).thenReturn(Optional.of("user-1"));

        Map<String, String> affected = new LinkedHashMap<>();
        Map<String, String> artifactListings = new LinkedHashMap<>();
        cleaner(owner, bus).markStaleAsyncTools(temp, affected, artifactListings);

        assertEquals("agent-1", affected.get("sess-1"));
        // 记录已被改写为 CANCELLED（3.7：中断语义，不再误标 FAILED）
        Map<?, ?> updated = MAPPER.readValue(record.toFile(), Map.class);
        assertEquals("CANCELLED", updated.get("status"));
        assertTrue(String.valueOf(updated.get("lastError")).contains("interrupted"));
        // 会话无 artifacts 目录 ⇒ 清单文案为「暂无产物」
        assertEquals("（盘上暂无产物）", artifactListings.get("sess-1"));
    }

    /**
     * 回归（2026-09-11）：旧布局（{@code tasks/*.json}）里丢失的在途任务，除了标记，
     * 还必须收集受影响会话——否则父会话重启后无人唤醒，静默等到 L3 看护的兜底。
     */
    @Test
    void marksLegacyTaskFileAndReturnsAffectedSession() throws Exception {
        // runtimes/{agent}/{session}/agents/{subagent}/tasks/{session}.json
        Path record = temp.resolve("agent-1/sess-1/agents/目录生成智能体/tasks/sess-1.json");
        Files.createDirectories(record.getParent());
        MAPPER.writeValue(record.toFile(), Map.of(
                "id", "task-1", "status", "RUNNING", "sessionId", "sess-1", "toolName", "agent_spawn"));

        Map<String, String> affected = new LinkedHashMap<>();
        Map<String, String> artifactListings = new LinkedHashMap<>();
        cleaner(mock(SessionOwnerService.class), mock(MessageBus.class))
                .markLegacyTaskFiles(temp, affected, artifactListings);

        assertEquals("agent-1", affected.get("sess-1"));
        Map<?, ?> updated = MAPPER.readValue(record.toFile(), Map.class);
        assertEquals("CANCELLED", updated.get("status"));
    }

    /**
     * 3.7：会话已有产物时，通知必须携带盘上产物清单（中断 ≠ 失败，
     * 父会话先核对产物再决定续做/重派）。
     */
    @Test
    void legacyListingIncludesExistingArtifacts() throws Exception {
        Path record = temp.resolve("agent-1/sess-1/agents/x/tasks/sess-1.json");
        Files.createDirectories(record.getParent());
        MAPPER.writeValue(record.toFile(), Map.of("id", "t", "status", "RUNNING"));
        Files.createDirectories(temp.resolve("agent-1/sess-1/artifacts"));
        Files.writeString(temp.resolve("agent-1/sess-1/artifacts/实训_v1.0.md"), "x");

        Map<String, String> artifactListings = new LinkedHashMap<>();
        cleaner(mock(SessionOwnerService.class), mock(MessageBus.class))
                .markLegacyTaskFiles(temp, new LinkedHashMap<>(), artifactListings);

        assertTrue(artifactListings.get("sess-1").contains("实训_v1.0.md"));
    }

    @Test
    void notifySessionTaskLostPushesHintAndWakeup() {
        SessionOwnerService owner = mock(SessionOwnerService.class);
        MessageBus bus = mock(MessageBus.class);
        when(bus.inboxPush(any(), any())).thenReturn(Mono.empty());
        when(bus.enqueueWakeup(any(), any(), any())).thenReturn(Mono.empty());
        when(owner.owner("agent-1", "sess-1")).thenReturn(Optional.of("user-1"));

        cleaner(owner, bus).notifySessionTaskLost("sess-1", "agent-1", null);

        verify(bus).inboxPush(eq("sess-1"), any());
        verify(bus).enqueueWakeup("user-1", "sess-1", "agent-1");
    }
}
