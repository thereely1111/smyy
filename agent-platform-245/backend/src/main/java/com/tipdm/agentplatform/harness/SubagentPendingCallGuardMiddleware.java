package com.tipdm.agentplatform.harness;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.state.AgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 子智能体派发前的悬挂 tool call 对账守卫。
 *
 * <p><b>缺陷</b>：子会话里一旦留下「工具调用已发出、结果未回写」的悬挂记录（进程被杀、
 * 上游流停滞、run 异常中断都会产生），父会话再次 {@code agent_spawn}/{@code agent_send}
 * 进入同一个子会话时会直接抛
 * {@code IllegalStateException: Pending tool calls exist without results}，
 * 该子会话就此报废，只能另起新会话重做。</p>
 *
 * <p><b>为什么由平台侧兜</b>：框架构建子智能体时逐字段复制父配置，但不复制
 * {@code enablePendingToolRecovery}，因此父 agent 开着该开关也传不到子 agent。
 * 平台补的中断结果与框架自动补的文案一致，两者行为等价。</p>
 *
 * <p><b>平台侧修法</b>：平台已有正确的修复逻辑（{@link SubagentSessionMaintenance#planRepair}
 * 补一条与框架同文案的中断结果并写回 state），但它只在<b>启动期</b>与每日 04:30 跑。
 * 本守卫把修复时机提前到<b>派发前</b>：{@link MiddlewareBase#onActing} 里一旦发现本轮
 * 含 {@code agent_spawn}/{@code agent_send} 调用，就先对当前用户的 {@code sub-*} 会话做一次
 * 幂等对账（{@link SubagentSessionMaintenance#repairPendingSubSessionsForUser}），再放行。</p>
 *
 * <p><b>为什么按工具名触发而不是按子会话 id</b>：这两个工具的入参只有
 * {@code agent_id}/{@code agent_key}（子智能体名 + 声明 id），<b>不含目标子会话 id</b>，
 * 无法直接定位；因此退化为「派发前对该用户的子会话做一次有界对账」，对账的覆盖范围与
 * 冷却策略见 {@link SubagentSessionMaintenance#repairPendingSubSessionsForUser}。</p>
 *
 * <p><b>取 state store 不按 {@code HarnessAgent} 判型</b>：中间件链以<b>内层 delegate
 * ReActAgent</b> 为 agent 构建（HarnessAgent 只是外层包装），按 {@code HarnessAgent} 判型
 * 永远不成立，会让守卫静默失效；故统一走
 * {@link HitlRecoveryGuardMiddleware#resolveStateStore} 按具体类型取 store。</p>
 *
 * <p><b>尽力而为</b>：任何异常都只记日志并放行原调用——守卫失败不能影响正常派发；
 * 不覆写 {@link MiddlewareBase#order()}（与输入改写类中间件正交）。</p>
 */
public class SubagentPendingCallGuardMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SubagentPendingCallGuardMiddleware.class);

    /**
     * 触发对账的工具名：框架内置的子智能体派发/续跑工具。
     *
     * <p>{@code agent_spawn} 会创建或重入子会话；{@code agent_send} 会续跑既有子会话——
     * 两者都是「可能撞上悬挂记录」的入口。</p>
     */
    private static final Set<String> DISPATCH_TOOLS = Set.of("agent_spawn", "agent_send");

    /**
     * 维护器提供者（懒取）。
     *
     * <p>用 {@link Supplier} 而不是直接注入：{@code SubagentSessionMaintenance} 反过来依赖
     * {@code AgentAssemblyService}，直接构造注入会形成循环依赖；由装配方传
     * {@code ObjectProvider::getObject} 即可在首次真正派发时再解析 bean。</p>
     */
    private final Supplier<SubagentSessionMaintenance> maintenanceSupplier;

    public SubagentPendingCallGuardMiddleware(Supplier<SubagentSessionMaintenance> maintenanceSupplier) {
        this.maintenanceSupplier = maintenanceSupplier;
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        if (containsDispatchCall(input)) {
            reconcile(agent, ctx);
        }
        return next.apply(input);
    }

    /** 本轮工具调用里是否含子智能体派发/续跑调用。 */
    private boolean containsDispatchCall(ActingInput input) {
        if (input == null || input.toolCalls() == null || input.toolCalls().isEmpty()) {
            return false;
        }
        for (ToolUseBlock call : input.toolCalls()) {
            if (call == null) {
                continue;
            }
            String name = call.getName();
            if (name != null && DISPATCH_TOOLS.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /** 派发前对账：修复当前用户子会话里的悬挂 tool call（幂等、尽力而为）。 */
    private void reconcile(Agent agent, RuntimeContext ctx) {
        try {
            if (ctx == null) {
                return;
            }
            String userId = ctx.getUserId();
            if (userId == null || userId.isBlank()) {
                return;
            }
            AgentStateStore store = HitlRecoveryGuardMiddleware.resolveStateStore(agent);
            if (store == null) {
                return;
            }
            SubagentSessionMaintenance maintenance = maintenanceSupplier.get();
            if (maintenance == null) {
                return;
            }
            int repaired = maintenance.repairPendingSubSessionsForUser(store, userId);
            if (repaired > 0) {
                log.warn("Subagent pending-call guard: repaired {} pending tool call(s) before dispatch "
                        + "(session={}, user={})", repaired, ctx.getSessionId(), userId);
            }
        } catch (RuntimeException e) {
            log.warn("Subagent pending-call guard: reconcile failed ({})", e.getMessage());
        }
    }
}
