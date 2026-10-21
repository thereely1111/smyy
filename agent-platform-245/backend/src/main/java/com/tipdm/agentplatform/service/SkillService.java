package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.ResourceStatus;
import com.tipdm.agentplatform.domain.Skill;
import com.tipdm.agentplatform.domain.SkillScope;
import com.tipdm.agentplatform.repository.SkillRepository;
import com.tipdm.agentplatform.web.auth.Viewer;
import com.tipdm.agentplatform.web.dto.CommonDtos.PageResponse;
import com.tipdm.agentplatform.web.dto.SkillDtos.SkillRequest;
import com.tipdm.agentplatform.web.dto.SkillDtos.SkillResponse;
import com.tipdm.agentplatform.web.error.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 技能服务（V54 个人/平台分离）：
 * <ul>
 *   <li><b>PLATFORM</b>：平台库，语义与既有行为一致——全员可读，写操作需
 *       {@code skill:create/update/status/delete} 权限码（门槛收敛在接口层，本服务不感知）。</li>
 *   <li><b>PERSONAL</b>：个人库，创建者自治——可见与可写均限 owner / 管理员，
 *       他人一律 404（不暴露存在性，与 {@code SessionAccessGuard} 同一反侦察口径）。</li>
 * </ul>
 * 归属字段（scope/ownerUserId）由服务端按端点语义写入，不经请求体（防变造）。
 */
@Service
public class SkillService {

    private final SkillRepository repository;

    public SkillService(SkillRepository repository) {
        this.repository = repository;
    }

