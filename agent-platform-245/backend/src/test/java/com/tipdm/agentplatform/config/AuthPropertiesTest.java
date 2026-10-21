package com.tipdm.agentplatform.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OAuth 配置启动校验测试（守护 P0-1）。
 *
 * <p>背景：userinfo 端点是 OAuth 登录者身份的**唯一可信来源**。历史实现在该值为空时合成固定身份
 * {@code user_id="oauth-user"}，使全部 OAuth 登录者被 upsert 成同一条本地用户记录——会话归属、产物、
 * 知识库、群组、通知全部串号，且默认角色为 developer。现改为 fail-fast。</p>
 *
 * <p>关键边界：OAuth 未启用（authServerUrl / clientId 为空）是**合法状态**，此时全部走本地密码登录，
 * 不得因缺 userinfo 端点而拒绝启动——所以校验必须带 {@code isEnabled()} 条件，不能像
 * {@code validateJwtSecret()} 那样无条件触发。</p>
 */
class AuthPropertiesTest {

    /** OAuth 已启用：authServerUrl 与 clientId 均非空（见 {@code OAuth#isEnabled()}）。 */
    private static AuthProperties oauthEnabled(String userinfoUrl) {
        AuthProperties props = new AuthProperties();
        props.getOauth().setAuthServerUrl("http://auth.internal:9623");
        props.getOauth().setClientId("agent-platform");
        props.getOauth().setUserinfoUrl(userinfoUrl);
        return props;
    }

    @Test
    void rejectsBlankUserinfoUrl_whenOauthEnabled() {
        // 空串 / null / 纯空白 三种形态都必须拒绝启动
        assertThatThrownBy(() -> oauthEnabled("").validateOauthConfig())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("userinfo-url")
                .hasMessageContaining("AGENT_PLATFORM_OAUTH_USERINFO_URL");

        assertThatThrownBy(() -> oauthEnabled(null).validateOauthConfig())
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> oauthEnabled("   ").validateOauthConfig())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsConfiguredUserinfoUrl_whenOauthEnabled() {
        assertThatCode(() -> oauthEnabled("http://auth.internal:9623/api/user/info")
                .validateOauthConfig())
                .doesNotThrowAnyException();
    }

    @Test
    void allowsBlankUserinfoUrl_whenOauthDisabled() {
        // 纯本地密码登录：authServerUrl 为空 → isEnabled()==false → 不该拒绝启动
        AuthProperties local = new AuthProperties();
        local.getOauth().setUserinfoUrl("");

        assertThat(local.getOauth().isEnabled()).isFalse();
        assertThatCode(local::validateOauthConfig).doesNotThrowAnyException();
    }

    @Test
    void allowsBlankUserinfoUrl_whenOauthPartiallyConfigured() {
        // 只配了 authServerUrl、没有 clientId：半配置视作未启用，同样不该拒绝启动
        AuthProperties partial = new AuthProperties();
        partial.getOauth().setAuthServerUrl("http://auth.internal:9623");
        partial.getOauth().setUserinfoUrl("");

        assertThat(partial.getOauth().isEnabled()).isFalse();
        assertThatCode(partial::validateOauthConfig).doesNotThrowAnyException();
    }

    @Test
    void jwtSecretValidationStillIndependent() {
        // 配置项校验相互独立：OAuth 合法时，JWT 校验该报错仍然报错（顺序不定，各自自洽）
        AuthProperties props = oauthEnabled("http://auth.internal:9623/api/user/info");
        props.setJwtSecretKey("too-short");

        assertThatCode(props::validateOauthConfig).doesNotThrowAnyException();
        assertThatThrownBy(props::validateJwtSecret).isInstanceOf(IllegalStateException.class);
    }
}
