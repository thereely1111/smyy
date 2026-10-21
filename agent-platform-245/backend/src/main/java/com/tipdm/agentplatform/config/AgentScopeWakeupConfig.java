package com.tipdm.agentplatform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.bus.WorkspaceMessageBus;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import com.tipdm.agentplatform.harness.AguiWakeupTarget;
import com.tipdm.agentplatform.harness.SessionActivityMiddleware;
import com.tipdm.agentplatform.harness.SessionActivityTracker;
import com.tipdm.agentplatform.harness.SessionAgentRegistry;
import com.tipdm.agentplatform.harness.SessionEventClock;
import com.tipdm.agentplatform.service.AgentAssemblyService;
import com.tipdm.agentplatform.service.PendingFormInterruptStore;
import com.tipdm.agentplatform.service.SessionOwnerService;
import com.tipdm.agentplatform.service.SessionWorkspaceManager;
import com.tipdm.agentplatform.harness.ServerSideRunLauncher;
import com.tipdm.agentplatform.harness.SessionPermissionReconciler;
import com.tipdm.agentplatform.harness.WakeupInterruptRegistrar;
import com.tipdm.agentplatform.service.SessionKnowledgeService;
import com.tipdm.agentplatform.service.SessionProgressService;
import com.tipdm.agentplatform.service.notify.SessionNotifyPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * AG-UI 模式下的「子智能体异步结果唤醒」装配。
 *
 * <p>背景：子智能体任务在后台完成时，完成回调会向父会话 inbox push 结果并 enqueue
 * wakeup。但 AG-UI 线程模型下没有 {@code HarnessGateway}，平台从未启动
 * {@code WakeupDispatcher}，导致父会话 idle 后永远不被唤醒（子任务已 COMPLETED、
 * 结果未交付，任务卡死）。</p>
 *
 * <p>本装配：</p>
 * <ol>
 *   <li>提供全平台共享的 {@link MessageBus}（根目录 {@code {workspaceRoot}/.agentscope/bus}），
 *       所有 {@code HarnessAgent} 通过 {@code AgentAssemblyService} 注入同一总线，使
 *       子智能体完成回调与 InboxMiddleware 读写同一队列；</li>
 *   <li>启动时将历史「每个智能体各自的总线」队列文件迁移到共享总线（含 pending 的
 *       wakeup / inbox hint，跨重启恢复会话）；</li>
 *   <li>{@link io.agentscope.harness.agent.gateway.WakeupDispatcher} 消费
 *       {@code agentscope:wakeups}，恢复空闲会话（{@link AguiWakeupTarget}）。</li>
 * </ol>
 */
