package com.tipdm.agentplatform.harness;

import com.tipdm.agentplatform.config.AgentScopeWakeupConfig;
import com.tipdm.agentplatform.service.AgentAssemblyService;
import com.tipdm.agentplatform.service.SessionOwnerService;
import com.tipdm.agentplatform.service.SessionWorkspaceManager;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.MessageBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * inbox 巡检补醒：定期扫描共享总线的 inbox 队列（{@code queues/agentscope_inbox_<sessionId>_*}），
 * 发现「存在未消费条目但会话已空闲」时补发一次 wakeup。
 *
 * <p>背景：子智能体完成结果 push 到父会话 inbox 时，若父会话恰好处于 RUNNING
 * （父 turn 卡在 agent_spawn 同步等待、120s 后台化未及时触发），harness 的
 * {@code WakeupDispatcher} 会因 "session is running" 跳过并 <b>drain 掉条目</b>，
 * 结果永久丢失（2026-09-04 复现：补充案例清单 spawn 已完成、inbox 残留一条
 * {@code tool_output} 结果，父会话始终收不到）。</p>
 *
 * <p>本巡检是平台侧兜底：只处理「结果还在、会话已空闲」的情况，把条目重新
 * 通过 wakeup 投递出去；不改 harness 语义。配合 1 分钟长工具后台化 + 30 分钟
 * 工具执行超时，最坏情况（父 turn 卡到超时结束）也会在下一轮巡检后自动恢复。</p>
 *
 * <p><b>归属解析（2026-09-05 修正）</b>：唤醒必须携带该会话的<b>真实 owner</b>。
 * 之前直接取 {@code registry.agentFor(sessionId)}——该值常是最近注册的
 * <b>子智能体运行期 UUID</b>（Harness 会把父 middleware 复制给子智能体并注册），
 * 用它查归属必然为空 → {@code enqueueWakeup(userId="")} → 平台 wakeup 回落
 * anonymous 续跑，把 agent_state 写进 {@code anonymous:} 分区，与前端转录读取的
 * 真实用户分区分裂，续跑产出（如 HITL 闸门表单）在前端永久不可见。故先按
 * {@code agentCandidates} 从新到旧取第一个平台可装配的（父）智能体，再查其 owner。</p>
 */
@Component
public class InboxWatchdogWakeupService {

    private static final Logger log = LoggerFactory.getLogger(InboxWatchdogWakeupService.class);

    private static final String INBOX_PREFIX = "agentscope_inbox_";
    private static final String KEEP_FILE = ".keep";
    /** 同一会话两次补醒的最小间隔，避免条目未被 drain 时反复唤醒空跑。 */
    private static final long WAKEUP_COOLDOWN_MILLIS = 120_000;

    /**
     * 计数泄漏判定门槛（分钟）：会话被标记在跑但本进程无新鲜租约，持续超过该时长
     * 即判定 {@link SessionActivityTracker} 计数泄漏（run 收尾 begin/end 不配对），
     * 强制释放计数并照常补醒。正常在跑的 run 由 ServerSideRunLauncher 双登记，
     * 租约心跳必然新鲜；两者矛盾只可能是泄漏。2026-09-29 实测：该形态下本看护与
     * Progress watchdog 被同一个泄漏计数同时静默，inbox 积压无人补醒，会话停流。
     */
    private static final long LEAK_RECOVER_MILLIS = 5 * 60_000L;

    /** 泄漏疑点 WARN 打点节流。 */
    private static final long LEAK_WARN_THROTTLE_MILLIS = 10 * 60_000L;

    private final SessionWorkspaceManager sessionWorkspaceManager;
    private final SessionActivityTracker sessionActivityTracker;
    private final SessionAgentRegistry sessionAgentRegistry;
    private final SessionOwnerService sessionOwnerService;
    private final AgentAssemblyService assemblyService;
    private final MessageBus messageBus;
    private final RunLeaseStore leaseStore;
    /** sessionId → 上次补醒时刻。 */
    private final ConcurrentHashMap<String, Long> lastWakeupAt = new ConcurrentHashMap<>();
    /** sessionId → 首次观测到「在跑但无新鲜租约」的时刻（泄漏判定锚）。 */
    private final ConcurrentHashMap<String, Long> firstSeenRunningNoLease = new ConcurrentHashMap<>();
    /** sessionId → 上次泄漏疑点 WARN 时刻。 */
    private final ConcurrentHashMap<String, Long> leakWarnedAt = new ConcurrentHashMap<>();

