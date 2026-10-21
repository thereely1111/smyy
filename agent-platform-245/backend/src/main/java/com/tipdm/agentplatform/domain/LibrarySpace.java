package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * 资料库空间：内容的持有边界，每人一个个人空间，可建多个团队空间。
 *
 * <p>DB 侧用部分唯一索引 {@code uq_library_spaces_personal} 保证每用户至多一个
 * {@code personal} 空间，团队空间数量不限。</p>
 */
@Entity
@Table(name = "library_spaces")
public class LibrarySpace extends BaseEntity {

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LibrarySpaceCategory category;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    /** 节点未单独授权时回退到此值；{@code null} 表示仅 owner 可访问。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_permission", length = 8)
    private LibraryPermission defaultPermission;

    @Column(columnDefinition = "text")
    private String description;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LibrarySpaceCategory getCategory() {
        return category;
    }

    public void setCategory(LibrarySpaceCategory category) {
        this.category = category;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(UUID ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public LibraryPermission getDefaultPermission() {
        return defaultPermission;
    }

    public void setDefaultPermission(LibraryPermission defaultPermission) {
        this.defaultPermission = defaultPermission;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}

