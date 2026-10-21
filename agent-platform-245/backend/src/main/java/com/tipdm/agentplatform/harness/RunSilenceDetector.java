package com.tipdm.agentplatform.harness;

import com.tipdm.agentplatform.repository.AgentSessionOwnerRepository;
import com.tipdm.agentplatform.service.notify.AlertNotifier;
import io.agentscope.harness.agent.bus.MessageBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * run 静默探测器（可靠性收口专项·批次2，2026-09-15 升级为「报警 + 自动恢复」）。
 *
 * <p><b>问题</b>：LLM 流式响应中途停滞时，run 在框架侧依然「活跃」
 * （{@link SessionActivityTracker} 计数 &gt; 0、run-timeout 不触发（超时取消链
 * 在框架 BufferedReader 锁上死锁，见 JdkHttpTransport 修复）、Progress watchdog
 * 只覆盖空闲会话）——2026-09-14 当日 4 次停滞每次都靠人工 jstack + netstack
 * 才确认性质。</p>
 *
 * <p><b>两级机制</b>：</p>
 * <ol>
 *   <li><b>报警级</b>（{@code agent-platform.silence-detect-minutes}，默认 20 分钟，
 *       覆盖 8~20 分钟的典型停滞 episode、不误报 30 分钟上限的合法异步工具执行）：
 *       打 WARN 日志（同一 episode 冷却内只报一次），可接入外部告警。</li>
 *   <li><b>恢复级</b>（{@code agent-platform.silence-recover-minutes}，默认 60 分钟，
 *       为历史最长自愈时间的 3 倍以上）：判定 run 已死，自动恢复——向会话 inbox
 *       push 恢复提示（由 {@code InboxWatchdogWakeupService} 复用 wakeup 全链路
 *       补醒，从最后一个成功工具边界后续推，天然幂等），随后
 *       {@link SessionActivityTracker#forceEnd} 释放活跃计数 + 删 run 租约。
 *       恢复失败（inboxPush 异常）时 5 分钟后重试，会话保持原状。</li>
 * </ol>
 *
 * <p><b>子智能体会话（{@code sub-*}）不判死</b>：判死只对「时钟在但长期无事件」这一形态
 * 豁免。该形态对子会话是结构性失真——子会话的细粒度事件被框架转发到父会话的流，本会话时钟
 * 只能靠相位级打点刷新（见 {@link SessionActivityMiddleware}），因此「无事件」推不出「卡死」。
 * 而 {@code clockMissing}（在跑但无时钟条目）是收尾账本真丢了，对父子会话同为真缺陷，照旧恢复。
 * 子会话真正的判死主体应是父侧任务台账与中断端点。</p>
 *
 * <p><b>边界</b>：僵尸 run 的线程与 SSE 订阅在框架侧依然泄漏（取消链死锁的残余，
 * 每次停滞约 2 个线程，重启回收）；恢复是「逻辑终止 + checkpoint 重放」而非
 * 真正 dispose——框架侧 JdkHttpTransport 死锁修复落地后，流停滞会以
 * TimeoutException 正常收尾，本探测器退化为纯观测兜底。恢复后若僵尸 run 极端
 * 情况下自愈，其事件被静默丢弃（订阅已撕），由 409 互斥守卫防止新 run 与其互踩。</p>
 */
@Component
public class RunSilenceDetector {

    private static final Logger log = LoggerFactory.getLogger(RunSilenceDetector.class);

    /** 同一会话两次静默告警的最小间隔（覆盖一个典型停滞 episode）。 */
    private static final long ALERT_COOLDOWN_MILLIS = 15 * 60_000L;

    /** 恢复尝试失败后的重试间隔。 */
    private static final long RECOVER_RETRY_MILLIS = 5 * 60_000L;

    /** 心跳新鲜度门槛（分钟）：更陈旧的租约视为僵尸凭证，不并入扫描源。 */
    private static final long LEASE_INCLUDE_MINUTES = 5;

    /**
     * 计数泄漏恢复门槛（分钟）：「活跃计数在跑但事件时钟无条目」的独恢复时限。
     *
     * <p>时钟条目由 begin() 在 run 起跑即登记、end() 归零才移除——一条正常在跑的
     * run 不可能没有时钟条目。两表出现这种形态即收尾只完成一半（计数泄漏）的铁证，
     * 恢复时限不必套用流停滞的 60 分钟：2026-09-29 实测该形态下 InboxWatchdog 与
     * Progress watchdog 的 {@code isRunning} 跳过点全部失明，会话停流 40 分钟
     * 仍差 20 分钟才够旧恢复阈值。</p>
     */
    private static final long LEAK_RECOVER_MINUTES = 10;

    private final SessionEventClock eventClock;
    private final SessionActivityTracker activityTracker;
    private final RunLeaseStore leaseStore;
    private final MessageBus messageBus;
    private final AgentSessionOwnerRepository ownerRepository;
    private final AlertNotifier alertNotifier;
    private final long thresholdMillis;
    private final long recoverThresholdMillis;

    /** sessionId → 上次告警时刻。 */
    private final ConcurrentHashMap<String, Long> lastAlertAt = new ConcurrentHashMap<>();

    /** sessionId → 上次恢复尝试时刻（失败重试节流）。 */
    private final ConcurrentHashMap<String, Long> lastRecoverAttemptAt = new ConcurrentHashMap<>();

    /** sessionId → 首次观测到「在跑但无事件时钟条目」的时刻（静默起点锚）。 */
    private final ConcurrentHashMap<String, Long> firstSeenNoClock = new ConcurrentHashMap<>();

    /** sessionId → 上次泄漏打点告警时刻。 */
    private final ConcurrentHashMap<String, Long> leakWarnedAt = new ConcurrentHashMap<>();

    private final RunSubscriptionRegistry runSubscriptions;

    public RunSilenceDetector(
            SessionEventClock eventClock,
            SessionActivityTracker activityTracker,
            RunLeaseStore leaseStore,
            MessageBus messageBus,
            AgentSessionOwnerRepository ownerRepository,
            AlertNotifier alertNotifier,
            RunSubscriptionRegistry runSubscriptions,
            @Value("${agent-platform.silence-detect-minutes:20}") long silenceDetectMinutes,
            @Value("${agent-platform.silence-recover-minutes:60}") long silenceRecoverMinutes) {
        this.eventClock = eventClock;
        this.activityTracker = activityTracker;
        this.leaseStore = leaseStore;
        this.messageBus = messageBus;
        this.ownerRepository = ownerRepository;
        this.alertNotifier = alertNotifier;
        this.runSubscriptions = runSubscriptions;
        this.thresholdMillis = silenceDetectMinutes * 60_000L;
        this.recoverThresholdMillis = silenceRecoverMinutes * 60_000L;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void sweep() {
        long now = System.currentTimeMillis();
        Map<String, Long> clockSnapshot = eventClock.snapshot();
        // 3.2 扫描源 = 三方并集（事件时钟 ∪ 活跃计数 ∪ 本进程心跳新鲜租约）。
        // 原来只遍历 eventClock：计数泄漏（有 running 无时钟条目）的会话对探测器
        // 完全不可见 ⇒ 既不告警也不恢复 ⇒ 租约被 reconciler 规则 2 无限续命 ⇒
        // 停滞闸门永久关死（2026-09-27 实测 10 小时零自检的成因链）。并集后
        // 两表不齐本身成为可观测状态；无时钟条目的会话以首次被观测到在跑为静默起点。
        Set<String> candidates = new HashSet<>(clockSnapshot.keySet());
        candidates.addAll(activityTracker.runningSessionIds());
        try {
            for (RunLeaseStore.RunLease lease : leaseStore.findAll()) {
                if (leaseStore.instanceId().equals(lease.instanceId())
                        && lease.age().toMinutes() < LEASE_INCLUDE_MINUTES) {
                    candidates.add(lease.sessionId());
                }
            }
        } catch (RuntimeException e) {
            log.debug("Run silence: lease scan failed: {}", e.getMessage());
        }

        for (String sessionId : candidates) {
            if (!activityTracker.isRunning(sessionId)) {
                firstSeenNoClock.remove(sessionId);
                continue; // 计数为零：非本进程 run 或租约残留由 RunLeaseReconciler 收尾
            }
            Long lastEventAt = clockSnapshot.get(sessionId);
            boolean clockMissing = lastEventAt == null;
            if (clockMissing) {
                // 在跑但无事件时钟条目 = 计数与时钟两表不齐（doFinally 未执行的
                // 典型泄漏形态）：打点告警，并以首次观测到为静默起点纳入判定。
                warnLeakOnce(sessionId, now);
                lastEventAt = firstSeenNoClock.computeIfAbsent(sessionId, k -> now);
            } else {
                firstSeenNoClock.remove(sessionId);
            }
            long silence = now - lastEventAt;
            // 恢复判定分档：时钟条目缺失（收尾丢失铁证）走短时限；时钟在但事件
            // 停滞（疑似上游流卡死，需给合法异步工具留时间）走常规长时限。
            long recoverAt = clockMissing
                    ? LEAK_RECOVER_MINUTES * 60_000L
                    : recoverThresholdMillis;
            if (silence < thresholdMillis && silence < recoverAt) {
                continue;
            }
            if (silence >= recoverAt) {
                attemptRecovery(sessionId, silence, now, clockMissing);
            } else {
                alert(sessionId, silence, now);
            }
        }
    }

    /** 泄漏打点：同一会话只 WARN 一次（恢复/计数释放后重新登记）。 */
    private void warnLeakOnce(String sessionId, long now) {
        Long last = leakWarnedAt.get(sessionId);
        if (last != null && now - last < ALERT_COOLDOWN_MILLIS) {
            return;
        }
        leakWarnedAt.put(sessionId, now);
        log.warn("Activity tracker leak suspected: session {} has a running count but no "
                + "event-clock entry — begin/doFinally pairs are out of sync; the stall "
                + "gate is closed while nobody observes events. Watchdog still covers it "
                + "via first-seen anchor.", sessionId);
    }

    /** 报警级：同一 episode 冷却内只报一次。 */
    private void alert(String sessionId, long silence, long now) {
        Long last = lastAlertAt.get(sessionId);
        if (last != null && now - last < ALERT_COOLDOWN_MILLIS) {
            return;
        }
        lastAlertAt.put(sessionId, now);
        if (isSubagentSession(sessionId)) {
            // 子会话的「无事件」推不出「卡死」，诊断文案必须指向子会话自己的证据，否则会
            // 把人引去查上游 LLM 流。不投递用户告警（子会话本就没有 owner 行，AlertNotifier
            // 只会静默忽略），也不承诺自动恢复（判死对子会话已豁免）。
            log.warn(
                    "Sub-agent run silence: session {} has an active run but no agent event for "
                            + "{} min. For a sub-agent session this signal is structurally "
                            + "unreliable — its fine-grained events are forwarded to the parent "
                            + "session's stream. Check liveness from the sub-agent's own artifacts "
                            + "under workspace/runtimes/{{agentId}}/{{sessionId}}/ and from "
                            + "AgentTraceMiddleware lines carrying its agent name. No auto-recovery "
                            + "runs for a sub-agent session.",
                    sessionId, silence / 60_000);
            return;
        }
        log.warn(
                "Run silence detected: session {} has an active run but no agent event "
                        + "for {} min — likely upstream LLM stream stall (episodes typically "
                        + "self-heal in 8-20 min; auto-recovery triggers at {} min). "
                        + "Confirm via jstack: blocked on HttpResponseInputStream.read",
                sessionId, silence / 60_000, recoverThresholdMillis / 60_000);
        alertNotifier.publish(new AlertNotifier.AlertRequest(
                AlertNotifier.LEVEL_WARN,
                String.format("会话有 run 在跑但已 %d 分钟无事件，疑似上游流停滞", silence / 60_000),
                "典型停滞 episode 会在 8~20 分钟内自愈；平台将在 "
                        + (recoverThresholdMillis / 60_000) + " 分钟时判定 run 已死并自动恢复。"
                        + "若长时间未恢复，可打开会话确认进度。",
                resolveUserId(sessionId), sessionId, null, "silence:" + sessionId));
    }

    /** 子智能体会话（{@code sub-*}）：细粒度事件被转发到父会话流，其静默信号结构性不可靠。 */
    private static boolean isSubagentSession(String sessionId) {
        return sessionId != null
                && sessionId.startsWith(SubagentSessionMaintenance.SUB_SESSION_PREFIX);
    }

    /** 会话归属用户（告警按用户投递；查不到时 AlertNotifier 自行忽略）。 */
    private String resolveUserId(String sessionId) {
        try {
            return ownerRepository.findBySessionId(sessionId).stream()
                    .findFirst()
                    .map(owner -> owner.getUserId())
                    .orElse(null);
        } catch (RuntimeException e) {
            log.debug("Run silence: owner lookup failed for {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 恢复级：inbox push 恢复提示 → 成功后 forceEnd 释放计数 + 删租约。
     * push 失败保留现场，节流后重试。
     *
     * @param clockMissing true = 计数泄漏形态（时钟无条目，收尾丢失）；false = 流停滞形态
     */
    private void attemptRecovery(String sessionId, long silence, long now, boolean clockMissing) {
        Long last = lastRecoverAttemptAt.get(sessionId);
        if (last != null && now - last < RECOVER_RETRY_MILLIS) {
            return;
        }
        lastRecoverAttemptAt.put(sessionId, now);
        // 「时钟在但长期无事件」对子会话是结构性失真（事件被转发到父会话流），据此判死会
        // 把仍在写产物的子代理改成账本上的「已结束」：dispose 对子会话本就无句柄
        // （RunSubscriptionRegistry 只登记服务端自发 run），而 forceEnd 会释放活跃计数、
        // 删租约、还往子会话 inbox 塞一条恢复提示。所以这一形态只留痕、不动账本。
        // clockMissing 不走这条豁免：那是收尾账本真丢了，父子会话同为真缺陷。
        if (!clockMissing && isSubagentSession(sessionId)) {
            log.warn(
                    "Run silence recovery: skipped for sub-agent session {} (no agent event for "
                            + "{} min). The silence signal is structurally unreliable for a "
                            + "sub-agent run — its events are forwarded to the parent session, and "
                            + "the run may still be writing artifacts. Not releasing the activity "
                            + "count, not deleting the lease, not pushing a recovery hint; the "
                            + "parent task ledger and the interrupt endpoint remain the judging "
                            + "authority.",
                    sessionId, silence / 60_000);
            return;
        }
        // 方案 A（2026-09-30）：先 dispose 订阅再宣告死亡。此前只 forceEnd + 删租约
        // （改平台账本），底层 LLM 流若后来自愈，agent 循环会继续执行工具而平台认为
        // 会话空闲 → 看护可能再唤醒第二个 run 并发写状态。dispose 让 Reactor cancel
        // 沿链 abort HTTP 连接，僵尸从根上死透，账本与真实运行态重新一致。
        boolean disposed = runSubscriptions != null && runSubscriptions.dispose(sessionId);
        log.error(
                "Run silence recovery: session {} {} for {} min — declaring the run dead "
                        + "and pushing a recovery hint (agent events will be dropped; new runs "
                        + "may take over the session)",
                sessionId,
                clockMissing
                        ? "has a leaked running count but no event-clock entry (begin/end "
                                + "bookkeeping lost mid-finish)"
                        : "has an active run but no agent event",
                silence / 60_000);
        alertNotifier.publish(new AlertNotifier.AlertRequest(
                AlertNotifier.LEVEL_ERROR,
                String.format("会话 run 静默 %d 分钟，平台已判定其死亡并自动恢复", silence / 60_000),
                "平台已向会话 inbox 投递恢复提示，会话将从最后一个成功工具边界续跑。"
                        + "若恢复后仍无进展，需要人工接手。",
                resolveUserId(sessionId), sessionId, null, "silence-recover:" + sessionId));
        String hint = String.format(
                "<system-notification>Platform watchdog: the previous run in this session was "
                        + "silent for %d minutes (likely an upstream LLM stream stall) and has "
                        + "been declared dead by the platform. Before doing anything else, "
                        + "self-check current progress (task list and workspace files) to "
                        + "determine which deliverables are already written and which are "
                        + "missing, then decide whether to resume or redispatch the unfinished "
                        + "part.</system-notification>",
                silence / 60_000);
        Map<String, Object> payload = Map.of(
                "type", "hint",
                "id", UUID.randomUUID().toString().replace("-", ""),
                "hint", hint,
                "source", "run_silence_recovery");
        messageBus.inboxPush(sessionId, payload)
                .doOnSuccess(v -> {
                    // 逻辑终止僵尸 run：释放活跃计数（新 run 得以进入，AG-UI 入口
                    // 的 409 互斥守卫据此放行）并清租约（RunLeaseReconciler 不再心跳）。
                    activityTracker.forceEnd(sessionId);
                    eventClock.clear(sessionId);
                    leaseStore.delete(sessionId);
                    firstSeenNoClock.remove(sessionId);
                    leakWarnedAt.remove(sessionId);
                    lastAlertAt.remove(sessionId);
                    lastRecoverAttemptAt.remove(sessionId);
                    log.error(
                            "Run silence recovery: recovery hint pushed for session {} — "
                                    + "activity released, lease deleted; inbox watchdog will "
                                    + "wake the session for checkpoint replay",
                            sessionId);
                })
                .doOnError(e -> log.warn(
                        "Run silence recovery: inboxPush failed for session {}: {} "
                                + "(will retry in {} min)",
                        sessionId, e.getMessage(), RECOVER_RETRY_MILLIS / 60_000))
                .subscribe();
    }

    /** 供测试/运维观察的恢复阈值。 */
    Duration recoverThreshold() {
        return Duration.ofMillis(recoverThresholdMillis);
    }
}
