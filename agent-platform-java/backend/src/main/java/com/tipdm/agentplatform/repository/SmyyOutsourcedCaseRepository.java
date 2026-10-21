package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.SmyyOutsourcedCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SmyyOutsourcedCaseRepository extends JpaRepository<SmyyOutsourcedCase, UUID> {

    Optional<SmyyOutsourcedCase> findByProtoId(String protoId);

    boolean existsByProtoId(String protoId);

    boolean existsBySlug(String slug);

    List<SmyyOutsourcedCase> findAllByOrderByProtoIdAsc();
}
