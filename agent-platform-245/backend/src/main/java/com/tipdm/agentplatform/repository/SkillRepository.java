package com.tipdm.agentplatform.repository;

import com.tipdm.agentplatform.domain.ResourceStatus;
import com.tipdm.agentplatform.domain.Skill;
import com.tipdm.agentplatform.domain.SkillScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SkillRepository extends JpaRepository<Skill, UUID> {

    Page<Skill> findByNameContainingIgnoreCaseAndStatus(String name, ResourceStatus status, Pageable pageable);

    Page<Skill> findByNameContainingIgnoreCase(String name, Pageable pageable);

    Page<Skill> findByStatus(ResourceStatus status, Pageable pageable);

    // ── scope 维度（V54 个人/平台分离；未带 scope 前缀的方法语义=全部，仅框架内部使用）──

    Page<Skill> findByScopeAndNameContainingIgnoreCaseAndStatus(
            SkillScope scope, String name, ResourceStatus status, Pageable pageable);

    Page<Skill> findByScopeAndNameContainingIgnoreCase(SkillScope scope, String name, Pageable pageable);

    Page<Skill> findByScopeAndStatus(SkillScope scope, ResourceStatus status, Pageable pageable);

    Page<Skill> findByScope(SkillScope scope, Pageable pageable);

    Page<Skill> findByScopeAndOwnerUserIdAndNameContainingIgnoreCaseAndStatus(
            SkillScope scope, UUID ownerUserId, String name, ResourceStatus status, Pageable pageable);

    Page<Skill> findByScopeAndOwnerUserIdAndNameContainingIgnoreCase(
            SkillScope scope, UUID ownerUserId, String name, Pageable pageable);

    Page<Skill> findByScopeAndOwnerUserIdAndStatus(
            SkillScope scope, UUID ownerUserId, ResourceStatus status, Pageable pageable);

    Page<Skill> findByScopeAndOwnerUserId(SkillScope scope, UUID ownerUserId, Pageable pageable);

    Optional<Skill> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);
}
