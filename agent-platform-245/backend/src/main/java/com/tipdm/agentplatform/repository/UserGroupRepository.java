package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.UserGroup;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserGroupRepository extends JpaRepository<UserGroup, UUID> {

    List<UserGroup> findByUserId(UUID userId);

    List<UserGroup> findByGroupId(UUID groupId);

    Optional<UserGroup> findByGroupIdAndUserId(UUID groupId, UUID userId);

    void deleteByGroupId(UUID groupId);

    void deleteByGroupIdAndUserId(UUID groupId, UUID userId);
}
