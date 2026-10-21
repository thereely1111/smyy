package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.AgentSessionOwner;
import com.tipdm.agentplatform.repository.AgentSessionOwnerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话归属服务：把产物（{@code runtimes/{agentId}/{sessionId}}）在逻辑层关联到用户。
 *
 * <p>产物目录是两层、不含 userId（原因见 {@link SessionWorkspaceManager} 类注释），
 * 所以「谁的东西」这条信息只能落在库里：</p>
 * <ul>
 *   <li>产物视图按 {@code (agent_id, session_id)} 查出 {@code user_id} 做归属校验；
 *   <li>按用户维度的清理、统计依赖本表的 {@code user_id} 索引；
 *   <li>{@code last_seen_at} 供回收策略判断会话是否仍活跃。
 * </ul>
 *
 * <p>归属与隔离是两件事：本服务只解决「归属」，「模型看不到别人的产物」由装配期
 * {@code IsolationScope.SESSION} 负责，与目录里有没有 userId 层无关。</p>
 */
@Service
public class SessionOwnerService {

    private static final Logger log = LoggerFactory.getLogger(SessionOwnerService.class);

    private final AgentSessionOwnerRepository repository;

    public SessionOwnerService(AgentSessionOwnerRepository repository) {
        this.repository = repository;
    }

    /**
     * 记录会话归属（幂等）。
     *
     * <p>归属先到先得：同一会话若已被记给其他用户，本次写入不影响既有归属
     * （upsert 返回 0 行），只打告警——出现这种情况通常意味着会话 id 被复用或
     * 鉴权链路有问题，值得排查，但不能让后到者接管别人的产物。</p>
     *
     * <p>调用方应自己 catch 异常：归属记录失败不应阻断会话本身。</p>
     */
    @Transactional
    public void record(String agentId, String sessionId, String userId) {
        if (isBlank(agentId) || isBlank(sessionId) || isBlank(userId)) {
            return;
        }
        int updated = repository.upsert(UUID.randomUUID(), agentId, sessionId, userId);
        if (updated == 0) {
            log.warn("Session owner mismatch, keep existing: agent={}, session={}, incomingUser={}",
                    agentId, sessionId, userId);
        }
    }

    /**
     * 查询会话归属用户；无记录时返回 empty（历史会话、或归属写入失败）。
     */
    @Transactional(readOnly = true)
    public Optional<String> owner(String agentId, String sessionId) {
        if (isBlank(agentId) || isBlank(sessionId)) {
            return Optional.empty();
        }
        return repository.findByAgentIdAndSessionId(agentId, sessionId)
                .map(AgentSessionOwner::getUserId);
    }

    /**
     * 归属判定：记录存在且等于传入 userId 才为 true。
     *
     * <p>无记录一律返回 false，由调用方决定降级策略（例如管理员放行）——
     * 这里不做策略判断，避免把权限语义埋进数据访问层。</p>
     */
    @Transactional(readOnly = true)
    public boolean isOwner(String agentId, String sessionId, String userId) {
        return owner(agentId, sessionId)
                .map(recorded -> recorded.equals(userId))
                .orElse(false);
    }

    /**
     * 按会话反查归属 agent（跨用户取首个）。供 InboxWatchdog 回退解析父 agent——
     * 该 watchdog 依赖的内存 SessionAgentRegistry 目前无登记源，owner 表才是
     * session→agent 的可靠映射（AguiWakeupTarget/record 写入）。
     */
    @Transactional(readOnly = true)
    public Optional<String> agentBySessionId(String sessionId) {
        if (isBlank(sessionId)) {
            return Optional.empty();
        }
        return repository.findBySessionId(sessionId).stream()
                .findFirst()
                .map(AgentSessionOwner::getAgentId);
    }

    /**
     * 会话是否已归档（弃用）。
     *
     * <p><b>为什么单独提供判据而不是靠调用方过滤</b>：归档此前只是「列表里看不见」，
     * 唤醒链路（{@code AguiWakeupTarget} / 看护自检 / inbox 补醒）全都不读该列，
     * 于是「弃用」在运行时毫无语义——弃用的会话照样被唤醒续跑。归档必须成为
     * 唤醒链路的一等判据，否则这个功能只做了一半。</p>
     *
     * <p>无归属记录返回 false（从未注册过 run 的会话不在任何唤醒路径上，
     * 按未归档处理与既有口径一致）。</p>
     */
    @Transactional(readOnly = true)
    public boolean isArchived(String agentId, String sessionId) {
        if (isBlank(agentId) || isBlank(sessionId)) {
            return false;
        }
        return repository.findByAgentIdAndSessionId(agentId, sessionId)
                .map(o -> o.getArchivedAt() != null)
                .orElse(false);
    }

