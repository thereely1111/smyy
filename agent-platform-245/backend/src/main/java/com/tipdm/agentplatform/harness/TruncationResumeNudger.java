package com.tipdm.agentplatform.harness;

import com.tipdm.agentplatform.service.SessionOwnerService;
import io.agentscope.harness.agent.bus.MessageBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 迭代上限截断续跑：run 因框架 {@code maxIters} 被强制收尾时，把「预算耗尽 ≠
 * 目标完成」这一事实转成一条 inbox hint 并请求一次唤醒，让会话在秒级接着做完，
 * 而不是等停滞看护的下一个补醒周期。
 *
 * <p><b>事实来源</b>：框架撞上限后走 {@code summarizing()}——给未执行的 tool call
 * 补 error result、生成摘要文本、发 {@code ExceedMaxItersEvent} 收场，平台侧看到的
 * 只是一次普通文本收尾。该事件在两条 run 路径上各有旁路点：
 * {@link ServerRunEventRecorder}（服务端自发 run）与 {@link DeclarativeEndingWatchdog}
 * （AG-UI 请求路径），二者互斥，同一 run 只会命中一处。</p>
 *
 * <p><b>投递语义</b>：hint 写入会话 inbox（持久队列），唤醒请求<b>延迟</b>发出。
 * 截断发生在 run 收尾过程中，此刻立刻请求唤醒会被 {@code WakeupDispatcher} 因
 * 「session running」跳过，而唤醒条目已被 drain 掉——延迟到 run 结束之后再请求，
 * 正常情况一次即拉起续跑。hint 走独立队列，即使唤醒条目被丢弃也不会丢工作：
 * {@link InboxWatchdogWakeupService} 的周期巡检会补投，而任何后续 run 的
 * {@code InboxMiddleware} 也会把积压 hint 注入模型上下文。</p>
 *
 * <p><b>封顶</b>：同一会话在滑动窗口内超过 {@code max-per-window} 次截断后停止
 * 自动续跑。反复撞上限说明是系统性问题（预算过小或任务切分不当），继续续跑只会
 * 烧 token 并把会话推向「醒了又撞」的循环；此时留给停滞看护的时间/告警通道，
 * 由它按停滞与等人工语义上报。</p>
 */
@Component
public class TruncationResumeNudger {

    private static final Logger log = LoggerFactory.getLogger(TruncationResumeNudger.class);

    /** inbox hint 的来源标记，模型与前端据此识别这是截断续跑提示。 */
    static final String HINT_SOURCE = "max-iters-truncation";

    private final MessageBus messageBus;
    private final SessionOwnerService sessionOwnerService;
    private final boolean enabled;
    private final Duration wakeupDelay;
    private final int maxPerWindow;
    private final Duration window;

    /** sessionId → 当前窗口的自动续跑计数（进程内，窗口到期自动重置）。 */
    private final ConcurrentHashMap<String, NudgeWindow> windows = new ConcurrentHashMap<>();

    public TruncationResumeNudger(
            MessageBus messageBus,
            SessionOwnerService sessionOwnerService,
            @Value("${agent-platform.truncation-resume.enabled:true}") boolean enabled,
            @Value("${agent-platform.truncation-resume.wakeup-delay-seconds:5}") long wakeupDelaySeconds,
            @Value("${agent-platform.truncation-resume.max-per-window:3}") int maxPerWindow,
            @Value("${agent-platform.truncation-resume.window-minutes:30}") long windowMinutes) {
        this.messageBus = messageBus;
        this.sessionOwnerService = sessionOwnerService;
        this.enabled = enabled;
        this.wakeupDelay = Duration.ofSeconds(Math.max(0, wakeupDelaySeconds));
        this.maxPerWindow = Math.max(1, maxPerWindow);
        this.window = Duration.ofMinutes(Math.max(1, windowMinutes));
    }

