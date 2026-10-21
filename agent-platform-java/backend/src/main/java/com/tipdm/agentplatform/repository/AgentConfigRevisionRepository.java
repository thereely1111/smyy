package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.AgentConfigRevision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentConfigRevisionRepository extends JpaRepository<AgentConfigRevision, UUID> {

    List<AgentConfigRevision> findByAgentIdOrderByVersionDesc(UUID agentId);

    Optional<AgentConfigRevision> findByAgentIdAndVersion(UUID agentId, int version);

    boolean existsByAgentId(UUID agentId);

    void deleteByAgentId(UUID agentId);
}
