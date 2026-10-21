package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.config.ProgressWatchdogProperties;
import com.tipdm.agentplatform.domain.AgentSessionOwner;
import com.tipdm.agentplatform.domain.AgentSessionProgress;
import com.tipdm.agentplatform.harness.RunLeaseStore;
import com.tipdm.agentplatform.harness.ServerSideRunLauncher;
import com.tipdm.agentplatform.harness.SessionActivityTracker;
import com.tipdm.agentplatform.harness.WakeupInterruptRegistrar;
import com.tipdm.agentplatform.service.notify.AlertNotifier;
import com.tipdm.agentplatform.repository.AgentSessionProgressRepository;
import com.tipdm.agentplatform.config.AgentScopeWakeupConfig;
import com.tipdm.agentplatform.web.dto.RuntimeDtos;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * L3 会话进度台账 + 停滞看护（平台级，适用所有智能体）。
 *
 * <p>动机（2026-09-05/06 教材长流程实测）：父智能体可能把「子任务分批返回」误判为
 * 「仍在后台运行」→ 结束回合空等一个不会再来的通知，会话停摆（曾达 10 小时）。
 * 平台无法通用判定「父该不该自己继续」，但能基于两点做保守兜底：</p>
 * <ol>
 *   <li><b>台账</b>：周期扫描活跃会话转录尾文本，解析父自报 `[进度]` 行（AGENTS 约定）
 *       或按尾语关键词分类 next_action（CONTINUE/WAIT_HITL/DONE/IDLE/UNKNOWN）；</li>
 *   <li><b>停滞补醒</b>：idle 超过阈值（或近期存在纯文本收尾且宽限期已过）、inbox
 *       无待消费完成条目、且父回合当前不在运行时 → 补发一次自检推进（防抖 +
 *       次数封顶）；「是否真完成」由完成契约核验决定（V61：session-complete.json
 *       交付件指纹全部通过 = 确定性终态；立过计划的会话契约未核验即不算完成），
 *       无工作痕迹的纯问答会话回落文本 DONE 语义；WAIT_HITL（等主编闸门）与
 *       用户喊停（stop_requested）永不补醒。</li>
 * </ol>
 *
 * <p>分类与唤醒判据都是「保守优先」：宁可漏醒等人工，也不误唤醒打扰合法等待/主编流程。</p>
 */
@Service
public class SessionProgressService {

    private static final Logger log = LoggerFactory.getLogger(SessionProgressService.class);

    /**
     * 归档跳过日志的节流间隔（10 分钟）：扫描每 60s 一轮，逐轮打 INFO 会刷屏。
     *留痕的理由见 {@link #isArchived}——判据必须可观测，否则零命中是歧义状态。
     */
    private static final long ARCHIVED_LOG_THROTTLE_MILLIS = 10 * 60_000L;

    /** sessionId → 上次打「已归档跳过看护」日志的时刻（见 {@link #isArchived}）。 */
    private final ConcurrentHashMap<String, Long> archivedScanLogAt =
            new ConcurrentHashMap<>();

    public static final String NEXT_CONTINUE = "CONTINUE";
    public static final String NEXT_WAIT_HITL = "WAIT_HITL";
    public static final String NEXT_DONE = "DONE";
    public static final String NEXT_IDLE = "IDLE";
    public static final String NEXT_UNKNOWN = "UNKNOWN";

    private static final String KEEP_FILE = ".keep";
    private static final String INBOX_PREFIX = "agentscope_inbox_";

    // 2026-09-15 修正：原 `[^\n]*` 贪婪吞行导致三个可选捕获组永远空匹配——
    // 「固定状态字段优先」分支实际从未生效（被下方关键词启发式掩盖）。改懒惰
    // `[^\n]*?` 并给每组加 `.*?` 扫描前缀，阶段/下步/状态 三组均可稳定捕获。
    private static final Pattern PROGRESS_PATTERN = Pattern.compile(
            "\\[进度\\][^\\n]*?(?:.*?阶段=([^，,\\s]+))?(?:.*?下步=([^，,。\\s]+))?(?:.*?状态=([^，,。\\s]+))?");

    private static final String[] STALL_WORDS = {
            "请稍候", "仍在后台", "仍在运行", "后台运行中", "自动通知", "进行中", "等待自动唤醒"};

    /**
     * 停滞自检提示（注入为 user 消息驱动父自查）。
     * 前缀「[停滞自检]」与前端 transcriptSync.isAsyncNotifyMessage 的隐藏集合对齐，
     * 不会被渲染成主编发言。
     *
     * <p>2026-09-22 修正：原模板硬编码教材工作流措辞——「齐备则按状态机推进下一步
     * （调用对应子智能体或进入 👤闸门表单）」「主编」，被无差别注入所有停滞会话。
     * 实证（main-aa68617d）：普通「总结附件」会话里，模型本已正确判断在等用户输入，
     * 仍被该句推进去弹了 ui_gate「规范文档审核」闸门（随后 HITL 超时自动关闭）。
     * 改为工作流中性表述 + 明确守卫：没有正在推进的多阶段任务时禁止发起子智能体
     * 调用与闸门/表单；工作流智能体自身的阶段推进由其 AGENTS.md 定义承载，不靠
     * 本模板措辞驱动。</p>
     */
    /**
     * 自检提示的稳定前缀。<b>唯一真源</b>：模板文本与「这一轮是不是自检自答」的
     * 判定（{@link #isSelfCheckExchange}）都从这里取，避免两处字面量漂移。
     * 与前端 {@code transcriptSync.INTERNAL_USER_PREFIXES} 的「[停滞自检]」对齐。
     */
    static final String SELF_CHECK_PREFIX = "[停滞自检]";

    private static final String SELF_CHECK_TEXT = SELF_CHECK_PREFIX
            + " 平台检测到本会话长时间无新消息与产物更新，"
            + "且后台无待消费的完成通知。请立即自查并处理：\n"
            + "1) 用 task_list / task_output 核查是否仍有后台子任务及其终态；\n"
            + "2) 若后台任务已终态或不存在，检查当前任务的目标产物是否齐备：齐备则继续既定的下一步；"
            + "不齐则继续补齐；\n"
            + "3) 若本会话并没有正在推进的多阶段任务或工作流（例如只是普通问答、总结或附件解读），"
            + "不要调用子智能体、不要发起闸门/表单，直接结束回合；\n"
            + "4) 若实际正等待用户输入或闸门/表单已挂起，忽略本提示并结束回合。";

    private final SessionOwnerService sessionOwnerService;
    private final SessionProgressRepositoryFacade progressRepository;
    private final AgentAssemblyService assemblyService;
    private final SessionWorkspaceManager sessionWorkspaceManager;
    private final SessionActivityTracker activityTracker;
    private final ProgressWatchdogProperties watchdogProps;
    private final RunLeaseStore runLeaseStore;
    private final AlertNotifier alertNotifier;
    private final PendingFormInterruptStore pendingFormInterruptStore;
    private final WakeupInterruptRegistrar interruptRegistrar;
    private final ServerSideRunLauncher runLauncher;
    private final SessionKnowledgeService sessionKnowledgeService;
    /** 完成契约探针（3.13）：「是否真完成」的判定基准——产出核验，替代文本猜测。 */
    private final SessionCompletionContractService completionContract;
    /** sessionId → 上次「在跑但无新鲜租约」WARN 时刻。 */
    private final ConcurrentHashMap<String, Long> staleRunningWarnedAt =
            new ConcurrentHashMap<>();

    public SessionProgressService(
            SessionOwnerService sessionOwnerService,
            AgentSessionProgressRepository progressRepository,
            AgentAssemblyService assemblyService,
            SessionWorkspaceManager sessionWorkspaceManager,
            SessionActivityTracker activityTracker,
            ProgressWatchdogProperties watchdogProps,
            RunLeaseStore runLeaseStore,
            AlertNotifier alertNotifier,
            PendingFormInterruptStore pendingFormInterruptStore,
            WakeupInterruptRegistrar interruptRegistrar,
            ServerSideRunLauncher runLauncher,
            SessionKnowledgeService sessionKnowledgeService,
            SessionCompletionContractService completionContract) {
        this.sessionOwnerService = sessionOwnerService;
        this.progressRepository = new SessionProgressRepositoryFacade(progressRepository);
        this.assemblyService = assemblyService;
        this.sessionWorkspaceManager = sessionWorkspaceManager;
        this.activityTracker = activityTracker;
        this.watchdogProps = watchdogProps;
        this.runLeaseStore = runLeaseStore;
        this.alertNotifier = alertNotifier;
        this.pendingFormInterruptStore = pendingFormInterruptStore;
        this.interruptRegistrar = interruptRegistrar;
        this.runLauncher = runLauncher;
        this.sessionKnowledgeService = sessionKnowledgeService;
        this.completionContract = completionContract;
    }

