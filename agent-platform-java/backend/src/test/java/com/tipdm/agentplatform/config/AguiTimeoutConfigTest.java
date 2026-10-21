package com.tipdm.agentplatform.config;

import io.agentscope.spring.boot.agui.common.AguiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AG-UI 关键配置绑定回归测试。
 *
 * <p>背景：2026-09-03 长任务在运行约 10 分钟后被掐断（SSE 连接超时，
 * run 被 interrupt）。根因是框架默认 {@code agentscope.agui.sse-timeout=600000ms}
 * 为绝对超时。此测试防止该配置被误改回默认值 / 丢失。
 */
class AguiTimeoutConfigTest {

    @Test
    void aguiSseTimeout_configuredForLongRunningAgents() throws IOException {
        AguiProperties props = bindAguiProperties();

        // 2026-09-15：run/sse-timeout 收敛为 2h——主编闸门审阅历史实测 1-2h，
        // 1h 会掐断合法审阅；停滞类僵尸 run 由 RunSilenceDetector（60min 判死
        // 自动恢复）兜底，死锁兜底见 ResumeCoordinatorDeadlockCleaner；框架修复
        // 后超时可正常收尾不再锁死会话。
        assertEquals(Duration.ofHours(2).toMillis(), props.getSseTimeout());
        // 与 SSE 超时对齐
        assertEquals(Duration.ofHours(2), props.getRunTimeout());
    }

    private static AguiProperties bindAguiProperties() throws IOException {
        Resource resource = new DefaultResourceLoader().getResource("classpath:application.yml");
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application.yml", resource);
        Binder binder = new Binder(ConfigurationPropertySources.from(sources));
        return binder.bind("agentscope.agui", Bindable.of(AguiProperties.class)).get();
    }
}
