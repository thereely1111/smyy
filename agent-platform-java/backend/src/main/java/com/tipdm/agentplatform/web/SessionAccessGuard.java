package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.service.PermissionCodeCache;
import com.tipdm.agentplatform.service.SessionOwnerService;
import com.tipdm.agentplatform.service.SessionWorkspaceManager;
import com.tipdm.agentplatform.web.auth.JwtInterceptor;
import com.tipdm.agentplatform.web.auth.Viewer;
import com.tipdm.agentplatform.web.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话级只读接口的准入判定：把「请求人是谁」与「这个会话能不能看」收敛到一处。
 *
 * <p>产物目录是两层（{@code runtimes/{agentId}/{sessionId}}）、不含 userId，用户归属由
 * {@code agent_session_owner} 表承载。判定规则：</p>
 * <ol>
 *   <li>归属用户 = 请求用户 → 放行；
 *   <li>归属用户 ≠ 请求用户 → 404（不暴露他人会话是否存在）；
 *   <li>无归属记录（历史会话 / 归属写入失败）→ admin 放行、普通用户 404。
 * </ol>
 *
 * <p>产物视图与子任务汇报视图共用本判定，避免两处规则漂移。</p>
 */
@Component
public class SessionAccessGuard {

    /** 管理端准入权限码，与前端 /admin 路由守卫同一口径。 */
    public static final String ADMIN_ACCESS = PermissionCodeCache.ADMIN_ACCESS;

    private final SessionOwnerService sessionOwnerService;
    private final SessionWorkspaceManager sessionWorkspaceManager;
    private final PermissionCodeCache permissionCodeCache;

    public SessionAccessGuard(SessionOwnerService sessionOwnerService,
                              SessionWorkspaceManager sessionWorkspaceManager,
                              PermissionCodeCache permissionCodeCache) {
        this.sessionOwnerService = sessionOwnerService;
        this.sessionWorkspaceManager = sessionWorkspaceManager;
        this.permissionCodeCache = permissionCodeCache;
    }

    /**
     * 请求级登录用户（只认 JWT 属性，无身份头兜底）；未登录抛 401。
     * <p>ANONYMOUS_USER 同拒：HTTP 入口已由 JwtAuthFilter 强制登录，正常不可达，
     * 保留为防御性检查——此前五个 Controller 各自私有的 requireUser 均带此检查，
     * 收敛到本方法后语义保持不变。</p>
     */
    public String requireUserId(HttpServletRequest request) {
        String userId = JwtInterceptor.resolveUserId(request);
        if (userId == null || userId.isBlank() || JwtInterceptor.ANONYMOUS_USER.equals(userId)) {
            throw ApiException.unauthorized("未登录或登录已过期，请重新登录");
        }
        return userId;
    }

    /**
     * 构造已裁决的调用者上下文：userId 来自 JWT，admin 标志按 admin:access
     * 权限码裁决（与 {@code @RequirePermission} 切面同源）。接口层统一用本方法
     * 产出 {@link Viewer} 下传 service，service 层不再自行做角色/权限码判定。
     */
    public Viewer viewer(HttpServletRequest request) {
        String userId = requireUserId(request);
        UUID parsed;
        try {
            parsed = UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            throw ApiException.unauthorized("无效的用户身份");
        }
        return new Viewer(parsed, isAdmin(request));
    }

    /**
     * 校验访问权限并返回会话产物目录。
     *
     * @throws ApiException 404（会话不属于请求人）/ 401（未登录）
     */
    public Path requireOwnedSessionRoot(UUID agentId, String sessionId, HttpServletRequest request) {
        String userId = requireUserId(request);
        return requireOwnedSessionRoot(agentId, userId, sessionId, request);
    }

    /** 同上，调用方已解析出 userId（避免重复解析 JWT）。 */
    public Path requireOwnedSessionRoot(UUID agentId, String userId, String sessionId,
                                        HttpServletRequest request) {
        requireSessionOwned(agentId, userId, sessionId, request);
        return sessionWorkspaceManager.resolveSessionDir(agentId.toString(), sessionId);
    }

    /**
     * 只做归属校验、不解析产物目录——供「删除 / 归档 / 改标题」等本就不需要
     * 操作目录的会话级接口复用，避免各处重写这套判定。
     *
     * @throws ApiException 404（会话不属于请求人，或无人认领且非管理员）
     */
    public void requireSessionOwned(UUID agentId, String userId, String sessionId,
                                    HttpServletRequest request) {
        Optional<String> owner = sessionOwnerService.owner(agentId.toString(), sessionId);
        if (owner.isPresent() && !owner.get().equals(userId)) {
            throw ApiException.notFound("会话不存在");
        }
        if (owner.isEmpty() && !isAdmin(request)) {
            throw ApiException.notFound("会话不存在");
        }
    }

    /**
     * 接口层条件性权限码门禁：与 {@code @RequirePermission} 切面同一数据源与语义。
     *
     * <p>静态门槛（整个端点固定一码）用切面注解；按请求内容分支的门槛
     * （如「仅 scope=PLATFORM 的编辑需要 knowledge:update」）无法用注解表达，
     * 由 Controller 显式调用本方法——权限判定仍收敛在接口层，service 不感知。</p>
     *
     * @throws ApiException 401（未登录）/ 503（权限缓存未初始化，同切面口径）/ 403（缺码）
     */
    public void requirePermissionCode(HttpServletRequest request, String code) {
        String userId = requireUserId(request);
        UUID parsed;
        try {
            parsed = UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            throw ApiException.unauthorized("无效的用户身份");
        }
        List<String> codes = permissionCodeCache.read(parsed)
                .orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "PERMISSION_SERVICE_UNAVAILABLE", "权限缓存未初始化"));
        if (!codes.contains(code)) {
            throw ApiException.forbidden("缺少权限: " + code);
        }
    }

    /**
     * 请求人是否为管理员：以权限码判定，与 {@code @RequirePermission} 切面同一口径。
     *
     * <p>这里原先读 JWT role 并允许 {@code X-User-Role} 头兜底——属「单点失效即可伪造身份」，
     * 已随角色鉴权一并移除。缓存未命中或身份无法解析时按**非管理员**处理（fail-close）：
     * 宁可让无归属的历史会话暂时不可访问，也不能错放成管理员。</p>
     */
    public boolean isAdmin(HttpServletRequest request) {
        String userId = JwtInterceptor.resolveUserId(request);
        if (userId == null || userId.isBlank()) {
            return false;
        }
        UUID parsed;
        try {
            parsed = UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return permissionCodeCache.isAdmin(parsed);
    }
}
