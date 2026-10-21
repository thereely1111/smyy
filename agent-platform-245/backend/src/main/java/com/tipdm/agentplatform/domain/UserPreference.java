package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 用户偏好（见 V72 迁移）：一个人一行，偏好体为 JSONB。
 *
 * <p><b>为什么用 JSONB 而不是一列一个偏好项</b>：偏好项会持续增加（主题、语言、
 * 打字机、默认模型、免打扰……），每加一项就 ALTER TABLE 会让每次发版都带着 DDL。
 * JSONB 让新增偏好项只是多一个 JSON 字段，不动表结构。</p>
 *
 * <p>代价是失去数据库层的取值范围约束——由应用层的
 * {@code UserPreferencePayload} 承担校验，见其类注释。</p>
 */
@Entity
@Table(name = "user_preferences")
public class UserPreference {

    /** 一个人一行；与 {@code users} 级联删除。 */
    @Id
    @Column(name = "user_id", nullable = false)
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID userId;

    /**
     * 偏好 JSON。字段语义见 {@code UserPreferencePayload}。
     *
     * <p>映射成 {@code Map} 而非 {@code String}：本项目已有的 jsonb 列
     * （{@code Skill.resources}、{@code ModelCredential.enabledModels} 等）一律是
     * {@code @JdbcTypeCode(SqlTypes.JSON)} + {@code Map}/{@code List}。把 jsonb 列
     * 声明成 String 会让 Hibernate 用文本绑定读写，与列类型不一致——
     * 而 {@code ddl-auto=validate} 只校验列类型存在，不校验 JDBC 绑定方式，
     * 写法不对时只在第一次读写偏好时才报错。</p>
     */
    @Column(name = "prefs", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> prefs = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public Map<String, Object> getPrefs() {
        return prefs;
    }

    public void setPrefs(Map<String, Object> prefs) {
        this.prefs = prefs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
