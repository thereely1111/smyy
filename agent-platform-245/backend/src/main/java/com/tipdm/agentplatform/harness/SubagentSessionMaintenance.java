package com.tipdm.agentplatform.harness;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 子智能体会话维护（运行期，派发前对账，不做主动批量改写）。
 *
 * <p>只针对 {@code sub-*} 会话（父会话一律不碰），在「子会话即将被派发/续跑」之前，
 * 把悬挂的 pending 工具调用补成与框架同文案的中断结果：</p>
 *
 * <ul>
 *   <li><b>修复被中断遗留的 pending 工具调用</b>：sub 会话在「工具调用已发出、结果未落」
 *       时被进程重启/杀掉打断，上下文里就留下没有结果的 {@code tool_use}。框架对这种情况
 *       有两条路——{@code enablePendingToolRecovery} 开着就自动补一条中断结果，没开就直接抛
 *       {@code IllegalStateException: Pending tool calls exist without results}。
 *       框架构建子智能体时逐字段复制父配置，但不复制该开关，所以父 agent 开着也传不到子
 *       agent；子智能体重进即硬失败，把已有产出全部作废。这里补上与框架同文案的中断结果，
 *       让它能像父会话一样自行续跑。</li>
 * </ul>
 *
 * <p>修复只在用户主动派发（{@code agent_spawn}/{@code agent_send}）时触发，且仅作用于
 * 当前用户的 {@code sub-*} 会话：属于"派发前即时修"，不是系统在无人操作时改写用户数据。
 * 不自动回收孤儿会话——是否清理交由用户自行决定。安全边界：正在跑的会话
 * （{@link SessionActivityTracker}）一律不处理。</p>
 */
@Component
public class SubagentSessionMaintenance {

    private static final Logger log = LoggerFactory.getLogger(SubagentSessionMaintenance.class);

    static final String SUB_SESSION_PREFIX = "sub-";

    /** 与框架 {@code enablePendingToolRecovery} 自动补的文案例一致，让子智能体行为与父会话相同。 */
    static final String INTERRUPTED_RESULT_TEXT =
            "[ERROR] Previous tool execution failed or was interrupted";

    private static final String STATE_KEY = "agent_state";

    private final SessionActivityTracker activityTracker;

    /**
     * 运行期定向对账单次最多检查多少个会话。
     *
     * <p>必须显著大于单用户的子会话总量：会话集合的迭代顺序每轮固定，总量一旦超过上限，
     * 超出部分就被整段截断在外、永不进入对账，形成<b>静默盲区</b>——悬挂调用照旧触发框架
     * 硬失败，而日志上看不出守卫曾经跑过。该值同时充当盲区告警阈值。</p>
     */
    static final int RUNTIME_REPAIR_MAX_SESSIONS = 500;

    /**
     * 同一会话的运行期对账冷却（毫秒）：一轮里密集派发时不重复读 state。
     *
     * <p>须短于「子会话 run 结束 → 父会话下次派发」的间隔：冷却期内新产生的悬挂调用
     * 会被整段跳过，直接压到框架硬失败路径。</p>
     */
    static final long RUNTIME_REPAIR_COOLDOWN_MILLIS = 15_000L;

    /**
     * sessionId → 上次运行期对账时间戳。
     *
     * <p>条目数上界即子会话数量级（数百），不需要淘汰；进程重启即清空，语义自洽。</p>
     */
    private final ConcurrentMap<String, Long> lastRuntimeCheckAt = new ConcurrentHashMap<>();

    public SubagentSessionMaintenance(SessionActivityTracker activityTracker) {
        this.activityTracker = activityTracker;
    }

