package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 资料库节点：空间内的树节点，**只承载树结构与元信息，正文按 kind 外置**。
 *
 * <p>「薄壳」是刻意设计——doc 的markdown、database 的行列、drive 的文件字节、
 * link 的剪藏正文都不进本表，新增 kind 时无需改动此表或迁移数据。
 * 相应代价是取正文要按 kind 分派。</p>
 *
 * <p>{@code parentId} 与 {@code nodePath} 双写：读子树 / 读祖先链走 path
 * （前缀一次命中），移动节点时改父指针 + 重写子树 path。
 * 深度上限在 service 层拦截，不靠 DB 约束。</p>
 *
 * <p>删除用软删（{@code deletedAt}）：保留行以免破坏子树 path 的引用完整性，
 * 所有读取一律过滤 {@code deletedAt IS NULL}。</p>
 */
@Entity
@Table(name = "library_nodes")
public class LibraryNode extends BaseEntity {

    @Column(name = "space_id", nullable = false)
    private UUID spaceId;

    /** 自引用父节点；根节点为 null。 */
    @Column(name = "parent_id")
    private UUID parentId;

    /**
     * 物化路径：以 '/' 分隔的 id 序列且首尾保留分隔符，如 {@code /<rootId>/<childId>/}。
     * 首尾分隔符让前缀匹配天然只命中子树，无需再拼 LIKE 的边界条件。
     */
    @Column(name = "node_path", nullable = false, columnDefinition = "text")
    private String nodePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LibraryNodeKind kind;

    @Column(nullable = false, length = 255)
    private String title;

    /** 同级排序位（越小越靠前）。 */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** {@code kind=knowledge} 时指向 {@code knowledge_bases.id}；其余 kind 为 null。 */
    @Column(name = "payload_kb_id")
    private UUID payloadKbId;

    /** 正文的存储相对路径（doc 的 markdown、drive 的文件字节）；DB 只存相对路径。 */
    @Column(name = "content_rel", length = 512)
    private String contentRel;

    /** 预览摘要，列表页展示用，避免为列表页读正文文件。 */
    @Column(columnDefinition = "text")
    private String excerpt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public UUID getSpaceId() {
        return spaceId;
    }

    public void setSpaceId(UUID spaceId) {
        this.spaceId = spaceId;
    }

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    public String getNodePath() {
        return nodePath;
    }

    public void setNodePath(String nodePath) {
        this.nodePath = nodePath;
    }

    public LibraryNodeKind getKind() {
        return kind;
    }

    public void setKind(LibraryNodeKind kind) {
        this.kind = kind;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public UUID getPayloadKbId() {
        return payloadKbId;
    }

    public void setPayloadKbId(UUID payloadKbId) {
        this.payloadKbId = payloadKbId;
    }

    public String getContentRel() {
        return contentRel;
    }

    public void setContentRel(String contentRel) {
        this.contentRel = contentRel;
    }

    public String getExcerpt() {
        return excerpt;
    }

    public void setExcerpt(String excerpt) {
        this.excerpt = excerpt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    /** 是否已被软删。查询一律走 {@code deleted_at IS NULL}，此处供业务判断复用。 */
    public boolean isDeleted() {
        return deletedAt != null;
    }
}

