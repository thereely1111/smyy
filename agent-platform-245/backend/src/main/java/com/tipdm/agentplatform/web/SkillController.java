package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.domain.ResourceStatus;
import com.tipdm.agentplatform.domain.Skill;
import com.tipdm.agentplatform.domain.SkillScope;
import com.tipdm.agentplatform.service.SkillService;
import com.tipdm.agentplatform.web.auth.Viewer;
import com.tipdm.agentplatform.web.dto.CommonDtos.PageResponse;
import com.tipdm.agentplatform.web.dto.CommonDtos.StatusRequest;
import com.tipdm.agentplatform.web.dto.SkillDtos.SkillRequest;
import com.tipdm.agentplatform.web.dto.SkillDtos.SkillResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 技能接口（V54 个人/平台分离）。
 *
 * <p>权限模型：</p>
 * <ul>
 *   <li>读：平台库全员可读（智能体装配/表单选择依赖）；个人库仅 owner / 管理员。</li>
 *   <li>平台库写（POST /skills、POST /skills/platform；写端点按 scope 分流）：
 *       {@code skill:create/update/status/delete} 权限码（2026-09-21 动词统一为
 *       CRUD 风格，原 new/edit/switch/remove 已废弃）。</li>
 *   <li>个人库写（POST /skills/personal 建；写端点 scope=PERSONAL 分支）：
 *       owner / 管理员即可，无需权限码——普通用户维护自己的技能不再要求全局码，
 *       同时持码用户也无法触碰他人个人技能（水平越权面关闭）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillService service;
    private final SessionAccessGuard accessGuard;

    public SkillController(SkillService service, SessionAccessGuard accessGuard) {
        this.service = service;
        this.accessGuard = accessGuard;
    }

    /**
     * 列表：{@code scope=PERSONAL} 返回调用者个人库；缺省/PLATFORM 返回平台库。
     * 对登录用户开放（与既有全局可读语义一致）。
     */
    @GetMapping
    public PageResponse<SkillResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) ResourceStatus status,
            @RequestParam(required = false) SkillScope scope,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {
        Viewer viewer = accessGuard.viewer(request);
        return service.list(search, status, page, size, scope, viewer);
    }

    @GetMapping("/{id}")
    public SkillResponse get(@PathVariable UUID id, HttpServletRequest request) {
        return service.get(id, accessGuard.viewer(request));
    }

    /**
     * 平台库创建（历史端点，保持原语义）：skill:create 门槛不变。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SkillResponse create(
            @Valid @RequestBody SkillRequest request, HttpServletRequest httpRequest) {
        accessGuard.requirePermissionCode(httpRequest, "skill:create");
        return service.createPlatform(request);
    }

    /** 平台库创建：skill:create 门槛（与 POST /skills 等价，命名显式）。 */
    @PostMapping("/platform")
    @ResponseStatus(HttpStatus.CREATED)
    public SkillResponse createPlatform(
            @Valid @RequestBody SkillRequest request, HttpServletRequest httpRequest) {
        accessGuard.requirePermissionCode(httpRequest, "skill:create");
        return service.createPlatform(request);
    }

    /**
     * 个人库创建：任何登录用户可建，scope/归属人由服务端写入（不经请求体，防变造）。
     */
    @PostMapping("/personal")
    @ResponseStatus(HttpStatus.CREATED)
    public SkillResponse createPersonal(
            @Valid @RequestBody SkillRequest request, HttpServletRequest httpRequest) {
        return service.createPersonal(request, accessGuard.viewer(httpRequest));
    }

    /**
     * 编辑：按技能 scope 分流——平台库要求 skill:update；个人库仅 owner / 管理员。
     * 个人库可见性（他人 404 反侦察）由 service.requireVisible 先行保证。
     */
    @PutMapping("/{id}")
    public SkillResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody SkillRequest request,
            HttpServletRequest httpRequest) {
        Viewer viewer = accessGuard.viewer(httpRequest);
        requirePlatformCodeIfNeeded(id, viewer, httpRequest, "skill:update");
        return service.update(id, request, viewer);
    }

    /** 停用/启用：分流规则同 {@link #update}（平台库 skill:status）。 */
    @PatchMapping("/{id}/status")
    public SkillResponse updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody StatusRequest request,
            HttpServletRequest httpRequest) {
        Viewer viewer = accessGuard.viewer(httpRequest);
        requirePlatformCodeIfNeeded(id, viewer, httpRequest, "skill:status");
        return service.updateStatus(id, request.status(), viewer);
    }

    /** 删除：分流规则同 {@link #update}（平台库 skill:delete）。 */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, HttpServletRequest httpRequest) {
        Viewer viewer = accessGuard.viewer(httpRequest);
        requirePlatformCodeIfNeeded(id, viewer, httpRequest, "skill:delete");
        service.delete(id, viewer);
    }

    /**
     * 条件码门槛：仅当目标技能是平台库时要求对应权限码（静态注解表达不了
     * 「按资源 scope 分支」，与知识库平台库创建的 requirePermissionCode 同模式）。
     * 个人库的可见性判定（他人 404）已经拦在前面。
     */
    private void requirePlatformCodeIfNeeded(
            UUID id, Viewer viewer, HttpServletRequest request, String code) {
        Skill skill = service.requireVisible(id, viewer);
        if (skill.getScope() == SkillScope.PLATFORM) {
            accessGuard.requirePermissionCode(request, code);
        }
    }
}
