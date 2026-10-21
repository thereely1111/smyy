package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.PlatformNotification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台通知数据访问：所有查询强制带 {@code user_id}，实现存储侧天然隔离。
 */
public interface PlatformNotificationRepository
        extends JpaRepository<PlatformNotification, UUID> {

    List<PlatformNotification> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    List<PlatformNotification> findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(
            String userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(String userId);

    /** 按用户 + id 取一条（标已读时校验归属，越权 id 查不到即视为不存在）。 */
    Optional<PlatformNotification> findByUserIdAndId(String userId, UUID id);

    /**
     * 未读抑制：该 (user, alertKey) 是否已有未读告警。
     * 优先于时间冷却——「提醒一次，直到你看过」才符合告知语义，
     * 否则周期性看护会在一夜之间把同一件事刷成十几条。
     */
    boolean existsByUserIdAndAlertKeyAndReadAtIsNull(String userId, String alertKey);

    /**
     * 时间冷却：取该 (user, alertKey) 在 {@code since} 之后最近一条的时间
     * （兜底用户读完即被再次提醒的场景）。
     */
    @Query("""
            SELECT max(n.createdAt) FROM PlatformNotification n
             WHERE n.userId = :userId AND n.alertKey = :alertKey
               AND n.createdAt >= :since
            """)
    Instant findLatestAlertAt(@Param("userId") String userId,
                              @Param("alertKey") String alertKey,
                              @Param("since") Instant since);

    /**
     * 未读抑制的时效判定：该 (user, alertKey) 在 {@code since} 之后是否还有<b>未读</b>通知。
     *
     * <p>与 {@link #existsByUserIdAndAlertKeyAndReadAtIsNull} 的区别是带上时间下限，
     * 让「未读抑制」不会无限期生效（见 AlertProperties#unreadSuppressMaxHours）。</p>
     */
    @Query("""
            SELECT COUNT(n) FROM PlatformNotification n
             WHERE n.userId = :userId AND n.alertKey = :alertKey
               AND n.readAt IS NULL AND n.createdAt >= :since
            """)
    long countUnreadSince(@Param("userId") String userId,
                          @Param("alertKey") String alertKey,
                          @Param("since") Instant since);

    // ⚠️ @Modifying 的 JPQL 更新必须在事务内执行，否则抛 TransactionRequiredException
    // （2026-09-16 实证：漏标导致 POST /api/notifications/{id}/read 500）。
    @Modifying
    @Transactional
    @Query("""
            UPDATE PlatformNotification n SET n.readAt = :now
             WHERE n.userId = :userId AND n.id = :id AND n.readAt IS NULL
            """)
    int markRead(@Param("userId") String userId, @Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("""
            UPDATE PlatformNotification n SET n.readAt = :now
             WHERE n.userId = :userId AND n.readAt IS NULL
            """)
    int markAllRead(@Param("userId") String userId, @Param("now") Instant now);

    @Modifying
    @Transactional
    @Query("DELETE FROM PlatformNotification n WHERE n.createdAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
