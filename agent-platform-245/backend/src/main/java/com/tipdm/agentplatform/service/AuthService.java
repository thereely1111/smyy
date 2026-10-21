package com.tipdm.agentplatform.service;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.util.RedisKeyConstants;
import com.tipdm.agentplatform.util.RedisUtils;
import com.tipdm.agentplatform.config.AuthProperties;
import com.tipdm.agentplatform.domain.User;
import com.tipdm.agentplatform.domain.UserRole;
import com.tipdm.agentplatform.repository.UserRepository;
import com.tipdm.agentplatform.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证服务（参考 agent-platform 参考项目的 AuthService 设计）：
 * 1. 本地密码登录 —— bcrypt 校验本地 users 表；
 * 2. OAuth2.0 密码模式委托 —— 向外部鉴权服务发起 grant_type=password，验证通过后同步用户并签发本地 JWT；
 * 3. OAuth2.0 Authorization Code + PKCE —— 前端重定向到鉴权系统登录，回调后交换 code 并签发本地 JWT。
 * 所有登录方式最终都签发统一的本地 JWT，后续请求校验链路一致。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 仅用于解析认证中心错误响应体（无外部输入的窄用途，复用实例避免重复构造）。 */
    private static final ObjectMapper ERROR_BODY_MAPPER = new ObjectMapper();

    /**
     * OAuth state 有效期（秒）：超时未回调自动过期。
     */
    private static final long OAUTH_STATE_TTL_SECONDS = 600;

    private final AuthProperties properties;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final RedisUtils redisUtils;
    private final PermissionCodeCache permissionCodeCache;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * state -> (code_verifier, 过期时间)。
     */
    private final ConcurrentHashMap<String, PkceState> pkceStates = new ConcurrentHashMap<>();

    private final RestClient restClient = RestClient.create();

    public AuthService(AuthProperties properties, JwtService jwtService, UserRepository userRepository,
            RedisUtils redisUtils, PermissionCodeCache permissionCodeCache) {
        this.properties = properties;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.redisUtils = redisUtils;
        this.permissionCodeCache = permissionCodeCache;
    }

    // ── 登录（本地密码 + OAuth 委托） ─────────────────────────────────────────

    /**
     * 登录（本地密码 + OAuth 委托）。
     *
     * <p><b>刻意不加 @Transactional</b>（2026-09-17）：方法内串行调用 token / userinfo /
     * permissions 三个远程接口（秒级、外部抖动时可达数十秒），加事务等于把这些时长
     * 全算进「持有一个 DB 连接」的时间，连接池会被少数几个登录请求耗尽。方法内唯一的
     * DB 写是 {@link #syncOauthUser} 的单次 save（本身就是事务，且并发首次登录由
     * (oauth_user_id, oauth_provider) 唯一约束兜底）。</p>
     */
    public TokenResult login(String username, String password) {
        AuthProperties.OAuth oauth = properties.getOauth();
        if (!oauth.isEnabled()) {
            // 与"鉴权服务不可达"(AUTH_UNAVAILABLE) 区分：此 code 专指未配置，便于前端/排障直接判定
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_NOT_CONFIGURED", "统一认证服务未启用，禁止登录");
        }

        // ── 阶段一：委托 tipdm 校验凭据并换取 access_token ──
        // 只有此阶段才算"密码错误"/"账号问题"；拆成独立 try，避免后续用户信息同步的 4xx 被误判成凭据错误
        Map<String, Object> oauthToken;
        try {
            oauthToken = exchangePasswordToken(username, password);
        } catch (HttpClientErrorException e) {
            throw mapTokenClientError(e);
        } catch (HttpServerErrorException e) {
            log.error("外部统一认证服务故障（token 接口），状态码: {}，错误详细: {}",
                    e.getStatusCode().value(), e.getResponseBodyAsString(), e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AUTH_ERROR", "统一认证服务异常，请稍后再试");
        } catch (ResourceAccessException e) {
            log.warn("鉴权服务不可达（token 接口）", e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE", "统一认证服务不可达，请稍后重试");
        }

        String oauthAccessToken = str(oauthToken.get("access_token"));
        if (oauthAccessToken == null || oauthAccessToken.isBlank()) {
            // 显式兜住"成功但没给 token"，避免后续 userinfo 拉 4xx 后冤枉成"密码错误"
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AUTH_ERROR", "认证服务未返回有效的登录凭证");
        }

        // ── 阶段二：获取用户信息并同步本地镜像、拉权限码、签发 JWT ──
        // 此阶段的下游失败都不是凭据问题，统一归为服务异常，绝不误报成"密码错误/账号禁用"
        try {
            Map<String, Object> userinfo = fetchUserinfo(oauthAccessToken);
            User user = syncOauthUser(userinfo);
            List<String> permissions = fetchAndSavePermissions(user.getId(), oauthAccessToken);
            return issueTokens(user, permissions);
        } catch (HttpClientErrorException e) {
            // 4xx 客户端/凭证错误（400、401、403 等）
            HttpStatusCode statusCode = e.getStatusCode();
            String rawBody = e.getResponseBodyAsString();
            String decodedMsg;
            try {
                decodedMsg = URLDecoder.decode(rawBody, StandardCharsets.UTF_8);
            } catch (Exception ex) {
                decodedMsg = rawBody;
            }
            //  账号被禁用或锁定（HTTP 403 或消息含 disabled/locked）
            log.warn("外部系统认证失败，状态码: {}, 错误详细: {}", statusCode, decodedMsg, e);
            if (statusCode.isSameCodeAs(HttpStatus.FORBIDDEN)
                    || decodedMsg.contains("disabled") || decodedMsg.contains("locked")) {
                throw ApiException.forbidden("账号已被禁用或锁定，请联系管理员");
            }
            // 首登改密策略触发（见 mapTokenClientError 内注释），不归为凭据错误
            if (decodedMsg.contains("密码已经过期") || decodedMsg.contains("密码已过期")) {
                throw ApiException.unauthorized(
                        "密码已过期（首次登录须修改密码），请先到统一认证中心修改密码或使用“找回密码”重置后再登录");
            }
            // 授权模式或客户端凭据配置问题（系统配置问题，非用户密码错误）
            if (decodedMsg.contains("grant_type") || decodedMsg.contains("invalid_client")
                    || decodedMsg.contains("unauthorized_client")) {
                log.error("统一认证服务配置异常（grant_type 或 client 凭据配置有误）: {}", decodedMsg);
                throw new ApiException(HttpStatus.BAD_GATEWAY, "AUTH_ERROR", "统一认证服务配置异常，请联系管理员");
            }
            // 账号密码错误
            if (statusCode.isSameCodeAs(HttpStatus.UNAUTHORIZED) || decodedMsg.contains("invalid_grant")) {
                throw ApiException.unauthorized("用户名或密码错误");
            }
            // 其它4xx异常
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AUTH_ERROR", "统一认证服务异常，请稍后再试");
        } catch (HttpServerErrorException e) {
            // 5xx 外部服务端错误（500、502、503、504 等）
            log.error("外部统一认证服务故障，状态码: {}, 错误详细: {}",
                    e.getStatusCode().value(), e.getResponseBodyAsString(), e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AUTH_ERROR", "统一认证服务异常，请稍后再试");
        }  catch (ResourceAccessException e) {
            // 网络不可达（连不上 IP、端口超时等）
            log.warn("鉴权服务不可达", e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE", "统一认证服务不可达，请稍后重试");
        }
    }

    /** 将 token 交换阶段的 4xx 异常映射为精确的业务提示：
     *  403 / disabled / locked → 账号被禁用或锁定
     *  invalid_client / grant_type / unauthorized_client → client 配置错误
     *  密码已经过期 → 首登改密策略触发，引导去统一认证中心改密（不是密码输错）
     *  invalid_grant / 400 / 401 → 用户名或密码错误
     *  其它 4xx → 统一认证服务异常 */
    private ApiException mapTokenClientError(HttpClientErrorException e) {
        HttpStatusCode statusCode = e.getStatusCode();
        String rawBody = e.getResponseBodyAsString();
        String decodedMsg = rawBody;
        try {
            decodedMsg = URLDecoder.decode(rawBody, StandardCharsets.UTF_8);
        } catch (Exception ex) {
                decodedMsg = rawBody; // 解码失败沿用原样
            }
        log.warn("外部系统认证失败，状态码: {}，错误详细: {}", statusCode, decodedMsg, e);

        if (statusCode.isSameCodeAs(HttpStatus.FORBIDDEN)
                || decodedMsg.contains("disabled") || decodedMsg.contains("locked")) {
            return ApiException.forbidden("账号已被禁用或锁定，请联系管理员");
        }
        // tipdm 首登改密策略（first-login-change-password）触发：管理员建号后未改初始密码，
        // 凭据本身正确，oauth token 端点返回 invalid_grant +「密码已经过期，请修改密码」。
        if (decodedMsg.contains("密码已经过期") || decodedMsg.contains("密码已过期")) {
            return ApiException.unauthorized(
                    "密码已过期（首次登录须修改密码），请先到统一认证中心修改密码或使用“找回密码”重置后再登录");
        }
        if (decodedMsg.contains("grant_type") || decodedMsg.contains("invalid_client")
                || decodedMsg.contains("unauthorized_client")) {
            log.error("统一认证服务配置异常（grant_type 或 client 凭据配置有误）: {}", decodedMsg);
            return new ApiException(HttpStatus.BAD_GATEWAY, "AUTH_ERROR", "统一认证服务配置异常，请联系管理员");
        }
        // 认证中心的 error_description 本身就是面向用户的精确文案（如账号锁定、
        // 密码错误次数限制等），直接透传给前端展示；提取不到再走通用兜底
        String errorDescription = extractErrorDescription(rawBody);
        if (errorDescription != null && !errorDescription.isBlank()) {
            return ApiException.unauthorized(errorDescription);
        }
        if (statusCode.isSameCodeAs(HttpStatus.UNAUTHORIZED) || decodedMsg.contains("invalid_grant")
                || statusCode.isSameCodeAs(HttpStatus.BAD_REQUEST)) {
            return ApiException.unauthorized("用户名或密码错误");
        }
        return new ApiException(HttpStatus.BAD_GATEWAY, "AUTH_ERROR", "统一认证服务异常，请稍后再试");
    }

    /** 从认证中心错误响应体（JSON）中提取 error_description；非 JSON 或无该字段返回 null。 */
    private static String extractErrorDescription(String rawBody) {
        try {
            String value = ERROR_BODY_MAPPER.readTree(rawBody).path("error_description").asText(null);
            // tipdm 的值可能是 percent-encoded；不含 %/+ 时 decode 是恒等变换
            return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("统一认证服务返回的错误响应体无法解析为 JSON，将原样返回给前端：{}", rawBody, e);
            return null;
        }
    }

    @Transactional
    public TokenResult refresh(String refreshToken) {
        JwtService.TokenPayload payload = jwtService.decode(refreshToken)
                .filter(p -> "refresh".equals(p.type()))
                .orElseThrow(() -> ApiException.unauthorized("无效的 refresh token"));
        User user = userRepository.findById(UUID.fromString(payload.userId()))
                .orElseThrow(() -> ApiException.unauthorized("用户不存在"));

        List<String> permissions = getCachedPermissions(user.getId());
        return issueTokens(user, permissions);
    }

    // ── 登出与缓存清理 ────────────────────────────────────────────────────────

    /**
     * 用户登出：清除 Redis 中缓存的权限码数据
     *
     * @param userId 当前登出的用户 ID
     */
    public void logout(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        String redisKey = RedisKeyConstants.authPermKey(userId);
        boolean deleted = redisUtils.delete(redisKey);
        if (deleted) {
            log.info("用户 [{}] 登出， 已清除 Redis 权限缓存：{}", userId, redisKey);
        } else {
            log.debug("用户 [{}] 登出，Redis 中无对应的权限缓存: {}", userId, redisKey);
        }
    }


    // ── OAuth2.0 Authorization Code + PKCE ────────────────────────────────────

    public LoginUrlResult generateLoginUrl() {
        AuthProperties.OAuth oauth = properties.getOauth();
        if (!oauth.isEnabled()) {
            throw ApiException.badRequest("OAuth2.0 未配置");
        }
        String codeVerifier = randomToken(64);
        String state = randomToken(32);
        sweepExpiredStates();
        pkceStates.put(state, new PkceState(codeVerifier, Instant.now().plusSeconds(OAUTH_STATE_TTL_SECONDS)));

        String codeChallenge = sha256Hex(codeVerifier);
        String url = oauth.url(oauth.getAuthorizePath())
                + "?response_type=code"
                + "&client_id=" + urlEncode(oauth.getClientId())
                + "&redirect_uri=" + urlEncode(oauth.getRedirectUri())
                + "&scope=" + urlEncode(oauth.getScopes())
                + "&state=" + urlEncode(state)
                + "&code_challenge=" + urlEncode(codeChallenge)
                + "&code_challenge_method=S256";
        return new LoginUrlResult(url, state, oauth.getRedirectUri());
    }

    /** 授权码回调：同 {@link #login}，远程换 token / userinfo 期间不应占用 DB 连接，故不加事务。 */
    public TokenResult exchangeCallback(String code, String state) {
        PkceState pkce = pkceStates.remove(state);
        if (pkce == null || pkce.expiresAt().isBefore(Instant.now())) {
            throw ApiException.badRequest("无效或已过期的 state 参数");
        }
        Map<String, Object> oauthToken = exchangeAuthorizationCode(code, pkce.codeVerifier());
        String oauthAccessToken = str(oauthToken.get("access_token"));
        Map<String, Object> userinfo = fetchUserinfo(oauthAccessToken);
        User user = syncOauthUser(userinfo);

        // 获取外部权限码并存入 Redis
        List<String> permissions = fetchAndSavePermissions(user.getId(), oauthAccessToken);

        return issueTokens(user, permissions);
    }

    /**
     * 从 Redis 读取用户权限码缓存，若不存在或异常则返回空列表兜底
     */
    public List<String> getCachedPermissions(UUID userId) {
        return permissionCodeCache.read(userId).orElseGet(List::of);
    }

    /**
     * 调用外部统一认证服务(9623)获取用户权限码，并写入 Redis。
     */
    private List<String> fetchAndSavePermissions(UUID userId, String oauthAccessToken) {
        if (oauthAccessToken == null || oauthAccessToken.isBlank()) {
            return List.of();
        }
        try {
            AuthProperties.OAuth oauth = properties.getOauth();
            String url = oauth.url(oauth.getPermissionsPath());
            if (oauth.getPermissionsScope() != null && !oauth.getPermissionsScope().isBlank()) {
                url += "?name=" + oauth.getPermissionsScope();
            }
            Map<String, Object> resp = restClient.get()
                .uri(url)
                .header("Authorization", "Bearer " + oauthAccessToken)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() { });

            if (resp != null && "200".equals(String.valueOf(resp.get("status")))) {
                Object dataObj = resp.get("data");
                if (dataObj instanceof List<?> list) {
                    // 过滤掉空项和空白字符串
                    List<String> permissions = list.stream()
                            .filter(Objects::nonNull)
                            .map(Object::toString)
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList();

                String redisKey = RedisKeyConstants.authPermKey(userId);
                // 写入 Redis（JSON 字符串格式，读写对称），过期时间与 RefreshToken 保持一致（默认 7 天）
                permissionCodeCache.write(userId, permissions, properties.getRefreshExpireDays());

                    log.info("已成功获取用户 [{}] 的权限码（共 {} 个）并写入 Redis: {} 权限数据: {}",
                            userId, permissions.size(), redisKey, permissions);

                    return permissions;
                }
            } else {
                    log.warn("调用外部权限接口未返回成功状态：{}", resp);
                    return List.of();
            }
        } catch (RestClientResponseException e) {
            // 权限接口明确拒绝（4xx）：不影响登录，但权限未刷新，降级用缓存/空；按 warn 便于排障
            int sc = e.getStatusCode().value();
            log.warn("权限接口拒绝（{}），用户权限将使用缓存或为空，userId={}", sc, userId, e);
        } catch (Exception e) {
            log.error("获取外部权限码异常，用户ID：{}", userId, e);
        }
        return List.of();
    }



    private TokenResult issueTokens(User user, List<String> permissions) {
        return new TokenResult(
                jwtService.createAccessToken(user),
                jwtService.createRefreshToken(user),
                user.getId().toString(),
                user.getUsername(),
                user.getRole().name().toLowerCase(),
                user.getName(),
                permissions
                );
    }

    private Map<String, Object> exchangePasswordToken(String username, String password) {
        AuthProperties.OAuth oauth = properties.getOauth();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("username", username);
        form.add("password", password);
        form.add("client_id", oauth.getClientId());
        form.add("scope", oauth.getScopes());
        if (oauth.getClientSecret() != null && !oauth.getClientSecret().isBlank()) {
            form.add("client_secret", oauth.getClientSecret());
        }
        return postTokenEndpoint(form);
    }

    private Map<String, Object> exchangeAuthorizationCode(String code, String codeVerifier) {
        AuthProperties.OAuth oauth = properties.getOauth();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", oauth.getRedirectUri());
        form.add("client_id", oauth.getClientId());
        form.add("code_verifier", codeVerifier);
        if (oauth.getClientSecret() != null && !oauth.getClientSecret().isBlank()) {
            form.add("client_secret", oauth.getClientSecret());
        }
        return postTokenEndpoint(form);
    }

    private Map<String, Object> postTokenEndpoint(MultiValueMap<String, String> form) {
        AuthProperties.OAuth oauth = properties.getOauth();
        return restClient.post()
                .uri(oauth.url(oauth.getTokenPath()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {
                });
    }

    private Map<String, Object> fetchUserinfo(String accessToken) {
        String userinfoUrl = properties.getOauth().getUserinfoUrl();
        if (userinfoUrl == null || userinfoUrl.isBlank()) {
            log.error("OAuth userinfo 端点未配置，拒绝登录（agent-platform.auth.oauth.userinfo-url 为空）");
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL",
                    "OAuth userinfo 端点未配置，无法确认登录者身份，请联系管理员");
        }
        return restClient.get()
                .uri(userinfoUrl)
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {
                });
    }

    /**
     * 在本地 users 表 upsert OAuth 用户：按 oauth_user_id + provider 查找，
     * 存在则更新 name/email，不存在则创建（auth_type=oauth，默认 developer 角色）。
     * 兼容扁平与嵌套（data 包裹）两种 userinfo 响应格式。
     */
    private User syncOauthUser(Map<String, Object> raw) {
        Map<String, Object> info = raw;
        if (raw.get("data") instanceof Map<?, ?> nested) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) nested;
            info = cast;
        }
        String oauthUserId = firstNonBlank(str(info.get("user_id")), str(info.get("userId")),
                str(info.get("sub")), str(info.get("id")));
        if (oauthUserId == null) {
            throw ApiException.badRequest("OAuth userinfo 缺少用户标识");
        }
        String provider = defaultIfBlank(str(info.get("provider")), "default");
        String username = firstNonBlank(str(info.get("username")), str(info.get("preferred_username")),
                "oauth_" + oauthUserId);
        String email = str(info.get("email"));
        String phone = str(info.get("phone"));
        // 显示名以 userAlias 为准（标准映射：本地 name ← tipdm userAlias），否则退到 name/username
        String name = firstNonBlank(str(info.get("userAlias")), str(info.get("name")),
                str(info.get("alias")), username);

        User user = userRepository.findByOauthUserIdAndOauthProvider(oauthUserId, provider).orElse(null);
        if (user != null) {
            user.setEmail(email);
            user.setPhone(phone);
            user.setName(name);
            return userRepository.save(user);
        }
        // 用户名被常规登录用户占用时追加后缀
        if (userRepository.existsByUsername(username)) {
            username = username + "_" + oauthUserId.substring(0, Math.min(8, oauthUserId.length()));
        }
        User created = new User();
        created.setUsername(username);
        created.setPasswordHash("");
        created.setRole(UserRole.DEVELOPER);
        created.setAuthType("oauth");
        created.setOauthUserId(oauthUserId);
        created.setOauthProvider(provider);
        created.setEmail(email);
        created.setPhone(phone);
        created.setName(name);
        return userRepository.save(created);
    }

    private void sweepExpiredStates() {
        Instant now = Instant.now();
        pkceStates.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }

    private String randomToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    // ── 结果记录 ──────────────────────────────────────────────────────────────

    public record TokenResult(String accessToken, String refreshToken, String userId, String username,
            String role, String name, List<String> permissions) {
    }

    public record LoginUrlResult(String loginUrl, String state, String redirectUri) {
    }

    private record PkceState(String codeVerifier, Instant expiresAt) {
    }
}