    /**
     * 列表：scope=PERSONAL 时只返回调用者自己的技能；
     * scope=PLATFORM 或缺省时返回平台库（全局可读语义不变）。
     */
    public PageResponse<SkillResponse> list(
            String search, ResourceStatus status, int page, int size,
            SkillScope scope, Viewer viewer) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Page<Skill> result;
        if (scope == SkillScope.PERSONAL) {
            UUID owner = requireOwner(viewer);
            result = findOwned(search, status, owner, pageable);
        } else {
            result = findPlatform(search, status, pageable);
        }
        return new PageResponse<>(
                result.getContent().stream().map(this::toResponse).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    /** 详情：平台库全员可读；个人库仅 owner / 管理员（他人 404）。 */
    public SkillResponse get(UUID id, Viewer viewer) {
        return toResponse(requireVisible(id, viewer));
    }

    /** 平台库创建（skill:create 门槛在接口层）。createdBy 保留请求值（仓库导入标记 'skill-repository'）。 */
    @Transactional
    public SkillResponse createPlatform(SkillRequest request) {
        if (repository.existsByNameIgnoreCase(request.name().trim())) {
            throw ApiException.conflict("技能名称已存在: " + request.name());
        }
        Skill skill = new Skill();
        apply(skill, request);
        skill.setScope(SkillScope.PLATFORM);
        skill.setOwnerUserId(null);
        return toResponse(repository.save(skill));
    }

    /**
     * 个人库创建：任何登录用户可建，归属人 = 调用者。
     * createdBy 不采信请求值（个人库无仓库导入语义），固定标 'personal'。
     */
    @Transactional
    public SkillResponse createPersonal(SkillRequest request, Viewer viewer) {
        if (repository.existsByNameIgnoreCase(request.name().trim())) {
            throw ApiException.conflict("技能名称已存在: " + request.name());
        }
        Skill skill = new Skill();
        apply(skill, request);
        skill.setScope(SkillScope.PERSONAL);
        skill.setOwnerUserId(requireOwner(viewer));
        skill.setCreatedBy("personal");
        return toResponse(repository.save(skill));
    }

    /** 编辑：平台库需持码（接口层已把门）；个人库仅 owner / 管理员。 */
    @Transactional
    public SkillResponse update(UUID id, SkillRequest request, Viewer viewer) {
        Skill skill = requireForWrite(id, viewer);
        repository.findByNameIgnoreCase(request.name().trim())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw ApiException.conflict("技能名称已存在: " + request.name());
                });
        apply(skill, request);
        // 归属字段不随编辑漂移（apply 不触碰 scope/ownerUserId）
        return toResponse(repository.save(skill));
    }

    /** 停用/启用：归属判定同 {@link #update}。 */
    @Transactional
    public SkillResponse updateStatus(UUID id, ResourceStatus status, Viewer viewer) {
        Skill skill = requireForWrite(id, viewer);
        skill.setStatus(status);
        return toResponse(repository.save(skill));
    }

    /** 删除：归属判定同 {@link #update}。 */
    @Transactional
    public void delete(UUID id, Viewer viewer) {
        Skill skill = requireForWrite(id, viewer);
        repository.delete(skill);
    }

    /**
     * 可见性判定：返回调用者可见的技能（详情/写操作共用的第一步）。
     * 个人库且非 owner 非管理员 → 404。
     */
    public Skill requireVisible(UUID id, Viewer viewer) {
        Skill skill = requireSkill(id);
        if (skill.getScope() == SkillScope.PERSONAL && !isOwnerOrAdmin(skill, viewer)) {
            throw ApiException.notFound("技能不存在: " + id);
        }
        return skill;
    }

    /** 写操作判定：个人库必须 owner / 管理员（平台库的码门槛在接口层）。 */
    public Skill requireForWrite(UUID id, Viewer viewer) {
        Skill skill = requireVisible(id, viewer);
        if (!isOwnerOrAdmin(skill, viewer)) {
            // 平台库走到这里说明接口层码门槛已放行，正常不会到达；防御性兜底。
            throw ApiException.forbidden("缺少该技能的维护权限");
        }
        return skill;
    }

    /**
     * 运行态技能可见性解析（对话页「/ 调用 skill」，AG-UI run 期用登录用户重校验，
     * 不信任前端）：PLATFORM 需启用（active），PERSONAL 需归属当前用户。
     *
     * <p>与 {@link #requireVisible(UUID, Viewer)} 的差异：requireVisible 面向接口层
     * （不可见抛 404，反侦察口径）；本方法面向 run 前置解析——无效项<b>静默跳过</b>
     * 不 fail run（2026-09-21 设计拍板：无效技能调用 warn 丢弃即可）。</p>
     *
     * @param ids    前端上报的技能 ID（逐个校验，保持入参顺序）
     * @param userId 登录用户（运行态按非管理员 fail-close，不放大权限）
     * @return 校验通过的技能列表（顺序与入参一致）
     */
    public List<Skill> resolveVisible(Collection<UUID> ids, UUID userId) {
        if (ids == null || ids.isEmpty() || userId == null) {
            return List.of();
        }
        List<Skill> out = new ArrayList<>();
        for (UUID id : ids) {
            repository.findById(id)
                    .filter(skill -> isRuntimeVisible(skill, userId))
                    .ifPresent(out::add);
        }
        return out;
    }

    /** 运行态可见性：个人库 = owner 本人；平台库 = 启用中（停用的平台技能不注入）。 */
    private boolean isRuntimeVisible(Skill skill, UUID userId) {
        if (skill.getScope() == SkillScope.PERSONAL) {
            return userId.equals(skill.getOwnerUserId());
        }
        return skill.getStatus() == ResourceStatus.active;
    }

    /**
     * 读取技能的 SKILL.md 正文：DB content 优先（导入时已去 frontmatter 的 body），
     * 为空时回退磁盘源目录（originDir）下的 SKILL.md 文件。
     *
     * @return 正文；两者都不可用时返回空串（调用方据此跳过注入）
     */
    public String readSkillBody(Skill skill) {
        if (skill.getContent() != null && !skill.getContent().isBlank()) {
            return skill.getContent();
        }
        String originDir = skill.getOriginDir();
        if (originDir != null && !originDir.isBlank()) {
            Path md = Path.of(originDir, "SKILL.md");
            if (Files.isRegularFile(md)) {
                try {
                    return Files.readString(md);
                } catch (IOException ignored) {
                    // 读不动按空处理，与「无正文」同口径
                }
            }
        }
        return "";
    }

    private Skill requireSkill(UUID id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("技能不存在: " + id));
    }

    private boolean isOwnerOrAdmin(Skill skill, Viewer viewer) {
        if (viewer.admin()) {
            return true;
        }
        return skill.getScope() == SkillScope.PLATFORM
                || (skill.getOwnerUserId() != null && skill.getOwnerUserId().equals(viewer.userId()));
    }

    private UUID requireOwner(Viewer viewer) {
        if (viewer.userId() == null) {
            throw ApiException.unauthorized("未登录或登录已过期，请重新登录");
        }
        return viewer.userId();
    }

    private Page<Skill> findPlatform(String search, ResourceStatus status, Pageable pageable) {
        if (StringUtils.hasText(search) && status != null) {
            return repository.findByScopeAndNameContainingIgnoreCaseAndStatus(
                    SkillScope.PLATFORM, search.trim(), status, pageable);
        }
        if (StringUtils.hasText(search)) {
            return repository.findByScopeAndNameContainingIgnoreCase(SkillScope.PLATFORM, search.trim(), pageable);
        }
        if (status != null) {
            return repository.findByScopeAndStatus(SkillScope.PLATFORM, status, pageable);
        }
        return repository.findByScope(SkillScope.PLATFORM, pageable);
    }

    private Page<Skill> findOwned(String search, ResourceStatus status, UUID owner, Pageable pageable) {
        if (StringUtils.hasText(search) && status != null) {
            return repository.findByScopeAndOwnerUserIdAndNameContainingIgnoreCaseAndStatus(
                    SkillScope.PERSONAL, owner, search.trim(), status, pageable);
        }
        if (StringUtils.hasText(search)) {
            return repository.findByScopeAndOwnerUserIdAndNameContainingIgnoreCase(
                    SkillScope.PERSONAL, owner, search.trim(), pageable);
        }
        if (status != null) {
            return repository.findByScopeAndOwnerUserIdAndStatus(SkillScope.PERSONAL, owner, status, pageable);
        }
        return repository.findByScopeAndOwnerUserId(SkillScope.PERSONAL, owner, pageable);
    }

    private void apply(Skill skill, SkillRequest request) {
        skill.setName(request.name().trim());
        skill.setDescription(request.description());
        skill.setImplementationType(StringUtils.hasText(request.implementationType())
                ? request.implementationType().trim() : "skill");
        skill.setStatus(request.status() == null ? ResourceStatus.active : request.status());
        skill.setCreatedBy(request.createdBy());
        skill.setContent(request.content());
        skill.setResources(request.resources());
        skill.setOriginDir(request.originDir());
    }

    private SkillResponse toResponse(Skill skill) {
        return new SkillResponse(
                skill.getId(),
                skill.getName(),
                skill.getDescription(),
                skill.getImplementationType(),
                skill.getStatus(),
                skill.getCreatedBy(),
                skill.getScope(),
                skill.getOwnerUserId(),
                skill.getCreatedAt(),
                skill.getUpdatedAt(),
                skill.getContent(),
                skill.getResources(),
                skill.getOriginDir());
    }
}
