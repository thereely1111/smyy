package com.tipdm.agentplatform.harness;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.gateway.WakeupDispatcher;
import com.tipdm.agentplatform.service.AgentAssemblyService;
import com.tipdm.agentplatform.service.PendingFormInterruptStore;
import com.tipdm.agentplatform.service.SessionKnowledgeService;
import com.tipdm.agentplatform.service.SessionOwnerService;
import com.tipdm.agentplatform.service.SessionWorkspaceManager;
import com.tipdm.agentplatform.service.notify.SessionNotifyPublisher;
import com.tipdm.agentplatform.web.auth.JwtInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AG-UI 模式下的 Wakeup 目标：当后台子智能体任务完成、会话已空闲时，
 * 恢复该会话所属智能体的一次 run，让父智能体继续推进（而不是永远 idle）。
 *
 * <p><b>注入语义</b>：平台 wakeup 不再以空输入触发 {@code streamEvents}，而是
 * 先从 {@link MessageBus} drain 该 session 的 inbox，把待办 hint 拼成一条
 * user 消息作为本次输入。该改动的根本原因：</p>
 *
 * <ol>
 *   <li>{@code ReActAgent.reasoning} 在进入 middleware 链之前就构造好
 *       {@code modelInput}（由 {@code event.getInputMessages()} 拷贝而来），它
 *       是当前轮的快照。</li>
 *   <li>{@code InboxMiddleware.onReasoning} 在推理前 drain inbox 并把 hint 写入
 *       {@code state.contextMutable()}，但写入的是 context，不是已冻结的
 *       {@code modelInput} —— 本轮 hint 对模型不可见。</li>
 *   <li>普通多轮里这无影响（下一轮自然看到），但 wakeup 本身就是「空输入 run」，
 *       父智能体醒来后只会输出开场白 + 再次询问意向，再无后续轮，hint 永远丢。</li>
 * </ol>
 *
 * <p>把 drain 提前到 {@code streamEvents} 调用之前，hint 直接进入 {@code modelInput}，
 * 本轮即生效；inbox 已 drain，{@code InboxMiddleware} 不会再消费。空 inbox 视为无新工作，
 * 直接返回 {@code Mono.empty()}，避免「无输入空转 → 模型重新开场」的副作用。</p>
 */
public class AguiWakeupTarget implements WakeupDispatcher.WakeupTarget {

    private static final Logger log = LoggerFactory.getLogger(AguiWakeupTarget.class);

    /** 平台 wakeup 每次 drain 的 inbox 上限，与 {@code InboxMiddleware} 默认对齐。 */
    private static final int WAKEUP_INBOX_MAX = 100;

    private final SessionAgentRegistry sessionAgentRegistry;
    private final SessionActivityTracker tracker;
    private final AgentAssemblyService assemblyService;
    private final SessionWorkspaceManager sessionWorkspaceManager;
    private final SessionOwnerService sessionOwnerService;
    private final MessageBus messageBus;
    private final PendingFormInterruptStore pendingFormInterruptStore;
    private final SessionPermissionReconciler permissionReconciler;
    private final WakeupInterruptRegistrar wakeupInterruptRegistrar;
    private final ServerSideRunLauncher runLauncher;
    private final SessionNotifyPublisher notifyPublisher;
    private final SessionKnowledgeService sessionKnowledgeService;
    private final com.tipdm.agentplatform.service.SessionProgressService sessionProgressService;

