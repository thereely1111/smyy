package com.tipdm.agentplatform.harness;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.function.Function;
import java.util.function.Supplier;
import com.tipdm.agentplatform.service.PathDisclosureSanitizer;

/**
 * 在所有智能体上注册的追踪中间件：把"会话当前有 run 在跑"登记进
 * {@link SessionActivityTracker}，并把 sessionId → agentId 写入 {@link SessionAgentRegistry}。
 *
 * <p>覆盖 AG-UI 的 {@code streamEvents}、平台 chat 的 {@code call}，以及 Wakeup 自身的
 * 空输入 run——所有路径都经过 onAgent 链。会话结束（完成/异常/取消）统一释放计数。</p>
 *
 * <p><b>为什么还要相位级打点</b>：{@code onAgent} 链上的逐事件 touch 对子智能体 run 不成立
 * ——框架把子会话的细粒度事件转发到父会话的流，子会话自己的链只收到起止事件，时钟因此冻在
 * run 起跑时刻。详见 {@link #touchOnPhaseEntry}。</p>
 */
public class SessionActivityMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SessionActivityMiddleware.class);

    private final SessionActivityTracker tracker;
    private final SessionAgentRegistry registry;
    private final SessionEventClock eventClock;

    public SessionActivityMiddleware(
            SessionActivityTracker tracker, SessionAgentRegistry registry,
            SessionEventClock eventClock) {
        this.tracker = tracker;
        this.registry = registry;
        this.eventClock = eventClock;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        String sessionId = ctx != null ? ctx.getSessionId() : null;
        if (sessionId == null || sessionId.isBlank()) {
            return next.apply(input);
        }
        if (agent != null) {
            registry.register(agent.getAgentId(), sessionId);
        }
        return Flux.defer(
                () -> {
                    tracker.begin(sessionId);
                    // run 起跑即登记时钟：否则「一个事件都还没产出就卡住」的 run
                    // 不在探测器快照里，既无告警也无自动恢复（见 SessionEventClock）。
                    eventClock.begin(sessionId);
                    return next.apply(input)
                            .onErrorResume(error -> recoverStaleHitl(agent, ctx, input, error)
                                    ? Flux.defer(() -> next.apply(input))
                                    : Flux.error(error))
                            .map(SessionActivityMiddleware::sanitizeEvent)
                            // 逐事件 touch：供 RunSilenceDetector 判定「run 活跃但
                            // 长时间无事件」（LLM 流停滞的平台侧可观测信号）。
                            .doOnNext(event -> eventClock.touch(sessionId))
                            .doFinally(sig -> {
                                // end 按引用计数：并发 run 中先结束的一方不再擦掉
                                // 仍在跑那方的时钟
                                eventClock.end(sessionId);
                                tracker.end(sessionId);
                            });
                });
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        return touchOnPhaseEntry(ctx, () -> next.apply(input));
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return touchOnPhaseEntry(ctx, () -> next.apply(input));
    }

    /**
     * 相位级活性打点：进入 reasoning / acting 相位即 touch 一次，相位内每条事件再 touch。
     *
     * <p><b>为什么 {@link #onAgent} 的逐事件 touch 不够</b>：子智能体 run 的细粒度事件被
     * 框架转发到父会话的流（{@code AgentSpawnTool} 的 forwarding emitter 路径），子会话自身
     * 的 onAgent 链只收到 {@code AgentStartEvent}/{@code AgentResultEvent} ⇒ 子会话时钟冻在
     * run 起跑时刻、「静默量」恒等于 run 已跑时长 ⇒ 20 分钟后必被 {@link RunSilenceDetector}
     * 判成上游流停滞。实测该子会话在被告警的同一分钟仍在写产物，属确定性误报。</p>
     *
     * <p>相位钩子走的是独立于 emitter 绑定的另一条链，进入相位即触发，不依赖事件能否流回
     * 本会话，因此对子智能体同样生效。覆盖不到的是「单个工具调用长时间执行」那一段——那段
     * 本就没有相位边界，是框架工具心跳要解决的问题。</p>
     *
     * <p>{@link SessionEventClock#touch} 不新建条目，对不在本进程跑的会话是无害空操作。</p>
     */
    private Flux<AgentEvent> touchOnPhaseEntry(
            RuntimeContext ctx, Supplier<Flux<AgentEvent>> next) {
        String sessionId = ctx != null ? ctx.getSessionId() : null;
        if (sessionId == null || sessionId.isBlank()) {
            return next.get();
        }
        return Flux.defer(() -> {
            eventClock.touch(sessionId);
            return next.get().doOnNext(event -> eventClock.touch(sessionId));
        });
    }

    private static AgentEvent sanitizeEvent(AgentEvent event) {
        if (event instanceof AgentResultEvent result) {
            return copyMetadata(new AgentResultEvent(result.getId(), result.getSource(),
                    PathDisclosureSanitizer.sanitize(result.getResult())), result);
        }
        if (event instanceof TextBlockDeltaEvent delta) {
            return copyMetadata(new TextBlockDeltaEvent(delta.getId(), delta.getSource(),
                    delta.getReplyId(), delta.getBlockId(), PathDisclosureSanitizer.sanitize(delta.getDelta())), delta);
        }
        if (event instanceof ToolResultTextDeltaEvent delta) {
            return copyMetadata(new ToolResultTextDeltaEvent(delta.getId(), delta.getSource(),
                    delta.getReplyId(), delta.getToolCallId(), delta.getToolCallName(),
                    PathDisclosureSanitizer.sanitize(delta.getDelta())), delta);
        }
        return event;
    }

    private static AgentEvent copyMetadata(AgentEvent replacement, AgentEvent original) {
        if (original.getMetadata() != null) {
            replacement = replacement.withMetadata(original.getMetadata());
        }
        return replacement;
    }

    /**
     * AG-UI resume state is process-local while the AgentState is persistent.
     * After a restart those two can disagree and AgentScope rejects the next
     * run as a stale ASKING confirmation.  A normal user message can safely
     * start from a clean state; a confirmation cannot, because replaying it
     * could approve a different tool call.
     */
    private boolean recoverStaleHitl(Agent agent, RuntimeContext ctx, AgentInput input, Throwable error) {
        if (!isStaleHitl(error) || hasConfirmResults(input)) {
            return false;
        }
        // 中间件链以「内层 delegate ReActAgent」为 agent 构建（HarnessAgent 只是外层包装），
        // 按 HarnessAgent 判型永远不成立；而 HarnessAgent 的 clearContext/clearStateCache
        // 只是转调 delegate，故直接对内层实例操作，行为等价。
        if (!(agent instanceof io.agentscope.core.ReActAgent react)) {
            return false;
        }
        String userId = ctx.getUserId();
        String sessionId = ctx.getSessionId();
        if (userId == null || userId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        // 这条路径是破坏性的：清掉整个会话上下文与持久状态（转录、任务清单等），
        // 且原实现成功时一条日志都没有——出问题只能看到"历史凭空消失"。因此在动手前
        // 记全上下文（含原始 error 与原因链），失败也留痕。
        String reason = rootMessage(error);
        log.warn("Stale HITL detected: clearing context and persisted state for "
                        + "session={} user={} — original error: {} "
                        + "(process-local resume state disagreed with persisted AgentState; "
                        + "a normal user message can restart from clean state, a confirmation cannot)",
                sessionId, userId, reason);
        try {
            react.clearContext(userId, sessionId);
            react.getStateStore().delete(userId, sessionId);
            react.clearStateCache(userId, sessionId);
            log.warn("Stale HITL recovered: session={} user={} state cleared, retrying the run",
                    sessionId, userId);
            return true;
        } catch (RuntimeException recoveryError) {
            // Preserve the original failure; recovery is best-effort.
            log.warn("Stale HITL recovery failed: session={} user={} recoveryError={} (original: {})",
                    sessionId, userId, rootMessage(recoveryError), reason);
            return false;
        }
    }

    /** 取原因链上最后一段非空 message，供日志说明"为什么判定为陈旧 HITL"。 */
    private static String rootMessage(Throwable error) {
        String message = null;
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                message = current.getMessage();
            }
        }
        return message == null ? String.valueOf(error) : message;
    }

    private static boolean hasConfirmResults(AgentInput input) {
        return input.msgs().stream().anyMatch(msg ->
                msg.getMetadata() != null && msg.getMetadata().containsKey(Msg.METADATA_CONFIRM_RESULTS));
    }

    private static boolean isStaleHitl(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && (message.contains("Agent is paused for human-in-the-loop confirmation")
                    || message.contains("does not match any open interrupt")
                    || message.contains("ASKING state"))) {
                return true;
            }
        }
        return false;
    }
}
