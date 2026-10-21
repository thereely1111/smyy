package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, UUID> {

    List<KnowledgeBase> findByOwnerUserId(UUID ownerUserId);
}
