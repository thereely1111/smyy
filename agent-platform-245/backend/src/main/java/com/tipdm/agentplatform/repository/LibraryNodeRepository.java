package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.LibraryNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LibraryNodeRepository extends JpaRepository<LibraryNode, UUID> {

    /**
     * 列某空间下某父节点的直接子节点（已软删的排除），按 sort_order 升序。
     * 这是最热的查询：目录树的一层展开。
     *
     * @param parentId 父节点 id；null 表示列空间根节点
     */
    List<LibraryNode> findBySpaceIdAndParentIdAndDeletedAtIsNullOrderBySortOrderAsc(
            UUID spaceId, UUID parentId);

    /** 未被软删的单节点。 */
    Optional<LibraryNode> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * 取子树：按物化路径前缀一次命中（路径首尾带分隔符，故前缀天然只匹配子树）。
     *
     * @param pathPrefix 形如 {@code /<rootId>/} 的前缀
     * @param excludeId 需排除的节点 id（移动子树时排除自身），可为 null
     */
    @Query("SELECT n FROM LibraryNode n WHERE n.nodePath LIKE CONCAT(:pathPrefix, '%') "
            + "AND n.deletedAt IS NULL AND (:excludeId IS NULL OR n.id <> :excludeId) "
            + "ORDER BY n.nodePath ASC, n.sortOrder ASC")
    List<LibraryNode> findSubtree(@Param("pathPrefix") String pathPrefix,
                                  @Param("excludeId") UUID excludeId);

    /** 同级排序位的下一个值：新增子节点时取 max+1。 */
    @Query("SELECT COALESCE(MAX(n.sortOrder), -1) + 1 FROM LibraryNode n "
            + "WHERE n.spaceId = :spaceId AND n.parentId = :parentId AND n.deletedAt IS NULL")
    int nextSortOrder(@Param("spaceId") UUID spaceId, @Param("parentId") UUID parentId);

    /** 反查挂载了某知识库的节点。 */
    List<LibraryNode> findByPayloadKbId(UUID payloadKbId);

    /** 某空间下全部未删节点（空间删除/导出时用）。 */
    List<LibraryNode> findBySpaceIdAndDeletedAtIsNull(UUID spaceId);

    /**
     * 全部未删节点（不限空间）。
     *
     * <p>用于「用户可见范围 = 其所有空间」的检索场景——此时空间集合由 ACL 决定，
     * 逐空间查不如一次取回再按 ACL 过滤。<b>数据量增长后应改为按 owner 收窄</b>。</p>
     */
    List<LibraryNode> findByDeletedAtIsNull();
}


