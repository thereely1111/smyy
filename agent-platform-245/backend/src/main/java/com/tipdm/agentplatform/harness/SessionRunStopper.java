package com.tipdm.agentplatform.harness;

import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话删除前的「在跑 run 停止」：触发该会话的中断并等待 run 收敛。
 *
 * <p><b>为什么需要</b>：删会话（清会话）会删掉状态行，而正在跑的 run 结束时会用 CAS
 * 回写状态（{@code persistAgentStateCas → getVersioned(agent_state)}）——行已被删，
 * 保存必然失败并抛未处理异常（2026-09-11 12:29 实测：清 main-06805831 时该会话仍有
 * 在跑的 run，接口返回 500，日志 {@code Failed to get versioned state}）。
 * 删除前先把 run 停掉，写完最后一份状态再删，语义也更干净。</p>
 *
 * <p>停止顺序（2026-09-30 方案 A）：<b>先 dispose 订阅</b>——Reactor cancel 沿订阅链
 * 上游传播，reactor-netty 直接 abort 底层 HTTP 连接，阻塞中的 LLM 流立刻死；随后仍调
 * {@code agent.interrupt(userId, sessionId)} 作检查点语义兜底（run 若已死于 cancel 则为
 * 静默 no-op，框架自己负责落一次状态）。此前只靠 interrupt()：它碰不到阻塞中的调用，
 * 实测 3s宽限超时后僵尸run 继续占住活跃计数 11 分钟以上（dashscope 客户端超时远长于宽限）。</p>
 *
 * <p>停止是尽力而为：等待超时只记WARN，不阻断删除（用户要删就该删掉）。</p>
 *
 * <p><b>在跑判据不能只看活跃计数</b>（2026-10-09）：计数 {@code isRunning} 是进程内存
 * 里的 begin/end 配对值，与真实 run 状态无因果保证（收尾半途而废即永久泄漏，见
 * {@link InboxWatchdogWakeupService#recoverLeakedCountIfAny}）。原实现首行
 * {@code if (!tracker.isRunning(sessionId)) return false;} 于是把「计数说没在跑」
 * 直接等同于「无需中断」，中断请求被静默丢弃，而接口照样返回 202 —— 用户点停止
 * 全无反应。现改为取<b>两个相互独立的来源的并集</b>：活跃计数（覆盖聊天路径 run）
 * 与订阅登记表（{@link RunSubscriptionRegistry} 在 {@code doOnSubscribe} 登记，
 * 与计数不同源）。任一为真即执行停止。</p>
 */
public final class SessionRunStopper {

    private static final Logger log = LoggerFactory.getLogger(SessionRunStopper.class);

    /** 等待 run 收敛的轮询间隔与上限：run 收到中断后通常在几百毫秒内结束。 */
    private static final long POLL_INTERVAL_MS = 100;
    private static final int POLL_MAX_ROUNDS = 30;

    private SessionRunStopper() {
    }

    /**
     * 若该会话有在跑的 run，则 dispose 订阅 + 触发中断并等待其结束。
     *
     * @param runSubscriptions 服务端自发 run 的订阅登记表（可 null，null 时退化为
     *                         纯框架 interrupt 语义——聊天路径 run 无句柄可 dispose）
     * @return 是否确实发出了中断（两个在跑来源均为假 ⇒ 无 run，未发中断）
     */
    public static boolean stopIfRunning(
            HarnessAgent agent,
            SessionActivityTracker tracker,
            RunSubscriptionRegistry runSubscriptions,
            String userId,
            String sessionId) {
        if (agent == null || tracker == null || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        // 两个独立的「在跑」来源取并集：任一为真都要尝试停止。
        // 只信计数会漏掉「计数已泄漏为false、run 实际还在」的情形（=停止无效）。
        boolean countedRunning = tracker.isRunning(sessionId);
        boolean subscribed = runSubscriptions != null && runSubscriptions.hasSubscription(sessionId);
        if (!countedRunning && !subscribed) {
            return false;
        }
        if (!countedRunning) {
            // 计数说没在跑却有登记订阅 = 计数泄漏的确定性证据（正常在跑的 run
            // 计数必然为真）。记 WARN：这既解释了本次为何走到这里，也留痕给
            // 「用户点停止无效」的排查——修复前该分支根本不会执行。
            log.warn("Stop session run: session {} has a registered run subscription but "
                    + "activity count says idle (count leak suspected)", sessionId);
        }
        if (runSubscriptions != null) {
            boolean disposed = runSubscriptions.dispose(sessionId);
            if (disposed) {
                log.info("Stop session run: subscription disposed (HTTP abort) for session={}", sessionId);
            }
        }
        try {
            agent.interrupt(userId, sessionId);
        } catch (RuntimeException e) {
            log.warn("Stop session run: interrupt failed (session={}): {}", sessionId, e.getMessage());
            return false;
        }
        // 等待收敛以「计数归零」为准；但计数本就可能失真（上面那条泄漏），
        // 此时订阅已被 dispose、interrupt 已发出，等待无意义，立即返回。
        if (!countedRunning) {
            log.info("Stop session run: interrupt dispatched for session={} "
                    + "(activity count was already idle, skipped convergence wait)", sessionId);
            return true;
        }
        for (int i = 0; i < POLL_MAX_ROUNDS && tracker.isRunning(sessionId); i++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (tracker.isRunning(sessionId)) {
            log.warn("Stop session run: run still active after {}ms (session={}), proceeding anyway",
                    POLL_INTERVAL_MS * POLL_MAX_ROUNDS, sessionId);
        } else {
            log.info("Stop session run: in-flight run interrupted before deleting session={}", sessionId);
        }
        return true;
    }
}
