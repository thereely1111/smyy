package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.ResourceStatus;
import com.tipdm.agentplatform.domain.Tool;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ToolRepository extends JpaRepository<Tool, UUID> {

    Page<Tool> findByNameContainingIgnoreCaseAndStatus(String name, ResourceStatus status, Pageable pageable);

    Page<Tool> findByNameContainingIgnoreCase(String name, Pageable pageable);

    Page<Tool> findByStatus(ResourceStatus status, Pageable pageable);

    Optional<Tool> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);
}
