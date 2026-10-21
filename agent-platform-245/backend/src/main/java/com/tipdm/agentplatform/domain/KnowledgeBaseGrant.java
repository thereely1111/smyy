package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * 知识库授权：将某知识库的 READ/WRITE 授予一个用户或群组。
 * 同一 (knowledge_base_id, principal_type, principal_id) 仅一条，权限取最大。
 */
@Entity
@Table(name = "knowledge_base_grants")
public class KnowledgeBaseGrant extends BaseEntity {

    @Column(name = "knowledge_base_id", nullable = false)
    private UUID knowledgeBaseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", nullable = false, length = 8)
    private GrantPrincipalType principalType;

    @Column(name = "principal_id", nullable = false)
    private UUID principalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "permission", nullable = false, length = 8)
    private KnowledgeBasePermission permission;

    @Column(name = "granted_by", nullable = false)
    private UUID grantedBy;

    public UUID getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public void setKnowledgeBaseId(UUID knowledgeBaseId) {
        this.knowledgeBaseId = knowledgeBaseId;
    }

    public GrantPrincipalType getPrincipalType() {
        return principalType;
    }

    public void setPrincipalType(GrantPrincipalType principalType) {
        this.principalType = principalType;
    }

    public UUID getPrincipalId() {
        return principalId;
    }

    public void setPrincipalId(UUID principalId) {
        this.principalId = principalId;
    }

    public KnowledgeBasePermission getPermission() {
        return permission;
    }

    public void setPermission(KnowledgeBasePermission permission) {
        this.permission = permission;
    }

    public UUID getGrantedBy() {
        return grantedBy;
    }

    public void setGrantedBy(UUID grantedBy) {
        this.grantedBy = grantedBy;
    }
}
