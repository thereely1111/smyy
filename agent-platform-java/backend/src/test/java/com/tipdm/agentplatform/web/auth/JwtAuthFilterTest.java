package com.tipdm.agentplatform.web.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import com.tipdm.agentplatform.config.AuthProperties;
import com.tipdm.agentplatform.domain.User;
import com.tipdm.agentplatform.domain.UserRole;
import com.tipdm.agentplatform.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局 JWT 时效性过滤器单元测试：不允许匿名访问，token 缺失/无效/过期/类型错误 → 401，
 * 有效 access token 放行并填充用户信息，公开路径（登录/刷新）放行。
 */
class JwtAuthFilterTest {

    private JwtService jwtService;
    private ObjectMapper objectMapper;
    private JwtAuthFilter filter;
    private MockFilterChain chain;
    private MockHttpServletResponse response;

    private final UUID userId = UUID.randomUUID();
    private String accessToken;
    private String refreshToken;

    @BeforeEach
    void setUp() {
        AuthProperties props = new AuthProperties();
        props.setJwtSecretKey("unit-test-secret-key-0123456789-abcdefghijklmn");
        jwtService = new JwtService(props);
        objectMapper = new ObjectMapper();
        filter = new JwtAuthFilter(jwtService, objectMapper);
        chain = new MockFilterChain();
        response = new MockHttpServletResponse();

        User user = new User();
        user.setId(userId);
        user.setUsername("alice");
        user.setRole(UserRole.ADMIN);
        accessToken = jwtService.createAccessToken(user);
        refreshToken = jwtService.createRefreshToken(user);
    }

    @Test
    void missingToken_returns401() throws Exception {
        MockHttpServletRequest request = request("/api/agents", null);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer realm=\"agent-platform\"");
        assertThat(chain.getRequest()).isNull(); // 未放行到后续链
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
    }

    @Test
    void validAccessToken_continuesAndPopulatesUser() throws Exception {
        MockHttpServletRequest request = request("/api/agents", "Bearer " + accessToken);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(JwtInterceptor.ATTR_USER_ID)).isEqualTo(userId.toString());
        assertThat(request.getAttribute(JwtInterceptor.ATTR_ROLE)).isEqualTo("admin");
        assertThat(request.getAttribute(JwtAuthFilter.ATTR_TOKEN_EXPIRES_AT)).isNotNull();
    }

    @Test
    void expiredToken_returns401() throws Exception {
        String expired = expiredAccessToken();
        MockHttpServletRequest request = request("/api/agents", "Bearer " + expired);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer realm=\"agent-platform\"");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void refreshToken_returns401() throws Exception {
        MockHttpServletRequest request = request("/api/agents", "Bearer " + refreshToken);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer realm=\"agent-platform\"");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void garbageToken_returns401() throws Exception {
        MockHttpServletRequest request = request("/api/agents", "Bearer not-a-jwt");

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer realm=\"agent-platform\"");
    }

    @Test
    void publicAuthEndpoint_withoutToken_isAllowed() throws Exception {
        MockHttpServletRequest request = request("/api/auth/login", null);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void aguiPath_requiresToken() throws Exception {
        MockHttpServletRequest request = request("/agui/run/agent-1", null);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    private static MockHttpServletRequest request(String uri, String authorization) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        if (authorization != null) {
            req.addHeader("Authorization", authorization);
        }
        return req;
    }

    private String expiredAccessToken() {
        AuthProperties props = new AuthProperties();
        props.setJwtSecretKey("unit-test-secret-key-0123456789-abcdefghijklmn");
        SecretKey key = Keys.hmacShaKeyFor(props.getJwtSecretKey().getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", "alice")
                .claim("role", "admin")
                .claim("type", "access")
                .issuer("agent-platform")
                .issuedAt(Date.from(now.minusSeconds(120)))
                .expiration(Date.from(now.minusSeconds(60)))
                .signWith(key)
                .compact();
    }
}
