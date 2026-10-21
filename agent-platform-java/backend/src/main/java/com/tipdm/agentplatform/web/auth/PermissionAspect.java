package com.tipdm.agentplatform.web.auth;

import com.tipdm.agentplatform.service.PermissionCodeCache;
import com.tipdm.agentplatform.web.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 权限校验切面：从 Redis 读取当前用户权限码并校验 {@link RequirePermission}。
 */
@Aspect
@Component
public class PermissionAspect {

    private static final Logger log = LoggerFactory.getLogger(PermissionAspect.class);

    private final PermissionCodeCache permissionCodeCache;
    private final HttpServletRequest request;

    public PermissionAspect(PermissionCodeCache permissionCodeCache, HttpServletRequest request) {
        this.permissionCodeCache = permissionCodeCache;
        this.request = request;
    }

    @Pointcut("@annotation(com.tipdm.agentplatform.web.auth.RequirePermission)")
    public void permissionMethod() {
    }

    @Before("permissionMethod() && @annotation(requirePermission)")
    public void checkPermission(RequirePermission requirePermission) {
        String userId = JwtInterceptor.resolveUserId(request);
        if (userId == null) {
            log.warn("Permission check rejected: unauthenticated, permission={}, method={}, uri={}",
                    requirePermission.value(), request.getMethod(), request.getRequestURI());
            throw ApiException.unauthorized("请先登录");
        }

        // read() 内部吞掉所有读写异常并归一为「未命中」——不会再因一条脏数据打挂权限面
        List<String> permissions = permissionCodeCache.read(parseUserId(userId))
                .orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "PERMISSION_SERVICE_UNAVAILABLE", "权限缓存未初始化"));
        if (!permissions.contains(requirePermission.value())) {
            log.warn("Permission check denied: userId={}, permission={}, method={}, uri={}",
                    userId, requirePermission.value(), request.getMethod(),
                    request.getRequestURI());
            throw ApiException.forbidden("缺少权限: " + requirePermission.value());
        }
    }

    private UUID parseUserId(String userId) {
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            throw ApiException.unauthorized("无效的用户身份");
        }
    }
}
