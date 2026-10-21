package com.tipdm.agentplatform.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@code agent-platform.shell.*} 绑定与默认值回归。
 *
 * <p>这两个配置是**可选的开发机开关**，其「生产零回归」承诺完全建立在一个事实上：
 * 默认（未注入环境变量）必须是空串。空串才能让 {@code RuntimeLocalFilesystemSpec}
 * 走平台内置语义、让 {@code PlatformShellExecuteTool} 走框架原生命令承载方式——
 * 任何非空默认值都会在 Linux 容器上改变子进程环境或命令包装方式。</p>
 */
class ShellPropertiesTest {

    @Test
    void defaultsAreBlank_soProductionBehaviourIsUnchanged() {
        ShellProperties props = new ShellProperties();

        assertThat(props.getPath()).isEmpty();
        assertThat(props.getCommandPrefix()).isEmpty();
    }

    @Test
    void nullBindingIsNormalisedToBlank_neverNull() {
        // @ConfigurationProperties 把 ${VAR:} 绑成空串，但显式 YAML null 也可能落到 setter 上；
        // 归一为 "" 才能让下游的 isBlank() 判断保持单一形态（否则 null 会穿透成 NPE / 语义分叉）
        ShellProperties props = new ShellProperties();
        props.setPath(null);
        props.setCommandPrefix(null);

        assertThat(props.getPath()).isEmpty();
        assertThat(props.getCommandPrefix()).isEmpty();
    }

    @Test
    void blankOverrides_areBoundVerbatim() {
        ShellProperties props = new ShellProperties();
        props.setPath("D:\\software\\Git\\usr\\bin");
        props.setCommandPrefix("D:\\software\\Git\\bin\\bash.exe");

        assertThat(props.getPath()).isEqualTo("D:\\software\\Git\\usr\\bin");
        assertThat(props.getCommandPrefix()).isEqualTo("D:\\software\\Git\\bin\\bash.exe");
    }

    @Test
    void startupLog_isSilentWhenNothingConfigured() {
        // 生产日志不得因为引入本能力而变化：未配置时启动钩子必须是彻底 no-op
        assertThatCode(() -> new ShellProperties().logEffectiveOverride())
                .doesNotThrowAnyException();
    }

    @Test
    void startupLog_survivesPartialConfiguration() {
        // 只配一半（只配 path）也要能安全启动：这是最容易被误配出来的形态，日志应显形而不是抛异常
        ShellProperties props = new ShellProperties();
        props.setPath("D:\\software\\Git\\usr\\bin");

        assertThatCode(props::logEffectiveOverride).doesNotThrowAnyException();
    }
}