    /**
     * 运行期定向对账：在「子会话即将被派发/续跑」之前，把该用户 {@code sub-*} 会话里
     * 悬挂的 tool call 补成与框架同文案的中断结果。
     *
     * <p><b>为什么需要它</b>：悬挂记录多由<b>运行期</b>事件造成（进程被杀、上游流停滞、
     * run 异常中断）。一旦产生，父会话再次 {@code agent_spawn}/{@code agent_send} 到同一个
     * sub 会话就会命中框架的硬失败
     * {@code IllegalStateException: Pending tool calls exist without results}，
     * 该子会话从此报废（H-6 首轮 {@code sub-c2c383c3} 实测：任务判 FAILED、另起会话重做，
     * 净损耗约 40 分钟）。这里在派发前即时修好，避免撞上硬失败。</p>
     *
     * <p><b>判定与写法与 {@link #planRepair} 完全同一套</b>，因此行为一致、无新算法；
     * 成本受三重约束：只处理本用户的 {@code sub-*} 会话、跳过在跑会话
     * （{@link SessionActivityTracker}）、单次最多 {@value #RUNTIME_REPAIR_MAX_SESSIONS}
     * 个会话且每会话有 {@value #RUNTIME_REPAIR_COOLDOWN_MILLIS} 毫秒冷却。</p>
     *
     * @param store  该用户本次 run 所用 agent 的 {@code AgentStateStore}
     * @param userId 会话归属用户
     * @return 实际补写的结果条数；0 = 无需修复、被跳过或读取失败（一律静默降级）
     */
    public int repairPendingSubSessionsForUser(AgentStateStore store, String userId) {
        if (store == null || userId == null || userId.isBlank()) {
            return 0;
        }
        Set<String> sessionIds;
        try {
            sessionIds = store.listSessionIds(userId);
        } catch (RuntimeException e) {
            log.debug("Subagent maintenance: runtime reconcile skipped for user {}: {}",
                    userId, e.getMessage());
            return 0;
        }
        if (sessionIds == null || sessionIds.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int candidates = 0;
        int examined = 0;
        int repaired = 0;
        int skippedRunning = 0;
        int skippedCooldown = 0;
        for (String sessionId : sessionIds) {
            if (sessionId == null || !sessionId.startsWith(SUB_SESSION_PREFIX)) {
                continue;
            }
            candidates++;
            if (activityTracker.isRunning(sessionId)) {
                skippedRunning++;
                continue; // 在跑的一律不动
            }
            Long lastCheck = lastRuntimeCheckAt.get(sessionId);
            if (lastCheck != null && now - lastCheck < RUNTIME_REPAIR_COOLDOWN_MILLIS) {
                skippedCooldown++;
                continue;
            }
            if (examined >= RUNTIME_REPAIR_MAX_SESSIONS) {
                break;
            }
            examined++;
            lastRuntimeCheckAt.put(sessionId, now);
            AgentState state;
            try {
                state = store.get(userId, sessionId, STATE_KEY, AgentState.class).orElse(null);
            } catch (RuntimeException e) {
                continue;
            }
            if (state == null) {
                continue;
            }
            Repair repair = planRepair(state);
            if (repair == null) {
                continue;
            }
            try {
                store.save(userId, sessionId, STATE_KEY, repair.state());
                repaired += repair.count();
                log.warn("Subagent maintenance: repaired {} pending tool call(s) in {} at dispatch time "
                                + "(runtime reconcile before agent_spawn/agent_send; ids={})",
                        repair.count(), sessionId, repair.pendingIds());
            } catch (RuntimeException e) {
                log.warn("Subagent maintenance: runtime repair failed for {}: {}", sessionId, e.getMessage());
            }
        }
        // 每次派发对账都留痕：守卫究竟有没有跑、覆盖了多少会话，只能由这条日志判定。
        // 仅在 repaired>0 时打日志会让「从未生效」和「确实无需修复」无法区分。
        log.info("Subagent pending-call reconcile: user={} subSessions={} examined={} repaired={} "
                        + "skippedRunning={} skippedCooldown={}",
                userId, candidates, examined, repaired, skippedRunning, skippedCooldown);
        if (candidates > RUNTIME_REPAIR_MAX_SESSIONS) {
            log.warn("Subagent pending-call reconcile: {} sub-sessions exceed the scan limit {}, "
                            + "the excess is never reconciled (raise the limit)",
                    candidates, RUNTIME_REPAIR_MAX_SESSIONS);
        }
        return repaired;
    }

    /** 修复方案：无 pending 调用时返回 {@code null}（幂等：没有变更就不写库）。 */
    record Repair(AgentState state, int count, List<String> pendingIds) {
    }

    static Repair planRepair(AgentState state) {
        List<Msg> context = state.getContext() == null ? List.of() : state.getContext();
        List<ToolUseBlock> pending = pendingToolCalls(context);
        if (pending.isEmpty()) {
            return null;
        }
        List<ContentBlock> results = new ArrayList<>(pending.size());
        List<String> ids = new ArrayList<>(pending.size());
        for (ToolUseBlock use : pending) {
            ids.add(use.getId());
            results.add(ToolResultBlock.builder()
                    .id(use.getId())
                    .name(use.getName())
                    .output(TextBlock.builder().text(INTERRUPTED_RESULT_TEXT).build())
                    .state(ToolResultState.ERROR)
                    .build());
        }
        // 补一条 TOOL 消息承载全部中断结果；时间戳沿用最后一条消息，避免把"最后活动时间"
        // 推到当前时刻（那会让孤儿判定失效、也会污染转录排序）。
        String timestamp = context.isEmpty() ? null : context.get(context.size() - 1).getTimestamp();
        Msg.Builder builder = Msg.builderForRole(MsgRole.TOOL).content(results);
        if (timestamp != null && !timestamp.isBlank()) {
            builder.timestamp(timestamp);
        }
        List<Msg> patched = new ArrayList<>(context.size() + 1);
        patched.addAll(context);
        patched.add(builder.build());

        AgentState rebuilt = AgentState.builder()
                .sessionId(state.getSessionId())
                .userId(state.getUserId())
                .summary(state.getSummary())
                .context(patched)
                .replyId(state.getReplyId())
                .curIter(state.getCurIter())
                .shutdownInterrupted(state.isShutdownInterrupted())
                .permissionContext(state.getPermissionContext())
                .toolContext(state.getToolContext())
                .tasksContext(state.getTasksContext())
                .planModeContext(state.getPlanModeContext())
                .build();
        return new Repair(rebuilt, pending.size(), ids);
    }

    /** 没有对应 {@code tool_result} 的工具调用（判据与框架的前置校验一致：只看有没有结果）。 */
    static List<ToolUseBlock> pendingToolCalls(List<Msg> context) {
        Set<String> withResult = new HashSet<>();
        List<ToolUseBlock> uses = new ArrayList<>();
        for (Msg msg : context) {
            for (ToolResultBlock result : msg.getContentBlocks(ToolResultBlock.class)) {
                if (result.getId() != null) {
                    withResult.add(result.getId());
                }
            }
            for (ToolUseBlock use : msg.getContentBlocks(ToolUseBlock.class)) {
                if (use.getId() != null) {
                    uses.add(use);
                }
            }
        }
        List<ToolUseBlock> pending = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ToolUseBlock use : uses) {
            if (!withResult.contains(use.getId()) && seen.add(use.getId())) {
                pending.add(use);
            }
        }
        return pending;
    }

}
