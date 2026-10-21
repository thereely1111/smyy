package com.tipdm.agentplatform.service;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.HarnessAgent;
import com.tipdm.agentplatform.domain.AgentConfig;
import com.tipdm.agentplatform.repository.AgentSessionOwnerRepository;
import com.tipdm.agentplatform.harness.SessionActivityTracker;
import com.tipdm.agentplatform.harness.SessionRunStopper;
import com.tipdm.agentplatform.domain.AgentSessionOwner;
import com.tipdm.agentplatform.web.dto.RuntimeDtos.SessionInfo;
import com.tipdm.agentplatform.web.dto.RuntimeDtos.SessionTranscriptResponse;
import com.tipdm.agentplatform.web.dto.RuntimeDtos.SessionTurnsResponse;
import com.tipdm.agentplatform.harness.RunSubscriptionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 会话管理服务：保留 AgentScope 会话列表查询和会话清理功能。
 *
 * <p>运行时交互（SSE 流式对话、HITL 恢复、表单提交等）已迁移至 AG-UI 标准协议，
 * 通过框架自动暴露的 POST /agui/run/{agentId} 端点提供。</p>
 *
 * <p>本服务仅负责会话元数据查询和会话清理；转录读取（transcript / turns）委托给
 * {@link SessionTranscriptService}。</p>
 *
 * <p>会话按 (userId, sessionId) 分区存储：userId 取自调用端解析的真实用户
 * （只认 JWT，见 {@link com.tipdm.agentplatform.web.auth.JwtInterceptor#resolveUserId}），
 * 不再使用按 Agent 共享的 {@code web:{agentId}} 命名空间，避免终端用户间会话串扰。</p>
 */
@Service
public class AgentRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeService.class);

    private final AgentAssemblyService assemblyService;
    private final SessionWorkspaceManager sessionWorkspaceManager;
    private final SessionOwnerService sessionOwnerService;
    private final SessionActivityTracker sessionActivityTracker;
    private final PendingFormInterruptStore pendingFormInterruptStore;
    private final SessionCleanupService sessionCleanupService;
    private final SessionRunStopCoordinator sessionRunStopCoordinator;
    private final RunSubscriptionRegistry runSubscriptions;
    private final SessionTranscriptService transcriptService;

    public AgentRuntimeService(AgentAssemblyService assemblyService,
                               SessionWorkspaceManager sessionWorkspaceManager,
                               SessionOwnerService sessionOwnerService,
                               SessionActivityTracker sessionActivityTracker,
                               PendingFormInterruptStore pendingFormInterruptStore,
                               SessionCleanupService sessionCleanupService,
                               SessionRunStopCoordinator sessionRunStopCoordinator,
                               RunSubscriptionRegistry runSubscriptions,
                               SessionTranscriptService transcriptService) {
        this.assemblyService = assemblyService;
        this.sessionWorkspaceManager = sessionWorkspaceManager;
        this.sessionOwnerService = sessionOwnerService;
        this.sessionActivityTracker = sessionActivityTracker;
        this.pendingFormInterruptStore = pendingFormInterruptStore;
        this.sessionCleanupService = sessionCleanupService;
        this.sessionRunStopCoordinator = sessionRunStopCoordinator;
        this.runSubscriptions = runSubscriptions;
        this.transcriptService = transcriptService;
    }

    public List<SessionInfo> listSessions(AgentConfig config, String userId, boolean includeArchived) {
        String agentId = config.getId().toString();
        // 快速路径：单条 SQL（归属表 JOIN 状态库）拿齐列表字段，不加载整份 agent_state。
        // 任何异常（如 state_data 非法 JSON 导致的 ::jsonb 转换失败）都回退旧的全量扫描。
        try {
            List<SessionInfo> fast = listSessionsFast(config, userId, agentId, includeArchived);
            if (fast != null) {
                fast.sort(SESSION_ORDER);
                return fast;
            }
        } catch (Exception ex) {
            log.warn("Fast session listing failed, fall back to full state scan: agent={}", agentId, ex);
        }
        List<SessionInfo> sessions = legacyListSessions(config, userId, agentId, includeArchived);
        sessions.sort(SESSION_ORDER);
        return sessions;
    }

    private static final Comparator<SessionInfo> SESSION_ORDER = (a, b) -> {
        if (a.updatedAt() == null || b.updatedAt() == null) {
            return a.sessionId().compareTo(b.sessionId());
        }
        return b.updatedAt().compareTo(a.updatedAt());
    };

    /**
     * 会话列表快速路径。
     *
     * <p>旧实现逐会话 {@code stateStore.get(...)} 把整份 {@code agent_state}（实测单条
     * 300–640KB）拉回 Java 反序列化，只为取 summary / 标题 / 最后更新时间；会话一多，
     * 每次列表请求都要解析数 MB JSON，而前端（侧栏树 + 聊天列 + 各类事件刷新）会
     * 频繁触发，是 {@code GET /sessions} 慢的直接原因。现在常规情况全部由 SQL 提供，
     * 仅对「还没有自动标题」的会话回退全量加载（首次列出后 setAutoTitle 即持久化，
     * 之后不再触发）。</p>
     *
     * @return 会话列表；查询本身抛异常时由调用方回退（返回值不会是 null）
     */
    private List<SessionInfo> listSessionsFast(AgentConfig config, String userId,
                                               String agentId, boolean includeArchived) {
        List<AgentSessionOwnerRepository.SessionSummaryRow> rows =
                sessionOwnerService.listSummaries(agentId, userId, includeArchived);
        // 有归属记录但状态已清理的会话不展示（SQL 里 s.updated_at IS NOT NULL 已过滤）
        List<SessionInfo> sessions = new ArrayList<>(rows.size());
        List<AgentSessionOwnerRepository.SessionSummaryRow> untitled = null;
        for (AgentSessionOwnerRepository.SessionSummaryRow row : rows) {
            String title = row.getTitle();
            if (title == null || title.isBlank()) {
                if (untitled == null) {
                    untitled = new ArrayList<>();
                }
                untitled.add(row);
            } else {
                sessions.add(new SessionInfo(row.getSessionId(), row.getSummary(),
                        row.getStateUpdatedAt() == null ? null : row.getStateUpdatedAt().toInstant(),
                        row.getArchivedAt() == null ? null : row.getArchivedAt().toInstant(),
                        title));
            }
        }
        if (untitled != null) {
            // 无标题会话才回退全量加载：提取首条用户消息做标题并持久化（与旧逻辑一致）
            HarnessAgent agent = assemblyService.requireAssembled(config);
            for (AgentSessionOwnerRepository.SessionSummaryRow row : untitled) {
                String sessionId = row.getSessionId();
                try {
                    var state = agent.getStateStore()
                            .get(userId, sessionId, "agent_state", AgentState.class)
                            .orElse(null);
                    if (state == null) {
                        continue;
                    }
                    String title = sessionTitle(state);
                    if (title != null) {
                        sessionOwnerService.setAutoTitle(agentId, sessionId, userId, title);
                    }
                    sessions.add(new SessionInfo(sessionId, row.getSummary(),
                            row.getStateUpdatedAt() == null ? null : row.getStateUpdatedAt().toInstant(),
                            row.getArchivedAt() == null ? null : row.getArchivedAt().toInstant(),
                            title));
                } catch (Exception ex) {
                    log.warn("Failed to load state for session " + sessionId, ex);
                }
            }
        }
        return sessions;
    }

    /**
     * 旧的全量扫描实现（保留作快速路径的兜底）：逐会话加载完整 agent_state。
     * 仅在快速查询抛异常时使用——行为与 2026-09-17 之前的版本一致。
     */
    private List<SessionInfo> legacyListSessions(AgentConfig config, String userId,
                                                 String agentId, boolean includeArchived) {
        HarnessAgent agent = assemblyService.requireAssembled(config);
        // 状态库按 (userId, sessionId) 全局分区、不区分智能体；按智能体过滤
        // 依赖 agent_session_owners 归属表，避免把其它智能体的会话混进列表。
        // 已归档会话只在 includeArchived=true 时返回（前端据此分「最近 / 已归档」两组）。
        List<AgentSessionOwner> owners = sessionOwnerService.ownersOf(agentId, userId, includeArchived);
        List<SessionInfo> sessions = new ArrayList<>();
        for (AgentSessionOwner owner : owners) {
            String sessionId = owner.getSessionId();
            String summary = null;
            Instant updatedAt = null;
            try {
                var state = agent.getStateStore()
                        .get(userId, sessionId, "agent_state", AgentState.class)
                        .orElse(null);
                if (state != null) {
                    // AgentScope 的 summary 是上下文压缩摘要，不是适合展示在侧栏的会话标题。
                    // 标题优先从首条用户消息提取，避免新会话和已发生压缩的会话都显示为“未命名”。
                    summary = state.getSummary();
                    String title = owner.getTitle();
                    if (title == null || title.isBlank()) {
                        title = sessionTitle(state);
                        if (title != null) {
                            sessionOwnerService.setAutoTitle(agentId, sessionId, userId, title);
                        }
                    }
                    List<Msg> context = state.getContext();
                    updatedAt = context == null ? null : context.stream()
                            .map(Msg::getTimestamp)
                            .filter(t -> t != null)
                            .map(this::parseTimestamp)
                            .filter(Objects::nonNull)
                            .max(Instant::compareTo)
                            .orElse(null);
                    sessions.add(new SessionInfo(sessionId, summary, updatedAt, owner.getArchivedAt(), title));
                } else {
                    // 有归属记录但状态已清理的会话不再展示（如 clear 后异常残留）
                    log.debug("Session state missing, skip: agent={}, session={}", agentId, sessionId);
                }
            } catch (Exception ex) {
                log.warn("Failed to load state for session " + sessionId, ex);
            }
        }
        return sessions;
    }

    public void setTitle(AgentConfig config, String userId, String sessionId, String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        sessionOwnerService.setManualTitle(config.getId().toString(), sessionId, userId, title);
    }

    /**
     * 从会话第一轮对话生成稳定、无需额外模型调用的短标题。
     * 优先使用首条用户文本；若历史数据没有 user 消息，则回退到首条文本，
     * 最后才使用状态摘要的首行（兼容没有可读消息的旧状态）。
     */
    private String sessionTitle(AgentState state) {
        String firstUser = firstText(state.getContext(), "user");
        if (firstUser != null) {
            return shortenTitle(firstUser);
        }
        String firstText = firstText(state.getContext(), null);
        if (firstText != null) {
            return shortenTitle(firstText);
        }
        String summary = state.getSummary();
        if (summary == null || summary.isBlank()) {
            return null;
        }
        String firstLine = summary.lines().map(String::strip).filter(s -> !s.isBlank()).findFirst().orElse(null);
        return firstLine == null ? null : shortenTitle(firstLine);
    }

    private String firstText(List<Msg> context, String role) {
        if (context == null) {
            return null;
        }
        for (Msg message : context) {
            if (role != null && (message.getRole() == null || !role.equalsIgnoreCase(message.getRole().name()))) {
                continue;
            }
            if (message.getContent() == null) {
                continue;
            }
            for (ContentBlock block : message.getContent()) {
                if (block instanceof TextBlock textBlock && textBlock.getText() != null
                        && !textBlock.getText().isBlank()) {
                    return textBlock.getText().strip();
                }
            }
        }
        return null;
    }

    private String shortenTitle(String text) {
        String normalized = text.replaceAll("\\s+", " ").strip();
        if (normalized.length() <= 40) {
            return normalized;
        }
        return normalized.substring(0, 40).stripTrailing() + "…";
    }

    /**
     * 中断该会话正在运行的 run（停止按钮的后端支撑，2026-09-22）。
     *
     * <p><b>为什么需要</b>：前端停止按钮此前只断开本地 SSE 连接，而框架
     * AguiMvcController 没有 cancel 端点、也不因客户端断连取消 run
     * （{@code RunSilenceDetector} 注释：取消链死锁，SSE 订阅在框架侧泄漏）——
     * agent 会在后台把 run 跑完（持续烧 token），且线程锁被持有到自然结束，
     * 期间用户再发消息会撞「already has an active run」。</p>
     *
     * <p>实现复用 {@link SessionRunStopper}（与删会话前停 run 同一条链路）：
     * 触发框架 {@code HarnessAgent.interrupt} 让 run 按中断路径收尾（框架自己
     * 落状态、释放线程锁），并尽力等待收敛（最多 3s）。会话无在跑 run
     * （HITL 挂起等表单的场景）时是幂等 no-op，返回 false。</p>
     */
    public boolean interruptRun(AgentConfig config, String userId, String sessionId) {
        HarnessAgent agent = assemblyService.requireAssembled(config);
        return SessionRunStopper.stopIfRunning(
                agent, sessionActivityTracker, runSubscriptions, userId, sessionId);
    }

    /**
     * 清理会话：Postgres 状态与会话产物目录（runtimes/{agentId}/{sessionId}）联动删除。
     *
     * <p>框架的 {@code clearContext} 仅清空上下文并保留状态行，会话仍会出现在列表中；
     * 这里在清空后追加 {@code stateStore.delete} 彻底删除状态行，使会话列表与产物目录同步消失。</p>
     *
     * <p>2026-09-19 补齐兜底清理链（{@link SessionCleanupService}）：agui_events、
     * agent_session_progress、session_alert_push、agent_session_extensions 此前
     * 全部残留（实测「会话删除不彻底」，且 inbox 残留让 InboxWatchdog 每 30 秒 WARN）。
     * 兜底链每项独立容错，某项失败不影响其余。</p>
     *
     * <p><b>归属行改置墓碑而非删除（2026-10-13）</b>：删除会话后该会话仍在后台运行、
     * 且又生成了产物。根因不是「没停 run」，而是<b>删除动作把唤醒链路的抑制判据载体
     * 一起清掉了</b>——{@code purgeSessionData} 删掉 progress 行（stop_requested /
     * awaiting_human_gate 的载体）、物理删除 owner 行（archived 判据的载体），
     * 于是三道闸门全部读成「未封存 ⇒ 放行」，唤醒随即 {@code ensureSessionDir}+{@code record}
     * 重建目录与归属行，会话复活并继续产出。
     * 故本方法保留 owner 行并置 {@code deleted_at}，让「已删除」成为唤醒链路可读的判据。
     * 列表/统计侧一律走 {@code deleted_at IS NULL} 过滤。</p>
     *
     * <p><b>顺序：先置墓碑再停run</b>——停 run 会触发 {@code doFinally}（含截断续跑的
     * {@code enqueueWakeup}），墓碑必须在它之前落库，否则那次唤醒请求会被当作正常请求受理。</p>
     */
    public void clearSession(AgentConfig config, String userId, String sessionId) {
        HarnessAgent agent = assemblyService.requireAssembled(config);
        String agentId = config.getId().toString();
        // ① 先置墓碑：此后任何唤醒链路读到本会话都会静默跳过。必须早于停 run——
        //    stopIfRunning 内部会 dispose 订阅触发 doFinally，而截断续跑在 doFinally 里
        //    enqueueWakeup；墓碑未落库时那次唤醒会被正常受理。
        sessionOwnerService.markDeleted(agentId, sessionId, userId);
        // ② 停掉在跑的 run 再删：删完状态行后，那个 run 结束时的 CAS 回写必然失败
        //    （2026-09-11 12:29 实测 500: Failed to get versioned state）。
        //    含子智能体会话——子会话是独立的 sub-* 会话、独立计数，只停父 run 停不掉它们。
        sessionRunStopCoordinator.stopSessionAndSubSessions(agent, userId, sessionId);
        // 目录（含子智能体会话目录）在删状态行**之前**清理：子会话血缘取自父会话状态里的
        // 框架 spawn registry，状态行一删就查不到血缘，子目录会变成孤儿。
        sessionWorkspaceManager.deleteSessionDir(agentId, userId, sessionId);
        // 先删状态行再清上下文：clearContext 内部会反序列化已有状态做 CAS 保存，
        // 若状态 JSON 已损坏（如抽象类型 State 无法实例化）会直接抛异常，
        // 先删后 clearContext 最多写回一条空状态，随后再删即可彻底清除。
        agent.getStateStore().delete(userId, sessionId);
        try {
            agent.clearContext(userId, sessionId);
        } catch (Exception e) {
            log.warn("clearContext failed (state already deleted), session={}: {}", sessionId, e.getMessage());
        }
        // clearContext 成功时可能写回一条空状态，再删一次确保彻底清除
        agent.getStateStore().delete(userId, sessionId);
        // 会话域各表的兜底清理（agui_events / progress / alert_push / extensions），
        // 内部逐项容错，失败只告警不中断删除流程。
        sessionCleanupService.purgeSessionData(agentId, sessionId);
        // 内存里的待续填表单登记随会话一并作废：否则同一 sessionId 被复用（前端沿用 URL）
        // 时，旧的 interrupt 登记会让前端拿到一张已失效的表单。
        pendingFormInterruptStore.clear(agentId, userId, sessionId);
        log.info("Cleared session: agent={}, user={}, session={}", config.getId(), userId, sessionId);
    }


    /**
     * 解卡会话：清除可能遗留的 ASKING/interrupt 运行态，但保留会话归属、
     * 会话 ID 以及 runtimes 下的产物目录。下一次发送消息即可在同一会话 ID
     * 上创建全新的上下文。用于进程重启后框架仍报告“Agent is paused”时自愈。
     */
    public void recoverSession(AgentConfig config, String userId, String sessionId) {
        HarnessAgent agent = assemblyService.requireAssembled(config);
        AgentState snapshot = agent.getStateStore()
                .get(userId, sessionId, "agent_state", AgentState.class)
                .orElse(null);
        try {
            agent.clearContext(userId, sessionId);
        } finally {
            agent.getStateStore().delete(userId, sessionId);
            if (snapshot != null) {
                // 保留历史消息，但移除无法再恢复的陈旧 ASKING 工具调用。
                List<Msg> sanitized = snapshot.getContext() == null ? List.of() : snapshot.getContext().stream()
                        .map(this::removeStaleAskingCalls)
                        .toList();
                AgentState restored = AgentState.builder()
                        .sessionId(sessionId)
                        .userId(userId)
                        .summary(snapshot.getSummary())
                        .context(sanitized)
                        .replyId(null)
                        .curIter(0)
                        .shutdownInterrupted(false)
                        .permissionContext(snapshot.getPermissionContext())
                        .toolContext(snapshot.getToolContext())
                        .tasksContext(snapshot.getTasksContext())
                        .planModeContext(snapshot.getPlanModeContext())
                        .build();
                agent.getStateStore().save(userId, sessionId, "agent_state", restored);
            }
        }
        log.info("Recovered stuck session: agent={}, user={}, session={}", config.getId(), userId, sessionId);
    }

    private Msg removeStaleAskingCalls(Msg message) {
        List<ContentBlock> content = message.getContent();
        if (content == null || content.isEmpty()) {
            return message;
        }
        List<ContentBlock> cleaned = content.stream()
                .filter(block -> !(block instanceof ToolUseBlock tool && tool.getState() == ToolCallState.ASKING))
                .toList();
        return cleaned.size() == content.size() ? message : message.withContent(cleaned);
    }

    /**
     * 归档 / 取消归档会话。
     *
     * <p>与 {@link #clearSession} 的分工：归档只动归属表的 {@code archived_at}，
     * 状态行与产物目录 {@code runtimes/{agentId}/{sessionId}} 全部保留，取消归档即可恢复；
     * clear 才做不可逆清除。因此归档不要求智能体已装配——只查归属表，装配失败的
     * 智能体也能管理自己的会话列表。</p>
     */
    public void setArchived(AgentConfig config, String userId, String sessionId, boolean archived) {
        sessionOwnerService.setArchived(config.getId().toString(), sessionId, userId, archived);
        log.info("Session archived={}: agent={}, user={}, session={}",
                archived, config.getId(), userId, sessionId);
    }

    /**
     * 返回某会话的持久化转录（消息上下文），供前端增量同步后台唤醒续跑产生的段落。
     *
     * <p>实现见 {@link SessionTranscriptService#transcript}。</p>
     */
    public SessionTranscriptResponse transcript(AgentConfig config, String userId, String sessionId,
                                                Integer limit, String afterId, String beforeId) {
        return transcriptService.transcript(config, userId, sessionId, limit, afterId, beforeId);
    }

    /**
     * 会话轮次锚点列表，实现见 {@link SessionTranscriptService#turns}。
     */
    public SessionTurnsResponse turns(AgentConfig config, String userId, String sessionId) {
        return transcriptService.turns(config, userId, sessionId);
    }

    private Instant parseTimestamp(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
        }
        try {
            String normalized = value.replace(' ', 'T');
            return LocalDateTime.parse(normalized).atZone(ZoneId.systemDefault()).toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }
}
