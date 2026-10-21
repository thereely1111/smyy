package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    Optional<User> findByOauthUserIdAndOauthProvider(String oauthUserId, String oauthProvider);

    boolean existsByUsername(String username);
}
