package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "knowledge_bases")
public class KnowledgeBase extends BaseEntity {

    /** RAG 分块默认参数：分块大小（字符）与重叠字符数；仅 rag 摄入管线使用。 */
    public static final int DEFAULT_CHUNK_SIZE = 1000;
    public static final int DEFAULT_CHUNK_OVERLAP = 200;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private KnowledgeBaseType type;

    /**
     * 归属范围（V52）：PLATFORM=平台库（knowledge:create 权限码创建，存量默认）；
     * PERSONAL=个人库。仅做分类，不改变可见性判定。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private KnowledgeBaseScope scope = KnowledgeBaseScope.PLATFORM;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    /** rag 路线必填：创建时锁定嵌入模型配置（维度随模型固定）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "embedding_model_config", columnDefinition = "jsonb")
    private Map<String, Object> embeddingModelConfig;

    /** 相对 knowledge-base-root 的目录（如 {kbId}）；rag/text 必填。 */
    @Column(name = "storage_root_rel", length = 512)
    private String storageRootRel;

    /** external 路线必填。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "external_provider", length = 32)
    private ExternalProvider externalProvider;

    /** external 路线必填。 */
    @Column(name = "external_endpoint", length = 512)
    private String externalEndpoint;

    /** external 路线：加密后的 API Key，绝不回显前端（P4 落库）。 */
    @Column(name = "external_api_key_enc", columnDefinition = "text")
    private String externalApiKeyEnc;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "external_extra", columnDefinition = "jsonb")
    private Map<String, Object> externalExtra;

    /** RAG 分块参数：分块大小（字符）。仅 rag 类型使用；external/text 为 null。 */
    @Column(name = "chunk_size")
    private Integer chunkSize;

    /** RAG 分块参数：相邻分块间的重叠字符数，服务端会收敛到 size/2 以内。 */
    @Column(name = "chunk_overlap")
    private Integer chunkOverlap;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public KnowledgeBaseType getType() {
        return type;
    }

    public void setType(KnowledgeBaseType type) {
        this.type = type;
    }

    public KnowledgeBaseScope getScope() {
        return scope;
    }

    public void setScope(KnowledgeBaseScope scope) {
        this.scope = scope;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(UUID ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public Map<String, Object> getEmbeddingModelConfig() {
        return embeddingModelConfig;
    }

    public void setEmbeddingModelConfig(Map<String, Object> embeddingModelConfig) {
        this.embeddingModelConfig = embeddingModelConfig;
    }

    public String getStorageRootRel() {
        return storageRootRel;
    }

    public void setStorageRootRel(String storageRootRel) {
        this.storageRootRel = storageRootRel;
    }

    public ExternalProvider getExternalProvider() {
        return externalProvider;
    }

    public void setExternalProvider(ExternalProvider externalProvider) {
        this.externalProvider = externalProvider;
    }

    public String getExternalEndpoint() {
        return externalEndpoint;
    }

    public void setExternalEndpoint(String externalEndpoint) {
        this.externalEndpoint = externalEndpoint;
    }

    public String getExternalApiKeyEnc() {
        return externalApiKeyEnc;
    }

    public void setExternalApiKeyEnc(String externalApiKeyEnc) {
        this.externalApiKeyEnc = externalApiKeyEnc;
    }

    public Map<String, Object> getExternalExtra() {
        return externalExtra;
    }

    public void setExternalExtra(Map<String, Object> externalExtra) {
        this.externalExtra = externalExtra;
    }

    public Integer getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(Integer chunkSize) {
        this.chunkSize = chunkSize;
    }

    public Integer getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(Integer chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }
}
