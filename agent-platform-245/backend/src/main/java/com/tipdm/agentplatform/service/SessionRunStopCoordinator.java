package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.harness.RunSubscriptionRegistry;
import com.tipdm.agentplatform.harness.SessionActivityTracker;
import com.tipdm.agentplatform.harness.SessionRunStopper;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会话删除时的「停 run」编排：父会话 + 其子智能体会话。
 *
 * <p><b>为什么不能只停父会话</b>：{@link SessionRunStopper} 只按传入的 sessionId 查
 * 活跃计数与订阅表，而子智能体是独立的 {@code sub-*} 会话、独立计数
 * （{@code SessionActivityMiddleware} 按 {@code ctx.sessionId} 登记），
 * 完全不在父会话的判据内。删除时只停父 run 的话，子 run 会继续跑到自然结束、
 * 继续往已删会话的目录写产物——用户报障「删除会话后还生成了产物」。</p>
 *
 * <p><b>为什么抽成独立类</b>：本逻辑需要「先于状态行删除」执行（子会话血缘取自父会话
 * 状态里的框架 spawn registry），必须与删除编排贴在一起；但把它内联进
 * {@code AgentRuntimeService} 会让那个类越过 Checkstyle 的文件长度上限。
 * 拆出来后删除编排只留一行调用，语义与顺序约束由本类的Javadoc 承载。</p>
 *
 * <p>全部动作尽力而为：单个会话停不掉不影响删除流程（目录与状态行照常清理），
 * 墓碑已落库的情况下残留 run 也无法再唤醒会话。</p>
 */
@Component
public class SessionRunStopCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SessionRunStopCoordinator.class);

    private final SessionActivityTracker activityTracker;
    private final RunSubscriptionRegistry runSubscriptions;
    private final SessionLineageService lineageService;

    public SessionRunStopCoordinator(SessionActivityTracker activityTracker,
                                     RunSubscriptionRegistry runSubscriptions,
                                     SessionLineageService lineageService) {
        this.activityTracker = activityTracker;
        this.runSubscriptions = runSubscriptions;
        this.lineageService = lineageService;
    }

    /**
     * 停掉该会话及其子智能体会话在跑的 run。
     *
     * <p>⚠️ 必须在删除状态行<b>之前</b>调用：子会话清单来自父会话状态，
     * 状态行一删就查不到血缘。</p>
     *
     * @return 实际发出中断的会话数（含父会话自身）
     */
    public int stopSessionAndSubSessions(HarnessAgent agent, String userId, String sessionId) {
        int stopped = 0;
        if (SessionRunStopper.stopIfRunning(agent, activityTracker, runSubscriptions, userId, sessionId)) {
            stopped++;
        }
        for (String subSessionId : listSubSessions(userId, sessionId)) {
            try {
                // 子会话同样有状态行与产物目录（runtimes/{agentId}/sub-*），
                // 走的是与父会话完全相同的停止链路。
                if (SessionRunStopper.stopIfRunning(
                        agent, activityTracker, runSubscriptions, userId, subSessionId)) {
                    stopped++;
                    log.info("Interrupted subagent run session={} (parent={})", subSessionId, sessionId);
                }
            } catch (RuntimeException e) {
                // 单个子会话停不掉不影响删除：目录与状态行照常清理。
                log.warn("Failed to interrupt subagent run session={} (parent={}): {}",
                        subSessionId, sessionId, e.getMessage());
            }
        }
        return stopped;
    }

    /**
     * 父会话的子智能体会话清单；血缘不可得时返回空列表
     * （调用方据此降级为「只停父 run」，不因枚举失败中断删除）。
     */
    private List<String> listSubSessions(String userId, String sessionId) {
        if (lineageService == null || userId == null || userId.isBlank()) {
            return List.of();
        }
        try {
            List<String> subs = lineageService.subSessionsOf(userId, sessionId);
            return subs == null ? List.of() : subs;
        } catch (RuntimeException e) {
            log.warn("Cannot resolve subagent lineage for session={}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }
}
