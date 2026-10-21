package com.tipdm.agentplatform.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 认证配置（参考 agent-platform 参考项目的 Settings）：
 * 本地密码登录 + OAuth2.0（密码模式委托 / Authorization Code + PKCE），统一签发本地 JWT。
 */
@Component
@ConfigurationProperties(prefix = "agent-platform.auth")
public class AuthProperties {

    /**
     * JWT 签名密钥（HS256，须 ≥ 32 字节）。
     *
     * <p>此处刻意不设默认值：历史上代码与 application.yml 内置过同一个硬编码字符串，
     * 任何拿到源码的人都能自签任意 userId/role 的 token 从而绕过全站鉴权。
     * 未配置时 {@link #validateJwtSecret()} 会让应用启动失败（fail-fast），
     * 而不是悄悄退化成一个公开可知的密钥。</p>
     */
    private String jwtSecretKey = "";

    /** Access token 有效期（分钟）。 */
    private long accessExpireMinutes = 30;

    /** Refresh token 有效期（天）。 */
    private long refreshExpireDays = 7;

    private final OAuth oauth = new OAuth();

    public static class OAuth {

        /** 外部鉴权服务获取用户权限码接口路径和权限范围。 */
        private String permissionsPath = "";

        private String permissionsScope = "";

        /** 外部鉴权服务地址；为空表示未启用 OAuth（全部走本地密码）。 */
        private String authServerUrl = "";

        private String clientId = "";

        private String clientSecret = "";

        /** 回调地址（须在鉴权服务侧登记）。 */
        private String redirectUri = "http://localhost:5174/auth/callback";

        private String authorizePath = "/oauth/authorize";

        private String tokenPath = "/oauth/token";

        /**
         * userinfo 端点完整 URL —— OAuth 登录者身份的**唯一可信来源**。
         *
         * <p>OAuth 已启用（见 {@link #isEnabled()}）时该值不得为空，否则应用启动失败
         * （见 {@link AuthProperties#validateOauthConfig()}）。</p>
         *
         * <p>历史行为（已移除）：为空时会合成固定身份 {@code user_id="oauth-user"}，
         * 使所有 OAuth 登录者被 upsert 成同一个本地账号（会话/知识库/通知串号）。</p>
         */
        private String userinfoUrl = "";

        /** 外部鉴权服务的公开注册接口（username 方式免验证码）。 */
        private String registerPath = "/api/register";

        /** 外部鉴权服务的自助改密接口。 */
        private String passwordPath = "/api/user/password/self";

        /** 外部鉴权服务的未登录态密码重置接口。 */
        private String resetPasswordPath = "/api/users/password/reset";

        /** 外部鉴权服务的验证码发送接口前缀（实际拼 /api/captcha/{principal}）。 */
        private String captchaPath = "/api/captcha";

        /** 外部鉴权服务注册时使用的 RSA 公钥（PKCS#8，X509EncodedKeySpec 解析）。 */
        private String registerPublicKey = "";

        private String scopes = "read";

        /** tipdm 认证服务改密接口用的 RSA 公钥（X509 公钥，配合 tipdm.api.private-key 使用）。 */
        private String tipdmPublicKey = "";

        public String getTipdmPublicKey() {
            return tipdmPublicKey;
        }


        public String getPermissionsScope() {
            return permissionsScope;
        }
        public void setPermissionsScope(String permissionScope) {
            this.permissionsScope = permissionScope;
        }
        public String getPermissionsPath() {
            return permissionsPath;
        }
        public void setPermissionsPath(String permissionsPath) {
            this.permissionsPath = permissionsPath;
        }

        public void setTipdmPublicKey(String tipdmPublicKey) {
            this.tipdmPublicKey = tipdmPublicKey;
        }

        public boolean isEnabled() {
            return authServerUrl != null && !authServerUrl.isBlank() && clientId != null && !clientId.isBlank();
        }

        /**
         * 以 authServerUrl 为基准拼接接口路径，自动归一化 base 尾斜杠与 path 首斜杠。
         * 所有调用鉴权服务的地址都必须走这里，避免手工拼串漏/重斜杠。
         */
        public String url(String path) {
            String base = authServerUrl == null ? "" : authServerUrl;
            String suffix = path == null ? "" : path;
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            while (suffix.startsWith("/")) {
                suffix = suffix.substring(1);
            }
            return base + "/" + suffix;
        }

        public String getAuthServerUrl() {
            return authServerUrl;
        }

