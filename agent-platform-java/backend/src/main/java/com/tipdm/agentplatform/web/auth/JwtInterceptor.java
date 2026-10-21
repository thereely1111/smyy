package com.tipdm.agentplatform.web.auth;

import com.tipdm.agentplatform.service.JwtService;
import com.tipdm.agentplatform.web.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 认证拦截器（参考 agent-platform 参考项目的 AuthMiddleware）：
 * 请求携带 Authorization: Bearer 时解码 JWT 并将用户信息放入 request attribute，
 * 供权限码切面与控制器读取；token 无效或过期返回 401。
 * 全局认证由 {@link JwtAuthFilter} 强制（/api/**、/agui/** 不允许匿名访问），
 * 拦截器仅在过滤器已填充用户信息时直接放行，避免重复解码。
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {

    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String ATTR_USER_ID = "auth.userId";
    public static final String ATTR_USERNAME = "auth.username";
    public static final String ATTR_ROLE = "auth.role";

    /**
     * 匿名兜底用户常量（仅保留给内部 Wakeup 等非 HTTP 路径兜底）。
     * <p>HTTP 入口已由 {@link JwtAuthFilter} 强制登录，不再允许匿名访问。</p>
     */
    public static final String ANONYMOUS_USER = "anonymous";

    private final JwtService jwtService;

    public JwtInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 已由全局 JwtAuthFilter 校验并填充用户信息（/api/**、/agui/**），直接放行
        if (request.getAttribute(ATTR_USER_ID) != null) {
            return true;
        }
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header == null || header.isBlank()) {
            return true;
        }
        String[] parts = header.trim().split("\\s+", 2);
        if (parts.length != 2 || !"bearer".equalsIgnoreCase(parts[0])) {
            return true;
        }
        String token = parts[1].trim();
        JwtService.TokenPayload payload = jwtService.decode(token)
                .orElseThrow(() -> ApiException.unauthorized("登录已过期或 token 无效"));
        if (!"access".equals(payload.type())) {
            throw ApiException.unauthorized("token 类型错误，需要 access token");
        }
        request.setAttribute(ATTR_USER_ID, payload.userId());
        request.setAttribute(ATTR_USERNAME, payload.username());
        request.setAttribute(ATTR_ROLE, payload.role());
        return true;
    }

    /**
     * 解析当前登录用户：只认 JWT payload（{@link JwtAuthFilter} 写入的请求属性）。
     *
     * <p>原先还有 {@code X-User-ID} 头兜底（"显式开发身份"），属 fail-open：该头由客户端
     * 完全控制，一旦 {@link JwtAuthFilter} 的 {@code urlPatterns} 被调整、或某个路径被加进
     * 免鉴权白名单，兜底立刻变成「任意伪造身份」。已随「身份只认 JWT」一并移除。</p>
     *
     * <p>不允许匿名：无法解析时返回 {@code null}，调用方应视为未登录（401）。
     * 用于会话列表/清理、运行时目录托管等端点确定用户级会话分区。</p>
     */
    public static String resolveUserId(HttpServletRequest request) {
        Object attr = request.getAttribute(ATTR_USER_ID);
        if (attr instanceof String userId && !userId.isBlank()) {
            return userId;
        }
        return null;
    }
}
