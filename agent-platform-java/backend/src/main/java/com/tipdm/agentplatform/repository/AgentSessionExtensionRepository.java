package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.AgentSessionExtension;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * 智能体会话扩展信息数据访问。
 *
 * <p>教材库等动态多条件搜索统一通过 {@link JpaSpecificationExecutor} 的
 * {@code Specification} 实现（条件按需拼接、分页），不在此定义固定的 {@code @Query} 字符串。</p>
 */
public interface AgentSessionExtensionRepository
        extends JpaRepository<AgentSessionExtension, UUID>,
        JpaSpecificationExecutor<AgentSessionExtension> {

    Optional<AgentSessionExtension> findByAgentIdAndSessionId(String agentId, String sessionId);

    void deleteByAgentId(String agentId);

    void deleteByAgentIdAndSessionId(String agentId, String sessionId);

    /** 会话清理：不依赖 agentId 的按会话删除（条目随主行 ON DELETE CASCADE 级联）。 */
    void deleteBySessionId(String sessionId);
}
