package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.FileAgentDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Optional;
import java.util.UUID;

public interface FileAgentDraftRepository extends JpaRepository<FileAgentDraft, UUID> {

    Optional<FileAgentDraft> findByAgentId(UUID agentId);

    @Modifying
    void deleteByAgentId(UUID agentId);
}
