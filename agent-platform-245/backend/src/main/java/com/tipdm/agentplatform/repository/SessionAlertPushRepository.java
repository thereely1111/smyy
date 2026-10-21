package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.SessionAlertPush;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface SessionAlertPushRepository extends JpaRepository<SessionAlertPush, UUID> {

    Optional<SessionAlertPush> findByUserIdAndSessionId(String userId, String sessionId);

    /** 会话清理：删除该会话的告警推送配置。 */
    @Modifying
    @Transactional
    @Query("DELETE FROM SessionAlertPush p WHERE p.sessionId = :sessionId")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
