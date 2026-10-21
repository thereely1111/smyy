package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.AguiEventRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * AG-UI 事件落库记录仓储。
 *
 * <p>查询一律带 {@code userId}：事件写入时的归属用户即查询者的天然过滤条件，
 * 非本人会话查不到任何行（无需额外归属校验）。</p>
 */
public interface AguiEventRecordRepository extends JpaRepository<AguiEventRecord, UUID> {

    /** 会话内已落库的最大事件序号（用于服务重启后接续分配 event_sequence）。 */
    @Query("select max(e.eventSequence) from AguiEventRecord e where e.sessionId = :sessionId")
    Long findMaxSequenceBySessionId(@Param("sessionId") String sessionId);

    /** 按序号升序取某用户某会话的事件（limit 用 Pageable 表达）。 */
    List<AguiEventRecord> findByUserIdAndSessionIdOrderByEventSequenceAsc(
            String userId, String sessionId, Pageable pageable);

    /** 增量拉取：只取 eventSequence > after 的事件（前端/调用方断点续传）。 */
    List<AguiEventRecord> findByUserIdAndSessionIdAndEventSequenceGreaterThanOrderByEventSequenceAsc(
            String userId, String sessionId, long after, Pageable pageable);

    /** 某用户某会话的最大事件序号（增量跟随的起点）。 */
    @Query("select max(e.eventSequence) from AguiEventRecord e "
            + "where e.userId = :userId and e.sessionId = :sessionId")
    Long findMaxSequenceByUserIdAndSessionId(@Param("userId") String userId,
                                             @Param("sessionId") String sessionId);

    /** 某用户某会话最后一条 RUN_STARTED 的序号（无记录返回 null）。 */
    @Query("select max(e.eventSequence) from AguiEventRecord e "
            + "where e.userId = :userId and e.sessionId = :sessionId and e.eventType = 'RUN_STARTED'")
    Long findMaxRunStartSequenceByUserIdAndSessionId(@Param("userId") String userId,
                                                     @Param("sessionId") String sessionId);

    /** 会话清理：删除该会话全部落库事件（一次长会话可达数千行，用批量 delete 而非逐条）。 */
    @Modifying
    @Transactional
    @Query("DELETE FROM AguiEventRecord e WHERE e.sessionId = :sessionId")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
