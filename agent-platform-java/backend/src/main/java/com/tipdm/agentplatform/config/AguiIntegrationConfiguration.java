package com.tipdm.agentplatform.config;

import io.agentscope.spring.boot.agui.common.AguiAgentRegistryCustomizer;
import com.tipdm.agentplatform.domain.AgentConfig;
import com.tipdm.agentplatform.repository.AgentConfigRepository;
import com.tipdm.agentplatform.service.AgentAssemblyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.util.List;

/**
 * AG-UI 集成配置：桥接平台 Agent 装配服务到 AG-UI 注册表。
 *
 * <p>
 * 阶段二实现：启动时加载所有 active 状态的 Agent 并注册工厂到 AG-UI 注册表，
 * 使 POST /agui/run/{agentId} 端点可用。框架将自动暴露 AG-UI 标准端点，
 * 前端可通过 agentId 路径变量或 X-Agent-Id Header 指定 Agent。
 * </p>
 */
@Component
public class AguiIntegrationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AguiIntegrationConfiguration.class);

    private final AgentAssemblyService assemblyService;
    private final AgentConfigRepository configRepository;
    private final FileSystemAgentLoader fileSystemAgentLoader;

    public AguiIntegrationConfiguration(
            AgentAssemblyService assemblyService,
            AgentConfigRepository configRepository,
            FileSystemAgentLoader fileSystemAgentLoader) {
        this.assemblyService = assemblyService;
        this.configRepository = configRepository;
        this.fileSystemAgentLoader = fileSystemAgentLoader;
    }

    /**
     * 创建 AG-UI Agent 注册表定制器。
     * 每次请求调用 assemble 方法（利用其内部 synchronized + ConcurrentHashMap 缓存机制）。
     * 对话选模型：工厂内先读 {@link com.tipdm.agentplatform.service.RunModelSelection}
     *（由 PlatformAguiRuntimeContextResolver 在同一请求线程上从 forwardedProps
     * 解析并设置），命中则用凭证模型实例装配覆盖实例，否则走默认装配。
     */
    @Bean
    public AguiAgentRegistryCustomizer aguiRegistryCustomizer() {
        return registry -> {
            // 1. 数据库 Agent（含草稿/下架态：在线调试的试运行要求非发布态也能
            // 开会话；可见性由列表接口承担，广场对终端用户只展示发布态）
            List<AgentConfig> dbConfigs = configRepository.findAll();
            for (AgentConfig config : dbConfigs) {
                String agentId = config.getId().toString();
                try {
                    registry.registerFactory(agentId, () -> assembleForCurrentRun(config));
                    log.info("Registered AG-UI factory for agent: {} ({}, status={})",
                            config.getName(), agentId, config.getStatus());
                } catch (Exception e) {
                    log.warn("Failed to register AG-UI factory for agent {}: {}", agentId, e.getMessage());
                }
            }
            log.info("AG-UI integration: registered {} agent(s)", dbConfigs.size());

            // 2. 文件型 Agent
            try {
                List<AgentConfig> fileConfigs = fileSystemAgentLoader.loadAll();
                for (AgentConfig config : fileConfigs) {
                    registry.registerFactory(config.getId().toString(), () -> assembleForCurrentRun(config));
                    log.info("Registered file-agent factory: {} ({})", config.getName(), config.getId());
                }
            } catch (IOException e) {
                log.error("Failed to load file agents", e);
            }
        };
    }

    /** 工厂入口：统一走 run 级装配（读三个 run 级选择后清理，与运行期注册一致）。 */
    private io.agentscope.harness.agent.HarnessAgent assembleForCurrentRun(AgentConfig config) {
        return assemblyService.assembleForCurrentRun(config);
    }

    /**
     * 当 Agent 状态变为 active 时，动态注册其工厂。
     * 当 Agent 被删除或状态变更时，从注册表移除。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("AG-UI integration ready. Active agents will be available at POST /agui/run/{agentId}");
    }
}
