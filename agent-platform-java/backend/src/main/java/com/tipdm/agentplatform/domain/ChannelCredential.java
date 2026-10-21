package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 渠道凭据关联表：每行一个「键 → 值」（webhook_url / secret / url ...）。
 *
 * <p>替代原 channels.credentials JSONB 列（2026-09-16 修订）。值为 TEXT，
 * 含机器人 webhook 地址与加签密钥，<b>任何接口出参不回显</b>；编辑语义为
 * 整体覆盖——先删该渠道全部行再写入新值，避免「改了个键旧密钥仍残留」。
 * 渠道删除时随外键级联删除。</p>
 */
@Entity
@Table(name = "channel_credentials",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_channel_credentials",
                columnNames = {"channel_id", "cred_key"}))
public class ChannelCredential extends BaseEntity {

    /** 所属渠道（ON DELETE CASCADE，见 V42）。 */
    @Column(name = "channel_id", nullable = false)
    private java.util.UUID channelId;

    /** 凭据键，如 webhook_url / secret。 */
    @Column(name = "cred_key", nullable = false, length = 100)
    private String credKey;

    /**
     * 凭据值（AES-GCM 密文，Base64(IV||ciphertext)）。密钥，永不回显。
     *
     * <p>写入侧加密见 {@code ChannelService#replaceCredentials}；读取侧解密并兼容
     * 存量明文见 {@code AlertChannelDispatcher#decodeCredential}。</p>
     */
    @Column(name = "cred_value", nullable = false, columnDefinition = "TEXT")
    private String credValue = "";

    public java.util.UUID getChannelId() {
        return channelId;
    }

    public void setChannelId(java.util.UUID channelId) {
        this.channelId = channelId;
    }

    public String getCredKey() {
        return credKey;
    }

    public void setCredKey(String credKey) {
        this.credKey = credKey;
    }

    public String getCredValue() {
        return credValue;
    }

    public void setCredValue(String credValue) {
        this.credValue = credValue == null ? "" : credValue;
    }
}
