package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * AG-UI 事件落库记录：HTTP 端点输出的全部 AG-UI 协议事件（SSE 线上格式）。
 *
 * <p>捕获点在框架 {@code AguiEventEnricher} 层（见
 * {@link com.tipdm.agentplatform.config.AguiEventLogEnricher}），异步批量写入。
 * 查询键为 {@code (userId, sessionId, eventSequence)}，会话内序号单调递增、跨 run 连续。</p>
 *
 * <p>{@code payload} 是事件序列化后的线上 JSON（与 SSE {@code data:} 载荷一致，
 * 含 {@code type} 字段），用 JSONB 列存储便于将来按内容检索。</p>
 *
 * <p>事件记录是只读的（append-only），不需要 {@code updated_at}，
 * 因此不继承 {@link BaseEntity}。</p>
 */
@Entity
@Table(name = "agui_events")
public class AguiEventRecord {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 归属用户 id（JWT payload 的 userId）。 */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 会话 id（AG-UI threadId，前端 main-{uuid}）。 */
    @Column(name = "session_id", nullable = false, length = 255)
    private String sessionId;

    /** 一次 run 的 id（AG-UI runId）。 */
    @Column(name = "run_id", nullable = false, length = 128)
    private String runId;

    /** 会话内单调递增序号（跨 run 连续，唯一键的一部分）。 */
    @Column(name = "event_sequence", nullable = false)
    private long eventSequence;

    /** 事件类型（AguiEventType 枚举名，如 RUN_STARTED / TEXT_MESSAGE_CONTENT）。 */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /** 事件完整线上 JSON（JSONB 存储）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public long getEventSequence() {
        return eventSequence;
    }

    public void setEventSequence(long eventSequence) {
        this.eventSequence = eventSequence;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    @PrePersist
    void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
