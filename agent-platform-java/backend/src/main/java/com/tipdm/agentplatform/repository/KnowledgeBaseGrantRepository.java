package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.GrantPrincipalType;
import com.tipdm.agentplatform.domain.KnowledgeBaseGrant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeBaseGrantRepository extends JpaRepository<KnowledgeBaseGrant, UUID> {

    List<KnowledgeBaseGrant> findByKnowledgeBaseId(UUID knowledgeBaseId);

    Optional<KnowledgeBaseGrant> findByKnowledgeBaseIdAndPrincipalTypeAndPrincipalId(
            UUID knowledgeBaseId, GrantPrincipalType principalType, UUID principalId);

    /** 展开某主体（用户或群组）被授予的所有知识库。 */
    List<KnowledgeBaseGrant> findByPrincipalTypeAndPrincipalId(
            GrantPrincipalType principalType, UUID principalId);

    /** 批量展开：某组主体（通常用于群组集合）被授予的知识库。 */
    List<KnowledgeBaseGrant> findByPrincipalTypeAndPrincipalIdIn(
            GrantPrincipalType principalType, List<UUID> principalIds);

    void deleteByKnowledgeBaseId(UUID knowledgeBaseId);

    /** 删除指向某主体（用户/群组）的全部授权，例如群组被删时清理。 */
    void deleteByPrincipalTypeAndPrincipalId(GrantPrincipalType principalType, UUID principalId);
}
