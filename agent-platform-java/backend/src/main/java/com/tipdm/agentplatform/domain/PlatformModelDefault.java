package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * 平台默认模型（全局单例行，id 恒为 1，见 V57 迁移）。
 *
 * <p>有且只有一个默认模型：以「平台共享凭证 + 该凭证下的启用模型」二元组表示，
 * 设置新值直接覆盖旧行（互斥由单例行结构保证）。不加外键——凭证删除 /
 * 模型禁用时由 {@code PlatformModelDefaultService} 清理，避免删除顺序耦合。</p>
 */
@Entity
@Table(name = "platform_model_default")
public class PlatformModelDefault {

    /** 单例行主键，恒为 1。 */
    @Id
    @Column(name = "id")
    private Short id = 1;

    /** 平台共享凭证 id。 */
    @Column(name = "credential_id", nullable = false)
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID credentialId;

    /** 凭证下的模型名（厂商 models 接口返回口径）。 */
    @Column(name = "model", nullable = false, length = 128)
    private String model;

    /** 最后设置人（审计）。 */
    @Column(name = "updated_by")
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID updatedBy;

    public Short getId() {
        return id;
    }

    public UUID getCredentialId() {
        return credentialId;
    }

    public void setCredentialId(UUID credentialId) {
        this.credentialId = credentialId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(UUID updatedBy) {
        this.updatedBy = updatedBy;
    }
}
