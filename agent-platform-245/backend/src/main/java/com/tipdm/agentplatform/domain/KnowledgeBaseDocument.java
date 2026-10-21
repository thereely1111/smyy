package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "knowledge_base_documents")
public class KnowledgeBaseDocument extends BaseEntity {

    @Column(name = "knowledge_base_id", nullable = false)
    private UUID knowledgeBaseId;

    @Column(nullable = false, length = 255)
    private String filename;

    /** 相对 storage_root_rel 的路径（如 {docId}/{filename}）。 */
    @Column(name = "relative_path", nullable = false, length = 512)
    private String relativePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private KnowledgeDocumentStatus status;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount = 0;

    @Column(columnDefinition = "text")
    private String error;

    public UUID getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(UUID knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public void setRelativePath(String relativePath) {
        this.relativePath = relativePath;
    }

    public KnowledgeDocumentStatus getStatus() {
        return status;
    }

    public void setStatus(KnowledgeDocumentStatus status) {
        this.status = status;
    }

    public int getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(int chunkCount) {
        this.chunkCount = chunkCount;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
