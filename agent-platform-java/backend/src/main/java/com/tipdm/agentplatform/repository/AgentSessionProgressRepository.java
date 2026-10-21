package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.AgentSessionProgress;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话进度台账数据访问。
 *
 * <p>{@code upsert} 语义与 {@code agent_session_owners} 一致：每 (agent, session) 一行，
 * 由停滞看护周期覆盖写；{@code last_stall_wake_at} 只在补醒时由 service 显式刷新。</p>
 */
public interface AgentSessionProgressRepository
        extends JpaRepository<AgentSessionProgress, UUID> {

    Optional<AgentSessionProgress> findByAgentIdAndSessionId(
            String agentId, String sessionId);

    void deleteByAgentId(String agentId);

    void deleteByAgentIdAndSessionId(String agentId, String sessionId);

    /** 会话清理：不依赖 agentId 的按会话删除（clearSession 兜底链路用）。 */
    @Modifying
    @Transactional
    @Query("DELETE FROM AgentSessionProgress p WHERE p.sessionId = :sessionId")
    int deleteBySessionId(@Param("sessionId") String sessionId);

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO agent_session_progress
                (id, agent_id, session_id, user_id, phase, next_action,
                 progress_line, last_text, last_active_at, last_stall_wake_at,
                 wake_count, last_progress_at, needs_human,
                 created_at, updated_at)
            VALUES
                (:id, :agentId, :sessionId, :userId, :phase, :nextAction,
                 :progressLine, :lastText, :lastActiveAt, :lastStallWakeAt,
                 :wakeCount, :lastProgressAt, :needsHuman,
                 now(), now())
            ON CONFLICT (agent_id, session_id) DO UPDATE SET
                phase = EXCLUDED.phase,
                next_action = EXCLUDED.next_action,
                progress_line = EXCLUDED.progress_line,
                last_text = EXCLUDED.last_text,
                last_active_at = EXCLUDED.last_active_at,
                last_stall_wake_at = EXCLUDED.last_stall_wake_at,
                wake_count = EXCLUDED.wake_count,
                last_progress_at = EXCLUDED.last_progress_at,
                needs_human = EXCLUDED.needs_human,
                -- stop_requested 只由 interrupt（置位）/ 用户发起 run（清除）显式修改，
                -- 进度周期写不覆盖，否则运行中每轮进度刷新都会抹掉停止标记（V60）。
                stop_requested = agent_session_progress.stop_requested,
                -- awaiting_human_gate 同理：HITL 超时取消置位、用户新 run 清除，
                -- 周期写不覆盖（V62）。
                awaiting_human_gate = agent_session_progress.awaiting_human_gate,
                updated_at = now()
            """, nativeQuery = true)
    int upsert(@Param("id") UUID id,
               @Param("agentId") String agentId,
               @Param("sessionId") String sessionId,
               @Param("userId") String userId,
               @Param("phase") String phase,
               @Param("nextAction") String nextAction,
               @Param("progressLine") String progressLine,
               @Param("lastText") String lastText,
               @Param("lastActiveAt") java.time.Instant lastActiveAt,
               @Param("lastStallWakeAt") java.time.Instant lastStallWakeAt,
               @Param("wakeCount") int wakeCount,
               @Param("lastProgressAt") java.time.Instant lastProgressAt,
               @Param("needsHuman") boolean needsHuman);

    /**
     * 按 agent+session 置位「用户停止」标记（V60），无行时插入占位行。
     *
     * <p>独立于 {@link #upsert}：upsert 由看护周期调用且不感知停止语义，
     * 这里是唯一写入口——interrupt 置位、用户发起 run 清除。</p>
     *
     * <p><b>为什么是 upsert 而非 UPDATE-only</b>：「用户点停止」最容易发生在会话刚建立、
     * 看护首次扫描该会话之前（首轮 run 刚起跑就点停），此时台账无行，
     * UPDATE 影响 0 行会静默丢掉标记 ⇒ 停止意图完全失效，看护与三条唤醒通道
     * 照旧把会话拉起来续跑（用户报障「停止不生效」）。
     * 与 {@link #upsertAwaitingHumanGate} 同构。</p>
     *
     * <p>冲突分支只写 {@code stop_requested}，不碰 {@code awaiting_human_gate}——
     * 两列语义独立、任一置位即封存，互不覆盖。</p>
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO agent_session_progress
                (id, agent_id, session_id, user_id, awaiting_human_gate,
                 stop_requested, wake_count, needs_human, created_at, updated_at)
            VALUES
                (:id, :agentId, :sessionId, :userId, FALSE,
                 :requested, 0, FALSE, now(), now())
            ON CONFLICT (agent_id, session_id) DO UPDATE SET
                stop_requested = :requested,
                updated_at = now()
            """, nativeQuery = true)
    int upsertStopRequested(@Param("id") UUID id,
                            @Param("agentId") String agentId,
                            @Param("sessionId") String sessionId,
                            @Param("userId") String userId,
                            @Param("requested") boolean requested);

    /** 会话是否处于「用户已停止」抑制态（无记录 = 未抑制）。 */
    boolean existsBySessionIdAndStopRequestedTrue(String sessionId);

    /**
     * 置位/清除「等人工唤醒」封存标记（V62）。
     *
     * <p>独立于 {@link #upsert}：upsert 的冲突分支显式保留本列，周期写不覆盖。
     * 这里是唯一写入口——HITL 超时取消置位、用户主动发起新 run 清除。</p>
     */
    @Modifying
    @Transactional
    @Query("UPDATE AgentSessionProgress p SET p.awaitingHumanGate = :awaiting, p.updatedAt = :now"
            + " WHERE p.agentId = :agentId AND p.sessionId = :sessionId")
    int updateAwaitingHumanGate(@Param("agentId") String agentId,
                               @Param("sessionId") String sessionId,
                               @Param("awaiting") boolean awaiting,
                               @Param("now") Instant now);

    /**
     * 按 agent+session 置位「等人工唤醒」封存标记（V62），无行时插入占位行。
     *
     * <p>用 upsert 而非 UPDATE-only：HITL 超时取消可能发生在看护首次扫描该会话之前
     * （闸门在前几秒就挂起、用户 30 分钟不理的短会话），此时台账无行，
     * UPDATE 影响 0 行会静默丢掉封存 ⇒ 超时后又可被唤醒。因此这里补一行占位记录。</p>
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO agent_session_progress
                (id, agent_id, session_id, user_id, awaiting_human_gate,
                 stop_requested, wake_count, needs_human, created_at, updated_at)
            VALUES
                (:id, :agentId, :sessionId, :userId, TRUE,
                 FALSE, 0, FALSE, now(), now())
            ON CONFLICT (agent_id, session_id) DO UPDATE SET
                awaiting_human_gate = TRUE,
                updated_at = now()
            """, nativeQuery = true)
    int upsertAwaitingHumanGate(@Param("id") UUID id,
                                @Param("agentId") String agentId,
                                @Param("sessionId") String sessionId,
                                @Param("userId") String userId);

    /** 会话是否处于「等人工唤醒」封存态（无记录 = 未封存）。 */
    boolean existsBySessionIdAndAwaitingHumanGateTrue(String sessionId);

    /**
     * 登记「纯文本收尾」（V61）：宣告式收尾检测器观察到 run 以纯文本正常收场时写入 now()。
     *
     * <p>独立于 {@link #upsert}：upsert 的冲突分支不包含该列（周期写不覆盖），
     * 这里是唯一写入口。无行时插入占位行（仅归属字段 + 时间戳），有行时只刷新时间。</p>
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO agent_session_progress
                (id, agent_id, session_id, user_id, last_text_ending_at,
                 stop_requested, wake_count, needs_human, created_at, updated_at)
            VALUES
                (:id, :agentId, :sessionId, :userId, now(),
                 FALSE, 0, FALSE, now(), now())
            ON CONFLICT (agent_id, session_id) DO UPDATE SET
                last_text_ending_at = now(),
                updated_at = now()
            """, nativeQuery = true)
    int recordTextEnding(@Param("id") java.util.UUID id,
                         @Param("agentId") String agentId,
                         @Param("sessionId") String sessionId,
                         @Param("userId") String userId);
}
