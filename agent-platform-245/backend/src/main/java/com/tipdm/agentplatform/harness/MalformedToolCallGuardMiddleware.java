package com.tipdm.agentplatform.harness;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 畸形 tool_call 守卫：把没有工具名的调用改写为哨兵名，降级成一次正常的
 * 「Tool not found」工具结果。
 *
 * <p>无名调用有两重危害，分别由两个挂点覆盖：</p>
 * <ul>
 *   <li><b>派发前（{@code onActing}）</b>：框架 trace 中间件会把它写进
 *       {@code ConcurrentHashMap}（拒 null 值）而抛出无消息 NPE，整个 run 当场死掉；
 *       若直接丢弃，上下文里会留下永远没有结果的悬挂调用。故保留 id/入参/内容/状态，
 *       只替换工具名。</li>
 *   <li><b>发给模型前（{@code onReasoning}）</b>：畸形调用一旦落进持久化上下文，
 *       此后每轮请求都带着一个没有 name 的 tool_call，模型侧直接以 400 拒绝整轮输入。
 *       历史里的块回改不了，只能在出站消息中补齐。</li>
 * </ul>
 *
 * <p>{@code order()=3}，在框架与平台其它中间件之前完成改写；哨兵名不注册到任何
 * toolkit，框架查不到即走 {@code ToolResultBlock.error("Tool not found: …")} 兜底
 * 分支。守卫自身异常一律降级 WARN 并放行原输入。</p>
 */
public class MalformedToolCallGuardMiddleware implements MiddlewareBase {

    private static final Logger log =
            LoggerFactory.getLogger(MalformedToolCallGuardMiddleware.class);

    /** 无工具名调用的哨兵名：名字本身即说明原因，且故意不注册到任何 toolkit。 */
    static final String UNNAMED_TOOL_SENTINEL = "INVALID_TOOL_CALL_missing_name";

    @Override
    public int order() {
        return 3;
    }

    /**
     * 发给模型前，把历史里没有工具名的 tool_use 补齐为哨兵名。
     *
     * <p>模型侧对 tool_call 的 {@code name} 字段是强校验（缺了就整轮 400），而畸形块
     * 已经落在持久化上下文里、只拦 acting 救不回来，故必须在出站消息上补。</p>
     */
    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        ReasoningInput sanitized;
        try {
            sanitized = sanitize(ctx, input);
        } catch (RuntimeException e) {
            log.warn("Malformed tool_call guard: outbound repair skipped", e);
            sanitized = null;
        }
        return next.apply(sanitized == null ? input : sanitized);
    }

    /**
     * 出站消息消毒：逐条重建含无名 tool_use 的消息（保留 id/name/role/元数据/时间戳/用量）。
     *
     * @return 消毒后的输入；无需消毒时返回 {@code null}，调用方沿用原 input
     */
    private static ReasoningInput sanitize(RuntimeContext ctx, ReasoningInput input) {
        if (input == null || input.messages() == null || input.messages().isEmpty()) {
            return null;
        }
        List<Msg> messages = input.messages();
        List<Msg> fixed = null;
        for (int i = 0; i < messages.size(); i++) {
            Msg message = messages.get(i);
            if (message == null || message.getContent() == null || message.getContent().isEmpty()) {
                continue;
            }
            List<ContentBlock> blocks = message.getContent();
            List<ContentBlock> repaired = null;
            for (int j = 0; j < blocks.size(); j++) {
                if (blocks.get(j) instanceof ToolUseBlock call && !isNamed(call)) {
                    if (repaired == null) {
                        repaired = new ArrayList<>(blocks);
                    }
                    repaired.set(j, rewrite(call));
                    log.warn("Malformed tool_call: missing tool name in outbound messages "
                                    + "(session={}, id={}); rewritten to '{}'",
                            ctx == null ? null : ctx.getSessionId(),
                            call.getId(), UNNAMED_TOOL_SENTINEL);
                }
            }
            if (repaired != null) {
                if (fixed == null) {
                    fixed = new ArrayList<>(messages);
                }
                fixed.set(i, message.withContent(repaired));
            }
        }
        return fixed == null ? null : new ReasoningInput(fixed, input.tools(), input.options());
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> repaired;
        try {
            repaired = repair(ctx, input);
        } catch (RuntimeException e) {
            log.warn("Malformed tool_call guard: repair skipped", e);
            repaired = null;
        }
        return next.apply(repaired == null ? input : new ActingInput(repaired));
    }

    /**
     * 把 name 为 null/空白的 tool_call 改写成哨兵调用。
     *
     * @return 改写后的列表；无需改写时返回 {@code null}，调用方沿用原 input
     */
    private static List<ToolUseBlock> repair(RuntimeContext ctx, ActingInput input) {
        if (input == null || input.toolCalls() == null || input.toolCalls().isEmpty()) {
            return null;
        }
        List<ToolUseBlock> calls = input.toolCalls();
        List<ToolUseBlock> fixed = null;
        for (int i = 0; i < calls.size(); i++) {
            ToolUseBlock call = calls.get(i);
            if (isNamed(call)) {
                continue;
            }
            if (fixed == null) {
                fixed = new ArrayList<>(calls);
            }
            fixed.set(i, rewrite(call));
            log.warn("Malformed tool_call: missing tool name (session={}, id={}); rewritten to '{}'",
                    ctx == null ? null : ctx.getSessionId(),
                    call == null ? null : call.getId(),
                    UNNAMED_TOOL_SENTINEL);
        }
        return fixed;
    }

    /** 工具名可用（非 null、非空白）视为正常调用。 */
    private static boolean isNamed(ToolUseBlock call) {
        return call != null && call.getName() != null && !call.getName().isBlank();
    }

    /** 保留 id/入参/内容/元数据/状态，只替换工具名。 */
    private static ToolUseBlock rewrite(ToolUseBlock call) {
        if (call == null) {
            return new ToolUseBlock(syntheticId(), UNNAMED_TOOL_SENTINEL, Map.of());
        }
        String id = call.getId() != null ? call.getId() : syntheticId();
        Map<String, Object> input = call.getInput() != null ? call.getInput() : Map.of();
        return new ToolUseBlock(
                id, UNNAMED_TOOL_SENTINEL, input, call.getContent(), call.getMetadata(),
                call.getState());
    }

    private static String syntheticId() {
        return "call_repaired_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