    /**
     * 本进程内已对「陈旧闸门」放过恢复重放的会话 → 上一次放行的时刻。
     *
     * <p>放行的目的是让模型重新渲染闸门，但后台 run 的中断只登记在框架内存里，
     * 前端（SSE 只收自己发起的 run）看不到，因此不会上报 form-interrupt——
     * 「存活表单登记」仍为空。若不加节流，每次唤醒都会把「刚重新渲染的闸门」再次
     * 判为陈旧而放行，闸门越堆越多（后续 resume 会报
     * {@code must address all of them} 而彻底卡死）。
     *
     * <p><b>为什么是 TTL 而不是一次性</b>（2026-10-07 修正）：一次性语义下，
     * 只要那次唯一的放行没能让闸门变得可提交（例如重渲染的闸门同样落在
     * {@code TOOL_SUSPENDED} 之外的收尾形态、再次漏登记），标记<b>永不复位</b>——
     * 复位只在「无未决HITL」的分支里，而未决闸门在上下文里一直在 ⇒ 会话被永久延后，
     * 实测 main-3f077bd9 从 00:01 到 09:41 每 2 分钟被挡一次、共 290 次，inbox 里
     * 积压的待办永远不执行。改为<b>有界重复尝试</b>：TTL 窗口内不重复放行（防烧
     * token 循环），窗口过后仍未提交则允许再试一次（防永久锁死）。
     * 兜底还有 {@link WakeupInterruptRegistrar#reconcileDanglingGate} 补登记，
     * 但登记本身可能因反射链不可达而只落平台侧，TTL 是与它正交的第二道保险。</p>
     */
    private final ConcurrentMap<String, Long> staleGateRecoveryAt = new ConcurrentHashMap<>();

    /**
     * 陈旧闸门恢复重放的节流窗口：同一会话两次放行的最小间隔。
     *
     * <p>取 15 分钟，与看护自检的 {@code wake-cooldown-minutes} 同量级：
     * 既能配合 {link HitlTimeoutReaper} 的 30 分钟硬超时（错过一次逃逸后仍留有
     * 重试机会），又不会形成高频 run 烧 token。
     */
    static final long STALE_GATE_RECOVERY_TTL_MS = 15 * 60_000L;

    public AguiWakeupTarget(
            SessionAgentRegistry sessionAgentRegistry,
            SessionActivityTracker tracker,
            AgentAssemblyService assemblyService,
            SessionWorkspaceManager sessionWorkspaceManager,
            SessionOwnerService sessionOwnerService,
            MessageBus messageBus,
            PendingFormInterruptStore pendingFormInterruptStore,
            SessionPermissionReconciler permissionReconciler,
            WakeupInterruptRegistrar wakeupInterruptRegistrar,
            ServerSideRunLauncher runLauncher,
            SessionNotifyPublisher notifyPublisher,
            SessionKnowledgeService sessionKnowledgeService,
            com.tipdm.agentplatform.service.SessionProgressService sessionProgressService) {
        this.sessionAgentRegistry = sessionAgentRegistry;
        this.tracker = tracker;
        this.assemblyService = assemblyService;
        this.sessionWorkspaceManager = sessionWorkspaceManager;
        this.sessionOwnerService = sessionOwnerService;
        this.messageBus = messageBus;
        this.pendingFormInterruptStore = pendingFormInterruptStore;
        this.permissionReconciler = permissionReconciler;
        this.wakeupInterruptRegistrar = wakeupInterruptRegistrar;
        this.runLauncher = runLauncher;
        this.notifyPublisher = notifyPublisher;
        this.sessionKnowledgeService = sessionKnowledgeService;
        this.sessionProgressService = sessionProgressService;
    }

    @Override
    public boolean isSessionRunning(String sessionId) {
        return tracker.isRunning(sessionId);
    }

    @Deprecated
    @Override
    public Mono<Msg> runWakeup(String sessionId) {
        return runWakeup(null, sessionId);
    }