@Configuration
public class AgentScopeWakeupConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeWakeupConfig.class);

    /** 共享总线相对 workspace-root 的根目录。 */
    public static final String SHARED_BUS_ROOT = ".agentscope/bus";

    @Bean
    public MessageBus agentScopeMessageBus(
            SessionWorkspaceManager sessionWorkspaceManager,
            SessionAgentRegistry sessionAgentRegistry) {
        Path wsRoot = sessionWorkspaceManager.getWorkspaceRoot();
        MessageBus bus =
                new WorkspaceMessageBus(new LocalFilesystem(wsRoot), SHARED_BUS_ROOT);
        migrateLegacyQueues(wsRoot, sessionAgentRegistry);
        return bus;
    }

    @Bean
    public SessionActivityTracker sessionActivityTracker() {
        return new SessionActivityTracker();
    }

    @Bean
    public SessionAgentRegistry sessionAgentRegistry() {
        return new SessionAgentRegistry();
    }

    @Bean
    public SessionEventClock sessionEventClock() {
        return new SessionEventClock();
    }

    @Bean
    public SessionActivityMiddleware sessionActivityMiddleware(
            SessionActivityTracker tracker, SessionAgentRegistry registry,
            SessionEventClock eventClock) {
        return new SessionActivityMiddleware(tracker, registry, eventClock);
    }

    @Bean
    public AguiWakeupTarget aguiWakeupTarget(
            SessionAgentRegistry sessionAgentRegistry,
            SessionActivityTracker tracker,
            AgentAssemblyService assemblyService,
            SessionWorkspaceManager sessionWorkspaceManager,
            SessionOwnerService sessionOwnerService,
            MessageBus messageBus,
            PendingFormInterruptStore pendingFormInterruptStore,
            SessionPermissionReconciler permissionReconciler,
            WakeupInterruptRegistrar wakeupInterruptRegistrar,
            ServerSideRunLauncher runLauncher,
            SessionNotifyPublisher notifyPublisher,
            SessionKnowledgeService sessionKnowledgeService,
            SessionProgressService sessionProgressService) {
        return new AguiWakeupTarget(
                sessionAgentRegistry, tracker, assemblyService, sessionWorkspaceManager,
                sessionOwnerService, messageBus, pendingFormInterruptStore, permissionReconciler,
                wakeupInterruptRegistrar, runLauncher, notifyPublisher, sessionKnowledgeService,
                sessionProgressService);
    }

    /**
     * 历史版本每个 {@code HarnessAgent} 各自创建 {@code WorkspaceMessageBus}
     * （根目录为各自工作区的 {@code .agentscope/bus}）。这些队列文件里的 pending
     * wakeup / inbox hint 在切换为共享总线后需要迁移，否则跨重启之后父会话依然
     * 收不到已完成的子任务结果。
     *
     * <p>队列子目录名由 {@code hashKey(key)} 决定，与总线根无关，因此按相对路径
     * 原样复制到共享总线的 {@code queues/} 下即可保持语义。迁移幂等：目标已存在则跳过。
     * 同时从 wakeup 文件回填 sessionId→agentId 注册表，保证重启后唤醒器能定位会话所属智能体。</p>
     */
    private void migrateLegacyQueues(
            Path wsRoot, SessionAgentRegistry sessionAgentRegistry) {
        Path sharedQueues = wsRoot.resolve(SHARED_BUS_ROOT).resolve("queues");
        List<Path> legacyQueueRoots = new ArrayList<>();
        collectLegacyQueueRoots(wsRoot.resolve("agents"), legacyQueueRoots);
        collectLegacyQueueRoots(wsRoot.resolve("runtimes"), legacyQueueRoots);
        // 共享总线自身也可能残留历史 wakeup（例如上一轮迁移后未消费、进程被杀）。
        // 队列子目录名仅由 key 派生，跨总线根一致，因此把 sharedQueues 本身也作为
        // 可回填注册表的扫描源，保证二次重启时仍能解析会话所属智能体。
        if (Files.isDirectory(sharedQueues)) {
            legacyQueueRoots.add(sharedQueues);
        }

        ObjectMapper mapper = new ObjectMapper();
        int copied = 0;
        for (Path queueRoot : legacyQueueRoots) {
            if (!Files.isDirectory(queueRoot)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(queueRoot)) {
                try (Stream<Path> regular = files.filter(Files::isRegularFile)) {
                    List<Path> entries = regular.toList();
                    for (Path entry : entries) {
                        Path rel = queueRoot.relativize(entry);
                        Path dest = sharedQueues.resolve(rel);
                        if (!Files.exists(dest)) {
                            try {
                                Files.createDirectories(dest.getParent());
                                Files.copy(entry, dest);
                                copied++;
                            } catch (IOException e) {
                                log.warn("Legacy bus migration: failed to copy {} -> {}: {}",
                                        entry, dest, e.getMessage());
                            }
                        }
                        // wakeup 队列文件：{userId, sessionId, agentId}，回填注册表
                        if (rel.toString().startsWith("agentscope_wakeups_")) {
                            seedFromWakeupFile(entry, mapper, sessionAgentRegistry);
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Legacy bus migration: failed to scan {}: {}", queueRoot, e.getMessage());
            }
        }
        if (copied > 0) {
            log.info("Legacy bus migration: copied {} queue file(s) to shared bus {}", copied, sharedQueues);
        }
    }

    private void collectLegacyQueueRoots(Path base, List<Path> out) {
        if (!Files.isDirectory(base)) {
            return;
        }
        try (Stream<Path> dirs = Files.list(base)) {
            dirs.filter(Files::isDirectory)
                    .map(dir -> dir.resolve(SHARED_BUS_ROOT).resolve("queues"))
                    .filter(Files::isDirectory)
                    .forEach(out::add);
        } catch (IOException e) {
            log.warn("Legacy bus migration: failed to list {}: {}", base, e.getMessage());
        }
    }

    private void seedFromWakeupFile(
            Path file,
            ObjectMapper mapper,
            SessionAgentRegistry registry) {
        try (InputStream in = Files.newInputStream(file)) {
            Map<?, ?> payload = mapper.readValue(in, Map.class);
            Object sessionId = payload.get("sessionId");
            Object agentId = payload.get("agentId");
            if (sessionId instanceof String s && agentId instanceof String a) {
                registry.register(a, s);
            }
        } catch (IOException e) {
            log.debug("Legacy bus migration: cannot parse wakeup file {}: {}", file, e.getMessage());
        }
    }
}
