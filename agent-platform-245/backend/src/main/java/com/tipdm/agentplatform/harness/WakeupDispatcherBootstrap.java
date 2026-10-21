package com.tipdm.agentplatform.harness;

import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.gateway.WakeupDispatcher;
import com.tipdm.agentplatform.config.AgentDirScanner;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * AG-UI 模式下 {@link WakeupDispatcher} 的启动/停止。
 *
 * <p>应用就绪后启动全局唤醒器：启动时立即 drain 一次共享总线上的 pending wakeups
 * （含迁移过来的历史 wakeup），随后由 {@code WorkspaceMessageBus} 的轮询订阅
 * （每 3s emit 一次信号）持续驱动，子智能体任务完成后即可唤醒空闲父会话。</p>
 */
@Component
public class WakeupDispatcherBootstrap implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WakeupDispatcherBootstrap.class);

    private final MessageBus messageBus;
    private final AguiWakeupTarget wakeupTarget;
    private final AgentDirScanner fileSystemAgentLoader;

    private volatile WakeupDispatcher dispatcher;

    public WakeupDispatcherBootstrap(
            MessageBus messageBus,
            AguiWakeupTarget wakeupTarget,
            AgentDirScanner fileSystemAgentLoader) {
        this.messageBus = messageBus;
        this.wakeupTarget = wakeupTarget;
        this.fileSystemAgentLoader = fileSystemAgentLoader;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (dispatcher != null) {
            return;
        }
        try {
            // 保证目录型 Agent 已加载：WakeupDispatcher 启动即 drain，可能立刻
            // 解析会话所属智能体（跨重启恢复 pending wakeup）。
            fileSystemAgentLoader.loadAll();
        } catch (Exception e) {
            log.warn("WakeupDispatcher start: failed to preload file-based agents: {}", e.getMessage());
        }
        dispatcher = new WakeupDispatcher(messageBus, wakeupTarget);
        dispatcher.start();
        log.info("WakeupDispatcher started: idle AG-UI sessions will resume on subagent completion");
    }

    @PreDestroy
    public void stop() {
        WakeupDispatcher d = dispatcher;
        if (d != null) {
            d.close();
            dispatcher = null;
            log.info("WakeupDispatcher stopped");
        }
    }

    @Override
    public void close() {
        stop();
    }
}
