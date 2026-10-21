package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.AlertChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlertChannelRepository extends JpaRepository<AlertChannel, UUID> {

    /** 渠道列表主路径：按用户倒序。 */
    List<AlertChannel> findByUserIdOrderByCreatedAtDesc(String userId);

    /** 告警扇出：取该用户全部启用渠道。 */
    List<AlertChannel> findByUserIdAndEnabledTrue(String userId);

    /** 单条归属校验：越权 id 查不到即 404。 */
    Optional<AlertChannel> findByIdAndUserId(UUID id, String userId);
}
