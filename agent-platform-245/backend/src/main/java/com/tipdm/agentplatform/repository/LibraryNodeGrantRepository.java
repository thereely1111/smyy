package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.GrantPrincipalType;
import com.tipdm.agentplatform.domain.LibraryNodeGrant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface LibraryNodeGrantRepository extends JpaRepository<LibraryNodeGrant, UUID> {

    /** 某节点的节点级授权；空间级授权（nodeId 为空）不在此列。 */
    List<LibraryNodeGrant> findByNodeId(UUID nodeId);

    Optional<LibraryNodeGrant> findByNodeIdAndPrincipalTypeAndPrincipalId(
            UUID nodeId, GrantPrincipalType principalType,
            UUID principalId);

    /** 某空间的成员（空间级授权，即 nodeId 为空的那一档）。 */
    List<LibraryNodeGrant> findBySpaceIdAndNodeIdIsNull(UUID spaceId);

    Optional<LibraryNodeGrant> findBySpaceIdAndNodeIdIsNullAndPrincipalTypeAndPrincipalId(
            UUID spaceId, GrantPrincipalType principalType, UUID principalId);

    /**
     * 批量取一个空间下的全部授权：判权时按空间一次拉齐，
     * 再在内存里按节点归位，避免逐节点查库。
     */
    List<LibraryNodeGrant> findBySpaceId(UUID spaceId);

    /**
     * 取「我作为成员参与的所有空间 id」（空间级授权那一档）。
     *
     * <p>列可见空间时用：除了自己 owner 的空间，还包括这些——不含它们
     * 被授权的团队空间在侧栏根本不出现，授权功能等于没做。</p>
     *
     * @param principalIds 我作为 USER 主体的 id + 我所属群组 id 合并后的集合
     */
    @Query("select distinct g.spaceId from LibraryNodeGrant g "
            + "where g.nodeId is null and g.principalType = com.tipdm.agentplatform.domain.GrantPrincipalType.USER "
            + "and g.principalId in :ids")
    List<UUID> findSpaceIdsGrantedToUser(@Param("ids") Set<UUID> ids);

    /** 同上，群组主体那一档。 */
    @Query("select distinct g.spaceId from LibraryNodeGrant g "
            + "where g.nodeId is null and g.principalType = com.tipdm.agentplatform.domain.GrantPrincipalType.GROUP "
            + "and g.principalId in :groupIds")
    List<UUID> findSpaceIdsGrantedToGroups(@Param("groupIds") Set<UUID> groupIds);

    /** 空间级授权中挂在某主体上的行：群组解散 / 删成员时按主体清理。 */
    @Query("select g from LibraryNodeGrant g "
            + "where g.nodeId is null and g.principalType = :type and g.principalId = :pid")
    List<LibraryNodeGrant> findSpaceLevelByPrincipal(@Param("type") GrantPrincipalType type,
            @Param("pid") UUID pid);

    void deleteByNodeId(UUID nodeId);
}
