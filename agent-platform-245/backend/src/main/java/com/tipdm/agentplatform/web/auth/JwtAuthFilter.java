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
 * 以及 CORS 预检 OPTIONS；其余 {@code /api/**}、{@code /agui/**} 一律要求登录。
 * 两个例外都是**带 ticket 参数的单一定点端点**：产物下载（浏览器原生下载带不了请求头）
 * 与文档预览回拉（外部预览服务不带 access token），改由短时票据自证身份。</p>
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

    /**
     * 产物下载路径后缀—— 带 {@code ticket} 查询参数时由票据自证身份，放行给 Controller 处理。
     *
     * <p>其余产物请求（含 {@code download} 不带 ticket 时）仍在过滤器内被强制校验 access token。</p>
     */
    private static final String ARTIFACT_DOWNLOAD_SUFFIX = "/artifacts/download";

    /**
     * 产物下载票据的查询参数名。
     *
     * <p>契约与前端 {@code artifact.ts#downloadTicket} 保持一致，改名需同步。</p>
     */
    public static final String TICKET_PARAM = "ticket";

    /**
     * 文档预览文件的回拉端点——供外部预览服务（TipDM-FV）匿名取字节。
     *
     * <p>该服务不会携带 access token（它只是按 {@code url} 参数发起下载），
     * 因此凭一次性短时票据放行。
     * ⚠️ <b>刻意不进 {@link #PUBLIC_PATHS}</b>：那里是<b>前缀级无条件放行</b>
     * （{@code path.startsWith(publicPath + "/")}），加进去等于让该前缀下所有请求
     * 都脱离本过滤器。放行范围严格限定在本单一路径 + 必须带 ticket 参数。</p>
     */
    private static final String DOC_PREVIEW_RAW_PATH = "/api/doc-preview/raw";

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
        if (path.startsWith("/actuator/")
                || path.equals("/health")
                || path.startsWith("/error")) {
            return true;
        }
        // 产物下载的票据通道：浏览器原生下载（<a href>）无法附加 Authorization 头，
        // 改由短时票据自证身份。此处**只放行确实带 ticket 的请求**——不放行
        // 整个 /api/agents/** 前缀，那会让会话列表、状态、闸门批准等全部脱离本过滤器，
        // 双保险变单保险。票据的签名与单次校验在 DownloadTicketService 内完成，
        // 且路径解析仍过 SessionAccessGuard 的归属校验。
        if (path.endsWith(ARTIFACT_DOWNLOAD_SUFFIX)
                && hasTicket(request)) {
            return true;
        }
        // 文档预览文件的回拉通道：外部预览服务（TipDM-FV）不带 access token，
        // 只按 url 参数发起下载。放行条件同样收窄到「单一路径 + 确有 ticket」——
        // 票据在 PreviewTicketService 内验签并单次消费（Redis SETNX，fail-close），
        // 且票据锁定到具体空间下的相对路径，换文件即失效。
        return path.equals(DOC_PREVIEW_RAW_PATH) && hasTicket(request);
    }

    /** 请求是否携带非空的票据参数。 */
    private static boolean hasTicket(HttpServletRequest request) {
        String ticket = request.getParameter(TICKET_PARAM);
        return ticket != null && !ticket.isBlank();
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
