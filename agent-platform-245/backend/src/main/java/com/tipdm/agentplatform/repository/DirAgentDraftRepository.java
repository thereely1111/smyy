package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.DirAgentDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Optional;
import java.util.UUID;

public interface DirAgentDraftRepository extends JpaRepository<DirAgentDraft, UUID> {

    Optional<DirAgentDraft> findByAgentId(UUID agentId);

    @Modifying
    void deleteByAgentId(UUID agentId);
}