        public void setAuthServerUrl(String authServerUrl) {
            this.authServerUrl = authServerUrl;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getRedirectUri() {
            return redirectUri;
        }

        public void setRedirectUri(String redirectUri) {
            this.redirectUri = redirectUri;
        }

        public String getAuthorizePath() {
            return authorizePath;
        }

        public void setAuthorizePath(String authorizePath) {
            this.authorizePath = authorizePath;
        }

        public String getTokenPath() {
            return tokenPath;
        }

        public void setTokenPath(String tokenPath) {
            this.tokenPath = tokenPath;
        }

        public String getUserinfoUrl() {
            return userinfoUrl;
        }

        public void setUserinfoUrl(String userinfoUrl) {
            this.userinfoUrl = userinfoUrl;
        }

        public String getRegisterPath() {
            return registerPath;
        }

        public void setRegisterPath(String registerPath) {
            this.registerPath = registerPath;
        }

        public String getPasswordPath() {
            return passwordPath;
        }

        public void setPasswordPath(String passwordPath) {
            this.passwordPath = passwordPath;
        }

        public String getResetPasswordPath() {
            return resetPasswordPath;
        }

        public void setResetPasswordPath(String resetPasswordPath) {
            this.resetPasswordPath = resetPasswordPath;
        }

        public String getCaptchaPath() {
            return captchaPath;
        }

        public void setCaptchaPath(String captchaPath) {
            this.captchaPath = captchaPath;
        }

        public String getRegisterPublicKey() {
            return registerPublicKey;
        }

        /**
         * 注册加密使用的实际公钥：优先用显式配置的 register-public-key，
         * 未配置时回退 tipdm-public-key（同一鉴权服务，注册/改密共用同一把解密私钥）。
         */
        public String effectiveRegisterPublicKey() {
            return isBlank(registerPublicKey) ? tipdmPublicKey : registerPublicKey;
        }

        private static boolean isBlank(String s) {
            return s == null || s.isBlank();
        }

        public void setRegisterPublicKey(String registerPublicKey) {
            this.registerPublicKey = registerPublicKey;
        }

        public String getScopes() {
            return scopes;
        }

        public void setScopes(String scopes) {
            this.scopes = scopes;
        }
    }


    public String getJwtSecretKey() {
        return jwtSecretKey;
    }

    public void setJwtSecretKey(String jwtSecretKey) {
        this.jwtSecretKey = jwtSecretKey;
    }

    /**
     * 启动即校验 JWT 密钥：缺失或过短直接让应用启动失败。
     *
     * <p>宁可起不来，也不能退化成一个公开可知的密钥——后者等于全站鉴权可被绕过。</p>
     */
    @PostConstruct
    public void validateJwtSecret() {
        byte[] raw = jwtSecretKey == null ? new byte[0] : jwtSecretKey.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            throw new IllegalStateException(
                    "JWT 签名密钥未配置或长度不足 32 字节（当前 " + raw.length + " 字节）。"
                    + "请通过环境变量配置：export AGENT_PLATFORM_JWT_SECRET=\"$(openssl rand -base64 48)\""
                    + "（或直接设置 agent-platform.auth.jwt-secret-key）。"
                    + "注意：修改密钥会使已签发的 token 全部失效。");
        }
    }

    /**
     * 启动即校验 OAuth 配置完整性：**仅当 OAuth 已启用**（authServerUrl + clientId 均非空，
     * {@link OAuth#isEnabled()}）时要求 userinfo 端点非空。
     *
     * <p>OAuth 未启用是**合法状态**——此时全部走本地密码登录，不该因为缺 userinfo 端点而拒绝启动；
     * 这正是本校验必须带 {@code isEnabled()} 条件、不能像 {@link #validateJwtSecret()} 那样无条件触发的原因。</p>
     *
     * <p>为什么 fail-fast：userinfo 是登录者身份的**唯一可信来源**。历史实现里该值为空时会合成固定
     * 身份 {@code user_id="oauth-user"}，所有 OAuth 登录者被 upsert 成同一条本地用户记录——会话归属、
     * 产物、知识库、群组、通知全部串号，且默认角色是 developer。宁可起不来，也不能静默合并身份。</p>
     */
    @PostConstruct
    public void validateOauthConfig() {
        String userinfoUrl = oauth.getUserinfoUrl();
        boolean userinfoMissing = userinfoUrl == null || userinfoUrl.isBlank();
        if (!oauth.isEnabled() || !userinfoMissing) {
            return;
        }
        throw new IllegalStateException(
                "OAuth 已启用（auth-server-url / client-id 已配置）但 oauth.userinfo-url 为空。"
                + "该端点是登录者身份的唯一可信来源，留空会使所有 OAuth 用户被识别为同一账号。"
                + "请配置：export AGENT_PLATFORM_OAUTH_USERINFO_URL=\"https://<鉴权服务>/api/user/info\""
                + "；若本环境不需要 OAuth 登录，请清空 agent-platform.auth.oauth.auth-server-url。");
    }

    public long getAccessExpireMinutes() {
        return accessExpireMinutes;
    }

    public void setAccessExpireMinutes(long accessExpireMinutes) {
        this.accessExpireMinutes = accessExpireMinutes;
    }

    public long getRefreshExpireDays() {
        return refreshExpireDays;
    }

    public void setRefreshExpireDays(long refreshExpireDays) {
        this.refreshExpireDays = refreshExpireDays;
    }

    public OAuth getOauth() {
        return oauth;
    }
}
