package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.AgentSessionOwner;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话归属仓储。
 *
 * <p><b>写方法逐个标 {@code @Transactional}</b>（2026-09-17）：{@code @Modifying}
 * 查询在无事务时抛 {@code TransactionRequiredException}。原实现靠
 * {@code SessionOwnerService} 的外层事务兜底，但 {@code harness/} 侧的直接调用点
 * 没有任何事务——同包的 {@code AgentSessionProgressRepository} /
 * {@code PlatformNotificationRepository} 都已标注，此处属遗漏（项目已踩过此坑）。
 * 标在方法上后：有外层事务就加入它，没有则各自开短事务（只标写方法，
 * 不给读方法平白加事务）。</p>
 */
public interface AgentSessionOwnerRepository extends JpaRepository<AgentSessionOwner, UUID> {

    /**
     * 幂等写入会话归属：首次调用确定归属，后续调用只刷新 {@code last_seen_at}。
     *
     * <p>用单条 upsert 而不是「先查后写」：会话首包可能并发到达，
     * 查写之间会撞唯一约束；{@code ON CONFLICT ... WHERE} 让冲突且归属不同的写入
     * 静默失败（返回 0 行），归属相同的只更新时间，无需捕获异常、也不会把事务
     * 标记成 rollback-only。</p>
     *
     * @return 受影响行数；0 表示该会话已归属给其他用户（调用方应告警）
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO agent_session_owners
                (id, agent_id, session_id, user_id, last_seen_at, created_at, updated_at)
            VALUES
                (:id, :agentId, :sessionId, :userId, now(), now(), now())
            ON CONFLICT (agent_id, session_id) DO UPDATE
                SET last_seen_at = now(),
                    updated_at = now()
                WHERE agent_session_owners.user_id = EXCLUDED.user_id
            """, nativeQuery = true)
    int upsert(@Param("id") UUID id,
               @Param("agentId") String agentId,
               @Param("sessionId") String sessionId,
               @Param("userId") String userId);

    Optional<AgentSessionOwner> findByAgentIdAndSessionId(String agentId, String sessionId);

    @Modifying
    @Transactional
    @Query(value = "UPDATE agent_session_owners SET title = :title, title_source = :source, updated_at = now() "
            + "WHERE agent_id = :agentId AND session_id = :sessionId AND user_id = :userId "
            + "AND (title_source IS NULL OR title_source <> 'manual')", nativeQuery = true)
    int setAutoTitle(@Param("agentId") String agentId, @Param("sessionId") String sessionId,
                     @Param("userId") String userId, @Param("title") String title, @Param("source") String source);

    @Modifying
    @Transactional
    @Query(value = "UPDATE agent_session_owners SET title = :title, title_source = 'manual', updated_at = now() "
            + "WHERE agent_id = :agentId AND session_id = :sessionId AND user_id = :userId", nativeQuery = true)
    int setManualTitle(@Param("agentId") String agentId, @Param("sessionId") String sessionId,
                       @Param("userId") String userId, @Param("title") String title);

    /** 按会话查全部归属行（跨用户；同一 agent+session 通常 1 行，供 InboxWatchdog 回退解析父 agent）。 */
    List<AgentSessionOwner> findBySessionId(String sessionId);

    /** 某用户在该智能体下的全部会话（供按用户清理产物）。 */
    List<AgentSessionOwner> findByAgentIdAndUserId(String agentId, String userId);

    /** 某用户在该智能体下的未归档会话（会话列表默认源，走 idx_session_owners_active）。 */
    List<AgentSessionOwner> findByAgentIdAndUserIdAndArchivedAtIsNull(String agentId, String userId);

    /**
     * 置 / 取消归档。用「时间戳」而非 boolean 表达状态：NULL 即未归档。
     *
     * @return 受影响行数；0 表示会话不存在或不属于该用户（调用方应告警）
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE agent_session_owners
               SET archived_at = CASE WHEN :archived THEN now() ELSE NULL END,
                   updated_at = now()
             WHERE agent_id = :agentId
               AND session_id = :sessionId
               AND user_id = :userId
            """, nativeQuery = true)
    int setArchived(@Param("agentId") String agentId,
                    @Param("sessionId") String sessionId,
                    @Param("userId") String userId,
                    @Param("archived") boolean archived);

    /** 某用户的全部会话（跨智能体统计 / 清理）。 */
    List<AgentSessionOwner> findByUserId(String userId);

    /** 最近 {@code since} 后活跃过的全部会话（停滞看护扫描源）。 */
    List<AgentSessionOwner> findByLastSeenAtGreaterThanEqual(java.time.Instant since);

    /**
     * 会话列表快速路径行：一次 JOIN 拿齐列表所需的全部字段。
     *
     * <p>{@code stateUpdatedAt} 是状态库的 {@code updated_at}（{@code timestamp without
     * time zone}，框架经 JDBC 以 JVM 默认时区写入）——调用方用 {@code toInstant()}
     * 按同一时区解释即可往返一致；{@code archivedAt} 是归属表的 {@code timestamptz}
     * （UTC），JDBC 返回的 Timestamp 自带正确时刻。</p>
     */
    interface SessionSummaryRow {
        String getSessionId();

        String getTitle();

        java.sql.Timestamp getArchivedAt();

        java.sql.Timestamp getStateUpdatedAt();

        String getSummary();
    }

    /**
     * 会话列表快速查询：归属表 LEFT JOIN 状态库（{@code agentscope_sessions}，行主键
     * 形如 {@code userId:sessionId}，见框架 {@code PostgresAgentStateStore}），
     * {@code summary} 直接在 SQL 里做 JSON 抽取。
     *
     * <p>旧实现逐会话 {@code stateStore.get(...)} 把整份 {@code agent_state}（实测单条
     * 300–640KB）拉回 Java 反序列化，只为取 summary / 标题 / 最后时间——长会话一多，
     * 每次 {@code GET /sessions} 都要解析数 MB JSON，前端又多处触发，网页明显卡顿。
     * 本查询全程走主键索引，且不把 state_data 传回应用层。</p>
     */
    @Query(value = """
            SELECT o.session_id                      AS "sessionId",
                   o.title                           AS "title",
                   o.archived_at                     AS "archivedAt",
                   s.updated_at                      AS "stateUpdatedAt",
                   s.state_data::jsonb ->> 'summary' AS "summary"
            FROM agent_session_owners o
            LEFT JOIN LATERAL (
                SELECT s2.updated_at, s2.state_data
                FROM agentscope_sessions s2
                WHERE s2.session_id = o.user_id || ':' || o.session_id
                  AND s2.state_key = 'agent_state'
                ORDER BY s2.item_index
                LIMIT 1
            ) s ON true
            WHERE o.agent_id = :agentId
              AND o.user_id = :userId
              AND (:includeArchived = TRUE OR o.archived_at IS NULL)
              AND s.updated_at IS NOT NULL
            """, nativeQuery = true)
    List<SessionSummaryRow> listSessionSummaries(@Param("agentId") String agentId,
                                                 @Param("userId") String userId,
                                                 @Param("includeArchived") boolean includeArchived);

    /** 删除智能体时连带清理归属记录。 */
    void deleteByAgentId(String agentId);

    void deleteByAgentIdAndSessionId(String agentId, String sessionId);
}