    public InboxWatchdogWakeupService(
            SessionWorkspaceManager sessionWorkspaceManager,
            SessionActivityTracker sessionActivityTracker,
            SessionAgentRegistry sessionAgentRegistry,
            SessionOwnerService sessionOwnerService,
            AgentAssemblyService assemblyService,
            MessageBus messageBus,
            RunLeaseStore leaseStore) {
        this.sessionWorkspaceManager = sessionWorkspaceManager;
        this.sessionActivityTracker = sessionActivityTracker;
        this.sessionAgentRegistry = sessionAgentRegistry;
        this.sessionOwnerService = sessionOwnerService;
        this.assemblyService = assemblyService;
        this.messageBus = messageBus;
        this.leaseStore = leaseStore;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void sweep() {
        Path queues = sessionWorkspaceManager.getWorkspaceRoot()
                .resolve(AgentScopeWakeupConfig.SHARED_BUS_ROOT)
                .resolve("queues");
        if (!Files.isDirectory(queues)) {
            return;
        }
        try (Stream<Path> dirs = Files.list(queues)) {
            dirs.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(INBOX_PREFIX))
                    .forEach(this::inspectQueue);
        } catch (IOException e) {
            log.warn("Inbox watchdog sweep failed: {}", e.getMessage());
        }
    }