    /**
     * 置位/清除「用户停止」标记（V60）。
     *
     * <p>置位：interrupt 端点成功受理后调用——此后该会话的一切自发 run 唤醒
     * （框架 wakeup 与本看护的自检注入）静默，直到用户主动发起新 run 清除。</p>
     *
     * <p>走 upsert 语义（无行时插入占位行）：「用户点停止」最容易发生在会话刚建立、
     * 看护首次扫描之前——台账此时无行，UPDATE-only 会静默丢掉标记（影响 0 行），
     * 于是停止意图完全失效，看护与三条唤醒通道照旧把会话拉起来续跑
     * （用户报障「停止不生效」）。语义与 {@link #markAwaitingHumanGate} 的封存一致。</p>
     */
    @Transactional
    public void markStopRequested(String agentId, String sessionId, String userId, boolean requested) {
        if (agentId == null || agentId.isBlank()
                || sessionId == null || sessionId.isBlank()) {
            return;
        }
        progressRepository.upsertStopRequested(UUID.randomUUID(), agentId, sessionId,
                userId == null || userId.isBlank() ? "anonymous" : userId, requested);
        log.info("Session stop_requested={} for session {} (agent {})",
                requested, sessionId, agentId);
    }

    /** 会话是否处于「用户已停止」抑制态。 */
    @Transactional(readOnly = true)
    public boolean isStopRequested(String sessionId) {
        return progressRepository.existsStopRequested(sessionId);
    }

    /**
     * 置位/清除「等人工唤醒」封存标记（V62）。
     *
     * <p>置位：HITL 审批超时被平台自动取消时调用——此后该会话的一切自发 run 唤醒
     * 源静默，直到用户主动发起新 run 清除。超时 ≠ 批准，平台不得代替主编推进。</p>
     *
     * <p>无台账记录时走 upsert 补占位行（{@link #markStopRequested} 的 UPDATE-only
     * 语义会静默丢标记）：闸门在前几秒就挂起、用户 30 分钟不理的短会话在看护首次
     * 扫描前台账尚无行，封存必须落上。</p>
     */
    @Transactional
    public void markAwaitingHumanGate(String agentId, String sessionId, String userId,
                                      boolean awaiting) {
        if (agentId == null || agentId.isBlank()
                || sessionId == null || sessionId.isBlank()) {
            return;
        }
        int updated = awaiting
                ? progressRepository.upsertAwaitingHumanGate(
                        UUID.randomUUID(), agentId, sessionId,
                        userId == null || userId.isBlank() ? "anonymous" : userId)
                : progressRepository.updateAwaitingHumanGate(
                        agentId, sessionId, false);
        if (updated > 0) {
            log.info("Session awaiting_human_gate={} for session {} (agent {})",
                    awaiting, sessionId, agentId);
        }
    }

    /** 会话是否处于「等人工唤醒」封存态（无台账记录 = 未封存）。 */
    @Transactional(readOnly = true)
    public boolean isAwaitingHumanGate(String sessionId) {
        return progressRepository.existsAwaitingHumanGate(sessionId);
    }

    /**
     * 会话是否处于「禁止一切自发唤醒」的封存态——两类封存任一成立即返回 true。
     *
     * <p><b>stop_requested（V60）</b>：用户 interrupt 过的会话，看护不得再注入自检
     * 消息强行续跑——否则「刚被停掉」被误判成「停滞」，注入「请自检」又把流水线
     * 整轮拉起（2026-09-29 实证：stop 后 2 分钟被看护 wake 1/3 拉起并继续真实工作
     * 1 小时）。清除只发生在用户发起新 run。</p>
     *
     * <p><b>awaiting_human_gate（V62）</b>：HITL 审批超时被平台自动取消后置位。
     * 超时 ≠ 批准，看护不得代替主编继续推进。两列语义不同（前者用户喊停、后者
     * 超时取消）故独立并存，解封都只发生在用户主动发起新 run
     * （PlatformAguiRuntimeContextResolver 入口）。</p>
     *
     * <p>无台账记录（会话从未被看护扫过）视为未封存，与唤醒侧口径一致。</p>
     */
    static boolean sealedByHuman(AgentSessionProgress existing) {
        return existing != null
                && (existing.isStopRequested() || existing.isAwaitingHumanGate());
    }

    /** 供单测/工具复用：对一段尾部文本做语义分类。 */
    public static NextActionDecision analyzeText(String tail) {
        if (tail == null || tail.isBlank()) {
            return new NextActionDecision(null, NEXT_UNKNOWN);
        }
        Matcher m = PROGRESS_PATTERN.matcher(tail);
        String phase = null;
        String step = null;
        String status = null;
        if (m.find()) {
            phase = m.group(1);
            step = m.group(2);
            status = m.group(3);
        }
        // 固定状态字段优先于自然语言启发式，避免“已完成但下步=继续...”造成误唤醒。
        if ("已完成".equals(status)) {
            return new NextActionDecision(phase, NEXT_DONE);
        }
        if ("等待人工".equals(status)) {
            return new NextActionDecision(phase, NEXT_WAIT_HITL);
        }
        if ("已阻塞".equals(status)) {
            return new NextActionDecision(phase, NEXT_IDLE);
        }
        if ("执行中".equals(status) || "部分完成".equals(status)) {
            return new NextActionDecision(phase, NEXT_CONTINUE);
        }
        if (step != null) {
            if (step.contains("闸门") || step.contains("等待") || step.contains("确认")) {
                return new NextActionDecision(phase, NEXT_WAIT_HITL);
            }
            if (step.startsWith("继续") || step.startsWith("调用") || step.contains("spawn")) {
                return new NextActionDecision(phase, NEXT_CONTINUE);
            }
            if (step.contains("完成") || step.contains("结束")) {
                return new NextActionDecision(phase, NEXT_DONE);
            }
        }
        String t = tail;
        if (t.contains("[完成]") || t.contains("定稿") || t.contains("📕")) {
            return new NextActionDecision(phase, NEXT_DONE);
        }
        // 后台任务等待语义优先于「闸门」字样（2026-09-07 盲区实证：教材会话尾文本
        // 「…仍在运行中…完成后统一推进主编闸门」——"闸门"只是未来计划式提法，实际
        // 在等后台内审任务，却因闸门关键词命中 WAIT_HITL → 永不补醒 → 死等 8h+）。
        // STALL 词（仍在运行/后台运行中/进行中…）表达「当前等系统/后台」→ CONTINUE；
        // 真正等主编的表述（提交闸门等确认、等待主编放行）不含 STALL 词，仍走下方闸门分支。
        for (String w : STALL_WORDS) {
            if (t.contains(w)) {
                return new NextActionDecision(phase, NEXT_CONTINUE);
            }
        }
        // 「无闸门未决」类否定表述（2026-09-15 实证：重启恢复自查尾文本「结论：无闸门
        // 未决、无重复派发…派 17e 前…」因含「闸门」二字且尾段无 ✅，被下方闸门分支
        // 误判 WAIT_HITL → 永不补醒，会话自 20:45 起死等）。智能体明确自述无待决闸门
        // 即无可等对象 → 按可推进处理；若实际仍在等主编，自检提示自带「等待则结束
        // 回合」指示，误醒代价为一次空转（wake-cooldown 兜底防刷）。
        if (t.contains("无闸门未决") || t.contains("无未决闸门") || t.contains("闸门无未决")
                || t.contains("无待决闸门") || t.contains("无挂起闸门")) {
            return new NextActionDecision(phase, NEXT_CONTINUE);
        }
        boolean hasOk = t.contains("✅");
        if (t.contains("GATE_WAIT") || (t.contains("闸门") && !hasOk)
                || (t.contains("主编") && (t.contains("表单") || t.contains("确认")) && !hasOk)) {
            return new NextActionDecision(phase, NEXT_WAIT_HITL);
        }
        return new NextActionDecision(phase, NEXT_UNKNOWN);
    }

