package com.tipdm.agentplatform.harness;

import com.tipdm.agentplatform.domain.AgentSessionOwner;
import com.tipdm.agentplatform.domain.AgentSessionProgress;
import com.tipdm.agentplatform.repository.AgentSessionProgressRepository;
import com.tipdm.agentplatform.service.AgentAssemblyService;
import com.tipdm.agentplatform.service.SessionOwnerService;
import com.tipdm.agentplatform.service.SessionWorkspaceManager;
import com.tipdm.agentplatform.service.notify.AlertNotifier;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.subagent.task.TaskDelivery;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 子智能体后台任务终态补投递看门狗（缺陷修复 2026-09-13）。
 *
 * <p><b>问题</b>：后台子任务到达终态后，正常路径由框架完成回调
 * （{@code TaskRepository.setCompletionCallback}）向父会话 inbox push 完成通知并
 * enqueue wakeup。但回调链路存在丢失场景——实测（2026-09-13，教材开发小助手
 * 会话 main-57f7925a）：目录生成智能体达最大迭代上限自行收工后，无任何
 * WakeupDispatcher 事件、inbox 为空、任务记录停在 RUNNING，父会话只能等
 * Progress watchdog 兜底自检，流程停滞 30+ 分钟。</p>
 *
 * <p><b>修复</b>：框架的 push-delivery API 本就为此设计了恢复通道——
 * {@link TaskRepository#findPendingDeliveries} 返回「终态但未投递」的任务
 * （deliveredAt 为空），由消费方投递后 {@link TaskRepository#markDelivered} 标记。
 * 但框架内唯一消费方（SubagentsMiddleware.onReasoning）只在父会话<b>真的跑起来</b>
 * 时才生效——而本缺陷恰恰是父会话再也不会跑。本看门狗补上最后一环：定期扫描
 * 归属表中空闲的父会话，发现未投递终态任务时，以与框架完成回调完全相同的格式
 * push inbox hint + enqueue wakeup，然后 markDelivered。</p>
 *
 * <p><b>幂等/去重</b>：markDelivered 由仓库保证幂等（首次非空写胜出）；与框架
 * 回调的竞态最坏造成一条重复 hint（框架注释原文："annoying, but safe"）。
 * 每会话设 120s 冷却，避免 wakeup 被闸门守卫延后时反复空转。</p>
 *
 * <p><b>回调链路不去重导致重复投递（2026-09-19 修复）</b>：框架完成回调
 * push inbox 后<b>不写 deliveredAt</b>（反编译 SubagentsMiddleware.lambda$wireMessageBus$2
 * 证实：只有 SubagentsMiddleware.onReasoning 拉取与 wait_async_results 工具两处标记），
 * 于是「回调已成功投递并被消费」的任务会永远留在 findPendingDeliveries 里，看门狗
 * 每轮都补投递——实测（教材开发小助手 main-78d39f79）：同一结果父会话消费两次，
 * 白烧一轮推理 + 一条重复的用户通知。修复：push 前先查重——①父会话转录
 * （runtimes/{agentId}/{sessionId}/ 下非 tasks 文件）已含本任务的完成通知标记
 * （与回调/看门狗同格式 {@code Background subagent task '<taskId>'}）→ 已投递且已消费，
 * 只补 markDelivered；②inbox 里已有含该标记的条目（回调已 push 但未被消费）→
 * 不重复 push，只补 markDelivered + 补醒。</p>
 */
@Component
public class TaskDeliveryWatchdog {

    private static final Logger log = LoggerFactory.getLogger(TaskDeliveryWatchdog.class);

    /** 同一会话两次补投递的最小间隔。 */
    private static final long SWEEP_COOLDOWN_MILLIS = 120_000;

    private final SessionOwnerService sessionOwnerService;
    private final SessionActivityTracker sessionActivityTracker;
    private final AgentAssemblyService assemblyService;
    private final MessageBus messageBus;
    private final AlertNotifier alertNotifier;
    private final SessionWorkspaceManager sessionWorkspaceManager;
    private final AgentSessionProgressRepository progressRepository;
    /** sessionId → 上次补投递时刻。 */
    private final ConcurrentHashMap<String, Long> lastSweepAt = new ConcurrentHashMap<>();

    public TaskDeliveryWatchdog(
            SessionOwnerService sessionOwnerService,
            SessionActivityTracker sessionActivityTracker,
            AgentAssemblyService assemblyService,
            MessageBus messageBus,
            AlertNotifier alertNotifier,
            SessionWorkspaceManager sessionWorkspaceManager,
            AgentSessionProgressRepository progressRepository) {
        this.sessionOwnerService = sessionOwnerService;
        this.sessionActivityTracker = sessionActivityTracker;
        this.assemblyService = assemblyService;
        this.messageBus = messageBus;
        this.alertNotifier = alertNotifier;
        this.sessionWorkspaceManager = sessionWorkspaceManager;
        this.progressRepository = progressRepository;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void sweep() {
        List<AgentSessionOwner> owners;
        try {
            owners = sessionOwnerService.all();
        } catch (RuntimeException e) {
            log.warn("Task delivery watchdog: owner scan failed: {}", e.getMessage());
            return;
        }
        if (owners.isEmpty()) {
            return;
        }
        for (AgentSessionOwner owner : owners) {
            try {
                inspect(owner);
            } catch (RuntimeException e) {
                log.debug("Task delivery watchdog: inspect failed for session {}: {}",
                        owner.getSessionId(), e.getMessage());
            }
        }
    }

    private void inspect(AgentSessionOwner owner) {
        String sessionId = owner.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        // 已删除会话不补投递（2026-10-13）：墓碑行仍在 owner 表里，本扫描会扫到它，
        // 而补投递的终点是 enqueueWakeup —— 会把刚删掉的会话重新拉起来跑。
        // 正常删除路径走 purgeSessionData 删了 agent_session_progress，但本看护读的是
        // 框架 TaskRepository（在盘上），那条路拦不住。
        if (owner.isDeleted()) {
            return;
        }
        // 父回合在跑：SubagentsMiddleware.onReasoning 会在下一轮推理时自行投递。
        if (sessionActivityTracker.isRunning(sessionId)) {
            return;
        }
        Long last = lastSweepAt.get(sessionId);
        long now = System.currentTimeMillis();
        if (last != null && now - last < SWEEP_COOLDOWN_MILLIS) {
            return;
        }
        HarnessAgent agent = assemblyService.requireAssembledById(owner.getAgentId());
        if (agent == null) {
            return;
        }
        TaskRepository repo = agent.getTaskRepository();
        if (repo == null) {
            return;
        }
        RuntimeContext rc = RuntimeContext.builder()
                .userId(owner.getUserId())
                .sessionId(sessionId)
                .build();
        List<TaskDelivery> pending;
        try {
            pending = repo.findPendingDeliveries(rc, sessionId);
        } catch (RuntimeException e) {
            log.debug("Task delivery watchdog: findPendingDeliveries failed for session {}: {}",
                    sessionId, e.getMessage());
            return;
        }
        if (pending == null || pending.isEmpty()) {
            return;
        }
        String agentId = owner.getAgentId();
        String userId = owner.getUserId() == null ? "" : owner.getUserId();
        log.warn(
                "Task delivery watchdog: session {} has {} terminal subagent task(s) never "
                        + "delivered (completion callback lost?) — pushing hint + wakeup "
                        + "(agent={}, user={}, totals: redelivered={}, humanAlerts={})",
                sessionId, pending.size(), agentId, userId,
                redeliveredCount.get(), humanAlertCount.get());
        List<Mono<Void>> pushes = new ArrayList<>();
        boolean needWakeup = false;
        for (TaskDelivery delivery : pending) {
            // ── 去重（2026-09-19）：框架回调 push 后不写 deliveredAt，回调投递成功
            // 的任务会永远留在 pending 列表里。push 前先判断是否已投递过。
            String marker = "Background subagent task '" + delivery.taskId() + "'";
            if (transcriptContains(owner, marker)) {
                // 已投递且父会话已消费（转录里有同格式完成通知）：只补投递标记，
                // 不重复 push 结果、不重复通知人。
                log.info("Task delivery watchdog: task {} already delivered & consumed "
                        + "(found completion notice in transcript), marking delivered only",
                        delivery.taskId());
                try {
                    repo.markDelivered(rc, sessionId, delivery.taskId());
                } catch (RuntimeException e) {
                    log.debug("Task delivery watchdog: markDelivered failed for task {}: {}",
                            delivery.taskId(), e.getMessage());
                }
                // 「已消费 ≠ 会继续」：父会话可能在该轮内读到通知后就被框架迭代上限
                // 截断，此后本看护因「已消费」而静默 ⇒ 永久停滞。判据取终态时刻与
                // 「纯文本收尾」登记时刻是否几乎重合（消费发生在收尾那一轮）；命中则
                // 补一条续跑提示——inbox 非空才能真正驱动 wakeup（空 inbox 的
                // enqueueWakeup 会被 drain 到空而 skip）。
                if (consumedAtTextEnding(owner, delivery)) {
                    log.warn("Task delivery watchdog: task {} was consumed in the same turn that "
                                    + "ended with a text-only ending — parent may have been truncated "
                                    + "right after consuming it; pushing resume hint + wakeup (session={})",
                            delivery.taskId(), sessionId);
                    needWakeup = true;
                    pushes.add(messageBus.inboxPush(sessionId, resumeHintPayload(delivery))
                            .doOnError(e -> log.warn(
                                    "Task delivery watchdog: resume hint push failed for task {} "
                                            + "session {}: {}", delivery.taskId(), sessionId, e.getMessage()))
                            .then());
                }
                continue;
            }
            Boolean queuedUnconsumed = hintQueuedUnconsumed(sessionId, marker);
            if (queuedUnconsumed != null && queuedUnconsumed) {
                // 回调已 push 但尚未被消费：不重复 push，只补投递标记 + 补醒
                // （inbox 里的原条目由父会话下一轮 drain 消费）。
                log.info("Task delivery watchdog: task {} already queued in inbox, "
                        + "marking delivered + wakeup only", delivery.taskId());
                try {
                    repo.markDelivered(rc, sessionId, delivery.taskId());
                } catch (RuntimeException e) {
                    log.debug("Task delivery watchdog: markDelivered failed for task {}: {}",
                            delivery.taskId(), e.getMessage());
                }
                continue;
            }
            String hint = buildHint(delivery);
            Map<String, Object> payload = Map.of(
                    "type", "hint",
                    "id", UUID.randomUUID().toString().replace("-", ""),
                    "hint", hint,
                    "source", "subagent_task");
            needWakeup = true;
            // 先确保 hint 落盘，再投递标记；wakeup 失败无妨——hint 在 inbox 里，
            // InboxWatchdogWakeupService 会继续补醒。
            pushes.add(messageBus.inboxPush(sessionId, payload)
                    .doOnSuccess(v -> {
                        try {
                            repo.markDelivered(rc, sessionId, delivery.taskId());
                        } catch (RuntimeException e) {
                            log.debug("Task delivery watchdog: markDelivered failed for task {}: {}",
                                    delivery.taskId(), e.getMessage());
                        }
                        notifyTaskTerminal(owner, delivery);
                    })
                    .doOnError(e -> log.warn(
                            "Task delivery watchdog: inboxPush failed for task {} session {}: {}",
                            delivery.taskId(), sessionId, e.getMessage()))
                    .then());
        }
        lastSweepAt.put(sessionId, now);
        if (!needWakeup) {
            // 全部命中去重：case ① 已消费无需唤醒；case ② inbox 非空时
            // InboxWatchdogWakeupService 会持续补醒，这里无需重复 enqueue。
            return;
        }
        // 顺序很关键（2026-09-17）：原实现每个 inboxPush 各自 subscribe（fire-and-forget）
        // 后立刻 enqueueWakeup，wakeup 可能先到、drain 到空 inbox，整轮补投递白跑。
        // 改为 concat 全部 push 完成后再 enqueueWakeup。
        Flux.concat(pushes)
                .then(messageBus.enqueueWakeup(userId, sessionId, agentId).then())
                .doOnError(e -> log.warn(
                        "Task delivery watchdog: enqueueWakeup failed for session {}: {}",
                        sessionId, e.getMessage()))
                .subscribe();
    }

    /**
     * 父会话转录中是否已出现本任务的完成通知标记。
     *
     * <p>框架完成回调与补投递 push 的 hint 都含
     * {@code Background subagent task '<taskId>'}；hint 被父会话消费后会作为
     * 用户输入落进会话转录（runtimes/{agentId}/{sessionId}/ 下的 jsonl / events）。
     * 该标记出现即证明此任务的结果已投递并被消费过——只补 deliveredAt，不再重复投递。</p>
     *
     * <p>跳过 tasks/ 子目录（任务记录本身含 taskId，恒为误报）；二进制产物
     * 读为 UTF-8 不会误命中。仅对极少数 pending 任务触发，逐文件全文扫描可接受。</p>
     */
    private boolean transcriptContains(AgentSessionOwner owner, String marker) {
        try {
            var sessionDir = sessionWorkspaceManager.resolveSessionDir(
                    owner.getAgentId(), owner.getSessionId());
            if (sessionDir == null || !Files.isDirectory(sessionDir)) {
                return false;
            }
            try (var files = Files.walk(sessionDir)) {
                return files.filter(Files::isRegularFile)
                        .filter(p -> !p.toString().contains("/tasks/"))
                        .anyMatch(p -> fileContains(p, marker));
            }
        } catch (Exception e) {
            log.debug("Task delivery watchdog: transcript scan failed for session {}: {}",
                    owner.getSessionId(), e.getMessage());
            return false;
        }
    }

    private static final long MAX_SCAN_FILE_BYTES = 64L * 1024 * 1024;

    private boolean fileContains(Path p, String marker) {
        try {
            if (Files.size(p) > MAX_SCAN_FILE_BYTES) {
                return false;
            }
            return Files.readString(p, StandardCharsets.UTF_8)
                    .contains(marker);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * inbox 中是否已有含本任务标记、尚未被消费的条目（框架回调已 push 的场景）。
     *
     * <p>MessageBus 没有带内容的 inbox peek，只有 drain——这里 drain 全量后检查，
     * 再按原顺序原样回填（payload 含原 entryId，语义等价）。看门狗仅在会话空闲时
     * 运行，与父会话 drain 的竞态窗口极小，最坏情形是回填前父会话 drain 到空、
     * 回填后照常消费，不丢数据。返回 null 表示检查失败（按未排队处理，走正常 push）。</p>
     */
    private Boolean hintQueuedUnconsumed(String sessionId, String marker) {
        try {
            List<BusEntry> entries =
                    messageBus.inboxDrain(sessionId, 200)
                            .block(Duration.ofSeconds(5));
            if (entries == null || entries.isEmpty()) {
                return false;
            }
            boolean found = false;
            for (BusEntry entry : entries) {
                Object hint = entry.payload() == null ? null : entry.payload().get("hint");
                if (hint instanceof String s && s.contains(marker)) {
                    found = true;
                }
            }
            // 按原顺序回填，保持 inbox 语义不变
            for (BusEntry entry : entries) {
                messageBus.inboxPush(sessionId, entry.payload())
                        .block(Duration.ofSeconds(5));
            }
            return found;
        } catch (Exception e) {
            log.debug("Task delivery watchdog: inbox inspect failed for session {}: {}",
                    sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 与框架完成回调（HarnessAgent.wireTaskRepositoryMessageBus）同格式的完成通知。
     *
     * <p>3.3：结果若还是 {@code status: timeout_promoted} 同步超时提升桩（历史记录
     * 残留），不得当最终结果重放给父会话——替换为提示语，父会话按 task_output /
     * artifacts 自查真实产出。</p>
     */
    private static String buildHint(TaskDelivery delivery) {
        String outcome;
        if (delivery.result() != null && delivery.result().contains("status: timeout_promoted")) {
            outcome = "[stale promotion stub in task record — not the final result. "
                    + "Check artifacts/ on disk or task_output for the real output]";
        } else if (delivery.result() != null && !delivery.result().isBlank()) {
            outcome = delivery.result();
        } else if (delivery.errorMessage() != null && !delivery.errorMessage().isBlank()) {
            outcome = "[task ended without result: " + delivery.errorMessage() + "]";
        } else {
            outcome = "(no output)";
        }
        return String.format(
                "<system-notification>Background subagent task '%s' (agent=%s) has completed."
                        + "\n\nResult:\n\n%s</system-notification>",
                delivery.taskId(),
                delivery.agentId() != null ? delivery.agentId() : "unknown",
                outcome);
    }

    /**
     * 任务终态落地后的人通知策略（3.6 降噪）：
     *
     * <p><b>仅失败终态发人</b>：成功完成时父会话已收到 inbox hint 并自动续跑，
     * 再发人通知是纯噪声——实测 114 条 task-terminal 通知里 100 条属此类。仅失败
     * 终态需要人确认（子智能体自身失败，可能需要调整任务或上报）。</p>
     *
     * <p><b>失败通知延迟宽限</b>：push 完成标记后等 {@code #HUMAN_ALERT_GRACE_SECONDS}
     * 再复查父会话转录——若 hint 已被消费（转录出现本任务完成标记）说明父会话
     * 已接管失败处理，不再打扰；仍未消费才发人（需人处理级）。</p>
     */
    private static final long HUMAN_ALERT_GRACE_SECONDS = 90;

    /** 累计观测打点：回调丢失的补投递量（父会话侧指标，log 供趋势观察）。 */
    private final AtomicLong redeliveredCount =
            new AtomicLong();
    private final AtomicLong humanAlertCount =
            new AtomicLong();

    private void notifyTaskTerminal(AgentSessionOwner owner, TaskDelivery delivery) {
        redeliveredCount.incrementAndGet();
        boolean failed = isFailed(delivery);
        if (!failed) {
            log.debug("Task delivery watchdog: task {} completed successfully; "
                    + "parent session resumed via inbox hint, no human alert",
                    delivery.taskId());
            return;
        }
        String marker = "Background subagent task '" + delivery.taskId() + "'";
        Mono.delay(Duration.ofSeconds(HUMAN_ALERT_GRACE_SECONDS))
                .subscribe(v -> {
                    if (transcriptContains(owner, marker)) {
                        log.info("Task delivery watchdog: failed task {} was consumed by "
                                + "the parent session within grace period; no human alert",
                                delivery.taskId());
                        return;
                    }
                    humanAlertCount.incrementAndGet();
                    String status = delivery.status() == null ? "unknown" : delivery.status().name();
                    String agent = delivery.agentId() != null ? delivery.agentId() : "unknown";
                    alertNotifier.publish(new AlertNotifier.AlertRequest(
                            AlertNotifier.LEVEL_WARN,
                            String.format("后台子任务失败且未被父会话接管，需人工确认：%s", agent),
                            String.format("任务 %s 终态 %s（完成回调丢失，由看护补投递，"
                                            + "宽限期 %ds 后仍未被父会话消费）。\n"
                                            + "请打开会话确认失败原因，决定重派或调整任务。",
                                    delivery.taskId(), status, HUMAN_ALERT_GRACE_SECONDS),
                            owner.getUserId(),
                            owner.getSessionId(),
                            owner.getAgentId(),
                            "task-terminal:" + owner.getSessionId() + ":" + delivery.taskId()));
                }, e -> log.debug("Task delivery watchdog: grace timer failed for task {}: {}",
                        delivery.taskId(), e.getMessage()));
    }

    /** 失败口径：状态 FAILED（或终态未知但带错误信息）。 */
    private static boolean isFailed(TaskDelivery delivery) {
        if (delivery.status()
                == TaskStatus.FAILED) {
            return true;
        }
        return delivery.status() == null
                && delivery.errorMessage() != null && !delivery.errorMessage().isBlank();
    }

    /**
     * 本任务的终态时刻是否与「纯文本收尾」登记时刻几乎重合。
     *
     * <p>重合说明通知是在父会话收尾那一轮之内被消费的，该轮此后推进空间很小；正常
     * 流程里父会话消费后还会持续工作数分钟，不会命中。任一时刻缺失一律返回 false。</p>
     */
    private boolean consumedAtTextEnding(AgentSessionOwner owner, TaskDelivery delivery) {
        if (delivery.completedAt() == null) {
            return false;
        }
        try {
            return progressRepository
                    .findByAgentIdAndSessionId(owner.getAgentId(), owner.getSessionId())
                    .map(AgentSessionProgress::getLastTextEndingAt)
                    .filter(Objects::nonNull)
                    .map(ending -> Math.abs(Duration.between(
                            delivery.completedAt(), ending).getSeconds())
                            <= TEXT_ENDING_PROXIMITY_SECONDS)
                    .orElse(false);
        } catch (RuntimeException e) {
            log.debug("Task delivery watchdog: text-ending proximity check failed for task {}: {}",
                    delivery.taskId(), e.getMessage());
            return false;
        }
    }

    /** 交付时刻与收尾登记时刻的最大可容忍差（双向）。 */
    private static final long TEXT_ENDING_PROXIMITY_SECONDS = 120;

    /**
     * 续跑提示：不重放结果（避免重复处理），只提示父会话回到断点；同时保证 inbox
     * 非空，否则 enqueueWakeup 会被 drain 到空而 skip。
     */
    private static Map<String, Object> resumeHintPayload(TaskDelivery delivery) {
        String hint = String.format(
                "<system-notification>Background subagent task '%s' (agent=%s) was already "
                        + "delivered and consumed, but this session has not advanced since. "
                        + "If the follow-up actions for that result were never executed, "
                        + "continue from that breakpoint now; if they are already done, "
                        + "simply end the turn.</system-notification>",
                delivery.taskId(),
                delivery.agentId() != null ? delivery.agentId() : "unknown");
        return Map.of(
                "type", "hint",
                "id", UUID.randomUUID().toString().replace("-", ""),
                "hint", hint,
                "source", "task_delivery_resume");
    }
}
