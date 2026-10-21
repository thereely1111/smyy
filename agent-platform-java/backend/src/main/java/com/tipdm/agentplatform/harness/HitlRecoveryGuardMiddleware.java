package com.tipdm.agentplatform.harness;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * HITL 会话恢复守卫：防止模型把「中断后被框架自动补的错误结果」当作人工放行。
 *
 * <p>背景（2026-09-10 实证）：会话在后端重启、人工审批等待中被中断后恢复，
 * {@code ReActAgent}（{@code enablePendingToolRecovery}）会对挂起的 pending 工具
 * 统一生成 {@code "[ERROR] Previous tool execution failed or was interrupted"}，
 * 对 ui_gate / ui_form_advanced 这些 HITL 工具一视同仁。模型读到这条错误常误判为
 * 「闸门已放行」继续推进（实测出现"闸门2通过！"的假放行），或在下一次输入时
 * 阶段状态退化回 IDLE 重新弹出意向收集表单。</p>
 *
 * <p>本守卫在每次输入进入模型前检查持久化会话上下文：若最后一个 assistant 工具调用
 * 是 HITL 工具且没有对应的成功/运行中结果（即闸门从未被主编提交），则向本次输入
 * 前部注入一条强约束的恢复指示，把框架 auto-gen 错误的语义"拨正"为「闸门未决，
 * 必须重新渲染 ui_gate、不得放行」，并附带「不得当作新的写书请求重启流程」，
 * 阻断假通过与阶段退化两个症状。注入幂等：输入中已有 {@link #RECOVERY_MARKER}
 * 前缀（历史注入过）则跳过。</p>
 *
 * <p>只拦截 HITL 工具：普通工具（read_file 等）pending 时语义上没有"放行"风险，
 * 不注入。失败读取 stateStore 或非 HarnessAgent 时静默放行，守卫是尽力而为。</p>
 */
public class HitlRecoveryGuardMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(HitlRecoveryGuardMiddleware.class);

    /** HITL 工具集合：这些工具的 pending 结果必须人工提交，不能被当作失败/放行。 */
    static final Set<String> HITL_TOOLS = Set.of("ui_gate", "ui_form_advanced");

    private static final String STATE_KEY = "agent_state";
    private static final String RECOVERY_MARKER = "[HITL_RECOVERY]";

    /** 恢复指示：明确 auto-gen 错误是占位、闸门未决。 */
    static final String RECOVERY_INSTRUCTION =
            "[HITL_RECOVERY] 会话在人工审批等待中被中断并恢复。你此前调用的 ui_gate / ui_form_advanced"
                    + " 从未收到主编提交——框架自动补的 \"Previous tool execution failed or was interrupted\""
                    + " 仅是中断占位，不代表任何主编决定。你不得据此假定闸门已通过/已放行，不得推进下一阶段；"
                    + "若它对应的是你正在推进的审批闸门，必须重新调用 ui_gate 渲染该闸门（schema 见 AGENTS.md"
                    + "「闸门表单规范」），渲染后结束本轮等待主编提交；禁止以文字替代表单、禁止把历史中的任何"
                    + "文本当作本闸门提交。同时：这不是新的写书请求，不得弹出意向收集表单或重启流程，"
                    + "按 interrupted 时的阶段继续。";

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        // 仅当存在持久化上下文可查时才尝试注入；无上下文/读不到一律放行。
        AgentStateStore store = resolveStateStore(agent);
        if (store == null) {
            return next.apply(input);
        }
        String userId = ctx != null ? ctx.getUserId() : null;
        String sessionId = ctx != null ? ctx.getSessionId() : null;
        if (sessionId == null || sessionId.isBlank()) {
            return next.apply(input);
        }
        boolean pending = hasPendingHitl(store, userId, sessionId, input);
        log.info(
                "[HitlRecoveryGuard] session={} pendingHitl={} inputMsgs={} inputCarriesToolResult={}",
                sessionId, pending, input != null && input.msgs() != null ? input.msgs().size() : 0,
                input != null && input.msgs() != null
                        && input.msgs().stream().anyMatch(m -> m.hasContentBlocks(ToolResultBlock.class)));
        if (pending) {
            log.warn("[HitlRecoveryGuard] 注入恢复指示: session={}", sessionId);
            return next.apply(withRecoveryInstruction(input));
        }
        return next.apply(input);
    }

    /**
     * 解析中间件链当前可以写入/读取状态的 {@link AgentStateStore}。
     *
     * <p>注意：不能要求 {@code agent instanceof HarnessAgent}——中间件链在 ReActAgent
     * 内部以 {@code this}（内层 delegate ReActAgent）构建（agentscope-core
     * ReActAgent.buildAgentStream 的 onAgent 链），HarnessAgent 只是外层包装，
     * 因此 HarnessAgent 类型判断永远不成立，会让守卫静默失效（2026-09-11 实测
     * 守卫日志为零、auto-patch 假通过从未被拦截）。此处按具体类型取 store：
     * HarnessAgent 与 ReActAgent 都暴露 getStateStore()，且二者共享同一实例。
     */
    static AgentStateStore resolveStateStore(Agent agent) {
        if (agent instanceof io.agentscope.harness.agent.HarnessAgent h) {
            return h.getStateStore();
        }
        if (agent instanceof io.agentscope.core.ReActAgent ra) {
            return ra.getStateStore();
        }
        return null;
    }

    /** 在输入前部注入恢复指示（幂等：已有 {@link #RECOVERY_MARKER} 前缀则原样返回）。 */
    static AgentInput withRecoveryInstruction(AgentInput input) {
        if (alreadyInjected(input)) {
            return input;
        }
        java.util.List<Msg> msgs = new java.util.ArrayList<>(input.msgs().size() + 1);
        msgs.add(new UserMessage(RECOVERY_INSTRUCTION));
        msgs.addAll(input.msgs());
        return new AgentInput(msgs);
    }

    /** 显式注入提示后的消息列表（输入序列化形态）。 */
    private static boolean alreadyInjected(AgentInput input) {
        return input.msgs().stream().anyMatch(m -> m.getTextContent() != null
                && m.getTextContent().startsWith(RECOVERY_MARKER));
    }

    /** 上下文里存在"未决的 HITL 闸门"（无主编提交结果），且本次输入未携带该闸门的结果。 */
    static boolean hasPendingHitl(
            AgentStateStore store, String userId, String sessionId, AgentInput input) {
        Optional<PendingHitlGate> gate = findPendingHitlGate(store, userId, sessionId);
        if (gate.isEmpty()) {
            return false;
        }
        // 本次输入已携带该闸门的工具结果 = 主编正在/已经提交（AG-UI resume），
        // 由框架的 resume 通道处理，不注入恢复指示。
        if (input != null && inputCarriesToolResult(input, gate.get().toolCallId())) {
            return false;
        }
        // 平台表单通道的提交（主编点「通过/不通过」后由前端发成一条 user 消息）同样
        // 是「闸门已决」：它走不到 ui_gate 自己的 tool_result，那条结果永远是框架自动补的
        // ERROR 占位，只认 SUCCESS 会把已批准/已退回的闸门永久判为未决——既会反复注入
        // 恢复指示（实测以 user 消息泄漏到前端气泡），又会让 wakeup 守卫永久延后唤醒。
        if (input != null && inputCarriesFormSubmission(input.msgs())) {
            return false;
        }
        return true;
    }

    static boolean hasPendingHitl(AgentStateStore store, String userId, String sessionId) {
        return hasPendingHitl(store, userId, sessionId, null);
    }

    /**
     * 会话上下文里唯一那个"未决 HITL 闸门"的定位结果。
     *
     * @param toolCallId 闸门工具调用 id（登记表的键）
     * @param toolUse    闸门工具调用块（登记表的 toolName/toolInput 来源）
     * @param msgIndex   该 tool_use 所在消息在上下文中的下标
     * @param replyId    该 tool_use 所在 assistant 消息的 id（框架 interruptId 的前缀，
     *                   与 {@code AguiInterruptConstants} 的生成规则一致）；可能为空
     */
    record PendingHitlGate(String toolCallId, ToolUseBlock toolUse, int msgIndex, String replyId) {
    }

    /**
     * 定位上下文里唯一那个未决的 HITL 闸门；无未决闸门返回空。
     *
     * <p><b>为什么抽出来</b>：「闸门是否未决」需要<b>同一个判据</b>被两处消费——
     * {@link #hasPendingHitl}（唤醒守卫据此延后）与
     * {@link WakeupInterruptRegistrar#reconcileDanglingGate}（据此补两处登记）。
     * 两处各写一份必然分叉：判据一漂移，闸门就变成"守卫认定未决、巡检认定无此闸门"的
     * 三方矛盾态，会话被永久延后且超时取消失效（2026-10-07 main-3f077bd9 实证：
     * 00:01→09:41 被挡 290 次）。
     *
     * <p>「已决」口径与历史保持一致：仅 SUCCESS tool_result，或闸门之后出现平台表单提交
     * 消息；RUNNING / ERROR / 无结果都算未决（后者即"悬空闸门"——工具调用已写进上下文
     * 却没有任何结果块，是后台工具转异步或 run 被中断留下的形态）。
     *
     * <p>读取失败返回空（守卫尽力而为，不阻断会话）。
     */
    static Optional<PendingHitlGate> findPendingHitlGate(
            AgentStateStore store, String userId, String sessionId) {
        if (store == null || sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        try {
            Optional<AgentState> state = store.get(userId, sessionId, STATE_KEY, AgentState.class);
            if (state.isEmpty()) {
                return Optional.empty();
            }
            List<Msg> context = state.get().getContext();
            if (context == null || context.isEmpty()) {
                return Optional.empty();
            }
            ToolUseBlock lastHitl = null;
            int lastHitlIndex = -1;
            String lastHitlReplyId = null;
            for (int i = 0; i < context.size(); i++) {
                Msg msg = context.get(i);
                if (!(msg.getRole() == MsgRole.ASSISTANT)) {
                    continue;
                }
                for (ToolUseBlock use : msg.getContentBlocks(ToolUseBlock.class)) {
                    // 不可变集合的 contains(null) 抛无消息 NPE，故先判空。
                    if (use.getName() != null && HITL_TOOLS.contains(use.getName())) {
                        lastHitl = use;
                        lastHitlIndex = i;
                        lastHitlReplyId = msg.getId();
                    }
                }
            }
            if (lastHitl == null) {
                return Optional.empty();
            }
            String pendingId = lastHitl.getId();
            if (pendingId == null || pendingId.isBlank()) {
                return Optional.empty();
            }
            // 闸门之后出现表单提交消息 = 主编已决（与 hasPendingHitl 同口径）。
            if (indexOfFormSubmissionAfter(context, lastHitlIndex) >= 0) {
                return Optional.empty();
            }
            for (Msg msg : context) {
                for (ToolResultBlock result : msg.getContentBlocks(ToolResultBlock.class)) {
                    if (pendingId.equals(result.getId())) {
                        // SUCCESS 才是主编的决定；RUNNING（挂起占位）/ ERROR
                        // （enablePendingToolRecovery 自动补错）/ 未知都算未决。
                        if (result.getState() == ToolResultState.SUCCESS) {
                            return Optional.empty();
                        }
                        return Optional.of(new PendingHitlGate(
                                pendingId, lastHitl, lastHitlIndex, lastHitlReplyId));
                    }
                }
            }
            // 无任何结果块 = 悬空闸门，恰恰是补登记要救的那一类。
            return Optional.of(new PendingHitlGate(
                    pendingId, lastHitl, lastHitlIndex, lastHitlReplyId));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * 平台表单提交消息的前缀（与前端 {@code usePlatformMessages.formValuesToSubmissionText}
     * 的文案一致，也是平台-前端之间的稳定契约）。
     */
    private static final String[] FORM_SUBMISSION_PREFIXES = {
            "（用户通过表单提交以下信息）", "（用户提交了表单，但未填写字段）"};

    /** 该消息是否为平台表单通道的主编提交（role=user 的提交文案）。 */
    static boolean isFormSubmission(Msg msg) {
        if (msg == null || msg.getRole() != MsgRole.USER) {
            return false;
        }
        String text = msg.getTextContent();
        if (text == null) {
            return false;
        }
        String trimmed = text.stripLeading();
        for (String prefix : FORM_SUBMISSION_PREFIXES) {
            if (trimmed.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inputCarriesFormSubmission(List<Msg> msgs) {
        if (msgs == null) {
            return false;
        }
        for (Msg m : msgs) {
            if (isFormSubmission(m)) {
                return true;
            }
        }
        return false;
    }

    /** 上下文里 {@code fromIndex} 之后（含）是否出现表单提交消息；无则返回 -1。 */
    private static int indexOfFormSubmissionAfter(List<Msg> context, int fromIndex) {
        for (int i = Math.max(0, fromIndex); i < context.size(); i++) {
            if (isFormSubmission(context.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 本次输入消息里是否携带了指定工具调用 id 的结果（resume/提交形态）。 */
    private static boolean inputCarriesToolResult(AgentInput input, String toolId) {
        for (Msg m : input.msgs()) {
            for (ToolResultBlock r : m.getContentBlocks(ToolResultBlock.class)) {
                if (toolId.equals(r.getId())) {
                    return true;
                }
            }
        }
        return false;
    }
}