    /**
     * 读取某会话进度台账（无记录返回缺省 UNKNOWN 视图）。
     *
     * <p>{@code stallNow}：next_action=CONTINUE 且 idle 超过阈值时为 true，供前端直接打徽标。
     * {@code hitlStuck}：会话状态残留「ASKING 且非表单/闸门类」的工具调用时为 true——此类
     * 权限确认（如后台子任务的 web_search）的应答无法经 resume 送达，用户在前端无任何
     * 入口解卡，只能调 recover 清除残留（2026-09-12 资料准备实测）。</p>
     */
    @Transactional(readOnly = true)
    public RuntimeDtos.SessionProgressResponse sessionProgress(
            String agentId, String sessionId) {
        var p = progressRepository.findByAgentIdAndSessionId(agentId, sessionId);
        if (p == null) {
            return new RuntimeDtos.SessionProgressResponse(
                    sessionId, null, NEXT_UNKNOWN, null, null, null, null, false, false);
        }
        boolean stallNow = NEXT_CONTINUE.equals(p.getNextAction())
                && p.getLastActiveAt() != null
                && Duration.between(p.getLastActiveAt(), Instant.now()).toMinutes()
                        >= watchdogProps.getStallMinMinutes();
        boolean hitlStuck = hasUnanswerableAsk(agentId, sessionId, p.getUserId());
        return new RuntimeDtos.SessionProgressResponse(
                sessionId, p.getPhase(), p.getNextAction(), p.getProgressLine(),
                p.getLastText(), p.getLastActiveAt(), p.getUpdatedAt(), stallNow, hitlStuck);
    }

    /** 表单/闸门类工具：ASKING 时前端有可交互卡片，不算卡死。 */
    private static final List<String> ANSWERABLE_HITL_TOOLS =
            List.of("ui_gate", "ui_form_advanced", "ui_form", "ui_card");

    /**
     * 检测会话状态是否残留「无法应答的权限确认」：最后一条 assistant 消息中存在
     * ASKING 状态且不属于表单/闸门类的 ToolUseBlock（语义与框架
     * {@code ReActAgent.askingToolCalls} 一致，排除可正常应答的 HITL 表单）。
     * 读不到状态一律视为未卡死（宁漏勿误报——误报会让用户 recover 掉正常会话）。
     */
    private boolean hasUnanswerableAsk(String agentId, String sessionId, String userId) {
        HarnessAgent agent;
        try {
            agent = assemblyService.requireAssembledById(agentId);
        } catch (RuntimeException e) {
            return false;
        }
        if (agent == null) {
            return false;
        }
        List<Msg> context;
        try {
            context = agent.getStateStore()
                    .get(userId, sessionId, "agent_state", AgentState.class)
                    .map(AgentState::getContext)
                    .filter(c -> c != null)
                    .orElse(List.of());
        } catch (RuntimeException e) {
            log.debug("Progress: hitlStuck state read failed {}: {}", sessionId, e.getMessage());
            return false;
        }
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg m = context.get(i);
            if (m.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            for (var block : m.getContent()) {
                if (block instanceof ToolUseBlock t
                        && t.getState() == ToolCallState.ASKING
                        // 不可变集合的 contains(null) 抛无消息 NPE，故先判空。
                        && t.getName() != null
                        && !ANSWERABLE_HITL_TOOLS.contains(t.getName())) {
                    return true;
                }
            }
            return false; // 只看最后一条 assistant 消息（与框架 askingToolCalls 同口径）
        }
        return false;
    }

    /**
     * 停滞看护主循环：扫描活跃会话 → 更新台账 → 判定是否补醒。
     *
     * <p><b>刻意不加 @Transactional</b>（2026-09-17）：本方法末尾会
     * {@code agent.streamEvents(...).subscribe(...)} 发起补醒 run，而 run 会写自己的
     * stateStore（另一个连接）。若整个方法处在一个事务里，run 与事务生命周期完全脱钩——
     * 它可能读到未提交的台账、也可能在事务回滚后仍在跑，且事务要等 run 的首次 DB 交互
     * 才结束（长事务占连接）。去掉方法级事务后每步落库各自短事务。</p>
     */
    public void scanAndGuard() {
        List<AgentSessionOwner> owners;
        try {
            owners = sessionOwnerService.recentActiveSince(
                    Instant.now().minus(Duration.ofHours(watchdogProps.getActiveWindowHours())));
        } catch (RuntimeException e) {
            log.warn("Progress scan: owners query failed: {}", e.getMessage());
            return;
        }
        for (AgentSessionOwner owner : owners) {
            try {
                scanOne(owner);
            } catch (RuntimeException e) {
                // 传异常对象而非 e.getMessage()：无消息 NPE 只会打成「: null」。
                log.warn("Progress scan failed for {}", owner.getSessionId(), e);
            }
        }
    }

/**
     * 会话是否已被用户「弃用」——归档或删除，二者都不看护、不补醒。
     *
     * <p>用户归档等于宣告这个会话不再需要平台自行推进。此前进藏只在前端列表侧生效
     * （{@code listSessions} 的 {@code includeArchived} 过滤），本看护仍会把它当停滞会话
     * 注入自检消息续跑（用户报障：弃用/删掉的会话重启后还在跑）。owner 行自带
     * {@code archivedAt} 与 {@code deletedAt}，无需额外查询；取消归档后本看护自动恢复。</p>
     *
     * <p><b>方法名沿用 {@code isArchived} 但语义已含删除</b>：两者的看护侧行为完全一致
     * （静默跳过），分开写只会让调用点多一处判断。读代码时以本Javadoc 为准。</p>
     *
     * <p><b>无条件留痕</b>：本判据每轮扫描都会命中大量已归档会话，若静默 return，
     * 则「被正确拦下」与「从未生效」在日志上无法区分——排查归档抑制是否起作用时
     * 零命中是歧义状态。故每次命中打一条 DEBUG（节流由日志级别承担，
     * 不加额外节流状态）。</p>
     */
    private boolean isArchived(AgentSessionOwner owner) {
        boolean archived = owner != null && owner.getArchivedAt() != null;
        // 已删除会话同样不看护、不补醒（2026-10-13）。墓碑过滤已在扫描源
        // （findByLastSeenAtGreaterThanEqual）做掉，这里是双保险：本看护是最积极的一条
        // 唤醒链路，一旦漏扫就会注入自检消息把已删会话整个拉起来跑。
        boolean deleted = owner != null && owner.isDeleted();
        if (archived || deleted) {
            // 节流到每会话 10 分钟一次：扫描每 60s 跑一次，逐轮打 INFO 会刷屏；
            // 但必须留痕——否则「被正确拦下」与「判据从未生效」在日志上零命中、
            // 无法区分（可观测性缺陷）。归档会话通常个位数，全量打也够用。
            String sessionId = owner.getSessionId();
            long now = System.currentTimeMillis();
            Long last = archivedScanLogAt.get(sessionId);
            if (last == null || now - last > ARCHIVED_LOG_THROTTLE_MILLIS) {
                archivedScanLogAt.put(sessionId, now);
                log.info("Progress scan: session {} is {} , skipping watchdog", sessionId,
                        deleted ? "deleted by user" : "archived (abandoned by user)");
            }
        }
        return archived || deleted;
    }

