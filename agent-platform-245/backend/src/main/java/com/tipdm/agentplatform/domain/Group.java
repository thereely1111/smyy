package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * 内置轻量群组：用于知识库按群组批量授权。
 */
@Entity
@Table(name = "groups")
public class Group extends BaseEntity {

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }
}
