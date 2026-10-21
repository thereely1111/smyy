package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, UUID> {

    java.util.List<KnowledgeBase> findByOwnerUserId(UUID ownerUserId);
}
