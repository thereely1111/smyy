package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.KnowledgeBaseDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KnowledgeBaseDocumentRepository extends JpaRepository<KnowledgeBaseDocument, UUID> {

    List<KnowledgeBaseDocument> findByKnowledgeBaseId(UUID knowledgeBaseId);

    void deleteByKnowledgeBaseId(UUID knowledgeBaseId);
}
