package com.tipdm.agentplatform.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * 模型凭证：模型供应商（DashScope / OpenAI / DeepSeek 等）的接入配置，
 * 本体是 schema 驱动的自由键值（api_key、base_url、name …）。
 *
 * <p><b>安全边界</b>：writeOnly 字段（api_key 等）入库前经
 * {@code ExternalApiKeyCipher} 加密；任何列表 / 详情接口只返回掩码哨兵，
 * 不回显明文；编辑时空值 / 掩码哨兵 = 保留旧值。</p>
 */
@Entity
@Table(name = "model_credentials")
public class ModelCredential extends BaseEntity {

    /** 归属用户（UUID 字符串由服务层与 JWT 用户对齐）。 */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * 共享范围：USER = 用户私有（归属即权限）；PLATFORM = 平台共享，
     * 管理员创建、全员可读可用于对话选模型（密钥仍不出库，非归属者只见 type/name）。
     */
    @Column(name = "scope", nullable = false, length = 16)
    private String scope = "USER";

    /** 供应商类型（credential schema 的 type const，如 dashscope / openai）。 */
    @Column(name = "cred_type", nullable = false, length = 64)
    private String credType;

    /** 凭证数据（schema 驱动的自由键值；密钥字段为密文）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", nullable = false, columnDefinition = "jsonb")
    private JsonNode data;

    /**
     * 启用模型清单（jsonb 字符串数组）：NULL = 未设置 = 全部可用（存量凭证兼容）；
     * 非空 = 仅列出的模型名在各模型下拉可见。核验清单本身存 data.vendor_models。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "enabled_models", columnDefinition = "jsonb")
    private JsonNode enabledModels;

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getCredType() {
        return credType;
    }

    public void setCredType(String credType) {
        this.credType = credType;
    }

    public JsonNode getData() {
        return data;
    }

    public void setData(JsonNode data) {
        this.data = data;
    }

    public JsonNode getEnabledModels() {
        return enabledModels;
    }

    public void setEnabledModels(JsonNode enabledModels) {
        this.enabledModels = enabledModels;
    }
}
