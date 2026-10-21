package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.KnowledgeBaseTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface KnowledgeBaseTagRepository extends JpaRepository<KnowledgeBaseTag, KnowledgeBaseTag.Pk> {

    List<KnowledgeBaseTag> findByKnowledgeBaseId(UUID knowledgeBaseId);

    List<KnowledgeBaseTag> findByTagId(UUID tagId);

    void deleteByKnowledgeBaseId(UUID knowledgeBaseId);

    void deleteByTagId(UUID tagId);

    /** JPQL bulk delete，语义见 {@link AgentTagRepository#deleteAllForAgent}。 */
    @Modifying
    @Query("delete from KnowledgeBaseTag k where k.knowledgeBaseId = :kbId")
    void deleteAllForKnowledgeBase(@Param("kbId") UUID kbId);
}