    private void scanOne(AgentSessionOwner owner) {
        String agentId = owner.getAgentId();
        String sessionId = owner.getSessionId();
        String userId = owner.getUserId();
        if (isArchived(owner)) {
            return;
        }
        if (activityTracker.isRunning(sessionId)) {
            // 在跑计数必须与新鲜租约互相印证（ServerSideRunLauncher 双登记，正常
            // 在跑的 run 租约心跳必然新鲜）。两者矛盾 = 计数泄漏（run 收尾 begin/end
            // 不配对）或非常规入口——不能因一个失真计数把空闲看护一票否决，继续
            // 走停滞判定（补醒误伤由 AG-UI 入口 409 互斥守卫兜底）。2026-09-29 实测：
            // 此处静默 return 会让停流会话对所有看护隐身。
            if (hasActiveLease(sessionId)) {
                return; // 真实在跑，不需要看护
            }
            logStaleRunningOnce(sessionId);
        }
        HarnessAgent agent = assemblyService.requireAssembledById(agentId);
        if (agent == null) {
            return; // 子智能体运行期 id 等不可装配的会话不处理
        }
        List<Msg> context;
        try {
            context = agent.getStateStore()
                    .get(userId, sessionId, "agent_state", AgentState.class)
                    .map(AgentState::getContext)
                    .filter(c -> c != null)
                    .orElse(List.of());
        } catch (RuntimeException e) {
            log.debug("Progress: state read failed {}: {}", sessionId, e.getMessage());
            return;
        }
        // 提取「尾文本」：最后一条含文本块的消息（assistant/user 均可，工具行不算）。
        String tailText = "";
        Instant lastActive = parseTs(lastMessageTimestamp(context));
        for (int i = context.size() - 1; i >= 0 && tailText.isEmpty(); i--) {
            Msg m = context.get(i);
            for (var block : m.getContent()) {
                if (block instanceof TextBlock tb && tb.getText() != null && !tb.getText().isBlank()) {
                    tailText = tb.getText();
                    break;
                }
            }
        }
        if (tailText.isBlank() && context.isEmpty()) {
            return;
        }
        String clipped = tailText.length() > 1900 ? tailText.substring(0, 1900) : tailText;
        NextActionDecision decision = analyzeText(clipped);
        Instant activeAt = lastActive != null ? lastActive : Instant.now();

        // ── 判据：平台自有事实（V40 真修复）──────────────────────────────────
        // 旧判据 `decision.action == CONTINUE` 把「该不该继续」押在模型自述上：
        // 模型不按 AGENTS 约定输出 [进度] 行 → UNKNOWN → 既不补醒也不告警 →
        // 会话静默空转（教材会话 2026-09-16 实证 7 小时）。现在改为四个平台
        // 能自己查到的硬信号，文本分类只用于展示与告警文案，不再参与决策。
        AgentSessionProgress existing = progressRepository.findByAgentIdAndSessionId(agentId, sessionId);
        boolean idleLong = Duration.between(activeAt, Instant.now()).toMinutes()
                >= watchdogProps.getStallMinMinutes();
        boolean pendingInbox = inboxHasPending(sessionId);
        boolean waitingHuman = waitingForHuman(agentId, sessionId, context);
        boolean activeElsewhere = hasActiveLease(sessionId);

        // 完成契约（3.13）：「是否真完成」由产出核验判定，见 {@link #genuinelyFinished}。
        SessionCompletionContractService.ContractCheck contract =
                completionContract.check(agentId, sessionId);
        boolean workEvidence = contract.workEvidence();
        // 后台任务台账（框架写，不经模型）：有派发过的子任务 ⇒ 确有多阶段工作。
        // 教材流水线的会话即使 plan/ 被清也会被这一项兜住，纯问答会话则恒 false。
        // 两者合起来即「有没有活要干」这个根本判据，见 {@link #hasPendingWork}。
        boolean taskLedger = hasTaskLedger(agentId, sessionId);
        boolean hasWork = workEvidence || taskLedger;
        boolean finished = genuinelyFinished(agentId, sessionId, decision, contract);

        // 进展核算：只有真变化才算进展；「自检自答」这种平台自问自答的伪变化
        // 不算（治本 A，见 progressedForWake）。否则计数永远归零、封顶形同虚设。
        boolean progressed = progressedForWake(existing, activeAt, clipped, context);
        // 「正在等人类/等通知/别处有 run」属合法等待，不消耗补醒预算。
        boolean legitWait = waitingHuman || pendingInbox || activeElsewhere;
        int baseWakeCount = computeWakeCount(existing, progressed, false);
        boolean wakeCapped = baseWakeCount >= Math.max(watchdogProps.getMaxWakeAttempts(), 1);

        // 「用户喊停」/「HITL 超时后等人工唤醒」两类封存：任一置位即禁止补醒。
        boolean sealedByHuman = sealedByHuman(existing);

        // 纯文本收尾快速通道（V61）：宣告式收尾检测器登记过「run 以纯文本收场」
        // 且宽限期已过、其后无任何新消息 ⇒ 3 分钟即可补醒，不等满停滞阈值
        // （宣告不执行的轮次占历史轮次约四成，每轮省 5 分钟墙钟）。用户在宽限期内
        // 回复 ⇒ last_active_at 晚于登记时间 ⇒ 快速通道失效，不打断正常对话节奏。
        boolean textEndingStale = textEndingStale(existing, activeAt, hasWork);
        boolean dueToStall = idleLong || textEndingStale;

        boolean shouldWake = shouldWake(hasWork, dueToStall, finished, waitingHuman,
                pendingInbox, activeElsewhere, wakeCapped, canWakeAgain(existing),
                sealedByHuman);

        boolean needsHuman = existing != null && existing.isNeedsHuman();
        if (wakeCapped && dueToStall && !finished && !legitWait) {
            needsHuman = true; // 停止自愈，转人工
        }
        // 无活要干的会话从来不该补醒，若台账里残留 needsHuman（此前几轮偷跑期间
        // 置上的），本次必须清掉：否则「会话需要人工介入：已补醒 3 次仍无进展」
        // 这条 error 告警会一直推给用户，而实际上没有补醒、也没有进展要补。
        if (!hasWork && !waitingHuman) {
            needsHuman = false;
        }
        int wakeCount = computeWakeCount(existing, progressed, shouldWake);
        Instant lastProgressAt = (progressed || existing == null)
                ? Instant.now()
                : (existing.getLastProgressAt() == null ? activeAt : existing.getLastProgressAt());

        progressRepository.upsert(
                UUID.randomUUID(), agentId, sessionId, userId,
                decision.phase, decision.action, extractProgressLine(clipped), clipped,
                activeAt, shouldWake ? Instant.now() : (existing != null ? existing.getLastStallWakeAt() : null),
                wakeCount, lastProgressAt, needsHuman);

        if (shouldWake) {
            injectSelfCheck(agent, agentId, userId, sessionId, contract, workEvidence, wakeCount);
        }
        // 无论是否补醒，都要把「会话停住了」这件事告诉人：补醒只覆盖 CONTINUE，
        // UNKNOWN/IDLE/WAIT_HITL 的会话一旦停住，平台此前没有任何机制让人知道。
        // 但「无活要干」的会话停住是正常状态（用户问完就走了），不是停摆，
        // 推 error 告警属于误报——2026-10-13 实测该会话被推了 14 轮
        // 「已空闲 N 分钟，可能已停摆」，而它本来就无事可做。
        if (hasWork || waitingHuman) {
            maybeAlertStall(agentId, sessionId, userId, decision, activeAt, wakeCount,
                    needsHuman, waitingHuman, finished);
        }
    }

    /**
     * 补醒放行判据（包可见静态实现，便于单测逐条锁住每个否定项）。
     *
     * <p><b>{@code hasWork} 排在第一位且不可绕过</b>（2026-10-13 根本修复）：
     * 「本会话有没有活要干」是补醒的前提，而不是与其它条件平级的某一项。
     * 此前这个前提根本不存在，判据只问「是不是明确说完了」与「停了多久」，
     * 于是纯问答会话被无限追问。详见 {@link #hasTaskLedger}。</p>
     *
     * <p>其余七项各自独立、缺一不可：完成契约已达���（真完成不必催）、
     * 等人类（球在人手里）、inbox 待消费（通知在路上，InboxWatchdog 会兜底）、
     * 别处有 run（并发互斥）、达封顶（停止自愈转人工）、冷却未过（防高频烧 token）、
     * 人工封存（用户喊停 / HITL 超时后等人工唤醒）。</p>
     */
    static boolean shouldWake(boolean hasWork, boolean dueToStall, boolean finished,
                              boolean waitingHuman, boolean pendingInbox,
                              boolean activeElsewhere, boolean wakeCapped,
                              boolean cooldownOk, boolean sealedByHuman) {
        return hasWork && dueToStall && !finished && !waitingHuman && !pendingInbox
                && !activeElsewhere && !wakeCapped && cooldownOk && !sealedByHuman;
    }

