package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.AgentSessionExtension;
import com.tipdm.agentplatform.repository.AgentSessionExtensionRepository;
import com.tipdm.agentplatform.web.dto.SessionExtensionDtos.EntryItem;
import com.tipdm.agentplatform.web.dto.SessionExtensionDtos.ExtensionResponse;
import com.tipdm.agentplatform.web.error.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话扩展信息服务单元测试。
 *
 * <p>覆盖 {@link SessionExtensionService} 的 CRUD 操作：获取、批量设置、单条设置、
 * 单条删除、全部删除，以及异常场景（不存在、空 key 等）。</p>
 */
class SessionExtensionServiceTest {

    private final AgentSessionExtensionRepository repository = mock(AgentSessionExtensionRepository.class);
    private final SessionExtensionService service = new SessionExtensionService(repository);

    private static final String AGENT_ID = "agent-1";
    private static final String SESSION_ID = "session-1";
    private static final String USER_ID = "user-1";

    private AgentSessionExtension createExtensionWithEntries(EntryItem... items) {
        AgentSessionExtension ext = new AgentSessionExtension();
        ext.setId(UUID.randomUUID());
        ext.setAgentId(AGENT_ID);
        ext.setSessionId(SESSION_ID);
        ext.setUserId(USER_ID);
        for (EntryItem item : items) {
            ext.putEntry(item.key(), item.value());
        }
        return ext;
    }

    // --- get ---

    @Test
    void getReturnsExtensionWhenExists() {
        AgentSessionExtension ext = createExtensionWithEntries(
                new EntryItem("chapterCount", "12"),
                new EntryItem("status", "draft"));
        when(repository.findByAgentIdAndSessionId(AGENT_ID, SESSION_ID))
                .thenReturn(Optional.of(ext));

        ExtensionResponse response = service.get(AGENT_ID, SESSION_ID);

        assertEquals(ext.getId(), response.id());
        assertEquals(AGENT_ID, response.agentId());
        assertEquals(SESSION_ID, response.sessionId());
        assertEquals(2, response.entries().size());
    }

