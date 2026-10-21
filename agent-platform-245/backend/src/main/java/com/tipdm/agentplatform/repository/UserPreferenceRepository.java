package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** 用户偏好存取。 */
@Repository
public interface UserPreferenceRepository extends JpaRepository<UserPreference, UUID> {
}
