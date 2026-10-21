package com.tipdm.agentplatform.harness;

import com.tipdm.agentplatform.config.DeclarativeEndingProperties;
import com.tipdm.agentplatform.service.SessionOwnerService;
import com.tipdm.agentplatform.service.SessionTextEndingRecorder;
import io.agentscope.core.agui.adapter.strategy.AguiEventEnricher;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 纯文本收尾登记（AG-UI 事件流旁路，框架 {@link AguiEventEnricher} 扩展点）。
 *
 * <p><b>判据（精确，勿用近似特征）</b>：本轮 {@code RUN_FINISHED}（无 interrupt
 * outcome，即不是 HITL 闸门挂起）前，最后一条 {@code TEXT_MESSAGE_END} 之后没有
 * {@code TOOL_CALL_START}，且确有助手正文。不能用「发言以冒号结尾」做判据：
 * 健康轮同样以冒号结尾（其后紧跟工具调用），会大量误报。</p>
 *
 * <p><b>只观察、不决策（V61 重构）</b>：命中即调用
 * {@link SessionTextEndingRecorder#recordTextEnding} 登记时间戳，其余一概不做。
 * 早期版本在此处按文本 pattern 决定「是否自动续跑」——两个缺陷：① 判定押在
 * 措辞上，列举式/汇报式宣告追不完（会话停滞 293 分钟实证）；② run 结束瞬间
 * 只有事件流，拿不到 waitingHuman / stop_requested / 完成契约等事实，无法区分
 * 「宣告干活」与「向用户提问」。决策已收敛到停滞看护：它手里事实最全，读到
 * 「登记时间过宽限期 + 完成契约未核验 + 非等人工」即提前补醒，补醒提示词带
 * 契约核验明细，模型的两个出口（落标记 / 继续干活）都是可验证动作。</p>
 *
 * <p><b>顺带观测</b>：token_usage CUSTOM 事件里 finishReason=length ⇒ 模型输出被
 * max_tokens 截断，WARN 留痕（该事件需 {@code agentscope.agui.emit-token-usage: true}）。
 * 本组件与 {@link com.tipdm.agentplatform.config.AguiEventLogEnricher} 一样是纯旁路：
 * 原样返回事件，不做任何修改。</p>
 *
 * <p><b>唯一副作用</b>：框架明确报出 {@code ExceedMaxItersEvent}（迭代上限耗尽是
 * 客观事件，非措辞推断）时，交由 {@link TruncationResumeNudger} 安排一次续跑。
 * 这不动摇「收尾判定不进本组件」的边界——本组件仍不判断本轮算不算完成，也不判断
 * 该不该补醒，那些仍由停滞看护依据完整事实决定。</p>
 */
@Component
public class DeclarativeEndingWatchdog implements AguiEventEnricher {

    private static final Logger log = LoggerFactory.getLogger(DeclarativeEndingWatchdog.class);

    /** 单会话最后一条助手文本的累积上限（超出截断，防长回复占用内存）。 */
    private static final int MAX_TEXT_LENGTH = 4000;

    private final DeclarativeEndingProperties properties;
    private final SessionOwnerService sessionOwnerService;
    /** 独立登记器（仅依赖仓储）：直接注入 SessionProgressService 会成环。 */
    private final SessionTextEndingRecorder textEndingRecorder;
    /** 截断续跑：框架明确报出迭代上限截断时安排一次续跑（不做任何措辞推断）。 */
    private final TruncationResumeNudger truncationResumeNudger;

    /** sessionId → 本轮 run 的事件轨迹（RunStarted 重建、RunFinished/RUN_ERROR 清除）。 */
    private final ConcurrentHashMap<String, RunTrack> tracks = new ConcurrentHashMap<>();

    public DeclarativeEndingWatchdog(
            DeclarativeEndingProperties properties,
            SessionOwnerService sessionOwnerService,
            SessionTextEndingRecorder textEndingRecorder,
            TruncationResumeNudger truncationResumeNudger) {
        this.properties = properties;
        this.sessionOwnerService = sessionOwnerService;
        this.textEndingRecorder = textEndingRecorder;
        this.truncationResumeNudger = truncationResumeNudger;
        log.info("Declarative-ending watchdog armed (enabled={}, mode=observe-and-record)",
                properties.isEnabled());
    }

    @Override
    public List<AguiEvent> enrich(AgentEvent source, List<AguiEvent> events,
                                  AguiStreamContext context) {
        if (events == null || events.isEmpty()) {
            return events;
        }
        try {
            // 框架达到迭代上限时会给未执行的 tool call 补 error result 再出摘要文本
            // 收场，平台侧看到的只是一次普通文本收尾。本组件不改事件，只把「被截断」
            // 变成可查事实（服务端自发 run 见 ServerRunEventRecorder），并把该事实
            // 交给续跑组件——续跑见类注释的「唯一副作用」段。
            if (source instanceof ExceedMaxItersEvent exceed) {
                String truncatedSession = context == null ? null : context.getThreadId();
                log.warn("Run truncated by max-iters: session={} run={} maxIters={} currentIter={}",
                        truncatedSession == null ? "?" : truncatedSession, exceed.getReplyId(),
                        exceed.getMaxIters(), exceed.getCurrentIter());
                // 截断（预算耗尽）不是完成：安排一次续跑让会话接着做完。
                truncationResumeNudger.onTruncated(
                        truncatedSession, exceed.getMaxIters(), exceed.getCurrentIter());
            }
            for (AguiEvent event : events) {
                observe(event);
            }
        } catch (RuntimeException e) {
            // 检测是纯旁路：任何异常都不得影响事件输出主流程
            log.debug("Declarative-ending watchdog error: {}", e.getMessage());
        }
        return events;
    }

    /**
     * 服务端自发 run（{@link ServerSideRunLauncher} 路径）的事件旁路入口。
     *
     * <p>srv run 不经过 AG-UI adapter 的 enricher 链（{@link #enrich} 看不到），
     * 由 {@link ServerRunEventRecorder} 把合成事件转喂到这里。</p>
     */
    public void observeEvents(List<AguiEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        try {
            for (AguiEvent event : events) {
                observe(event);
            }
        } catch (RuntimeException e) {
            // 检测是纯旁路：任何异常都不得影响调用方主流程
            log.debug("Declarative-ending watchdog error (server-run path): {}", e.getMessage());
        }
    }

    private void observe(AguiEvent event) {
        if (event instanceof AguiEvent.RunStarted runStarted) {
            tracks.put(runStarted.threadId(), new RunTrack(runStarted.runId()));
            return;
        }
        if (event instanceof AguiEvent.RunError runError) {
            tracks.remove(runError.threadId());
            return;
        }

        if (event instanceof AguiEvent.Custom custom && "token_usage".equals(custom.name())) {
            warnOnTruncatedFinishReason(custom);
            return;
        }

        // AguiEvent 接口本身不暴露 threadId()，按具体事件类型取
        String threadId = null;
        if (event instanceof AguiEvent.TextMessageStart textStart) {
            threadId = textStart.threadId();
        } else if (event instanceof AguiEvent.TextMessageContent content) {
            threadId = content.threadId();
        } else if (event instanceof AguiEvent.TextMessageEnd textEnd) {
            threadId = textEnd.threadId();
        } else if (event instanceof AguiEvent.ToolCallStart toolStart) {
            threadId = toolStart.threadId();
        } else if (event instanceof AguiEvent.RunFinished runFinished) {
            threadId = runFinished.threadId();
        }
        if (threadId == null) {
            return;
        }
        RunTrack track = tracks.get(threadId);
        if (track == null) {
            return; // 框架层 run 未经历 RunStarted（如 processor 错误路径），无法评估
        }

        if (event instanceof AguiEvent.TextMessageStart textStart) {
            if (!track.runId.equals(textStart.runId())) {
                return;
            }
            synchronized (track) {
                track.text.setLength(0);
                track.textEnded = false;
                track.toolCallAfterText = false;
            }
        } else if (event instanceof AguiEvent.TextMessageContent content) {
            synchronized (track) {
                if (track.text.length() < MAX_TEXT_LENGTH && content.delta() != null) {
                    track.text.append(content.delta());
                    if (track.text.length() > MAX_TEXT_LENGTH) {
                        track.text.setLength(MAX_TEXT_LENGTH);
                    }
                }
            }
        } else if (event instanceof AguiEvent.TextMessageEnd) {
            synchronized (track) {
                track.textEnded = true;
            }
        } else if (event instanceof AguiEvent.ToolCallStart) {
            synchronized (track) {
                track.toolCallAfterText = true;
            }
        } else if (event instanceof AguiEvent.RunFinished runFinished) {
            tracks.remove(threadId);
            if (!track.runId.equals(runFinished.runId())) {
                return;
            }
            synchronized (track) {
                record(threadId, track, runFinished.outcome() != null);
            }
        }
    }

    /** run 正常收场（无 interrupt）且以纯文本结束 ⇒ 登记事实，决策交给停滞看护。 */
    private void record(String sessionId, RunTrack track, boolean interrupted) {
        if (interrupted || !track.textEnded || track.toolCallAfterText) {
            return;
        }
        String text = track.text.toString().strip();
        if (text.isEmpty() || !properties.isEnabled()) {
            return;
        }
        log.info("Pure-text run ending registered: session {} (text head: {}) — "
                + "disposal decided by progress watchdog", sessionId, abbreviate(text));
        String agentId = sessionOwnerService.agentBySessionId(sessionId).orElse(null);
        if (agentId == null || agentId.isBlank()) {
            log.debug("Pure-text ending: no owner agent for session {}, skip recording", sessionId);
            return;
        }
        String userId = sessionOwnerService.owner(agentId, sessionId).orElse("");
        textEndingRecorder.recordTextEnding(agentId, sessionId, userId);
    }

    /** token_usage 事件里 finishReason=length ⇒ 输出被 max_tokens 截断。 */
    private void warnOnTruncatedFinishReason(AguiEvent.Custom custom) {
        Object value = custom.value();
        if (!(value instanceof Map<?, ?> map)) {
            return;
        }
        Object modelCall = map.get("modelCall");
        if (!(modelCall instanceof Map<?, ?> mc)) {
            return;
        }
        if ("length".equals(mc.get("finishReason"))) {
            log.warn("Model output truncated by max_tokens (finishReason=length): "
                    + "session={} run={}", custom.threadId(), custom.runId());
        }
    }

    private static String abbreviate(String text) {
        String stripped = text.strip();
        return stripped.length() <= 60 ? stripped : stripped.substring(0, 60) + "…";
    }

    /** 本轮 run 的轨迹状态（仅 RunStarted→RunFinished 生命周期内存在）。 */
    private static final class RunTrack {
        private final String runId;
        private final StringBuilder text = new StringBuilder();
        private boolean textEnded = false;
        private boolean toolCallAfterText = false;

        RunTrack(String runId) {
            this.runId = runId;
        }
    }
}
