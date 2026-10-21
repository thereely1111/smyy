package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * 资料库授权：把 READ / WRITE 授予一个用户或群组。一张表两档语义——
 *
 * <p><b>空间级</b>（{@code nodeId == null}）：团队空间成员资格。
 * 授予后整个空间可见可读（WRITE 还可写）。</p>
 *
 * <p><b>节点级</b>（{@code nodeId != null}）：单节点授权，沿祖先链冒泡覆盖其整棵子树
 * ——授权给某个文件夹即覆盖里面全部内容。</p>
 *
 * <p>两档各自「同一主体仅一条」：节点级唯一键是
 * {@code (node_id, principal_type, principal_id)}，空间级是
 * {@code (space_id, principal_type, principal_id)}，由 V67 建的两个部分唯一索引保证
 * ——不能合成一条 UNIQUE，因为 PostgreSQL 里 NULL 不参与唯一判定。
 * {@code spaceId} 在两档都冗余一份，使「按空间批量查授权」不必 join {@code library_nodes}。</p>
 */
@Entity
@Table(name = "library_node_grants")
public class LibraryNodeGrant extends BaseEntity {

    /** 为空表示整空间授权（团队空间成员）；非空表示节点级授权。 */
    @Column(name = "node_id")
    private UUID nodeId;

    @Column(name = "space_id", nullable = false)
    private UUID spaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", nullable = false, length = 8)
    private GrantPrincipalType principalType;

    @Column(name = "principal_id", nullable = false)
    private UUID principalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private LibraryPermission permission;

    @Column(name = "granted_by", nullable = false)
    private UUID grantedBy;

    public UUID getNodeId() {
        return nodeId;
    }

    public void setNodeId(UUID nodeId) {
        this.nodeId = nodeId;
    }

    public UUID getSpaceId() {
        return spaceId;
    }

    public void setSpaceId(UUID spaceId) {
        this.spaceId = spaceId;
    }

    public GrantPrincipalType getPrincipalType() {
        return principalType;
    }

    public void setPrincipalType(GrantPrincipalType principalType) {
        this.principalType = principalType;
    }

    public UUID getPrincipalId() {
        return principalId;
    }

    public void setPrincipalId(UUID principalId) {
        this.principalId = principalId;
    }

    public LibraryPermission getPermission() {
        return permission;
    }

    public void setPermission(LibraryPermission permission) {
        this.permission = permission;
    }

    public UUID getGrantedBy() {
        return grantedBy;
    }

    public void setGrantedBy(UUID grantedBy) {
        this.grantedBy = grantedBy;
    }
}

