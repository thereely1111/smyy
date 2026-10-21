package com.tipdm.agentplatform.harness;

import com.tipdm.agentplatform.service.AguiEventPersistenceService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 服务端自发 run 统一启动器（登记收口 2026-09-20）。
 *
 * <p>平台侧绕过 AG-UI 请求路径发起的 run 有多个入口：wakeup 补醒
 * （{@link AguiWakeupTarget}）、停滞自检（SessionProgressService），以及未来的
 * 定时/恢复类入口。这些 run 不经过 {@code AguiRequestProcessor}：框架既不会
 * track pending interrupts，前端也没有 SSE 流可上报 form-interrupt。若各入口
 * 自行 {@code agent.streamEvents(...).subscribe(...)}，就必须各自记得
 * 「run 租约登记 + 收尾闸门登记」——漏掉任何一处都是 2026-09-20 实证的事故形态：
 * 停滞自检 run 渲染的 ui_gate 无人登记 → {@link HitlTimeoutReaper} 巡检列表为空
 * → 30 分钟超时取消失效 → watchdog 判"无 pending HITL"反复注入自检 → 模型反复
 * 重渲染闸门，约 30 分钟一轮死循环烧 token。</p>
 *
 * <p>因此收口为本启动器：<b>所有服务端自发 run 必须经由 {@link #launch}</b>，
 * 禁止直接订阅 {@code agent.streamEvents(...)}。统一保证三件事：</p>
 * <ol>
 *   <li><b>run 级租约登记</b>（{@link RunLeaseStore#upsert}）：进程死亡后
 *       RunLeaseReconciler 能识别孤儿 run 并驱动恢复；</li>
 *   <li><b>收尾闸门双登记</b>（{@link WakeupInterruptRegistrar#registerSuspendedGates}）：
 *       框架 {@code pendingInterruptsByThread} + 平台 {@code PendingFormInterruptStore}，
 *       闸门可被主编提交（resume 契约成立）、可被超时巡检取消、可被前端刷新恢复；</li>
 *   <li><b>悬空闸门兜底对账</b>（{@link WakeupInterruptRegistrar#reconcileDanglingGate}）：
 *       上条只认 {@code TOOL_SUSPENDED} 收尾，而闸门还有工具转后台、run 中断、
 *       max-iters 截断三种收尾形态走不到它——那些形态下闸门对两处内存源均不可见，
 *       需在 {@code doFinally} 按持久化上下文补登记（2026-10-07 新增）；</li>
 *   <li><b>尽力而为容错</b>：登记/租约失败只降级 WARN，不影响 run 本身
 *       （与 AG-UI 请求路径上框架 track 失败的降级语义一致）。</li>
 * </ol>
 *
 * <p>兜底：即使未来某入口绕过本启动器，{@link HitlTimeoutReaper} 的框架对账
 * （框架有、登记表无 → 补登记）也会在一分钟内消除盲区——但那是安全网，不是
 * 豁免理由。</p>
 */
@Component
public class ServerSideRunLauncher {

    private static final Logger log = LoggerFactory.getLogger(ServerSideRunLauncher.class);

    private final RunLeaseStore runLeaseStore;
    private final WakeupInterruptRegistrar interruptRegistrar;
    private final AguiEventPersistenceService eventLog;
    private final DeclarativeEndingWatchdog declarativeEndingWatchdog;
    private final RunSubscriptionRegistry runSubscriptions;
    private final TruncationResumeNudger truncationResumeNudger;

    public ServerSideRunLauncher(
            RunLeaseStore runLeaseStore,
            WakeupInterruptRegistrar interruptRegistrar,
            AguiEventPersistenceService eventLog,
            DeclarativeEndingWatchdog declarativeEndingWatchdog,
            RunSubscriptionRegistry runSubscriptions,
            TruncationResumeNudger truncationResumeNudger) {
        this.runLeaseStore = runLeaseStore;
        this.interruptRegistrar = interruptRegistrar;
        this.eventLog = eventLog;
        this.declarativeEndingWatchdog = declarativeEndingWatchdog;
        this.runSubscriptions = runSubscriptions;
        this.truncationResumeNudger = truncationResumeNudger;
    }

    /**
     * 发起一次服务端自发 run。
     *
     * @param agent     已装配的智能体
     * @param inputs    本轮输入消息（作为 user 输入进入 modelInput）
     * @param rtc       运行时上下文（sessionId/userId 必填）
     * @param agentId   平台智能体 id（租约与闸门登记的归属）
     * @param userId    会话归属用户（必须是已解析的真实 owner，不得为 null——
     *                  null 会把状态写进 anonymous 分区，前端按真实 owner 读不到）
     * @param sessionId 会话 id
     * @return run 事件流（含 {@link AgentResultEvent}），订阅方式由调用方决定
     */
    public Flux<AgentEvent> launch(HarnessAgent agent, List<Msg> inputs, RuntimeContext rtc,
                                   String agentId, String userId, String sessionId) {
        try {
            runLeaseStore.upsert(userId, sessionId, agentId);
        } catch (RuntimeException e) {
            log.warn("Server run: lease upsert failed for session {}: {}", sessionId, e.getMessage());
        }
        // 3.1：自发 run 事件接入 agui_events 落库（最小集合：RUN_STARTED/FINISHED
        // + TEXT_MESSAGE_* + TOOL_CALL_START/END），后台工作对事件库/前端水位线
        // 可见。失败仅 WARN。合成事件回喂 3.5 watchdog（宣告式收尾检测覆盖 srv 路径）。
        Consumer<AguiEvent> watchdogObserver =
                declarativeEndingWatchdog == null
                        ? null
                        : event -> declarativeEndingWatchdog.observeEvents(List.of(event));
        ServerRunEventRecorder recorder = eventLog == null
                ? null : new ServerRunEventRecorder(eventLog, sessionId, watchdogObserver);
        if (recorder != null) {
            recorder.recordStart();
        }
        // 4.x（2026-09-30 方案 A）：doOnSubscribe 捕获订阅登记进 RunSubscriptionRegistry，
        // stop / 收尸路径据此 dispose（Reactor cancel 上游传播 → reactor-netty abort
        // 底层 HTTP 连接），替代「框架 interrupt 只能等检查点」的假停止。终态时按
        // (key, value) 注销，不影响后继 run。
        AtomicReference<Subscription> subRef =
                new AtomicReference<>();
        return agent.streamEvents(inputs, rtc)
                .doOnSubscribe(sub -> {
                    subRef.set(sub);
                    runSubscriptions.register(sessionId, sub);
                })
                .doOnNext(event -> {
                    if (recorder != null) {
                        recorder.onAgentEvent(event);
                    }
                    if (event instanceof AgentResultEvent result) {
                        try {
                            // 收尾闸门登记：run 以 TOOL_SUSPENDED 收尾时，代行"不存在的
                            // SSE 订阅者"完成框架 + 平台两处登记。幂等，多个结果事件无害
                            //（非 TOOL_SUSPENDED 的结果事件在 registrar 内部直接跳过）。
                            interruptRegistrar.registerSuspendedGates(
                                    result.getResult(), agentId, userId, sessionId);
                        } catch (RuntimeException e) {
                            log.warn("Server run: suspended-gate registration failed for session {}: {}",
                                    sessionId, e.getMessage());
                        }
                    }
                })
                .doOnError(e -> {
                    if (recorder != null) {
                        recorder.recordError(e);
                    }
                })
                .doFinally(signal -> {
                    Subscription s = subRef.get();
                    if (s != null) {
                        runSubscriptions.unregister(sessionId, s);
                    }
                    if (recorder != null && signal != SignalType.ON_ERROR) {
                        recorder.recordFinish();
                    }
                    // 迭代上限截断：本 run 没能做完，安排一次续跑。放在收尾点是因为
                    // 此刻 run 已结束，唤醒请求不会因「session running」被丢弃。
                    if (recorder != null && truncationResumeNudger != null) {
                        ServerRunEventRecorder.Truncation truncated = recorder.truncation();
                        if (truncated != null) {
                            truncationResumeNudger.onTruncated(
                                    sessionId, truncated.maxIters(), truncated.currentIter());
                        }
                    }
                    // 悬空闸门兜底对账（2026-10-07）：上面的收尾登记只认
                    // 「结果消息 GenerateReason==TOOL_SUSPENDED」，而闸门还有三种收尾
                    // 形态走不到它（工具转后台执行、run 被中断、max-iters 截断），
                    // 闸门 tool_use 留在上下文里却没有任何内存登记 ⇒ 前端无法提交、
                    // 30min reaper 扫不到、唤醒守卫却按 hasPendingHitl 判「有未决
                    // 闸门」而永久延后（实证 main-3f077bd9 被挡 290 次）。
                    // 这里在收尾点补一次对账，使三方重新一致。
                    // 放在 doFinally：无论正常/异常/取消都执行，且此刻状态已落盘。
                    reconcileGateAfterRun(agent, agentId, userId, sessionId);
                });
    }

    /**
     * run 收尾后的悬空闸门对账；任何失败只降级 WARN，绝不影响 run 本身
     * （与 AG-UI 请求路径上框架 track 失败的降级语义一致）。
     */
    private void reconcileGateAfterRun(HarnessAgent agent, String agentId, String userId,
                                       String sessionId) {
        try {
            if (interruptRegistrar == null || agent == null) {
                return;
            }
            // store 必须由 agent 实例解析：中间件链/launcher 拿到的是内层 ReActAgent，
            // instanceof HarnessAgent 的判断在此为真（HarnessAgent 是 launcher 的入参形态），
            // 但 resolveStateStore 已按两种类型依次解析并回退，直接复用它的口径。
            AgentStateStore store =
                    HitlRecoveryGuardMiddleware.resolveStateStore(agent);
            if (store == null) {
                return;
            }
            int repaired = interruptRegistrar.reconcileDanglingGate(store, agentId, userId, sessionId);
            if (repaired > 0) {
                log.warn("Server run: reconciled {} dangling HITL gate(s) after run of session {} "
                        + "(run did not end as TOOL_SUSPENDED; gate would otherwise stay unsubmittable)",
                        repaired, sessionId);
            }
        } catch (RuntimeException e) {
            log.warn("Server run: dangling-gate reconcile failed for session {}: {}",
                    sessionId, e.getMessage());
        }
    }
}
