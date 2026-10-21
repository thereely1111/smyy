package com.tipdm.agentplatform.config;

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.studio.StudioClient;
import io.agentscope.core.studio.StudioConfig;
import io.agentscope.core.studio.StudioMessageHook;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Schedulers;

/**
 * AgentScope Studio 集成配置（{@code agentscope-extensions-studio}）。
 *
 * <p>{@code agentscope.studio.enabled=true} 时（默认关闭）装配 {@link StudioMessageHook}：
 * <ol>
 *   <li>基于 {@link StudioProperties} 构建 {@link StudioConfig} 与 {@link StudioClient}；</li>
 *   <li>创建 {@link StudioMessageHook}，并注册为 {@link AgentBase} 的系统级 Hook——
 *       本平台智能体按需懒装配（首次请求时才 build），启动期注册即可覆盖此后
 *       装配的数据库型智能体、文件型智能体以及运行期由框架创建的子智能体，
 *       所有 Agent 的回复消息（PostCallEvent）都会被推送到 Studio；</li>
 *   <li>非阻塞地注册 run（不等待 Studio 可用，失败仅告警，不中断启动与智能体运行）。
 *       消息推送本身由 StudioClient 带重试，且 StudioMessageHook 推送失败只记日志，
 *       不会影响智能体执行。</li>
 * </ol>
 *
 * <p>不在启动时调用 {@code StudioManager.init()}：其注册 run / WebSocket 连接均为
 * 同步网络操作，若 Studio 短暂不可用会拖慢甚至阻断应用启动；这里只保留消息推送
 * 依赖的 HTTP 链路，保证「启用后不可用」退化为静默降级。</p>
 *
 * <p>关闭方式：将 {@code agentscope.studio.enabled} 置为 false（或删除该配置项），
 * 本类整体不装配，行为与引入依赖前完全一致。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StudioProperties.class)
@ConditionalOnProperty(name = "agentscope.studio.enabled", havingValue = "true")
public class StudioIntegrationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(StudioIntegrationConfiguration.class);

    private StudioClient studioClient;
    private StudioMessageHook studioMessageHook;

    /**
     * 创建并全局注册 Studio 消息 Hook。
     *
     * <p>返回 Bean 便于显式注入/单测；实际生效路径是 {@link AgentBase#addSystemHook}，
     * 所有后续装配的 Agent 自动携带，无需改动 {@code AgentAssemblyService}。</p>
     */
    @Bean
    public StudioMessageHook studioMessageHook(StudioProperties props) {
        StudioConfig.Builder builder = StudioConfig.builder()
                .studioUrl(props.getUrl())
                .project(props.getProject())
                .runName(props.getRunName())
                .maxRetries(props.getMaxRetries())
                .reconnectAttempts(props.getReconnectAttempts())
                .reconnectDelay(props.getReconnectDelay())
                .reconnectMaxDelay(props.getReconnectMaxDelay());
        if (isNotBlank(props.getTracingUrl())) {
            builder.tracingUrl(props.getTracingUrl());
        }
        if (isNotBlank(props.getRunId())) {
            builder.runId(props.getRunId());
        }
        StudioConfig config = builder.build();

        StudioClient client = new StudioClient(config);
        StudioMessageHook hook = new StudioMessageHook(client);
        AgentBase.addSystemHook(hook);

        // 异步注册 run：不阻塞启动；Studio 不可用时仅告警，
        // 推送链路（pushMessage 自带重试）不受影响。
        client.registerRun()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        v -> log.info("AgentScope Studio run registered: project={}, runId={}",
                                config.getProject(), config.getRunId()),
                        err -> log.warn("AgentScope Studio run registration failed, "
                                + "message push will still be attempted: {}", err.getMessage()));

        this.studioClient = client;
        this.studioMessageHook = hook;
        log.info("AgentScope Studio enabled: url={}, project={}, runName={}, runId={}",
                config.getStudioUrl(), config.getProject(), config.getRunName(), config.getRunId());
        return hook;
    }

    @PreDestroy
    public void shutdown() {
        if (studioMessageHook != null) {
            AgentBase.removeSystemHook(studioMessageHook);
        }
        if (studioClient != null) {
            studioClient.shutdown();
        }
        log.info("AgentScope Studio hook removed and clients shut down");
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