    /**
     * 按会话判归档（agentId 未知时用；跨用户取首个归属行）。
     *
     * <p>唤醒链路的抑制检查早于 agentId 解析（解析失败时也要能拦住），
     * 故提供不依赖 agentId 的入口。</p>
     */
    @Transactional(readOnly = true)
    public boolean isArchivedBySession(String sessionId) {
        if (isBlank(sessionId)) {
            return false;
        }
        return repository.findBySessionId(sessionId).stream()
                .findFirst()
                .map(o -> o.getArchivedAt() != null)
                .orElse(false);
    }

    /**
     * 某用户在该智能体下的未归档会话 id（默认列表源）。
     *
     * <p>已归档会话不进默认列表——它们仍然完整存在（状态行 + 产物目录都在），
     * 只是不参与「继续之前的对话」这类日常入口；显式传 {@code includeArchived=true} 才返回。</p>
     */
    @Transactional(readOnly = true)
    public List<String> sessionsOf(String agentId, String userId) {
        return ownersOf(agentId, userId, false).stream()
                .map(AgentSessionOwner::getSessionId)
                .toList();
    }

    /**
     * 某用户在该智能体下的归属记录。
     *
     * @param includeArchived 是否包含已归档会话；false 时只返回 {@code archived_at IS NULL} 的行
     */
    @Transactional(readOnly = true)
    public List<AgentSessionOwner> ownersOf(String agentId, String userId, boolean includeArchived) {
        if (isBlank(agentId) || isBlank(userId)) {
            return List.of();
        }
        return includeArchived
                ? repository.findByAgentIdAndUserId(agentId, userId)
                : repository.findByAgentIdAndUserIdAndArchivedAtIsNull(agentId, userId);
    }

    /**
     * 会话列表快速路径（见 {@link AgentSessionOwnerRepository#listSessionSummaries}）：
     * 单条 SQL 拿齐 title / 归档时间 / 状态最后写入时间 / 压缩摘要，不加载整份 agent_state。
     */
    @Transactional(readOnly = true)
    public List<AgentSessionOwnerRepository.SessionSummaryRow> listSummaries(
            String agentId, String userId, boolean includeArchived) {
        if (isBlank(agentId) || isBlank(userId)) {
            return List.of();
        }
        return repository.listSessionSummaries(agentId, userId, includeArchived);
    }

    @Transactional
    public void setAutoTitle(String agentId, String sessionId, String userId, String title) {
        if (!isBlank(title)) {
            repository.setAutoTitle(agentId, sessionId, userId, title.strip(), "ai");
        }
    }

    @Transactional
    public void setManualTitle(String agentId, String sessionId, String userId, String title) {
        if (!isBlank(title)) {
            repository.setManualTitle(agentId, sessionId, userId, title.strip());
        }
    }

    /**
     * 归档 / 取消归档（幂等）。
     *
     * <p>只改归属表的时间戳，不动状态行、不动产物目录，因此取消归档后会话
     * 与归档前完全一致。归属校验放在 SQL 的 {@code user_id} 条件里：越权请求
     * 静默无效果并告警，不暴露「该会话存在但不属于你」。</p>
     */
    @Transactional
    public void setArchived(String agentId, String sessionId, String userId, boolean archived) {
        if (isBlank(agentId) || isBlank(sessionId) || isBlank(userId)) {
            return;
        }
        int updated = repository.setArchived(agentId, sessionId, userId, archived);
        if (updated == 0) {
            log.warn("Archive update affected 0 rows: agent={}, session={}, archived={}",
                    agentId, sessionId, archived);
        }
    }

    /** 删除智能体时连带清理归属记录（产物目录由 {@code deleteRuntimeRoot} 负责）。 */
    @Transactional
    public void deleteByAgent(String agentId) {
        if (isBlank(agentId)) {
            return;
        }
        repository.deleteByAgentId(agentId);
    }

    /** 删除单个会话的归属记录。 */
    /**
     * 全部归属记录（跨用户、跨智能体）。
     *
     * <p>仅供运行态巡检使用（如子智能体会话维护需要拿到 (agentId, userId) 组合来枚举
     * 会话）——框架的 {@code AgentStateStore} 只能按 userId 列举会话，没有"列出所有
     * 用户"的接口，归属表是平台侧唯一可用的用户来源。</p>
     */
    @Transactional(readOnly = true)
    public List<AgentSessionOwner> all() {
        return repository.findAll();
    }

    @Transactional
    public void deleteSession(String agentId, String sessionId) {
        if (isBlank(agentId) || isBlank(sessionId)) {
            return;
        }
        repository.deleteByAgentIdAndSessionId(agentId, sessionId);
    }

    /**
     * 近 {@code since} 之后仍活跃的全部会话（供停滞看护扫描）。
     */
    @Transactional(readOnly = true)
    public List<AgentSessionOwner> recentActiveSince(Instant since) {
        if (since == null) {
            return List.of();
        }
        return repository.findByLastSeenAtGreaterThanEqual(since);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
