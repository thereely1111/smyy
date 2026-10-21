package com.tipdm.agentplatform.harness;

import com.tipdm.agentplatform.service.SessionOwnerService;
import com.tipdm.agentplatform.service.SessionWorkspaceManager;
import com.tipdm.agentplatform.service.AgentAssemblyService;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;

class InboxWatchdogWakeupServiceTest {

    @TempDir
    Path tempRoot;

    private SessionActivityTracker tracker;
    private SessionAgentRegistry registry;
    private SessionOwnerService ownerService;
    private AgentAssemblyService assemblyService;
    private MessageBus messageBus;
    private RunLeaseStore leaseStore;
    private InboxWatchdogWakeupService service;

    @BeforeEach
    void setUp() {
        tracker = new SessionActivityTracker();
        registry = new SessionAgentRegistry();
        ownerService = mock(SessionOwnerService.class);
        assemblyService = mock(AgentAssemblyService.class);
        messageBus = mock(MessageBus.class);
        leaseStore = mock(RunLeaseStore.class);
        service = new InboxWatchdogWakeupService(
                new SessionWorkspaceManager(tempRoot.toString()),
                tracker, registry, ownerService, assemblyService, messageBus, leaseStore);
    }

    private Path createInbox(String sessionId, boolean withEntry) throws IOException {
        Path dir = tempRoot.resolve(".agentscope/bus/queues/agentscope_inbox_" + sessionId + "_hash");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(".keep"), "");
        if (withEntry) {
            Files.writeString(dir.resolve("00000000000000000001.json"), "{\"hint\":\"x\"}");
        }
        return dir;
    }

    @Test
    void wakesIdleSessionWithPendingInboxEntry() throws IOException {
        createInbox("wb-test-1", true);
        registry.register("agent-1", "wb-test-1");
        Mockito.when(assemblyService.requireAssembledById("agent-1"))
                .thenReturn(mock(HarnessAgent.class));
        Mockito.when(ownerService.owner("agent-1", "wb-test-1"))
                .thenReturn(Optional.of("user-1"));
        Mockito.when(messageBus.enqueueWakeup(any(), any(), any())).thenReturn(Mono.empty());

        service.sweep();

        verify(messageBus).enqueueWakeup("user-1", "wb-test-1", "agent-1");
    }

    @Test
    void skipsRunningSession() throws IOException {
        createInbox("wb-test-running", true);
        registry.register("agent-1", "wb-test-running");
        tracker.begin("wb-test-running");
        // 无新鲜租约 = 泄漏疑点，但持续未满 LEAK_RECOVER_MILLIS(5min) 前仍跳过
        Mockito.when(leaseStore.existsFreshBySessionId(any(), ArgumentMatchers.anyLong()))
                .thenReturn(false);

        service.sweep();

        verify(messageBus, never()).enqueueWakeup(any(), any(), any());
    }

    @Test
    void skipsRunningSessionWithFreshLease() throws IOException {
        createInbox("wb-test-live", true);
        registry.register("agent-1", "wb-test-live");
        tracker.begin("wb-test-live");
        Mockito.when(leaseStore.existsFreshBySessionId(any(), ArgumentMatchers.anyLong()))
                .thenReturn(true);

        service.sweep();

        verify(messageBus, never()).enqueueWakeup(any(), any(), any());
    }

    @Test
    void skipsQueueWithoutEntries() throws IOException {
        createInbox("wb-test-empty", false);
        registry.register("agent-1", "wb-test-empty");

        service.sweep();

        verify(messageBus, never()).enqueueWakeup(any(), any(), any());
    }

    @Test
    void respectsWakeupCooldown() throws IOException {
        createInbox("wb-test-cool", true);
        registry.register("agent-1", "wb-test-cool");
        Mockito.when(assemblyService.requireAssembledById("agent-1"))
                .thenReturn(mock(HarnessAgent.class));
        Mockito.when(ownerService.owner("agent-1", "wb-test-cool"))
                .thenReturn(Optional.of("user-1"));
        Mockito.when(messageBus.enqueueWakeup(any(), any(), any())).thenReturn(Mono.empty());

        service.sweep();
        verify(messageBus).enqueueWakeup("user-1", "wb-test-cool", "agent-1");

        // 冷却期内再次巡检不应重复补醒（总计仍只有 1 次）
        service.sweep();
        verify(messageBus, Mockito.times(1)).enqueueWakeup(any(), any(), any());
    }
}