    /**
     * 登记一次截断并尽力安排续跑。
     *
     * <p>关闭、会话缺归属、超出窗口上限三种情况都只留痕不投递。整条路径尽力而为：
     * 投递失败只降级 WARN，不影响触发方（框架事件流）的主流程。</p>
     *
     * @param sessionId   被截断的会话
     * @param maxIters    本轮的迭代上限
     * @param currentIter 截断时的迭代计数
     */
    public void onTruncated(String sessionId, int maxIters, int currentIter) {
        if (!enabled || sessionId == null || sessionId.isBlank()) {
            return;
        }
        int attempt = registerAttempt(sessionId);
        if (attempt > maxPerWindow) {
            log.warn("Max-iters truncation nudge: session {} hit {} truncation(s) within {} min "
                            + "(cap {}); standing down and leaving continuation to the stall watchdog",
                    sessionId, attempt, window.toMinutes(), maxPerWindow);
            return;
        }
        // 已删除会话不续跑（2026-10-13）：本方法由 run 的 doFinally 触发，
        // 而 doFinally 在 run 被 interrupt/dispose（= 删除会话的第一步）时同样执行——
        // 也就是说**删除动作本身会经由这里发一次 enqueueWakeup**。
        // 墓碑判据必须排在入队之前，否则用户刚删掉的会话会被立刻续跑一轮。
        if (sessionOwnerService.isDeletedBySession(sessionId)) {
            log.info("Max-iters truncation nudge: session {} was deleted by user, skipping resume",
                    sessionId);
            return;
        }
        String agentId = sessionOwnerService.agentBySessionId(sessionId).orElse(null);
        if (agentId == null || agentId.isBlank()) {
            log.warn("Max-iters truncation nudge: no owner agent for session {}, skipping resume",
                    sessionId);
            return;
        }
        String userId = sessionOwnerService.owner(agentId, sessionId).orElse("");
        log.info("Max-iters truncation nudge: session {} truncated at iter {}/{} "
                        + "(resume attempt {}/{} in current window), queuing hint + wakeup (agent={})",
                sessionId, currentIter, maxIters, attempt, maxPerWindow, agentId);
        messageBus.inboxPush(sessionId, hintPayload(maxIters, currentIter))
                .doOnError(e -> log.warn(
                        "Max-iters truncation nudge: inbox hint push failed for session {}: {}",
                        sessionId, e.getMessage()))
                .subscribe();
        // 延迟请求唤醒：截断发生在 run 收尾过程中，此刻会话仍被标记为 running，
        // 立即请求会被 WakeupDispatcher 跳过并丢弃唤醒条目（见类注释）。
        Mono.delay(wakeupDelay)
                .flatMap(ignored -> messageBus.enqueueWakeup(userId, sessionId, agentId))
                .doOnError(e -> log.warn(
                        "Max-iters truncation nudge: wakeup request failed for session {}: {}",
                        sessionId, e.getMessage()))
                .subscribe();
    }

    /** 窗口内自增计数；窗口到期（或首次）重置为 1。 */
    private int registerAttempt(String sessionId) {
        long now = System.currentTimeMillis();
        long windowMillis = window.toMillis();
        return windows.compute(sessionId, (key, previous) -> {
            if (previous == null || now - previous.startedAtMillis() >= windowMillis) {
                return new NudgeWindow(now, 1);
            }
            return new NudgeWindow(previous.startedAtMillis(), previous.count() + 1);
        }).count();
    }

    /** inbox hint 载荷，形状与 {@code InboxMiddleware.deserializeHintBlock} 期望一致。 */
    private static Map<String, Object> hintPayload(int maxIters, int currentIter) {
        String hint = "[" + HINT_SOURCE + "] 上一轮回复因达到单轮迭代上限（maxIters=" + maxIters
                + "，在 iter=" + currentIter + " 处被框架强制收尾）而中断，任务尚未完成。"
                + "请接着完成上一轮未做完的部分（不要从头重来），做完后正常收尾。";
        return Map.of(
                "id", HINT_SOURCE + "-" + UUID.randomUUID(),
                "hint", hint,
                "source", HINT_SOURCE);
    }

    /** 滑动窗口计数（startedAtMillis 为窗口起点，count 为窗口内已安排的续跑次数）。 */
    private record NudgeWindow(long startedAtMillis, int count) {
    }
}
