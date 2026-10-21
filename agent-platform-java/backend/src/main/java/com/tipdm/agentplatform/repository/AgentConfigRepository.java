package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.AgentConfig;
import com.tipdm.agentplatform.domain.AgentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AgentConfigRepository extends JpaRepository<AgentConfig, UUID> {

    Page<AgentConfig> findByNameContainingIgnoreCase(String name, Pageable pageable);

    /** 广场口径：只取发布态（草稿仅管理端 includeDrafts 可见）。 */
    Page<AgentConfig> findByStatus(AgentStatus status, Pageable pageable);

    List<AgentConfig> findByStatus(AgentStatus status);

    Page<AgentConfig> findByNameContainingIgnoreCaseAndStatus(
            String name, AgentStatus status, Pageable pageable);

    /** 重名校验：create 传 null 排除自身不存在，update 传当前 id。 */
    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);
}
