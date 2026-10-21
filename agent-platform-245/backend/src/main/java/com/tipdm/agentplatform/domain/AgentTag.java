package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * 智能体 ↔ 标签关联（V50）。
 *
 * <p>纯 UUID 外键列、不带 @ManyToOne 映射——标签挂载是「全量替换」写法
 * （事务内 delete + insert），不需要懒加载导航；目录型智能体（fileBased，
 * 无配置记录）不落本表，其标签真相源是 AGENTS.md frontmatter，
 * 由 {@code AgentDirScanner} 解析后随响应拼装。</p>
 */
@Entity
@Table(name = "agent_tags")
@IdClass(AgentTag.Pk.class)
public class AgentTag {

    @Id
    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Id
    @Column(name = "tag_id", nullable = false)
    private UUID tagId;

    public AgentTag() {
    }

    public AgentTag(UUID agentId, UUID tagId) {
        this.agentId = agentId;
        this.tagId = tagId;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(UUID agentId) {
        this.agentId = agentId;
    }

    public UUID getTagId() {
        return tagId;
    }

    public void setTagId(UUID tagId) {
        this.tagId = tagId;
    }

    /** 复合主键 (agent_id, tag_id)。 */
    public static class Pk implements Serializable {

        private UUID agentId;
        private UUID tagId;

        public Pk() {
        }

        public Pk(UUID agentId, UUID tagId) {
            this.agentId = agentId;
            this.tagId = tagId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Pk pk)) {
                return false;
            }
            return Objects.equals(agentId, pk.agentId) && Objects.equals(tagId, pk.tagId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(agentId, tagId);
        }
    }
}