    /**
     * 停滞自检注入（服务端自发 run 的第三个入口）。
     *
     * <p>实测：空 inbox 时 enqueueWakeup 只会让 runWakeup drain 到空 → 直接 skip，
     * 父根本不被驱动（2026-09-06 14:58 补醒无效复现）。因此改为直接注入一条
     * 「停滞自检」user 消息（前缀与前端隐藏规则对齐，不冒充主编），让父真正跑
     * 一轮 task_list/task_output 自查并推进。立过计划的会话（workEvidence）
     * 提示词带契约核验明细：落标记或继续干活，两个分支都是可验证动作。</p>
     *
     * <p>run 统一经 {@link ServerSideRunLauncher} 发起（2026-09-20 收口）：
     * 此前直接 streamEvents().subscribe()，run 若以 ui_gate 挂起收尾，框架与
     * 平台登记表均无该中断——reaper 扫不到（30 分钟超时取消失效）、本服务下轮
     * 也判「无 pending HITL」继续注入自检，会话陷入重渲染死循环
     * （main-78d39f79 实证 16:16–17:47 四轮）。</p>
     */
    private void injectSelfCheck(HarnessAgent agent, String agentId, String userId,
                                 String sessionId,
                                 SessionCompletionContractService.ContractCheck contract,
                                 boolean workEvidence, int wakeCount) {
        log.warn("Progress watchdog: session {} stalled (contract={}, workEvidence={}, "
                        + "failures={}) — injecting self-check (agent={}, user={}, wake {}/{})",
                sessionId, contract.status(), workEvidence, contract.failures(),
                agentId, userId, wakeCount, watchdogProps.getMaxWakeAttempts());
        Msg selfCheck = new UserMessage(workEvidence && !contract.verified()
                ? contractHintText(contract)
                : SELF_CHECK_TEXT);
        RuntimeContext rtc = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                // 自检 run 不经过 AG-UI resolver：补注入 SessionKnowledgeRef，
                // 否则恢复轮的 knowledge_search 会判定「未关联知识库」。
                .put(SessionKnowledgeRef.class,
                        sessionKnowledgeService.refFor(agentId, sessionId))
                .build();
        runLauncher.launch(agent, List.of(selfCheck), rtc, agentId, userId, sessionId)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        ev -> { /* run 产出的消息由 state store 自行持久化 */ },
                        err -> log.warn("Progress self-check run failed for session {}: {}",
                                sessionId, err.getMessage()));
    }

    /**
     * 完成契约（3.13）：「是否真完成」的判定基准从文本分类换成产出核验。
     * VERIFIED = 智能体落件了 session-complete.json 且全部交付件指纹核验通过
     * ⇒ 确定性终态；立过计划（workEvidence）的会话契约未核验通过 ⇒ 无论文本
     * 怎么说都不算完成（「宣告不执行」与「谎报完成」一起堵死）；无工作痕迹的
     * 纯问答会话回落文本 DONE 语义，与历史行为一致。
     *
     * <p>契约声明的是某一阶段的交付集，长流水线会话在契约通过后仍要继续做后续
     * 阶段，故闩锁不能永久化：契约落地后若又派发过新子任务
     * （{@link SessionCompletionContractService#supersededByNewWork}），说明它并非
     * 会话终态，闩锁失效、恢复停滞补醒。</p>
     */
    private boolean genuinelyFinished(String agentId, String sessionId,
                                      NextActionDecision decision,
                                      SessionCompletionContractService.ContractCheck contract) {
        if (NEXT_DONE.equals(decision.action) && !contract.workEvidence()) {
            return true;
        }
        if (!contract.verified()) {
            return false;
        }
        return !completionContract.supersededByNewWork(agentId, sessionId);
    }

    /**
     * 纯文本收尾快速通道（V61）：宣告式收尾检测器登记过「run 以纯文本收场」
     * 且宽限期已过、其后无任何新消息 ⇒ 提前补醒，不等满停滞阈值。用户在宽限期内
     * 回复 ⇒ last_active_at 晚于登记时间 ⇒ 快速通道失效，不打断正常对话节奏。
     *
     * <p>入参是 {@code hasWork}（有无活要干，见 {@link #hasPendingWork}）而非
     * {@code workEvidence}：没有多阶段工作清单就没有「宣告了要干活却没干」可言，
     * 纯问答会话的纯文本收尾是<b>答完了</b>，不该 3 分钟后被追问「你怎么不干活」。</p>
     */
    private boolean textEndingStale(AgentSessionProgress existing, Instant activeAt,
                                   boolean hasWork) {
        return textEndingStale(existing, activeAt, hasWork,
                watchdogProps.getTextEndingGraceMinutes(), Instant.now());
    }

    /** 包可见静态实现（供单测复用），语义见上方实例方法注释。 */
    static boolean textEndingStale(AgentSessionProgress existing, Instant activeAt,
                                   boolean hasWork, long graceMinutes, Instant now) {
        if (!hasWork || existing == null || existing.getLastTextEndingAt() == null) {
            return false;
        }
        long endingAgeMinutes = Duration.between(existing.getLastTextEndingAt(), now).toMinutes();
        boolean noActivitySince = activeAt == null
                || !activeAt.isAfter(existing.getLastTextEndingAt().plusSeconds(5));
        return noActivitySince && endingAgeMinutes >= Math.max(graceMinutes, 0);
    }

    /** 立过计划的会话的补醒提示词：带契约核验失败明细，两个出口都是可验证动作。 */
    private static String contractHintText(SessionCompletionContractService.ContractCheck contract) {
        StringBuilder hint = new StringBuilder(
                SELF_CHECK_PREFIX + " 平台检测到本会话已立工作计划，但完成契约未核验通过：\n");
        for (String failure : contract.failures()) {
            hint.append("- ").append(failure).append('\n');
        }
        hint.append("请二选一：\n")
                .append("1) 若工作确已全部完成：在会话工作区落件 session-complete.json")
                .append("（含 summary 与 deliverables 逐项 path/sha256），完成后结束回合；\n")
                .append("2) 否则继续执行未完成的动作。\n")
                .append("若实际正等待用户输入或闸门/表单已挂起，忽略本提示并结束回合。");
        return hint.toString();
    }

    /**
     * 停滞告知（只报不治）：会话空闲超阈值且未被判定为已完成 → 推一条告警给用户。
     *
     * <p><b>为什么单独做</b>：自动补醒的条件是 {@code next_action=CONTINUE}，
     * 而 CONTINUE 又依赖模型自报的 {@code [进度]} 行或尾语关键词。模型不守约时
     * 判为 UNKNOWN → 不补醒 → 会话静默空转，用户完全无从知晓（教材会话
     * 2026-09-16 实证：连续空转 3.5 小时才被人工发现）。告警把「平台的无能为力」
     * 转成「人的可操作信息」，不做任何自愈动作，也就不会误伤合法等待。</p>
     *
     * <p><b>HITL 等待是独立类型</b>：会话有挂起表单/确认（ASKING 或活闸门登记）
     * 时，智能体不是「停摆」而是在等人工输入——动作项明确（去把表单填了），
     * 因此拆成独立去重键 {@code hitl-wait:{sessionId}}、level=warn、阈值更低
     * （默认 3 分钟），与停滞告警（{@code stall:{sessionId}}，error 级，30 分钟）
     * 互不抑制。复用 {@link AlertNotifier} 的未读抑制去重，可每轮无条件调用。</p>
     */
    void maybeAlertStall(String agentId, String sessionId, String userId,
                         NextActionDecision decision, Instant activeAt,
                         int wakeCount, boolean needsHuman, boolean waitingHuman,
                         boolean finished) {
        if (finished) {
            return; // 真完成收尾（契约 VERIFIED 或无工作痕迹的文本 DONE），不打扰
        }
        long idleMinutes = Duration.between(activeAt, Instant.now()).toMinutes();
        // 独立 HITL 等待告知：优先于一切停滞判定——会话在等输入时用户需要的
        // 是「去处理表单」这个动作项，而不是「会话可能停摆」的模糊告警。
        if (waitingHuman) {
            if (idleMinutes < watchdogProps.getHitlAlertMinMinutes()) {
                return;
            }
            alertNotifier.publish(new AlertNotifier.AlertRequest(
                    AlertNotifier.LEVEL_WARN,
                    String.format("智能体在等你输入（已等待 %d 分钟）", idleMinutes),
                    "会话存在挂起的交互（表单/确认待处理），智能体暂停等待人工输入。\n"
                            + "请打开会话处理挂起的表单或确认，处理后智能体会自动继续运行。",
                    userId, sessionId, agentId, "hitl-wait:" + sessionId));
            return;
        }
        if (idleMinutes < watchdogProps.getStallAlertMinMinutes()) {
            return;
        }
        // 达补醒上限后停止自愈，必须明确告诉人「平台做不了了」（去重键与停滞告警
        // 不同，避免两条通知互相抑制：未读抑制是同 key 才生效）。
        if (needsHuman) {
            alertNotifier.publish(new AlertNotifier.AlertRequest(
                    AlertNotifier.LEVEL_ERROR,
                    String.format("会话需要人工介入：已补醒 %d 次仍无进展", wakeCount),
                    "平台已自动补醒 " + wakeCount + " 次（上限 "
                            + watchdogProps.getMaxWakeAttempts() + " 次），会话仍无新消息或产物更新，"
                            + "已停止自动补醒以免继续空转烧 token。\n"
                            + "请打开会话确认状态：直接补一条指令，或手动清理残留任务后重跑。",
                    userId, sessionId, agentId, "needs-human:" + sessionId));
            return;
        }
        String title = String.format("会话已空闲 %d 分钟，可能已停摆", idleMinutes);
        StringBuilder body = new StringBuilder();
        body.append("智能体下一步判定：").append(describeAction(decision.action)).append('\n');
        if (decision.phase != null && !decision.phase.isBlank()) {
            body.append("阶段：").append(decision.phase).append('\n');
        }
        body.append("平台自动补醒：")
                .append(NEXT_CONTINUE.equals(decision.action) ? "已触发" : "未触发（无法判定应继续）")
                .append("（已补醒 ").append(wakeCount).append('/')
                .append(watchdogProps.getMaxWakeAttempts()).append(" 次）\n");
        // 等人工输入的场景已在上方 hitl-wait 分支提前返回，走到这里的一律是
        // 「平台无法判定该会话应自行继续」的停滞，固定 error 级。
        body.append("平台无法判定该会话应自行继续，可能需要人工接手：打开会话查看进度，或直接补一条指令。");
        alertNotifier.publish(new AlertNotifier.AlertRequest(
                AlertNotifier.LEVEL_ERROR,
                title, body.toString(), userId, sessionId, agentId, "stall:" + sessionId));
    }

    private String describeAction(String action) {
        return switch (action) {
            case NEXT_CONTINUE -> "应继续推进";
            case NEXT_WAIT_HITL -> "等待人工/闸门";
            case NEXT_DONE -> "已完成";
            case NEXT_IDLE -> "已阻塞";
            default -> "无法判定（模型未按要求自报进度）";
        };
    }

    /**
     * 补醒计数递进（包可见，便于单测）。
     *
     * <p>规则：只有<b>有实质进展</b>才从 0 起算，否则在既有计数上累加。
     * 「补醒」这个动作本身不算进展——否则每轮都归零，封顶永远触发不了，会话会
     * 被无限补醒（正是判据放开后必须防住的退化形态）。</p>
     *
     * <p><b>合法等待不清零</b>（2026-10-13 修正）。此前「等人类/等 inbox/别处有 run」
     * 会把计数重置为 0，理由是「这些状态说明会话换了阶段」。实测该理由不成立，且
     * 危害具体：自检 run 自己会在 {@link ServerSideRunLauncher} 登记租约，而租约
     * 在收尾后仍新鲜约 5 分钟（{@code RunLeaseReconciler} 满 2 分钟才删、
     * {@code existsFreshBySessionId} 按 5 分钟窗口判新鲜）⇒ 自检跑完后的下一轮扫描
     * {@code activeElsewhere=true} ⇒ 计数被清零 ⇒ 15 分钟冷却后又是
     * {@code wake 1/3} ⇒ 封顶<b>永不触发</b>、{@code needs_human} 永不置位 ⇒
     * 会话被无限补醒，而模型每轮都在回「没活了，别叫我」。</p>
     *
     * <p>更本质的一点：{@code legitWait} 的三个分量全都已经是 {@code shouldWake}
     * 的否定项，因此「合法等待期间不该消耗预算」由「不补醒」天然成立，
     * 清零纯属额外且有害的副作用。这三种状态都是<b>别的子系统的瞬时状态</b>
     * （主编在别处、通知在路上、别处有 run），不构成会话取得进展的证据。</p>
     */
    static int computeWakeCount(AgentSessionProgress existing, boolean progressed,
                                boolean shouldWake) {
        int base = (existing == null || progressed) ? 0 : existing.getWakeCount();
        return shouldWake ? base + 1 : base;
    }

    /**
     * 本轮是否算「有进展」（决定 {@link #computeWakeCount} 是否清零计数）。
     *
     * <p>基础判据：台账无行、首次活跃、或尾文本/活跃时间变化 ⇒ 算进展。</p>
     *
     * <p><b>治本 A（2026-10-09）：「自检自答」不算进展</b>。基础判据把「尾文本变化」
     * 当进展，而补醒注入的自检消息必然让模型产出一条新的 assistant 回复（内容还随轮次
     * 微调，如「自查结果」→「自查结论（状态未变化）」）⇒ 每轮都算进展 ⇒
     * {@code wake_count} 每轮归零 ⇒ 有界封顶<b>永不触发</b>、{@code needs_human}
     * 永不置位 ⇒ 会话被按冷却周期无限补醒，而模型每轮都在回「没活了，别叫我」。</p>
     *
     * <p>实证 main-c7b25b4d：15:09 / 15:24 / 15:39 / 15:54 四轮全是 {@code wake 1/3}，
     * 模型每轮原话「无后台任务、无进行中阶段流程、无待补齐产物、按自检项 3/4 结束」——
     * 模型已在明确拒绝工作，平台仍每 15 分钟问一次。</p>
     */
    static boolean progressedForWake(AgentSessionProgress existing, Instant activeAt,
                                     String clipped, List<Msg> context) {
        boolean changed = existing == null
                || existing.getLastActiveAt() == null
                || activeAt.isAfter(existing.getLastActiveAt())
                || !Objects.equals(existing.getLastText(), clipped);
        if (!changed) {
            return false;
        }
        // 变化是真的，但若它只来自「平台注入自检 → 模型回答自检」这个闭环，
        // 判为无进展，让计数累加直到封顶并转人工。
        return !isSelfCheckExchange(context);
    }

    /**
     * 本轮上下文里是否<b>只有</b>「平台注入自检 → 模型回答自检」这一个来回。
     *
     * <p>判据取<b>最后一条 user 消息</b>：若它是平台注入的自检提示（{@link #SELF_CHECK_PREFIX}
     * 前缀），那么此后模型产出的 assistant 回复是对自检的回答，不是主编的新指令 ⇒
     * 本轮没有真实工作推进。</p>
     *
     * <p><b>为什么只看最后一条 user</b>：自检注入总是作为新 user 消息追加在末尾，
     * 主编在此之后若有真实发言，必然排在它后面 ⇒ 判据自动失效、封顶计数恢复累积。
     * 这正是「模型连说四轮别叫我」与「主编真的来了」两种情形必须区别对待的分界
     *（若只看「历史上出现过自检」，就会把真实工作也误判成自答、会话真停滞时
     * 反而不再被唤醒）。</p>
     *
     * <p><b>为什么不看「有没有工具调用」</b>：模型在自检轮里也可能调 {@code task_list}
     * （该会话 15:39 那轮就调了），但那是自检动作、不改变会话是否需要被叫醒。
     * 真正的判据是「这句话是谁说的」——自检是平台问的，不是主编要工作。</p>
     *
     * <p>上下文为空或读不到 user 消息（刚建会话的首次扫描）时返回 false，保持原行为。</p>
     */
    static boolean isSelfCheckExchange(List<Msg> context) {
        if (context == null || context.isEmpty()) {
            return false;
        }
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg m = context.get(i);
            if (m.getRole() != MsgRole.USER) {
                continue;
            }
            String text = visibleTextOf(m);
            boolean hit = text != null && text.startsWith(SELF_CHECK_PREFIX);
            // 诊断留痕：判据没命中时必须能看见「最后一条 user 到底是什么」，
            // 否则零命中又变成「无法区分被正确拦下 与 判据从未生效」的歧义状态。
            if (!hit) {
                log.warn("Progress: last user msg is NOT self-check (len={}, head={}) — "
                                + "wake budget will NOT accumulate this round",
                        text == null ? -1 : text.length(),
                        text == null ? "<null>" : text.substring(0, Math.min(40, text.length())));
            }
            return hit;
        }
        log.warn("Progress: no USER message in context (size={}) — wake budget will NOT accumulate",
                context.size());
        return false;
    }

    /** 取消息里第一个非空文本块（自检提示是纯文本 user 消息）。 */
    private static String visibleTextOf(Msg m) {
        if (m == null || m.getContent() == null) {
            return null;
        }
        for (var block : m.getContent()) {
            if (block instanceof TextBlock tb && tb.getText() != null && !tb.getText().isBlank()) {
                return tb.getText().strip();
            }
        }
        return null;
    }

    /**
     * 【平台事实 1】会话是否真的在等人类：最后一条 assistant 消息里有 ASKING 态
     * 工具调用、平台登记表里仍有活闸门，或最后一个 HITL 闸门之后主编再无真实发言。
     *
     * <p>这是「在等人工」的权威信号，取代旧的「尾文本含闸门二字」猜法——后者
     * 双向误判：既把「无闸门未决」误判成等待（2026-09-15 实证死等），也把
     * 「闸门已渲染但登记表为空」误判成不在等。</p>
     *
     * <p>读不到状态一律返回 false（宁可多补醒一次，也不要把真等待的会话锁死；
     * 补醒提示自带「若正在等待请忽略」指令）。</p>
     */
    private boolean waitingForHuman(String agentId, String sessionId, List<Msg> context) {
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg m = context.get(i);
            if (m.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            for (var block : m.getContent()) {
                if (block instanceof ToolUseBlock t && t.getState() == ToolCallState.ASKING) {
                    return true;
                }
            }
            break; // 只看最后一条 assistant 消息（与框架 askingToolCalls 同口径）
        }
        try {
            List<PendingFormInterruptStore.PendingFormInterrupt> entries =
                    pendingFormInterruptStore.listBySession(agentId, sessionId);
            if (entries != null && !entries.isEmpty()) {
                // 登记表是权威事实源：先把条目回灌进框架 pendingInterruptsByThread
                //（幂等修复，框架映射会被自身 trackPendingInterrupts 的整体 put 抹掉），
                // 再判「在等人工」。若只查不修，漂移窗口内会误判「不在等」（2026-10-05
                // 20:14 实测 awaiting_human_gate=false，而闸门实际挂着）。
                interruptRegistrar.ensureFrameworkRegistration(sessionId, entries);
                return true;
            }
        } catch (RuntimeException e) {
            log.debug("Progress: pending HITL lookup failed {}: {}", sessionId, e.getMessage());
        }
        // 【平台事实 3】闸门后主编再无真实发言（含"挂起但登记缺失""刚被超时取消"
        // 两种此前漏判形态）——球在主编手里，见 {@link #lastHITLGateAwaitingHuman}。
        return lastHITLGateAwaitingHuman(context);
    }

    /** HITL 闸门类工具（与 HitlRecoveryGuardMiddleware.HITL_TOOLS 同口径；跨包复制）。 */
    private static final List<String> HITL_GATE_TOOLS = List.of("ui_gate", "ui_form_advanced");

    /**
     * 平台注入消息前缀集合：这些 user 角色消息由平台/框架生成，不代表主编真实发言。
     * 与前端 transcriptSync 隐藏集合、HitlRecoveryGuard 恢复指示、reaper 留痕消息、
     * wakeup 种子消息的前缀对齐。
     */
    static final String[] PLATFORM_INJECTED_PREFIXES = {
            "[停滞自检]", "[平台留痕]", "[HITL_RECOVERY]", "[异步任务完成通知]"};

    /**
     * 【平台事实 3】会话的决策权在主编手里且主编尚未回来：最后一个 HITL 闸门
     * 未获 SUCCESS 提交结果（挂起/中断占位），或其 SUCCESS 结果是平台超时取消
     * （{@code [HITL_TIMEOUT]}，非主编决定），且闸门之后没有任何主编真实发言
     * （平台注入的系统消息不算）。
     *
     * <p>覆盖两个此前漏判形态（2026-09-20 main-78d39f79 实证 16:16–17:47 四轮
     * 死循环）：</p>
     * <ol>
     *   <li>闸门挂起但登记缺失（服务端自发 run 渲染且收尾未登记）——ASKING 检查
     *       摸不到（挂起的 ui_gate tool_use 是 RUNNING 非 ASKING）、登记表为空，
     *       watchdog 判"无 pending HITL"反复注入自检，模型反复重渲染闸门；</li>
     *   <li>闸门刚被超时取消——"SUCCESS=已决"口径使等待判定短暂为 false，下一轮
     *       补醒驱动模型重渲染闸门，形成「取消→自检→重渲染→再取消」的慢循环。</li>
     * </ol>
     *
     * <p>语义：主编未表过态时，补醒驱动模型重渲染表单并不能产生主编的决定，只会
     * 烧 token；平台该做的是 hitl-wait 告警提醒人（{@link #maybeAlertStall} 已覆盖）。
     * 主编回来后任意真实发言（含表单提交）即解除该状态。</p>
     *
     * <p>包可见供单测。闸门已获主编真实提交（resume 回填的 SUCCESS 结果或表单
     * 提交消息）时返回 false，不抑制合法推进。</p>
     */
    static boolean lastHITLGateAwaitingHuman(List<Msg> context) {
        int lastGateIndex = -1;
        String lastGateToolCallId = null;
        for (int i = context.size() - 1; i >= 0 && lastGateIndex < 0; i--) {
            Msg m = context.get(i);
            if (m.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            for (var block : m.getContent()) {
                if (block instanceof ToolUseBlock t
                        && t.getId() != null
                        // 不可变集合的 contains(null) 抛无消息 NPE，故先判空。
                        && t.getName() != null
                        && HITL_GATE_TOOLS.contains(t.getName())) {
                    lastGateIndex = i;
                    lastGateToolCallId = t.getId();
                    break;
                }
            }
        }
        if (lastGateIndex < 0) {
            return false;
        }
        // 闸门结果状态：SUCCESS=主编已提交（resume 回填）；SUCCESS 但文本为
        // [HITL_TIMEOUT] 是平台超时取消，不算主编决定，落到下方"主编是否回来"扫描。
        if (lastGateToolCallId != null) {
            outer:
            for (int i = lastGateIndex; i < context.size(); i++) {
                Msg m = context.get(i);
                for (ToolResultBlock r : m.getContentBlocks(ToolResultBlock.class)) {
                    if (!lastGateToolCallId.equals(r.getId())
                            || r.getState() != ToolResultState.SUCCESS) {
                        continue;
                    }
                    if (!isTimeoutCancellationResult(r)) {
                        return false; // 主编真实提交过（resume 回填的 SUCCESS 结果）
                    }
                    break outer; // 平台超时取消 → 非主编决定，继续判定
                }
            }
        }
        // 未决或已被超时取消：闸门之后主编再无真实发言 → 球在主编手里。
        for (int i = lastGateIndex + 1; i < context.size(); i++) {
            Msg m = context.get(i);
            if (m.getRole() != MsgRole.USER) {
                continue;
            }
            String text = m.getTextContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            String trimmed = text.stripLeading();
            boolean injected = false;
            for (String prefix : PLATFORM_INJECTED_PREFIXES) {
                if (trimmed.startsWith(prefix)) {
                    injected = true;
                    break;
                }
            }
            if (!injected) {
                return false; // 主编回来过（真实发言/表单提交）→ 正常推进判定
            }
        }
        return true;
    }

    /** SUCCESS 结果是否为平台超时取消（reaper 以 [HITL_TIMEOUT] 前缀落结果文本）。 */
    private static boolean isTimeoutCancellationResult(ToolResultBlock result) {
        for (var block : result.getOutput()) {
            if (block instanceof TextBlock tb && tb.getText() != null
                    && tb.getText().startsWith("[HITL_TIMEOUT]")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 【平台事实 2】本会话在别处仍有活跃 run（租约心跳新鲜）。
     *
     * <p>{@link SessionActivityTracker} 只覆盖本进程；跨实例或崩溃残留的 run 由
     * 租约表兜住——有<b>心跳新鲜</b>的租约就说明有人在跑（僵尸由
     * {@code RunSilenceDetector} 判死清理），此时补醒只会撞上同会话并发 run 的
     * 409 互斥。</p>
     *
     * <p>3.2 假活锁修复：判据从「租约存在」收紧为「租约心跳在
     * {@code lease-stale-minutes} 窗口内」。心跳停止的陈旧租约是僵尸凭证
     * （活跃计数泄漏被无限续命的残余形态），不能再把停滞自检的闸门关死——
     * 否则「派了没跑」要等 10 小时外的人工干预而不是分钟级自愈。</p>
     */
    private boolean hasActiveLease(String sessionId) {
        try {
            return runLeaseStore.existsFreshBySessionId(
                    sessionId, watchdogProps.getLeaseStaleMinutes());
        } catch (RuntimeException e) {
            log.debug("Progress: lease query failed {}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    /** 「在跑但无新鲜租约」疑点 WARN 节流（每会话 10 分钟一次）。 */
    private void logStaleRunningOnce(String sessionId) {
        long now = System.currentTimeMillis();
        Long last = staleRunningWarnedAt.get(sessionId);
        if (last != null && now - last < 10 * 60_000L) {
            return;
        }
        staleRunningWarnedAt.put(sessionId, now);
        log.warn("Progress watchdog: session {} is marked running but has no fresh lease — "
                + "suspected activity-count leak (begin/end out of sync); "
                + "stall guard proceeds instead of skipping", sessionId);
    }

    private boolean canWakeAgain(AgentSessionProgress existing) {
        Instant last = existing == null ? null : existing.getLastStallWakeAt();
        return last == null
                || Duration.between(last, Instant.now()).toMinutes() >= watchdogProps.getWakeCooldownMinutes();
    }

    /** inbox 队列目录存在且仍有可消费条目 → true（这类会话本就有 InboxWatchdog 兜底）。 */
    private boolean inboxHasPending(String sessionId) {
        Path queues = sessionWorkspaceManager.getWorkspaceRoot()
                .resolve(AgentScopeWakeupConfig.SHARED_BUS_ROOT)
                .resolve("queues");
        if (!Files.isDirectory(queues)) {
            return false;
        }
        try (Stream<Path> dirs = Files.list(queues)) {
            return dirs.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(INBOX_PREFIX + sessionId))
                    .anyMatch(dir -> {
                        try (Stream<Path> files = Files.list(dir)) {
                            return files.anyMatch(f -> !KEEP_FILE.equals(f.getFileName().toString()));
                        } catch (IOException e) {
                            return false;
                        }
                    });
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 后台任务台账是否存在（框架写盘，不经模型）。两处布局任一命中即 true：
     * <ul>
     *   <li>会话目录内 {@code agents/{agentId}/tasks/{sessionId}.json}
     *       ——实测 {@code runtimes/{agentId}/{sessionId}/agents/{agentId}/tasks/}；</li>
     *   <li>运行时根下 {@code .agentscope/bus/async-tools/{sessionId}/} 非空
     *       ——实测 {@code runtimes/{agentId}/.agentscope/bus/async-tools/}。</li>
     * </ul>
     *
     * <p>与 {@link SessionCompletionContractService#supersededByNewWork} 取同一套路径
     * （它用这两处判「契约之后又派了新活」），此处只判存在性、不比时间。</p>
     *
     * <p><b>【根本判据·2026-10-13】与 {@code workEvidence}（{@code plan/} 或契约标记）
     * 合起来回答「本会话有没有活要干」</b>，是补醒的前提。停滞看护此前从未问过这个
     * 问题，只问「它是不是明确说完了」与「它停了多久」，于是一个<b>根本没有多阶段
     * 工作流</b>的纯问答会话（用户问一句、模型答完）也会在 8 分钟后被追问
     * 「你怎么不干活」，模型答「没活了」又被当成新一轮停滞 ⇒ 每 15 分钟一轮、
     * 无穷无尽。实证 main-c7b25b4d：2026-10-13 17:54–21:09 共 <b>14 轮</b>、
     * 2218 条事件，用户只在 17:50 说过一次话。</p>
     *
     * <p><b>为什么此前几次修复都没治本</b>：历次都在下游堵单一触发通道（先堵「合法
     * 等待清零计数」、再堵「3 分钟纯文本收尾快速通道」），而 8 分钟主通道
     * {@code idleLong} 从未被碰——第二轮起走的正是它。三次修复、三条路径，
     * 正是「修了好几次」的原因。</p>
     *
     * <p><b>不会漏救真停滞</b>：真停滞的流水线会话两者必居其一——用框架 plan 模式的
     * 写过 {@code plan/}；手工编排的派过子任务、有台账。本机实测 3 个教材流水线会话
     * （main-3f077bd9 / 9b94883c / 556b1096）皆 {@code plan=Y}，5 个纯问答会话皆全空，
     * 判据边界与两类会话精确重合。</p>
     *
     * <p>读不到文件系统一律按 {@code false}（不补醒）：漏救一次停滞的代价（告警仍会推、
     * 人可介入）远小于对每个闲聊会话每 15 分钟打扰一次。</p>
     */
    private boolean hasTaskLedger(String agentId, String sessionId) {
        try {
            Path runtimeRoot = sessionWorkspaceManager.resolveRuntimeRoot(agentId);
            Path ledger = sessionWorkspaceManager.resolveSessionDir(agentId, sessionId)
                    .resolve("agents").resolve(agentId)
                    .resolve("tasks").resolve(sessionId + ".json");
            if (Files.isRegularFile(ledger)) {
                return true;
            }
            Path asyncTools = runtimeRoot.resolve(AgentScopeWakeupConfig.SHARED_BUS_ROOT)
                    .resolve("async-tools").resolve(sessionId);
            if (!Files.isDirectory(asyncTools)) {
                return false;
            }
            try (Stream<Path> files = Files.list(asyncTools)) {
                return files.anyMatch(Files::isRegularFile);
            } catch (IOException e) {
                return false;
            }
        } catch (RuntimeException e) {
            log.debug("Progress: task ledger lookup failed for session {}: {}",
                    sessionId, e.getMessage());
            return false;
        }
    }

    private String extractProgressLine(String tail) {
        for (String line : tail.split("\n")) {
            if (line.contains("[进度]")) {
                return line.length() > 500 ? line.substring(0, 500) : line;
            }
        }
        return null;
    }

    private String lastMessageTimestamp(List<Msg> context) {
        for (int i = context.size() - 1; i >= 0; i--) {
            String ts = context.get(i).getTimestamp();
            if (ts != null && !ts.isBlank()) {
                return ts;
            }
        }
        return null;
    }

    private Instant parseTs(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            // fallthrough
        }
        try {
            String normalized = value.replace(' ', 'T');
            return LocalDateTime.parse(normalized).atZone(ZoneId.systemDefault()).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 轻量封装：内部仓储缺省值处理。 */
    private static final class SessionProgressRepositoryFacade {
        private final AgentSessionProgressRepository repo;

        private SessionProgressRepositoryFacade(AgentSessionProgressRepository repo) {
            this.repo = repo;
        }

        public AgentSessionProgress findByAgentIdAndSessionId(String a, String s) {
            return repo.findByAgentIdAndSessionId(a, s).orElse(null);
        }

        public void upsert(UUID id, String agentId, String sessionId, String userId,
                           String phase, String nextAction, String progressLine, String lastText,
                           Instant activeAt, Instant lastStallWakeAt,
                           int wakeCount, Instant lastProgressAt, boolean needsHuman) {
            repo.upsert(id, agentId, sessionId, userId, phase, nextAction,
                    progressLine, lastText, activeAt, lastStallWakeAt,
                    wakeCount, lastProgressAt, needsHuman);
        }

        /**
         * 置位/清除「用户停止」（upsert 语义，无行时补占位行）。
         *
         * <p>用 upsert 而非 UPDATE-only：点停止最常发生在看护首次扫描之前，
         * 此时台账无行、UPDATE 影响 0 行会静默丢标记 ⇒ 停止意图失效。</p>
         */
        public int upsertStopRequested(UUID id, String agentId, String sessionId,
                                        String userId, boolean requested) {
            return repo.upsertStopRequested(id, agentId, sessionId, userId, requested);
        }

        public boolean existsStopRequested(String sessionId) {
            return repo.existsBySessionIdAndStopRequestedTrue(sessionId);
        }

        public int upsertAwaitingHumanGate(UUID id, String agentId, String sessionId, String userId) {
            return repo.upsertAwaitingHumanGate(id, agentId, sessionId, userId);
        }

        public int updateAwaitingHumanGate(String agentId, String sessionId, boolean awaiting) {
            return repo.updateAwaitingHumanGate(agentId, sessionId, awaiting, Instant.now());
        }

        public boolean existsAwaitingHumanGate(String sessionId) {
            return repo.existsBySessionIdAndAwaitingHumanGateTrue(sessionId);
        }
    }

    /** 解析结果：阶段标签 + next_action 分类。 */
    public record NextActionDecision(String phase, String action) {
    }
}
