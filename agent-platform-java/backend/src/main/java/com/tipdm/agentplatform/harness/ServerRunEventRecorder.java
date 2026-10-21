package com.tipdm.agentplatform.harness;

import com.tipdm.agentplatform.service.AguiEventPersistenceService;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 服务端自发 run 的事件落库桥（3.1）。
 *
 * <p><b>缺口</b>：AG-UI 请求路径的事件经 AguiAgentAdapter 转换、由
 * {@link com.tipdm.agentplatform.config.AguiEventLogEnricher} 落库；而
 * {@link ServerSideRunLauncher} 发起的 run（wakeup 补醒、停滞自检等）不经过
 * adapter，事件只落在框架 jsonl——事件库/前端水位线完全看不到后台在干什么，
 * 「后台跑了几分钟、产出什么」无法观测。</p>
 *
 * <p><b>方案</b>：把框架 {@link AgentEvent} 流合成为 AG-UI 协议事件，复用
 * {@link AguiEventPersistenceService#capture} 落库——RUN_STARTED（run 开始）、
 * REASONING_MESSAGE_*（思考流）、TEXT_MESSAGE_START/CONTENT/END（文本块流）、
 * TOOL_CALL_START/ARGS/END + TOOL_CALL_RESULT（工具调用与结果，3.5 watchdog 的
 * 「文本后无工具调用」判据在 srv 路径依赖它）、RUN_FINISHED / RUN_ERROR（doFinally
 * 终态）。事件集与 adapter 路径（{@code ToolCallEventConverter} /
 * {@code ToolResultEventConverter} / {@code ThinkingBlockEventConverter}）对齐：
 * 只落 START 不落 ARGS/RESULT 的工具调用在前端永远停在 loading，且 todo_write
 * 这类「内容在入参里」的调用渲染不出正文——页面必须刷新才能从转录侧补齐。
 * runId 用 {@code srv-<uuid>} 前缀与 AG-UI 路径 run 区分。事件序号由落库服务按
 * 会话统一分配，与既有事件连续。</p>
 *
 * <p>合成事件同时回喂观察者（{@code DeclarativeEndingWatchdog::observeEvents}，
 * 3.5 与 3.1 的集成点），使宣告式收尾检测覆盖 srv 路径。</p>
 */
final class ServerRunEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(ServerRunEventRecorder.class);

    private final AguiEventPersistenceService eventLog;
    private final java.util.function.Consumer<AguiEvent> observer;
    private final String sessionId;
    private final String runId;
    private final AtomicBoolean textOpen = new AtomicBoolean(false);
    private volatile String openMessageId;
    private final AtomicBoolean thinkingOpen = new AtomicBoolean(false);
    private volatile String openThinkingId;
    private volatile String lastError;
    /** 本 run 因迭代上限被框架强制收尾的事实；未截断时为 null。 */
    private volatile Truncation truncation;

    ServerRunEventRecorder(AguiEventPersistenceService eventLog, String sessionId,
            java.util.function.Consumer<AguiEvent> observer) {
        this.eventLog = eventLog;
        this.observer = observer;
        this.sessionId = sessionId;
        this.runId = "srv-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** run 开始：登记 RUN_STARTED（失败仅 WARN，不影响 run）。 */
    void recordStart() {
        capture(new AguiEvent.RunStarted(sessionId, runId));
    }

    /** 逐事件桥接：把文本块事件合成为 AG-UI TEXT_MESSAGE_* 序列。 */
    void onAgentEvent(AgentEvent event) {
        // 子智能体转发事件（source 非空）不进父会话事件库：父会话转录不收录子
        // 内容，这里照父事件映射会让事件重放渲染出转录侧永远对不上的正文/工具
        // 气泡。adapter 路径（AG-UI 请求）由框架 SubagentEventConverter 把子事件
        // 转成 subagent.* CUSTOM，天然无此问题；srv 路径在此对齐。
        if (event.getSource() != null) {
            return;
        }
        if (event instanceof TextBlockStartEvent blockStart) {
            if (textOpen.compareAndSet(false, true)) {
                String messageId = blockStart.getReplyId() + ":" + blockStart.getBlockId();
                openMessageId = messageId;
                capture(new AguiEvent.TextMessageStart(
                        sessionId, runId, messageId, "assistant", null, null));
            }
        } else if (event instanceof TextBlockDeltaEvent delta) {
            String messageId = delta.getReplyId() + ":" + delta.getBlockId();
            String text = delta.getDelta() == null ? "" : delta.getDelta();
            capture(new AguiEvent.TextMessageContent(
                    sessionId, runId, messageId, text, null, null));
        } else if (event instanceof TextBlockEndEvent blockEnd) {
            if (textOpen.compareAndSet(true, false)) {
                capture(new AguiEvent.TextMessageEnd(
                        sessionId, runId, blockEnd.getReplyId() + ":" + blockEnd.getBlockId(),
                        null, null));
            }
        } else if (event instanceof io.agentscope.core.event.ToolCallStartEvent toolStart) {
            capture(new AguiEvent.ToolCallStart(
                    sessionId, runId, toolStart.getToolCallId(),
                    toolStart.getToolCallName(), null, null));
        } else if (event instanceof io.agentscope.core.event.ToolCallDeltaEvent toolArgs) {
            // 工具入参流：缺了它前端拿不到 input，工具面板只剩一个空壳标题，
            // todo_write 这类「结果在入参里」的调用连清单内容都渲染不出。
            capture(new AguiEvent.ToolCallArgs(
                    sessionId, runId, toolArgs.getToolCallId(),
                    toolArgs.getDelta() == null ? "" : toolArgs.getDelta(), null, null));
        } else if (event instanceof io.agentscope.core.event.ToolResultTextDeltaEvent resultDelta) {
            capture(new AguiEvent.ToolCallResult(
                    sessionId, runId, resultDelta.getToolCallId(),
                    resultDelta.getDelta() == null ? "" : resultDelta.getDelta(),
                    "tool", null, null, null));
        } else if (event instanceof io.agentscope.core.event.ToolCallEndEvent toolEnd) {
            capture(new AguiEvent.ToolCallEnd(
                    sessionId, runId, toolEnd.getToolCallId(), null, null));
        } else if (event instanceof io.agentscope.core.event.ThinkingBlockDeltaEvent thinkDelta) {
            // 思考流：与文本块同构（replyId + blockId）。前端按 messageId 归组，
            // 这里沿用同一命名，跨通道（事件/转录）的思考块 id 才对得上。
            String messageId = thinkDelta.getReplyId() + ":" + thinkDelta.getBlockId();
            if (thinkingOpen.compareAndSet(false, true)) {
                openThinkingId = messageId;
                capture(new AguiEvent.ReasoningMessageStart(
                        sessionId, runId, messageId, "assistant", null, null));
            }
            capture(new AguiEvent.ReasoningMessageContent(
                    sessionId, runId, messageId,
                    thinkDelta.getDelta() == null ? "" : thinkDelta.getDelta(), null, null));
        } else if (event instanceof io.agentscope.core.event.ThinkingBlockEndEvent thinkEnd) {
            if (thinkingOpen.compareAndSet(true, false)) {
                capture(new AguiEvent.ReasoningMessageEnd(
                        sessionId, runId,
                        openThinkingId == null
                                ? thinkEnd.getReplyId() + ":" + thinkEnd.getBlockId()
                                : openThinkingId,
                        null, null));
            }
        } else if (event instanceof io.agentscope.core.event.ExceedMaxItersEvent exceed) {
            // 框架达到迭代上限时会给未执行的 tool call 补 error result 再出摘要文本
            // 收场，平台侧看到的只是一次普通文本收尾。此处 WARN + 落一条 RAW 事件，
            // 把「被截断」变成可查事实（纯旁路，不改事件语义）；截断事实另暴露给
            // 调用方，由它决定是否安排续跑。
            closeText();
            this.truncation = new Truncation(exceed.getMaxIters(), exceed.getCurrentIter());
            log.warn("Run truncated by max-iters (server-side run): session={} run={} "
                            + "maxIters={} currentIter={}",
                    sessionId, runId, exceed.getMaxIters(), exceed.getCurrentIter());
            capture(new AguiEvent.Raw(sessionId, runId, exceed, "SERVER_RUN", null, null));
        }
    }

    /** run 正常收场：RUN_FINISHED。 */
    void recordFinish() {
        closeText();
        capture(new AguiEvent.RunFinished(sessionId, runId, null, null, null, null));
    }

    /**
     * run 异常收场：RUN_ERROR。
     *
     * <p>框架 {@code AguiEvent.RunError} 要求 message 非空，而异常没有 message 是常态
     * （无消息 NPE 等），故回落到异常类名；记录过程整体兜底，失败只降级 WARN，原始
     * 异常另行带栈落 WARN。</p>
     */
    void recordError(Throwable error) {
        closeText();
        this.lastError = describe(error);
        try {
            capture(new AguiEvent.RunError(sessionId, runId, lastError, "SERVER_RUN", null, null));
        } catch (RuntimeException e) {
            log.warn("Failed to record RUN_ERROR (session={}, run={}); original error was: {}",
                    sessionId, runId, lastError, e);
        }
        if (error != null) {
            log.warn("Server-side run failed: session={} run={}", sessionId, runId, error);
        }
    }

    /** RUN_ERROR 的消息必须非空：异常本身没有 message 时回落到异常类名。 */
    private static String describe(Throwable error) {
        if (error == null) {
            return "unknown";
        }
        String message = error.getMessage();
        return (message != null && !message.isBlank()) ? message : error.getClass().getName();
    }

    /** 本 run 的截断事实；未被截断时返回 null。 */
    Truncation truncation() {
        return truncation;
    }

    /** 截断事实：本轮的迭代上限与截断时的迭代计数。 */
    record Truncation(int maxIters, int currentIter) {
    }

    private void closeText() {
        if (textOpen.compareAndSet(true, false)) {
            capture(new AguiEvent.TextMessageEnd(
                    sessionId, runId,
                    openMessageId == null ? runId : openMessageId, null, null));
        }
        if (thinkingOpen.compareAndSet(true, false)) {
            capture(new AguiEvent.ReasoningMessageEnd(
                    sessionId, runId,
                    openThinkingId == null ? runId : openThinkingId, null, null));
        }
    }

    private void capture(AguiEvent event) {
        try {
            eventLog.capture(event);
        } catch (RuntimeException e) {
            log.warn("Server run event capture failed (session={} run={}): {}",
                    sessionId, runId, e.getMessage());
        }
        java.util.function.Consumer<AguiEvent> obs = observer;
        if (obs != null) {
            try {
                obs.accept(event);
            } catch (RuntimeException e) {
                log.debug("Server run event observer failed (session={}): {}", sessionId, e.getMessage());
            }
        }
    }
}
