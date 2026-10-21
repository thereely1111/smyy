package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "skills")
public class Skill extends BaseEntity {

    @Column(nullable = false, unique = true, length = 128)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    /** 技能正文（markdown，SKILL.md 去掉 frontmatter 后的 body）；驱动 AgentScope AgentSkill 的 skillContent。M9 新增。 */
    @Column(columnDefinition = "text")
    private String content;

    /**
     * 文本资源（相对路径 → 文本内容，如 {@code scripts/foo.py}、{@code references/api.md}）；
     * 随 JpaSkillRepository 适配进 AgentScope AgentSkill.resources，load 技能时落进会话工作区。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> resources = new LinkedHashMap<>();

    /**
     * 磁盘源目录（绝对路径；git 型技能仓库导入时的快照拷贝）。非空时框架 prompt provider
     * 对该技能输出 {@code <files-root>}，模型可直接 shell 执行目录内脚本。
     */
    @Column(name = "origin_dir", columnDefinition = "text")
    private String originDir;

    @Column(name = "implementation_type", nullable = false, length = 64)
    private String implementationType = "skill";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ResourceStatus status = ResourceStatus.active;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    /**
     * 归属范围（V54）：PLATFORM 平台库 / PERSONAL 个人库。
     * 口径与判定规则见 {@link SkillScope}。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SkillScope scope = SkillScope.PLATFORM;

    /** 个人库归属人；PLATFORM 技能为 null（平台共有）。 */
    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    public SkillScope getScope() {
        return scope;
    }

    public void setScope(SkillScope scope) {
        this.scope = scope;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(UUID ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Map<String, String> getResources() {
        return resources;
    }

    public void setResources(Map<String, String> resources) {
        this.resources = resources == null ? new LinkedHashMap<>() : new LinkedHashMap<>(resources);
    }

    public String getOriginDir() {
        return originDir;
    }

    public void setOriginDir(String originDir) {
        this.originDir = originDir == null || originDir.isBlank() ? null : originDir.trim();
    }

    public String getImplementationType() {
        return implementationType;
    }

    public void setImplementationType(String implementationType) {
        this.implementationType = implementationType;
    }

    public ResourceStatus getStatus() {
        return status;
    }

    public void setStatus(ResourceStatus status) {
        this.status = status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}
