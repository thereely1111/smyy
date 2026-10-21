package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 标签（管理员统一定义的封闭集合，2026-09-20）。
 *
 * <p>标签是一等资源：改名只动这一行即全平台生效；停用（enabled=false）是软停，
 * 不物理删，避免消费端悬挂；排序 sort 决定筛选 chips 与表单下拉的展示顺序。
 * color 字段已下线（V55）：前端选中态统一品牌蓝，不再按标签上彩。</p>
 */
@Entity
@Table(name = "tags")
public class Tag extends BaseEntity {

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    @Column(nullable = false)
    private int sort = 0;

    @Column(nullable = false)
    private boolean enabled = true;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getSort() {
        return sort;
    }

    public void setSort(int sort) {
        this.sort = sort;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
