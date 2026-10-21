package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.AgentTag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AgentTagRepository extends JpaRepository<AgentTag, AgentTag.Pk> {

    List<AgentTag> findByAgentId(UUID agentId);

    List<AgentTag> findByTagId(UUID tagId);

    void deleteByAgentId(UUID agentId);

    void deleteByTagId(UUID tagId);

    /**
     * JPQL bulk delete：直接在数据库执行、不经过持久化上下文——
     * 「全量替换」场景下先清后插不会因 flush 顺序（insert 先于 delete）撞唯一键。
     */
    @Modifying
    @Query("delete from AgentTag a where a.agentId = :agentId")
    void deleteAllForAgent(@Param("agentId") UUID agentId);
}
