package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 平台通知（主动告知闭环的持久化面）。
 *
 * <p>与 {@code NotifyStreamRegistry} 的 SSE 通道互补：SSE 只服务在线用户且不保交付，
 * 本表保证「事件不丢」——用户离线期间产生的告警，下次进站仍能在铃铛里看到未读。</p>
 *
 * <p>字段与意义：</p>
 * <ul>
 *   <li>{@code level}：{@code info} / {@code warn} / {@code error}；达到外部渠道阈值
 *       （见 {@code agent-platform.alert.wecom.min-level}）时额外外发企业微信机器人。</li>
 *   <li>{@code sessionId} / {@code agentId}：告警关联会话，前端据此提供「打开会话」跳转。</li>
 *   <li>{@code alertKey}：去重键（如 {@code stall:main-xxx}）。周期性看护每轮都会命中
 *       同一停摆会话，靠它在冷却窗口内抑制重复落库，避免刷屏（2026-09-16：
 *       看护 60s 一轮，若无去重一夜可产生数千条）。</li>
 *   <li>{@code readAt}：非空即已读；未读角标走部分索引。</li>
 * </ul>
 */
@Entity
@Table(name = "platform_notification")
public class PlatformNotification extends BaseEntity {

    /** 归属用户（通知严格按用户隔离）。 */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 级别：info / warn / error。 */
    @Column(name = "level", nullable = false, length = 16)
    private String level;

    /** 一句标题（铃铛列表与 Toast 首行）。 */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** 详情正文（可空）。 */
    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    /** 关联会话 id（AG-UI threadId，可空——系统级告警无会话）。 */
    @Column(name = "session_id", length = 255)
    private String sessionId;

    /** 关联智能体 id（可空）。 */
    @Column(name = "agent_id", length = 64)
    private String agentId;

    /** 去重键（可空；空键不参与冷却抑制）。 */
    @Column(name = "alert_key", length = 128)
    private String alertKey;

    /** 已读时间（null = 未读）。 */
    @Column(name = "read_at")
    private Instant readAt;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getAlertKey() {
        return alertKey;
    }

    public void setAlertKey(String alertKey) {
        this.alertKey = alertKey;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }
}
