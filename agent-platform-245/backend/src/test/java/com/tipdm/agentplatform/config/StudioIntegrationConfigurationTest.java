package com.tipdm.agentplatform.config;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.studio.StudioMessageHook;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * AgentScope Studio 集成装配回归测试。
 *
 * <p>验证两条关键约定：</p>
 * <ol>
 *   <li>{@code agentscope.studio.enabled} 为 true 时才装配 {@link StudioMessageHook}（默认关闭，
 *       缺失 / false 均不装配，行为与引入依赖前一致）；</li>
 *   <li>{@code application.yml} 中 {@code agentscope.studio.*} 可正确绑定到 {@link StudioProperties}。</li>
 * </ol>
 */
class StudioIntegrationConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StudioIntegrationConfiguration.class);

    @Test
    void studioDisabledByDefault_noHookBean() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(StudioMessageHook.class));
    }

    @Test
    void studioExplicitlyDisabled_noHookBean() {
        runner.withPropertyValues("agentscope.studio.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(StudioMessageHook.class));
    }

    @Test
    void studioEnabled_createsHookBean() {
        runner.withPropertyValues("agentscope.studio.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(StudioMessageHook.class);
                    assertThat(ctx).hasSingleBean(StudioProperties.class);
                    // 清理全局系统 Hook，避免污染其它测试（框架静态状态）
                    AgentBase.removeSystemHook(ctx.getBean(StudioMessageHook.class));
                });
    }

    @Test
    void ymlBindsStudioPropertiesDefaults() throws IOException {
        StudioProperties props = bindStudioProperties();

        // Studio 默认仍关闭（由 AGENTSCOPE_STUDIO_ENABLED 或 yml 显式开启）；
        // 但 run-id / tracing-url 已按固定 run 配置好，开启后即推送至该 run。
        assertFalse(props.isEnabled());
        assertEquals("http://localhost:3000", props.getUrl());
        assertEquals("agent-platform", props.getProject());
        assertEquals("agent-platform", props.getRunName());
        // run-id 默认空（2026-09-15 与 yml 对齐）：runId 是后端启动级，固定写死
        // 会随重启失效，改为经 AGENTSCOPE_STUDIO_RUN_ID 环境变量注入。
        assertEquals("", props.getRunId());
        // tracing-url 同 run-id：默认空，经 AGENTSCOPE_STUDIO_TRACING_URL 注入。
        assertEquals("", props.getTracingUrl());
        assertEquals(3, props.getMaxRetries());
        assertEquals(3, props.getReconnectAttempts());
        assertEquals(Duration.ofSeconds(1), props.getReconnectDelay());
        assertEquals(Duration.ofSeconds(5), props.getReconnectMaxDelay());
    }

    private static StudioProperties bindStudioProperties() throws IOException {
        Resource resource = new DefaultResourceLoader().getResource("classpath:application.yml");
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application.yml", resource);
        // yml 中配置项带 ${ENV:default} 占位符，需要 PlaceholderResolver 解析后绑定
        MutablePropertySources mutable = new MutablePropertySources();
        sources.forEach(mutable::addLast);
        Binder binder = new Binder(
                ConfigurationPropertySources.from(mutable),
                new PropertySourcesPlaceholdersResolver(mutable));
        return binder.bind("agentscope.studio", Bindable.of(StudioProperties.class)).get();
    }
}
