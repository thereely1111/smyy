package com.tipdm.agentplatform.harness;

import com.tipdm.agentplatform.service.AgentAssemblyService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.task.TaskDelivery;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 「这条会话还有没有活在跑」的探针（2026-10-14，P0-2）。
 *
 * <p><b>为什么需要它</b>：{@code GET …/run-status} 原先只回答
 * {@link SessionActivityTracker#isRunning(String)} —— 那是按 <b>sessionId</b> 计的
 * 父会话在跑计数。而本平台的智能体是<b>异步编排型</b>：父回合把子任务派出去
 * （{@code agent_spawn} 提升为 async）就收流空闲，子智能体跑在<b>它自己的会话</b>里，
 * 此后由 inbox → wakeup 通道把父会话拉回来续跑。于是「派完、等回报」这段空窗里
 * 父会话恒报空闲 —— 实测 2026-10-14 站点侧：父会话 16:34:04 收流、16:39:07 才被唤醒，
 * 空窗 303 秒，而框架任务记录里 {@code 题目解析智能体} 全程 RUNNING。
 * 前端据此判「会话已空闲」，把还在跑的活下了失败定论。</p>
 *
 * <p>本探针把「在途工作」的口径补齐成三路并集，与
 * {@link TaskDeliveryWatchdog} 读同一批框架数据：</p>
 * <ol>
 *   <li>{@code parentRunning} —— 父会话在跑（原 {@code running} 语义，一字不改）；</li>
 *   <li>{@code runningChildTasks} —— 子任务记录处于 PENDING / RUNNING（子智能体在跑）；</li>
 *   <li>{@code livePendingDeliveries} —— 子任务已到终态但结果尚未投递回父会话
 *       （父会话即将被唤醒，也算在途）。</li>
 * </ol>
 *
 * <p><b>第 3 路为什么加时效闸</b>：框架的完成回调 push 之后<b>不写 deliveredAt</b>
 * （见 {@link TaskDeliveryWatchdog} 类注释，2026-09-19 缺陷），所以「已投递且已消费」
 * 的任务会长期滞留在 {@code findPendingDeliveries} 里，只靠看门狗按转录去重后才补标记。
 * 不加时效，一条陈旧记录就能把前端跟随一路拖到上限。这里只认
 * {@link #PENDING_DELIVERY_FRESH} 之内完成的——真在途的那批会在看门狗
 * （60s 扫描 + 120s 冷却）几个周期内被投递掉，只有陈旧残留才会超时。</p>
 *
 * <p><b>只读、可失败</b>：探针不发 run、不改任何状态；任何异常一律降级为「这一路不计」，
 * 绝不因此让 {@code run-status} 报错（它是前端的存活探针，不能让一条会话的脏数据
 * 把整个端点带崩）。</p>
 */
@Component
public class SessionWorkProbe {

    private static final Logger log = LoggerFactory.getLogger(SessionWorkProbe.class);

    /**
     * 「终态未投递」的子任务超过这个岁数就不再算在途。
     *
     * <p>取 15 分钟是宽松到不会误判的量级：正常补投递在看门狗 60s 扫描节拍、
     * 120s 会话冷却、以及 wakeup 冷却之内完成（分钟级）；超过 15 分钟仍未投递的，
     * 要么是已消费的陈旧残留（框架不写 deliveredAt 所致），要么该会话已经不走正常
     * 通道了——后者归平台停滞看护（{@code SessionProgressService}）管，不该由前端
     * 跟随无限期吊住界面。</p>
     */
    private static final Duration PENDING_DELIVERY_FRESH = Duration.ofMinutes(15);

    private final SessionActivityTracker activityTracker;
    private final AgentAssemblyService assemblyService;

    public SessionWorkProbe(SessionActivityTracker activityTracker,
                           AgentAssemblyService assemblyService) {
        this.activityTracker = activityTracker;
        this.assemblyService = assemblyService;
    }

    /**
     * 一次探针的结果快照。
     *
     * @param parentRunning         父会话有 run 在跑（{@link SessionActivityTracker}）
     * @param runningChildTasks     PENDING / RUNNING 的子任务数（框架 TaskRepository）
     * @param livePendingDeliveries 新近终态、尚未投递回父会话的子任务数
     */
    public record WorkSnapshot(
            boolean parentRunning,
            int runningChildTasks,
            int livePendingDeliveries) {

        /** 后端还有活要干（父会话在跑，或子智能体在跑，或结果还没交回）。 */
        public boolean pendingWork() {
            return parentRunning || runningChildTasks > 0 || livePendingDeliveries > 0;
        }
    }

    /**
     * 探这条会话现在还有没有在途工作。
     *
     * <p>永远返回，不抛异常。读不到框架任务数据时只回父会话那一路
     * （即行为退化成改造前的 {@code running}）。</p>
     */
    public WorkSnapshot probe(String agentId, String userId, String sessionId) {
        boolean parentRunning = false;
        try {
            parentRunning = activityTracker.isRunning(sessionId);
        } catch (RuntimeException e) {
            log.debug("Session work probe: activity lookup failed for session {}: {}",
                    sessionId, e.getMessage());
        }
        int runningChildren = 0;
        int liveDeliveries = 0;
        try {
            HarnessAgent agent = assemblyService.requireAssembledById(agentId);
            TaskRepository repo = agent == null ? null : agent.getTaskRepository();
            if (repo != null) {
                RuntimeContext rc = RuntimeContext.builder()
                        .userId(userId)
                        .sessionId(sessionId)
                        .build();
                runningChildren = countTasks(repo, rc, sessionId, TaskStatus.RUNNING)
                        + countTasks(repo, rc, sessionId, TaskStatus.PENDING);
                liveDeliveries = countLiveDeliveries(repo, rc, sessionId);
            }
        } catch (RuntimeException e) {
            log.debug("Session work probe: task lookup failed for session {}: {}",
                    sessionId, e.getMessage());
        }
        return new WorkSnapshot(parentRunning, runningChildren, liveDeliveries);
    }

    private int countTasks(TaskRepository repo, RuntimeContext rc,
                           String sessionId, TaskStatus status) {
        try {
            Collection<?> tasks = repo.listTasks(rc, sessionId, status);
            return tasks == null ? 0 : tasks.size();
        } catch (RuntimeException e) {
            log.debug("Session work probe: listTasks({}) failed for session {}: {}",
                    status, sessionId, e.getMessage());
            return 0;
        }
    }

    private int countLiveDeliveries(TaskRepository repo, RuntimeContext rc, String sessionId) {
        List<TaskDelivery> pending;
        try {
            pending = repo.findPendingDeliveries(rc, sessionId);
        } catch (RuntimeException e) {
            log.debug("Session work probe: findPendingDeliveries failed for session {}: {}",
                    sessionId, e.getMessage());
            return 0;
        }
        if (pending == null || pending.isEmpty()) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(PENDING_DELIVERY_FRESH);
        int live = 0;
        for (TaskDelivery delivery : pending) {
            Instant completedAt = delivery.completedAt();
            // 时刻缺失按「就在途」处理：宁可多跟一会儿，也别在结果正要交回时判它收工。
            if (completedAt == null || completedAt.isAfter(cutoff)) {
                live++;
            }
        }
        return live;
    }
}
