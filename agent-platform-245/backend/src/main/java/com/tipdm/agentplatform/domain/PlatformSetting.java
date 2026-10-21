package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 系统配置覆盖层的一行（见 V71 迁移）。
 *
 * <p><b>本表是覆盖层，不是第二份真源</b>：生效值 = 本表覆盖值（若存在）
 * &gt; 环境变量 &gt; {@code application.yml} 默认值。表内无行时行为与本表存在之前
 * 完全一致，部署方不需要任何改动。</p>
 *
 * <p><b>只存覆盖值，不存完整配置</b>：{@code application.yml} 仍是默认值的唯一
 * 出处。若整份配置都落库，运维改yml 里的默认值只影响「本表没有覆盖」的部署，
 * 两处默认值各改一半的行为会长期难以排查。</p>
 *
 * <p>主键取配置键本身（不用代理 id）：访问路径永远是「按 key 读/写」，
 * 代理主键在这条路径上不提供任何额外能力，反而多一次查表。</p>
 */
@Entity
@Table(name = "platform_setting")
public class PlatformSetting {

    /**
     * 配置键，形如 {@code progress-watchdog.stall-min-minutes}
     * （与 {@code application.yml} 里的路径一致，便于对照）。
     *
     * <p>键的合法性由 {@code SettingCatalog} 判定：不在注册表里的键既不接受写入、
     * 也不在启动时应用——否则往表里手插一行就能改到一个「改了不生效」的死字段。</p>
     */
    @Id
    @Column(name = "key", nullable = false, length = 160)
    private String key;

    /**
     * 值的JSON 标量文本：数字与布尔直接是字面量，字符串是 JSON 字符串，
     * 字符串数组是 JSON 数组。
     *
     * <p>统一以文本存、而不是各建一个类型列：键的类型已由 {@code SettingCatalog}
     * 声明，读写时才知道该怎么解析；多几列的类型判断只会带来「类型与列不符」的
     * 第二处判据。</p>
     */
    @Column(name = "value", nullable = false, columnDefinition = "text")
    private String value;

    /** 最后修改人（审计）。 */
    @Column(name = "updated_by")
    @JdbcTypeCode(SqlTypes.UUID)
    private UUID updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(UUID updatedBy) {
        this.updatedBy = updatedBy;
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
