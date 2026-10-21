package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * 群组成员关系（用户 ↔ 群组 多对多）。
 * 知识库授权展开时：用户对某 KB 的有效权限 = 直接授权 ∪ 所属群组授权（取最大）。
 */
@Entity
@Table(name = "user_groups", uniqueConstraints = @UniqueConstraint(columnNames = {"group_id", "user_id"}))
public class UserGroup extends BaseEntity {

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    public UUID getGroupId() {
        return groupId;
    }

    public void setGroupId(UUID groupId) {
        this.groupId = groupId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }
}
