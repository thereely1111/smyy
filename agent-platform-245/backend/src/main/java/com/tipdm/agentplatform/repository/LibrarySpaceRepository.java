package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.LibrarySpace;
import com.tipdm.agentplatform.domain.LibrarySpaceCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LibrarySpaceRepository extends JpaRepository<LibrarySpace, UUID> {

    /** 取用户的个人空间（每用户至多一个，DB 部分唯一索引约束）。 */
    Optional<LibrarySpace> findByOwnerUserIdAndCategory(UUID ownerUserId,
                                                      LibrarySpaceCategory category);

    /**
     * 列某用户可见的空间：owner 自己的全部空间 + 被授权的团队空间。
     * 团队空间经节点授权冒泡上来（授权挂在节点上，空间 id 冗余在授权行）。
     */
    List<LibrarySpace> findByOwnerUserId(UUID ownerUserId);
}