    @Test
    void getThrowsNotFoundWhenMissing() {
        when(repository.findByAgentIdAndSessionId(AGENT_ID, SESSION_ID))
                .thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.get(AGENT_ID, SESSION_ID));
        assertEquals("NOT_FOUND", ex.getCode());
    }

    // --- getOrNull ---

    @Test
    void getOrNullReturnsNullWhenMissing() {
        when(repository.findByAgentIdAndSessionId(AGENT_ID, SESSION_ID))
                .thenReturn(Optional.empty());

        ExtensionResponse response = service.getOrNull(AGENT_ID, SESSION_ID);

        assertNull(response);
    }

    @Test
    void getOrNullReturnsResponseWhenExists() {
        AgentSessionExtension ext = createExtensionWithEntries(new EntryItem("key1", "val1"));
        when(repository.findByAgentIdAndSessionId(AGENT_ID, SESSION_ID))
                .thenReturn(Optional.of(ext));

        ExtensionResponse response = service.getOrNull(AGENT_ID, SESSION_ID);

        assertNotNull(response);
        assertEquals(1, response.entries().size());
    }

    // --- upsertEntries ---

    @Test
    void upsertEntriesCreatesNewExtensionWhenNotExists() {
        when(repository.findByAgentIdAndSessionId(AGENT_ID, SESSION_ID))
                .thenReturn(Optional.empty());
        when(repository.save(any(AgentSessionExtension.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<EntryItem> entries = List.of(
                new EntryItem("chapterCount", "12"),
                new EntryItem("tocItems", "[{\"title\":\"第一章\"}]"));

        ExtensionResponse response = service.upsertEntries(AGENT_ID, SESSION_ID, USER_ID, entries);

        assertNotNull(response);
        assertEquals(2, response.entries().size());
        verify(repository).save(any(AgentSessionExtension.class));
    }

    @Test
    void upsertEntriesUpdatesExistingExtension() {
        AgentSessionExtension ext = createExtensionWithEntries(new EntryItem("chapterCount", "10"));
        when(repository.findByAgentIdAndSessionId(AGENT_ID, SESSION_ID))
                .thenReturn(Optional.of(ext));
        when(repository.save(any(AgentSessionExtension.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<EntryItem> entries = List.of(
                new EntryItem("chapterCount", "15"),
                new EntryItem("status", "published"));

        ExtensionResponse response = service.upsertEntries(AGENT_ID, SESSION_ID, USER_ID, entries);

        assertEquals(2, response.entries().size());
        // chapterCount 应被更新为 15
        assertEquals("15", response.entries().stream()
                .filter(e -> "chapterCount".equals(e.key()))
                .findFirst().orElseThrow().value());
        verify(repository).save(ext);
    }

    @Test
    void upsertEntriesThrowsOnEmptyList() {
        assertThrows(ApiException.class,
                () -> service.upsertEntries(AGENT_ID, SESSION_ID, USER_ID, List.of()));
    }

    @Test
    void upsertEntriesThrowsOnNullList() {
        assertThrows(ApiException.class,
                () -> service.upsertEntries(AGENT_ID, SESSION_ID, USER_ID, null));
    }

    @Test
    void upsertEntriesThrowsOnBlankKey() {
        List<EntryItem> entries = List.of(new EntryItem("", "value"));

        assertThrows(ApiException.class,
                () -> service.upsertEntries(AGENT_ID, SESSION_ID, USER_ID, entries));
    }

    // --- putEntry ---

    @Test
    void putEntryCreatesNewExtensionWhenNotExists() {
        when(repository.findByAgentIdAndSessionId(AGENT_ID, SESSION_ID))
                .thenReturn(Optional.empty());
        when(repository.save(any(AgentSessionExtension.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ExtensionResponse response = service.putEntry(AGENT_ID, SESSION_ID, USER_ID, "chapterCount", "12");

        assertNotNull(response);
        assertEquals(1, response.entries().size());
        assertEquals("chapterCount", response.entries().get(0).key());
        assertEquals("12", response.entries().get(0).value());
        verify(repository).save(any(AgentSessionExtension.class));
    }

    @Test
    void putEntryUpdatesExistingKey() {
        AgentSessionExtension ext = createExtensionWithEntries(new EntryItem("chapterCount", "10"));
        when(repository.findByAgentIdAndSessionId(AGENT_ID, SESSION_ID))
                .thenReturn(Optional.of(ext));
        when(repository.save(any(AgentSessionExtension.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ExtensionResponse response = service.putEntry(AGENT_ID, SESSION_ID, USER_ID, "chapterCount", "20");

        assertEquals(1, response.entries().size());
        assertEquals("20", response.entries().get(0).value());
        verify(repository).save(ext);
    }

    @Test
    void putEntryThrowsOnBlankKey() {
        assertThrows(ApiException.class,
                () -> service.putEntry(AGENT_ID, SESSION_ID, USER_ID, "", "value"));
    }

    @Test
    void putEntryThrowsOnNullKey() {
        assertThrows(ApiException.class,
                () -> service.putEntry(AGENT_ID, SESSION_ID, USER_ID, null, "value"));
    }

    // --- deleteEntry ---

    @Test
    void deleteEntryRemovesKeyFromExtension() {
        AgentSessionExtension ext = createExtensionWithEntries(
                new EntryItem("chapterCount", "12"),
                new EntryItem("status", "draft"));
        when(repository.findByAgentIdAndSessionId(AGENT_ID, SESSION_ID))
                .thenReturn(Optional.of(ext));
        when(repository.save(any(AgentSessionExtension.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.deleteEntry(AGENT_ID, SESSION_ID, "chapterCount");

        verify(repository).save(ext);
        assertEquals(1, ext.getEntries().size());
        assertNull(ext.getEntryValue("chapterCount"));
        assertEquals("draft", ext.getEntryValue("status"));
    }

    @Test
    void deleteEntryThrowsWhenExtensionNotFound() {
        when(repository.findByAgentIdAndSessionId(AGENT_ID, SESSION_ID))
                .thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.deleteEntry(AGENT_ID, SESSION_ID, "anyKey"));
        assertEquals("NOT_FOUND", ex.getCode());
    }

    // --- deleteAll ---

    @Test
    void deleteAllCallsRepositoryDelete() {
        service.deleteAll(AGENT_ID, SESSION_ID);

        verify(repository).deleteByAgentIdAndSessionId(AGENT_ID, SESSION_ID);
    }

    // --- deleteByAgent ---

    @Test
    void deleteByAgentCallsRepositoryDelete() {
        service.deleteByAgent(AGENT_ID);

        verify(repository).deleteByAgentId(AGENT_ID);
    }

    // --- Response shape ---

    @Test
    void responseContainsCorrectStructure() {
        AgentSessionExtension ext = createExtensionWithEntries(
                new EntryItem("chapterCount", "12"),
                new EntryItem("tocItems", "[{\"title\":\"第一章\"}]"));
        when(repository.findByAgentIdAndSessionId(AGENT_ID, SESSION_ID))
                .thenReturn(Optional.of(ext));

        ExtensionResponse response = service.get(AGENT_ID, SESSION_ID);

        assertEquals(AGENT_ID, response.agentId());
        assertEquals(SESSION_ID, response.sessionId());
        assertEquals(USER_ID, response.userId());
        assertEquals(2, response.entries().size());
        assertTrue(response.entries().stream().anyMatch(e -> "chapterCount".equals(e.key())));
        assertTrue(response.entries().stream().anyMatch(e -> "tocItems".equals(e.key())));
    }
}
