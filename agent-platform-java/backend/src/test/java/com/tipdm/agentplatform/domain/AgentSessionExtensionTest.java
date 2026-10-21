package com.tipdm.agentplatform.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话扩展信息实体领域行为测试。
 *
 * <p>覆盖 {@link AgentSessionExtension} 的 putEntry / removeEntry / getEntryValue 方法，
 * 验证类似 MongoDB 文档的 key-value 操作语义。</p>
 */
class AgentSessionExtensionTest {

    private AgentSessionExtension newExtension() {
        AgentSessionExtension ext = new AgentSessionExtension();
        ext.setAgentId("agent-1");
        ext.setSessionId("session-1");
        ext.setUserId("user-1");
        return ext;
    }

    @Test
    void putEntryAddsNewEntry() {
        AgentSessionExtension ext = newExtension();

        ext.putEntry("chapterCount", "12");

        assertEquals(1, ext.getEntries().size());
        assertEquals("12", ext.getEntryValue("chapterCount"));
    }

    @Test
    void putEntryUpdatesExistingKey() {
        AgentSessionExtension ext = newExtension();
        ext.putEntry("chapterCount", "12");

        ext.putEntry("chapterCount", "15");

        assertEquals(1, ext.getEntries().size());
        assertEquals("15", ext.getEntryValue("chapterCount"));
    }

    @Test
    void putEntrySupportsMultipleKeys() {
        AgentSessionExtension ext = newExtension();

        ext.putEntry("chapterCount", "12");
        ext.putEntry("tocItems", "[{\"title\":\"第一章\"}]");
        ext.putEntry("status", "draft");

        assertEquals(3, ext.getEntries().size());
        assertEquals("12", ext.getEntryValue("chapterCount"));
        assertEquals("[{\"title\":\"第一章\"}]", ext.getEntryValue("tocItems"));
        assertEquals("draft", ext.getEntryValue("status"));
    }

    @Test
    void removeEntryDeletesByKey() {
        AgentSessionExtension ext = newExtension();
        ext.putEntry("chapterCount", "12");
        ext.putEntry("status", "draft");

        ext.removeEntry("chapterCount");

        assertEquals(1, ext.getEntries().size());
        assertNull(ext.getEntryValue("chapterCount"));
        assertEquals("draft", ext.getEntryValue("status"));
    }

    @Test
    void removeEntryDoesNothingWhenKeyMissing() {
        AgentSessionExtension ext = newExtension();
        ext.putEntry("chapterCount", "12");

        ext.removeEntry("nonExistentKey");

        assertEquals(1, ext.getEntries().size());
        assertEquals("12", ext.getEntryValue("chapterCount"));
    }

    @Test
    void getEntryValueReturnsNullForMissingKey() {
        AgentSessionExtension ext = newExtension();

        assertNull(ext.getEntryValue("missingKey"));
    }

    @Test
    void getEntryValueReturnsNullWhenNoEntries() {
        AgentSessionExtension ext = newExtension();

        assertTrue(ext.getEntries().isEmpty());
        assertNull(ext.getEntryValue("anyKey"));
    }

    @Test
    void putEntryWithNullValue() {
        AgentSessionExtension ext = newExtension();

        ext.putEntry("nullableKey", null);

        assertEquals(1, ext.getEntries().size());
        assertNull(ext.getEntryValue("nullableKey"));
    }
}
