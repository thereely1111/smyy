package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.ModelCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ModelCredentialRepository extends JpaRepository<ModelCredential, UUID> {

    List<ModelCredential> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    List<ModelCredential> findByScopeOrderByUpdatedAtDesc(String scope);

    Optional<ModelCredential> findByIdAndUserId(UUID id, UUID userId);
}
