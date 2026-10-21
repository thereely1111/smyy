package com.tipdm.agentplatform.web.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 全局 JWT 认证过滤器：对受保护路径（{@code /api/**}、{@code /agui/**}）
 * 强制要求携带有效未过期的 access token，不允许匿名访问。
 *
 * <p>校验通过后把用户信息写入 request attribute（沿用 {@link JwtInterceptor} 的常量），
 * 供 {@link JwtInterceptor}、权限码切面与控制器读取。
 * token 缺失、无效或过期时直接返回 401 JSON（过滤器阶段抛出的异常不会被
 * {@code @RestControllerAdvice} 捕获，因此这里自行写响应体）。</p>
 *
 * <p>公开放行：登录/刷新/OAuth 相关 auth 端点（{@code shouldNotFilter}），
 * 以及 CORS 预检 OPTIONS；其余 {@code /api/**}、{@code /agui/**} 一律要求登录。</p>
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_TOKEN_EXPIRES_AT = "auth.tokenExpiresAt";

    /** 无需登录即可访问的公开路径（前缀匹配）。 */
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/callback",
            "/api/auth/oauth/login",
            "/api/users/register",
            "/api/users/captcha",
            "/api/users/password/forgot"); // ← 新加的：注册不需要登录

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // CORS 预检由框架的 CORS 处理链接管，这里不做认证
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (path == null) {
            return true;
        }
        for (String publicPath : PUBLIC_PATHS) {
            if (path.equals(publicPath) || path.startsWith(publicPath + "/")) {
                return true;
            }
        }
        // 基础设施/错误端点不受保护（urlPatterns 已限定 /api、/agui，此处为防御性放行）
        return path.startsWith("/actuator/")
                || path.equals("/health")
                || path.startsWith("/error");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String header = request.getHeader(JwtInterceptor.AUTHORIZATION_HEADER);
        if (header == null || header.isBlank()) {
            writeUnauthorized(response, "未登录或 token 缺失，请重新登录");
            return;
        }
        String[] parts = header.trim().split("\\s+", 2);
        if (parts.length != 2 || !"bearer".equalsIgnoreCase(parts[0])) {
            writeUnauthorized(response, "未登录或 token 缺失，请重新登录");
            return;
        }
        String token = parts[1].trim();
        JwtService.TokenPayload payload = jwtService.decode(token).orElse(null);
        if (payload == null) {
            writeUnauthorized(response, "登录已过期或 token 无效");
            return;
        }
        if (!"access".equals(payload.type())) {
            writeUnauthorized(response, "token 类型错误，需要 access token");
            return;
        }
        // 显式校验时效性：decode 已会因过期失败，这里再兜底一次
        if (payload.expiresAt() == null || !payload.expiresAt().isAfter(Instant.now())) {
            writeUnauthorized(response, "登录已过期，请重新登录");
            return;
        }

        request.setAttribute(JwtInterceptor.ATTR_USER_ID, payload.userId());
        request.setAttribute(JwtInterceptor.ATTR_USERNAME, payload.username());
        request.setAttribute(JwtInterceptor.ATTR_ROLE, payload.role());
        request.setAttribute(ATTR_TOKEN_EXPIRES_AT, payload.expiresAt());
        filterChain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader("WWW-Authenticate", "Bearer realm=\"agent-platform\"");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of("code", "UNAUTHORIZED", "message", message));
    }
}
