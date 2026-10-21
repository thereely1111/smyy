package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.Group;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupRepository extends JpaRepository<Group, UUID> {

    List<Group> findByCreatedBy(UUID createdBy);
}
