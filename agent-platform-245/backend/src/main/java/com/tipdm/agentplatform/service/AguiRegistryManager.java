package com.tipdm.agentplatform.service;

import io.agentscope.core.agui.registry.AguiAgentRegistry;
import com.tipdm.agentplatform.domain.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AguiRegistryManager {

    private static final Logger log = LoggerFactory.getLogger(AguiRegistryManager.class);

    private final AguiAgentRegistry registry;
    private final AgentAssemblyService assemblyService;

    public AguiRegistryManager(AguiAgentRegistry registry, AgentAssemblyService assemblyService) {
        this.registry = registry;
        this.assemblyService = assemblyService;
    }

    /**
     * 注册 Agent（所有状态均可注册，2026-09-24 起）。
     *
     * <p>此前仅 active 注册；在线调试要求「草稿/下架态智能体也能试运行」
     * （编辑页点试运行直接开新会话验证，不经发布）。可见性仍由列表接口承担：
     * 广场/对话选择器只返回发布态（includeDrafts 仅管理端生效），终端用户
     * 无法从任何界面触达非发布智能体。</p>
     */
    public void register(AgentConfig config) {
        if (config == null) {
            return;
        }
        String agentId = config.getId().toString();
        try {
            // 防止重复注册：先移除旧的
            registry.unregister(agentId);
            // 注册工厂：每次请求时经 run 级统一装配入口（与启动期注册同语义），
            // 读对话选模型 / 联网开关 / 调试草稿三个 run 级选择——此前这里注册的是
            // 纯 assemble(config)，任何运行期重注册都会把三项 run 级能力静默降级
            //（3.8，见 AgentAssemblyService#assembleForCurrentRun）。
            registry.registerFactory(agentId, () -> assemblyService.assembleForCurrentRun(config));
            log.info("AG-UI registered agent: {} ({}, status={})",
                    config.getName(), agentId, config.getStatus());
        } catch (Exception e) {
            log.warn("Failed to register agent {} to AG-UI: {}", agentId, e.getMessage());
        }
    }

    /**
     * 注销 Agent
     */
    public void unregister(UUID agentId) {
        if (agentId == null) {
            return;
        }
        String id = agentId.toString();
        try {
            registry.unregister(id);
            log.info("AG-UI unregistered agent: {}", id);
        } catch (Exception e) {
            log.debug("Agent {} unregister skipped (not found)", id);
        }
    }

    /**
     * 刷新 Agent（先注销再按当前状态注册）
     */
    public void refresh(AgentConfig config) {
        if (config == null) {
            return;
        }
        unregister(config.getId());
        register(config);
    }
}
