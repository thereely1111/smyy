package com.tipdm.agentplatform.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能体会话扩展信息主表，存储垂类智能体会话过程中的自定义数据。
 *
 * <p>类似 MongoDB 文档结构：主表代表一个会话的扩展信息文档，
 * 子表 {@link AgentSessionExtensionEntry} 存储各个扩展字段（key-value）。</p>
 *
 * <p>例如教材编写智能体可存储 chapterCount、tocItems 等扩展字段供前端渲染目录。</p>
 */
@Entity
@Table(name = "agent_session_extensions")
public class AgentSessionExtension extends BaseEntity {

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(name = "session_id", nullable = false, length = 255)
    private String sessionId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @OneToMany(mappedBy = "extension", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<AgentSessionExtensionEntry> entries = new ArrayList<>();

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public List<AgentSessionExtensionEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<AgentSessionExtensionEntry> entries) {
        this.entries = entries;
    }

    /**
     * 添加或更新一个扩展条目。
     */
    public void putEntry(String key, String value) {
        entries.stream()
                .filter(e -> key.equals(e.getEntryKey()))
                .findFirst()
                .ifPresentOrElse(
                        e -> e.setEntryValue(value),
                        () -> {
                            AgentSessionExtensionEntry entry = new AgentSessionExtensionEntry();
                            entry.setExtension(this);
                            entry.setEntryKey(key);
                            entry.setEntryValue(value);
                            entries.add(entry);
                        });
    }

    /**
     * 移除指定 key 的扩展条目。
     */
    public void removeEntry(String key) {
        entries.removeIf(e -> key.equals(e.getEntryKey()));
    }

    /**
     * 读取指定 key 的值（无则返回 null）。
     */
    public String getEntryValue(String key) {
        return entries.stream()
                .filter(e -> key.equals(e.getEntryKey()))
                .findFirst()
                .map(AgentSessionExtensionEntry::getEntryValue)
                .orElse(null);
    }
}
