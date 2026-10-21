package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 出站渠道（外部 IM / webhook 集成）。
 *
 * <p>用户级资源：凭据（机器人 webhook 地址、加签密钥）自持，告警按归属人扇出。
 * 凭据本体在 {@link ChannelCredential} 关联表（渠道 × 键 → 值），本表只放
 * 渠道自身的标量属性。完整 channel 模块（双向 bot、会话绑定）落地时再按需
 * 建真实表，不预留空 JSONB 列。</p>
 *
 * <p><b>安全边界</b>：关联表里的凭据含密钥，任何接口出参不回显；
 * 编辑时前端可提交新凭据整体覆盖。</p>
 */
@Entity
@Table(name = "channels")
public class AlertChannel extends BaseEntity {

    /** 渠道类型：wechat_work / dingtalk / feishu / webhook。 */
    @Column(name = "channel_type", nullable = false, length = 32)
    private String channelType;

    /** 归属用户（渠道严格按用户隔离）。 */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 展示名（可空，空则列表显示类型名）。 */
    @Column(name = "name", length = 200)
    private String name;

    /** 外发最低级别：info / warn / error（默认 warn）。 */
    @Column(name = "min_level", nullable = false, length = 8)
    private String minLevel = "warn";

    /** 是否启用（停用渠道不参与告警扇出）。 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** 最近一次出站投递推导的状态：stopped / connected / failed。 */
    @Column(name = "status", nullable = false, length = 16)
    private String status = "stopped";

    /** 最近一次投递失败原因（成功时清空）。 */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /** 最近一次投递时间（含测试发送）。 */
    @Column(name = "last_sent_at")
    private Instant lastSentAt;

    public String getChannelType() {
        return channelType;
    }

    public void setChannelType(String channelType) {
        this.channelType = channelType;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMinLevel() {
        return minLevel;
    }

    public void setMinLevel(String minLevel) {
        this.minLevel = minLevel;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getLastSentAt() {
        return lastSentAt;
    }

    public void setLastSentAt(Instant lastSentAt) {
        this.lastSentAt = lastSentAt;
    }
}
