package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.ChannelCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ChannelCredentialRepository extends JpaRepository<ChannelCredential, UUID> {

    /** 单渠道凭据（编辑回显除外——凭据不回显，此处供发送/测试读取）。 */
    List<ChannelCredential> findByChannelId(UUID channelId);

    /** 批量读取：告警扇出前一次性取全部候选渠道的凭据，避免 N+1。 */
    List<ChannelCredential> findByChannelIdIn(Collection<UUID> channelIds);

    /**
     * 整体覆盖前的清场：删该渠道全部凭据行。
     * ⚠️ bulk delete 必须带 @Transactional（漏标 → TransactionRequiredException）。
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM ChannelCredential c WHERE c.channelId = :channelId")
    void deleteByChannelId(@Param("channelId") UUID channelId);
}
