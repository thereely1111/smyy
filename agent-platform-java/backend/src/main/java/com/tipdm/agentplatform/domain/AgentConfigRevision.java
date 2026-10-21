package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

/**
 * 智能体发布版本存档（agent_config_revisions）。
 *
 * <p>每次发布动作（首次发布 / 草稿发布 / 重新发布 / 创建即发布）把当时的完整
 * 配置按 {@code AgentConfigRequest} 字段形状快照一行；(agent_id, version) 唯一。
 * 存档仅供版本历史查看与回滚，不参与对外展示——对外永远只有
 * {@link AgentConfig} 主行承载的当前发布版本。</p>
 */
@Entity
@Table(name = "agent_config_revisions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_agent_config_revision",
                columnNames = {"agent_id", "version"}))
public class AgentConfigRevision extends BaseEntity {

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    /** 发布版本号，与 {@link AgentConfig#getVersion()} 同轴递增。 */
    @Column(nullable = false)
    private int version;

    /** 发布时点的完整配置快照（AgentConfigRequest 字段形状）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> snapshot;

    /** 触发发布的操作人（JWT userId）；迁移回填的存量快照为 null。 */
    @Column(name = "published_by", length = 128)
    private String publishedBy;

    public UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Map<String, Object> getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(Map<String, Object> snapshot) {
        this.snapshot = snapshot;
    }

    public String getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(String publishedBy) {
        this.publishedBy = publishedBy;
    }
}