    private void inspectQueue(Path queueDir) {
        String sessionId = extractSessionId(queueDir.getFileName().toString());
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        // 已归档（弃用）会话不补醒：归档是用户宣告「这个会话不再自行推进」，
        // 与 AguiWakeupTarget / 停滞看护同源判据。三条唤醒通道任一漏掉归档，
        // 弃用的会话就会被拉起来续跑（用户报障：弃用/删掉的会话重启后还在跑）。
        if (sessionOwnerService.isArchivedBySession(sessionId)) {
            log.info("Inbox watchdog: session {} is archived (abandoned by user), skipping wakeup",
                    sessionId);
            return;
        }
        // 已删除会话：静默并顺手清掉孤儿 inbox 队列（2026-10-13）。
        // 删除动作本身会清队列，但删完之后在跑的子 run 仍可能往里 push 完成通知
        // （框架任务完成回调），队列被重建；不主动清掉就会每 30 秒命中一次本分支。
        // 与「无 owner 记录 ⇒ 清孤儿队列」同一条清理路径，区别是这里 owner 行还在（带墓碑）。
        if (sessionOwnerService.isDeletedBySession(sessionId)) {
            sessionWorkspaceManager.deleteSessionInboxQueue(sessionId);
            log.info("Inbox watchdog: session {} was deleted by user, removed orphan inbox queue",
                    sessionId);
            return;
        }
        if (sessionActivityTracker.isRunning(sessionId)) {
            if (!recoverLeakedCountIfAny(sessionId)) {
                // 父回合真实在跑（有新鲜租约心跳）：它自己会 drain inbox，跳过。
                return;
            }
            // 计数泄漏已自愈释放，按空闲会话继续走下方正常补醒。
        }
        List<Path> entries;
        try (Stream<Path> files = Files.list(queueDir)) {
            entries = files.filter(Files::isRegularFile)
                    .filter(f -> !KEEP_FILE.equals(f.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return;
        }
        if (entries.isEmpty()) {
            return;
        }
        Long last = lastWakeupAt.get(sessionId);
        long now = System.currentTimeMillis();
        if (last != null && now - last < WAKEUP_COOLDOWN_MILLIS) {
            return;
        }
        // 注意：不能用 agentFor()——它返回最近注册者，可能是子智能体运行期 UUID，
        // 既查不到 owner，也会让 wakeup 以错误 agent 投递。必须解析到平台可装配的
        // 父智能体（与 AguiWakeupTarget 同一套候选回退逻辑）。
        String agentId = resolveParentAgentId(sessionId);
        if (agentId == null || agentId.isBlank()) {
            if (sessionOwnerService.agentBySessionId(sessionId).isEmpty()) {
                // 归属表无记录 = 会话已删除（或从未注册过 run）。队列是删除时序外的
                // 存量孤儿（clearSession 的 inbox 联动清理是 2026-09-1x 才补的，更早
                // 删除的会话会留下带未消费条目的队列；或删除后子智能体回写重建），
                // 直接清掉自愈，否则每 30 秒 WARN 一次永不终止（2026-09-19 实测）。
                // 有归属记录但装配失败（智能体配置坏/禁用）不在此列——条目可能是
                // 真实待消费结果，保留并维持 WARN 跳过。
                sessionWorkspaceManager.deleteSessionInboxQueue(sessionId);
                log.info("Inbox watchdog: session {} has no owner record (deleted), "
                        + "removed orphan inbox queue", sessionId);
            } else {
                log.warn("Inbox watchdog: no assemblable parent agent for session {}, skipping", sessionId);
            }
            return;
        }
        String userId = sessionOwnerService.owner(agentId, sessionId).orElse("");
        lastWakeupAt.put(sessionId, now);
        log.info(
                "Inbox watchdog: session {} idle but {} pending inbox entry(ies), "
                        + "enqueue wakeup (agent={}, user={})",
                sessionId, entries.size(), agentId, userId);
        messageBus.enqueueWakeup(userId, sessionId, agentId).subscribe();
    }

    /**
     * 「在跑计数」与「新鲜租约」矛盾时的泄漏自愈出口。
     *
     * <p>正常在跑的 run 由 {@code ServerSideRunLauncher} 双登记（租约 + 收尾闸门），
     * 租约心跳必然新鲜；「计数在跑 + 无新鲜租约」持续存在即收尾半途而废的计数泄漏。
     * 持续超过 {@link #LEAK_RECOVER_MILLIS} 后强制释放计数并放行补醒；未超时先打点
     * 观测（节流），保留正常 run 的容忍窗（装配/排队中的 run 可能短暂无心跳）。</p>
     *
     * @return true = 已判定泄漏并 forceEnd，调用方应继续走空闲补醒；false = 维持跳过
     */
    private boolean recoverLeakedCountIfAny(String sessionId) {
        boolean leaseFresh;
        try {
            leaseFresh = leaseStore.existsFreshBySessionId(sessionId, 5);
        } catch (RuntimeException e) {
            log.debug("Inbox watchdog: lease freshness check failed for {}: {}", sessionId, e.getMessage());
            leaseFresh = false;
        }
        if (leaseFresh) {
            firstSeenRunningNoLease.remove(sessionId);
            leakWarnedAt.remove(sessionId);
            return false;
        }
        long now = System.currentTimeMillis();
        Long firstSeen = firstSeenRunningNoLease.computeIfAbsent(sessionId, k -> now);
        Long lastWarn = leakWarnedAt.get(sessionId);
        if (lastWarn == null || now - lastWarn >= LEAK_WARN_THROTTLE_MILLIS) {
            leakWarnedAt.put(sessionId, now);
            log.warn("Inbox watchdog: session {} is marked running but has no fresh lease "
                            + "for {} s — suspected activity-count leak (begin/end out of sync); "
                            + "force-releasing at {} s",
                    sessionId, (now - firstSeen) / 1000, LEAK_RECOVER_MILLIS / 1000);
        }
        if (now - firstSeen < LEAK_RECOVER_MILLIS) {
            return false;
        }
        sessionActivityTracker.forceEnd(sessionId);
        firstSeenRunningNoLease.remove(sessionId);
        leakWarnedAt.remove(sessionId);
        log.error("Inbox watchdog: session {} had a leaked running count with no fresh lease "
                + "for over {} min — count force-released; proceeding with wakeup",
                sessionId, LEAK_RECOVER_MILLIS / 60_000);
        return true;
    }

    /**
     * 解析可装配的（父）智能体：先查注册候选（最新在前，装配验证通过即用）；
     * 候选为空时回退 owner 表（session→agent 可靠映射）再装配验证。
     *
     * <p>⚠️ 曾记「内存 SessionAgentRegistry 全仓无 register 调用、恒为空」——
     * <b>该结论已过时</b>：{@code PlatformAguiRuntimeContextResolver} 与
     * {@code SessionActivityMiddleware} 均有 register 调用，进程运行期内候选非空。
     * 这对本方法的影响是：已删除会话的 owner 行（带墓碑）仍能被解析出 agentId，
     * 所以<b>墓碑检查必须排在解析之前</b>（见 {@code inspectQueue} 开头），
     * 不能指望「解析不出来」来拦住已删会话。</p>
     */
    private String resolveParentAgentId(String sessionId) {
        for (String candidate : sessionAgentRegistry.agentCandidates(sessionId)) {
            HarnessAgent assembled = assemblyService.requireAssembledById(candidate);
            if (assembled != null) {
                return candidate;
            }
        }
        return sessionOwnerService.agentBySessionId(sessionId)
                .filter(candidate -> assemblyService.requireAssembledById(candidate) != null)
                .orElse(null);
    }

    /** {@code agentscope_inbox_<sessionId>_<hash>} → {@code sessionId}。 */
    private String extractSessionId(String dirName) {
        if (!dirName.startsWith(INBOX_PREFIX)) {
            return null;
        }
        int hashSep = dirName.lastIndexOf('_');
        if (hashSep <= INBOX_PREFIX.length()) {
            return null;
        }
        return dirName.substring(INBOX_PREFIX.length(), hashSep);
    }
}
