package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * 会话扩展信息的键值条目（类似 MongoDB 文档中的 field）。
 *
 * <p>每个条目归属于一个 {@link AgentSessionExtension}，
 * 以 key-value 形式存储单个扩展字段。key 在同一扩展文档内唯一。</p>
 */
@Entity
@Table(name = "agent_session_extension_entries",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_extension_entry_key",
                columnNames = {"extension_id", "entry_key"}))
public class AgentSessionExtensionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "extension_id", nullable = false)
    private AgentSessionExtension extension;

    @Column(name = "entry_key", nullable = false, length = 128)
    private String entryKey;

    @Column(name = "entry_value", columnDefinition = "TEXT")
    private String entryValue;

    public UUID getId() {
        return id;
    }

    public AgentSessionExtension getExtension() {
        return extension;
    }

    public void setExtension(AgentSessionExtension extension) {
        this.extension = extension;
    }

    public String getEntryKey() {
        return entryKey;
    }

    public void setEntryKey(String entryKey) {
        this.entryKey = entryKey;
    }

    public String getEntryValue() {
        return entryValue;
    }

    public void setEntryValue(String entryValue) {
        this.entryValue = entryValue;
    }
}
