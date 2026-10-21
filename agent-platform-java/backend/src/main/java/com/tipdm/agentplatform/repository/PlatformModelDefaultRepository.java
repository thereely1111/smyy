package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.PlatformModelDefault;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 平台默认模型单例行仓库。全局最多一行（id=1），见 V57 迁移。
 */
public interface PlatformModelDefaultRepository extends JpaRepository<PlatformModelDefault, Short> {
}
