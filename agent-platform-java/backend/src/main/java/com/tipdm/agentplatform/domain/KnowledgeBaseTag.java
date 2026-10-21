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
 * 知识库 ↔ 标签关联（V50）。设计约定见 {@link AgentTag}；
 * 知识库删除时关联行随 FK CASCADE 清理。
 */
@Entity
@Table(name = "knowledge_base_tags")
@IdClass(KnowledgeBaseTag.Pk.class)
public class KnowledgeBaseTag {

    @Id
    @Column(name = "knowledge_base_id", nullable = false)
    private UUID knowledgeBaseId;

    @Id
    @Column(name = "tag_id", nullable = false)
    private UUID tagId;

    public KnowledgeBaseTag() {
    }

    public KnowledgeBaseTag(UUID knowledgeBaseId, UUID tagId) {
        this.knowledgeBaseId = knowledgeBaseId;
        this.tagId = tagId;
    }

    public UUID getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(UUID knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public UUID getTagId() {
        return tagId;
    }

    public void setTagId(UUID tagId) {
        this.tagId = tagId;
    }

    /** 复合主键 (knowledge_base_id, tag_id)。 */
    public static class Pk implements Serializable {

        private UUID knowledgeBaseId;
        private UUID tagId;

        public Pk() {
        }

        public Pk(UUID knowledgeBaseId, UUID tagId) {
            this.knowledgeBaseId = knowledgeBaseId;
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
            return Objects.equals(knowledgeBaseId, pk.knowledgeBaseId)
                    && Objects.equals(tagId, pk.tagId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(knowledgeBaseId, tagId);
        }
    }
}
