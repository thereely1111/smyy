package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 会话级告警推送订阅（V43 建表，V44 升级为渠道白名单）。
 *
 * <p><b>opt-in + 显式选渠道</b>：无记录或 {@link #isEnabled()}=false → 不外发；
 * enabled=true 时只外发 {@link #getChannelIds()} 勾选的渠道（CSV 渠道 UUID，
 * 空串 = 开了总开关但还没勾渠道，同样不外发）。站内铃铛 / SSE 不受影响——
 * 本表只管「告警要不要扇出到 IM 渠道、扇出到哪几个」。</p>
 */
@Entity
@Table(name = "session_alert_push",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_session_alert_push_user_session",
                columnNames = {"user_id", "session_id"}))
public class SessionAlertPush extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "session_id", nullable = false, length = 255)
    private String sessionId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** 外发渠道白名单，CSV 渠道 UUID（空串 = 未勾选任何渠道）。 */
    @Column(name = "channel_ids", nullable = false)
    private String channelIds = "";

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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getChannelIds() {
        return channelIds;
    }

    public void setChannelIds(String channelIds) {
        this.channelIds = channelIds == null ? "" : channelIds;
    }
}