    @Override
    public Mono<Msg> runWakeup(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Mono.empty();
        }
        // 「用户已停止」抑制（V60）：用户 interrupt 过的会话，一切自发 wakeup
        // （子任务完成回执、收件箱补醒、任务补投递、启动恢复——它们都汇聚到本
        // 方法）不得拉起新 run。标记由 interrupt 置位、用户主动发起新 run 清除，
        // 因此 inbox hint 原样保留，用户下次说「继续」时仍能看到积压内容。
        if (sessionProgressService.isStopRequested(sessionId)) {
            log.info("Wakeup: session {} is user-stop suppressed, skipping wakeup (hint kept in inbox)",
                    sessionId);
            return Mono.empty();
        }
        // 「等人工唤醒」封存抑制（V62）：HITL 审批超时被平台自动取消后会话被封存，
        // 同样禁止一切自发唤醒——超时 ≠ 批准，平台不得代替主编推进。背景：
        // 超时取消摘掉 pending 登记后闸门不再阻塞唤醒通道，叠加 inbox 积压的子任务
        // 完成通知，超时后 37 秒即起新 run 继续输出（main-3f077bd9 实证）。
        // 解封只在用户主动发起新 run（PlatformAguiRuntimeContextResolver 入口）。
        // 与上面的stopRequested 是同类闸门、不同语义（前者用户喊停、后者超时取消），
        // 两列独立并存、任一置位即静默。
        if (sessionProgressService.isAwaitingHumanGate(sessionId)) {
            log.info("Wakeup: session {} is awaiting-human-gate sealed (HITL timeout), "
                    + "skipping wakeup (hint kept in inbox)", sessionId);
            return Mono.empty();
        }
        // 「已归档」抑制：用户归档（弃用）等于宣告这个会话不再需要平台自行推进。
        // 归档此前只在前端列表侧生效（listSessions 的 includeArchived 过滤），
        // 唤醒链路从不读该列——实测弃用会话重启后照样被唤醒续跑。这里补上运行态语义，
        // 检查放在 agentId 解析之前，解析失败时也能拦住。
        // 解封：取消归档（archived_at 置回 NULL）即自动恢复，无需额外清理。
        if (sessionOwnerService.isArchivedBySession(sessionId)) {
            log.info("Wakeup: session {} is archived (abandoned by user), "
                    + "skipping wakeup (hint kept in inbox)", sessionId);
            return Mono.empty();
        }
        // 会话可能在同一 run 内被子智能体注册覆盖（Harness 会把父智能体的 middleware
        // 复制给子智能体，子智能体运行期 UUID 不是平台可装配的智能体）。因此按
        // "最新→最旧"遍历候选，取第一个平台能解析装配的（父）智能体，避免
        // 子智能体完成通知到达时父会话因解析失败而永远 idle。
        HarnessAgent agent = null;
        String agentId = null;
        for (String candidate : sessionAgentRegistry.agentCandidates(sessionId)) {
            HarnessAgent assembled = assemblyService.requireAssembledById(candidate);
            if (assembled != null) {
                agentId = candidate;
                agent = assembled;
                break;
            }
        }
        if (agent == null) {
            // ⚠️ 实证（2026-09-07）：内存 SessionAgentRegistry 全仓无 register 调用者、恒为空——
            // 若只走候选，wakeup 永远 "no assembled agent ... skipping"（watchdog 已 enqueue 正确
            // agentId 但接口 runWakeup(userId, sessionId) 不带 agentId）。owner 表是 session→agent
            // 可靠映射（每次 run 由 record 写入），作为回退源。
            String ownerAgent = sessionOwnerService.agentBySessionId(sessionId).orElse(null);
            if (ownerAgent != null) {
                HarnessAgent assembled = assemblyService.requireAssembledById(ownerAgent);
                if (assembled != null) {
                    agentId = ownerAgent;
                    agent = assembled;
                }
            }
        }
        if (agent == null) {
            log.warn("Wakeup: no assembled agent for session {} (candidates={}, owner fallback empty), skipping",
                    sessionId, sessionAgentRegistry.agentCandidates(sessionId));
            return Mono.empty();
        }
        final String resolvedAgentId = agentId;
        final HarnessAgent resolvedAgent = agent;
        // 以下全部副作用（解析归属、建目录并写归属、权限对账写库、查挂起 HITL、
        // drain inbox 并起 run）都放进 Mono.defer：原实现把它们放在"返回 Mono"之前
        // 同步执行，调用方即使不订阅（去重逻辑判定跳过、上游取消、被合并）副作用
        // 照样发生——建目录 / 写归属 / 起 run 都不可撤销。
        return Mono.defer(() -> doWakeup(resolvedAgent, resolvedAgentId, userId, sessionId));
    }

    /** wakeup 实际执行体：只在被订阅时运行（见 {@link #runWakeup} 的 Mono.defer）。 */
    private Mono<Msg> doWakeup(HarnessAgent resolvedAgent, String resolvedAgentId,
                               String userId, String sessionId) {
        String resolvedUser = userId == null || userId.isBlank() ? null : userId;
        if (resolvedUser == null) {
            // WakeupDispatcher 在部分路径（后台工具完成通知、平台 Inbox 巡检兜底）
            // 不带 userId。若此时直接回落 anonymous 续跑，agent_state 会写入
            // "anonymous:" 分区，而前端转录按 (真实 owner) 分区读取——续跑产生的
            // 消息（典型：内审通过 + HITL 闸门表单）将永久不可见（2026-09-05 复现：
            // 教材开发小助手会话状态被拆成 real-user / anonymous 两行，闸门段丢失）。
            // 因此先按归属表解析真实 owner，仅当无归属记录时才回落 anonymous。
            resolvedUser = sessionOwnerService.owner(resolvedAgentId, sessionId)
                    .filter(u -> u != null && !u.isBlank())
                    .orElse(null);
        }
        if (resolvedUser == null) {
            resolvedUser = JwtInterceptor.ANONYMOUS_USER;
        }
        try {
            // 与 AG-UI resolver 保持一致：run 前幂等托管会话产物目录 + 记录归属
            sessionWorkspaceManager.ensureSessionDir(resolvedAgentId, sessionId);
            sessionOwnerService.record(resolvedAgentId, sessionId, resolvedUser);
        } catch (RuntimeException e) {
            log.warn("Wakeup: ensureSessionDir failed for session {}: {}", sessionId, e.getMessage());
        }
        if (!resolvedUser.equals(JwtInterceptor.ANONYMOUS_USER)) {
            log.info("Wakeup: resolved owner {} for session {} (dispatch user was blank)",
                    resolvedUser, sessionId);
        }
        // 唤醒 run 同样要走权限对账：后台唤醒不经过 AG-UI resolver，
        // 若会话权限上下文缺平台策略规则，被唤醒的工具调用会落回框架默认 ASK。
        permissionReconciler.reconcile(
                parseAgentId(resolvedAgentId), resolvedAgent.getStateStore(), resolvedUser, sessionId);

        final RuntimeContext rtc = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId(resolvedUser)
                // 会话知识库关联：wakeup/恢复 run 不经过 AG-UI resolver，必须在此
                // 补注入 SessionKnowledgeRef，否则恢复轮系统提示缺库清单、
                // knowledge_search 直接返回「未关联任何知识库」（知识库"丢失"）。
                .put(com.tipdm.agentplatform.service.SessionKnowledgeRef.class,
                        sessionKnowledgeService.refFor(resolvedAgentId, sessionId))
                .build();

        // 闸门挂起守卫：会话存在「未决 HITL 闸门」（ui_gate 已挂起等待主编提交）时，
        // 后台完成通知/wakeup 不得注入任何输入——否则框架 pending-recovery 会把挂起的
        // ui_gate 自动补错，曾造成"假通过/卡死"（2026-09-10/11 实证）。这里直接跳过
        // 重放并保留 inbox hint，待主编通过表单提交（resume 携带工具结果）、flush 掉
        // 挂起口径后，由后续 run 中的 InboxMiddleware 消费。
        //
        // 陈旧闸门例外（2026-09-11 补）：AG-UI 的 pending interrupt 与前端上报登记都在
        // 进程内存——后端重启后两者一并消失，"闸门永远无人可提交"却又"永远挡掉唤醒"，
        // 会话死锁（实证：main-f9462507 30 分钟内 28 次 "has an open HITL gate; deferring"，
        // 无表单可提交）。因此把守卫收紧为「闸门 + 本进程存在存活表单登记」才延后；
        // 登记为空即视为重启遗留的陈旧闸门，放行本次重放，由 HitlRecoveryGuardMiddleware
        // 注入「必须重新渲染 ui_gate」的恢复指示，把会话从死锁拉回可推进状态。
        final AtomicBoolean staleRecovery = new AtomicBoolean(false);
        if (HitlRecoveryGuardMiddleware.hasPendingHitl(
                resolvedAgent.getStateStore(), resolvedUser, sessionId, null)) {
            int liveForms = countLiveFormInterrupts(resolvedAgentId, sessionId);
            boolean recoverySpent = isStaleGateRecoveryCoolingDown(sessionId);
            if (liveForms == 0 && !recoverySpent) {
                staleRecovery.set(true);
                log.warn("Wakeup: session {} has a stale HITL gate (no form interrupt registered in this "
                        + "process; likely rendered before a restart) — proceeding with replay so the agent "
                        + "can re-render the gate", sessionId);
            } else {
                log.warn("Wakeup: session {} has an open HITL gate ({} live form interrupt(s), "
                        + "staleGateRecoveryCoolingDown={}); deferring wakeup replay "
                        + "(hints stay queued until the gate is resolved)",
                        sessionId, liveForms, recoverySpent);
                return Mono.empty();
            }
        } else {
            // 闸门已解决（主编提交过 / 阶段推进后无挂起 HITL）：复位节流标记，
            // 使下一次真正的陈旧闸门仍可恢复。
            staleGateRecoveryAt.remove(sessionId);
        }

        // 关键修复：先把 inbox 里待办 hint 取出来，构造为 user 输入直接进入 modelInput。
        // InboxMiddleware 在 streamEvents 内会再次 drain，但届时队列已空，幂等无副作用。
        final String wakeupUser = resolvedUser;
        return messageBus.inboxDrain(sessionId, WAKEUP_INBOX_MAX)
                .onErrorResume(e -> {
                    log.warn("Wakeup: inbox drain failed for session {}: {}",
                            sessionId, e.getMessage());
                    return Mono.just(List.of());
                })
                .flatMap(entries -> {
                    // 节流标记只在真的发起恢复 run 时消费：inbox 为空说明这一轮什么都不会跑，
                    // 提前标记会让后续带 hint 的唤醒被误判为"已恢复过"而永久延后。
                    if (!entries.isEmpty() && staleRecovery.get()) {
                        staleGateRecoveryAt.put(sessionId, System.currentTimeMillis());
                    }
                    if (entries.isEmpty()) {
                        log.debug("Wakeup: inbox empty for session {}, skipping run", sessionId);
                        return Mono.<Msg>empty();
                    }
                    Msg seedMsg = buildInboxSeedMessage(entries);
                    log.info(
                            "Wakeup: replaying {} inbox hint(s) to session {} as user input",
                            entries.size(), sessionId);
                    // 服务端自发 run 统一经 {@link ServerSideRunLauncher} 发起（2026-09-20
                    // 收口）：run 级租约登记（进程死亡后 RunLeaseReconciler 可识别孤儿）与
                    // 收尾闸门双登记（本类此前的 doOnNext registerSuspendedGates 逻辑上移）
                    // 都由 launcher 统一保证，wakeup 不再自带专属收尾代码。
                    // 实时通知发布点（wakeup）：wakeup run 绕过 AG-UI 请求路径、事件
                    // 不落库（无 SSE 流），在真正发起续跑前给归属用户推一个游标信号，
                    // 前端据此触发转录同步。尽力而为：发布失败不影响唤醒 run 本身。
                    notifyPublisher.publish(wakeupUser, sessionId, resolvedAgentId, null,
                            SessionNotifyPublisher.REASON_WAKEUP);
                    // 首个事件之前的静默由 RunSilenceDetector 兜底（2026-09-17）：
                    // SessionEventClock 现在在 run 起跑时就登记条目，卡在首个事件之前的
                    // run 也能被探测器看到（20min 告警 / 60min 判死恢复）。此处刻意不再叠
                    // 加 Reactor timeout —— 框架的取消链在 BufferedReader 锁上会死锁
                    // （见 RunSilenceDetector 类注释与 JdkHttpTransport 修复记录），
                    // 超时能发出 onError 但取消不了上游，反而多一条悬空订阅。
                    return runLauncher.launch(
                                    resolvedAgent, List.of(seedMsg), rtc,
                                    resolvedAgentId, wakeupUser, sessionId)
                            .filter(e -> e instanceof AgentResultEvent)
                            .cast(AgentResultEvent.class)
                            .map(AgentResultEvent::getResult)
                            .last()
                            // 空完成（模型既无文本也无工具调用）等情况下 run 不产出
                            // AgentResultEvent，last() 会抛 NoSuchElementException 并把
                            // 整次唤醒记为失败（2026-09-11 09:21 实测：
                            // "wakeup run failed … Flux#last() didn't observe any onNext"）。
                            // hint 此时已被 drain，无法回放——按「唤醒无结果」降级为 WARN，
                            // 由 L3 停滞看护兜底（next_action=CONTINUE 时补醒）。
                            .onErrorResume(java.util.NoSuchElementException.class, e -> {
                                log.warn("Wakeup: run produced no result event for session {} "
                                        + "(hints already drained; the stall watchdog will re-check)",
                                        sessionId);
                                return Mono.empty();
                            });
                });
    }

    /** 平台智能体 ID 即 config UUID；非 UUID 时返回 null（对账按 agentId 取策略）。 */
    private static java.util.UUID parseAgentId(String agentId) {
        try {
            return agentId == null ? null : java.util.UUID.fromString(agentId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 本进程内该会话仍存活（前端已上报、可提交）的闸门表单数。
     *
     * <p>按 agent+session 归并而非三元组精确命中：上报方（前端控制器）与这里解析
     * userId 的路径不同，精确匹配可能把活闸门误判为陈旧闸门（见
     * {@link PendingFormInterruptStore#listBySession}）。</p>
     *
     * <p>必须再经 {@link WakeupInterruptRegistrar#filterLive} 与框架
     * {@code pendingInterruptsByThread} 对账：登记表只在 RUN_FINISHED(success) 时清空，
     * 闸门经 cancelled resume 被消费后旧条目会残留。原实现直接取登记表 size，一条
     * 永不过期的陈旧条目就让 liveForms 恒 &gt; 0，「陈旧闸门恢复」分支永不进入
     * （注释里记的 28 次 deferring 死锁正是此形态）。</p>
     */
    private int countLiveFormInterrupts(String agentId, String sessionId) {
        try {
            return wakeupInterruptRegistrar.filterLive(
                    sessionId, pendingFormInterruptStore.listBySession(agentId, sessionId)).size();
        } catch (RuntimeException e) {
            // 判别失败时保守处理：当作活闸门延后，宁可晚醒也不误放行挂起闸门。
            log.warn("Wakeup: form-interrupt lookup failed for session {}: {}", sessionId, e.getMessage());
            return 1;
        }
    }

    /**
     * 该会话是否处于「陈旧闸门恢复重放」的节流窗口内（距上次放行不足 TTL）。
     *
     * <p>没有记录 ⇒ 不在窗口内（可放行）。记录已过期 ⇒ 视为不在窗口内，
     * 允许再试一次——这正是与一次性语义的差别：闸门始终提交不上时，
     * 至少每 {@link #STALE_GATE_RECOVERY_TTL_MS} 仍有机会重试一次，
     * 而不会像一次性语义那样一次失败即永久锁死（2026-10-07 实证
     * main-3f077bd9 被挡 290 次共 9h40m）。</p>
     */
    private boolean isStaleGateRecoveryCoolingDown(String sessionId) {
        Long last = staleGateRecoveryAt.get(sessionId);
        if (last == null) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - last;
        if (elapsed >= STALE_GATE_RECOVERY_TTL_MS) {
            return false;
        }
        return true;
    }

    /**
     * 把 inbox 条目拼成一条 user 消息，作为模型本轮输入。
     *
     * <p>payload 形状与 {@code InboxMiddleware.deserializeHintBlock} 一致
     * （{@code {id, hint, source}}），保留 {@code source} 便于模型区分通知来源，
     * 文本直接渲染 hint 内容，与 InboxMiddleware 落到模型面前的形态等价。</p>
     */
    private Msg buildInboxSeedMessage(List<BusEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("[异步任务完成通知] 收到以下来自后台子智能体的完成事件，请据此继续推进流程：\n\n");
        for (BusEntry entry : entries) {
            Object hint = entry.payload().get("hint");
            if (hint == null) {
                continue;
            }
            Object source = entry.payload().get("source");
            if (source != null) {
                sb.append('[').append(source).append("] ");
            }
            sb.append(hint.toString()).append("\n\n");
        }
        return new UserMessage(sb.toString());
    }
}
