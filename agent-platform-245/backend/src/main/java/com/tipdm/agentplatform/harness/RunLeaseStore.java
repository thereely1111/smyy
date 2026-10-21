package com.tipdm.agentplatform.harness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * run 在途租约（run lease）存储——run 级 checkpoint 的「在途标记」半边。
 *
 * <p><b>定位</b>：框架 stateStore 已在每个工具边界持久化 AgentState（完整消息
 * 上下文 + 迭代游标），「恢复执行」缺的不是状态数据，而是「有 run 被打断」
 * 这一事实的持久化凭证。本表即该凭证：run 启动时 upsert、运行中周期心跳、
 * 会话空闲后清理；进程死亡 ⇒ 心跳停止 ⇒ 重启后被 {@link RunLeaseReconciler}
 * 识别为孤儿并向会话 inbox push 恢复提示。</p>
 *
 * <p><b>instance_id</b>：进程级 UUID。当前进程对租约只做心跳/清理；不属于当前
 * 进程的租约必然来自已死亡的旧进程（平台单实例部署），可立即恢复，无需等待
 * 心跳过期——这消除了「重启后心跳新鲜度歧义」（崩溃后很快重启时，心跳看似
 * 仍新鲜，按时间判断会漏恢复）。</p>
 *
 * <p>写失败一律降级为 log.warn 不抛出：租约缺失只影响重启恢复的覆盖率，
 * 不能反过来打断正常 run。</p>
 */
@Component
public class RunLeaseStore {

    private static final Logger log = LoggerFactory.getLogger(RunLeaseStore.class);

    private final JdbcTemplate jdbc;
    /** 本进程实例标识：启动时生成，随每条租约写入。 */
    private final String instanceId = UUID.randomUUID().toString().replace("-", "");

    public RunLeaseStore(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 当前进程实例标识。 */
    public String instanceId() {
        return instanceId;
    }

    /**
     * run 启动时登记（或刷新）租约。同一会话同时只有一个活跃 run，冲突即覆盖
     * （新 run 接管该会话的「在途」凭证）。
     */
    public void upsert(String userId, String sessionId, String agentId) {
        try {
            jdbc.update(
                    "INSERT INTO run_leases (id, user_id, session_id, agent_id, instance_id) "
                            + "VALUES (?, ?, ?, ?, ?) "
                            + "ON CONFLICT (session_id) DO UPDATE SET "
                            + "user_id = EXCLUDED.user_id, agent_id = EXCLUDED.agent_id, "
                            + "instance_id = EXCLUDED.instance_id, heartbeat_at = now()",
                    UUID.randomUUID(), userId, sessionId, agentId, instanceId);
        } catch (RuntimeException e) {
            log.warn("Run lease upsert failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    /** 会话运行中的周期心跳。 */
    public void heartbeat(String sessionId) {
        try {
            jdbc.update(
                    "UPDATE run_leases SET heartbeat_at = now() "
                            + "WHERE session_id = ? AND instance_id = ?",
                    sessionId, instanceId);
        } catch (RuntimeException e) {
            log.warn("Run lease heartbeat failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    /** 删除租约（run 正常结束或恢复提示已投递）。 */
    public void delete(String sessionId) {
        try {
            jdbc.update("DELETE FROM run_leases WHERE session_id = ?", sessionId);
        } catch (RuntimeException e) {
            log.warn("Run lease delete failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    /** 全量租约（巡检用）。 */
    public List<RunLease> findAll() {
        return jdbc.query(
                "SELECT user_id, session_id, agent_id, instance_id, heartbeat_at FROM run_leases",
                (rs, i) -> new RunLease(
                        rs.getString("user_id"),
                        rs.getString("session_id"),
                        rs.getString("agent_id"),
                        rs.getString("instance_id"),
                        rs.getTimestamp("heartbeat_at").toInstant()));
    }

    /**
     * 该会话是否有一条在途租约。
     *
     * <p>供看护循环使用：原调用方是 {@code findAll().stream().anyMatch(...)}，
     * 每个会话一次全表扫描再内存过滤，看护每分钟对全部活跃会话各跑一遍。</p>
     */
    public boolean existsBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Integer one = jdbc.query(
                "SELECT 1 FROM run_leases WHERE session_id = ? LIMIT 1",
                rs -> rs.next() ? 1 : null,
                sessionId);
        return one != null;
    }

    /**
     * 该会话是否有一条<b>心跳新鲜</b>的在途租约（3.2 假活锁修复）。
     *
     * <p>闸门语义修正：租约存在 ≠ 有 run 在跑。心跳停止超过 {@code staleMinutes}
     * 的租约是僵尸凭证（活跃计数泄漏被规则 2 无限续命的场景由静默探测器的
     * forceEnd 收口，此后心跳停更），停滞闸门不应再被它关死——陈旧租约返回
     * false，让 {@code SessionProgressService} 的自检接管。</p>
     */
    public boolean existsFreshBySessionId(String sessionId, long staleMinutes) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Integer one = jdbc.query(
                "SELECT 1 FROM run_leases WHERE session_id = ? "
                        + "AND heartbeat_at > ? LIMIT 1",
                rs -> rs.next() ? 1 : null,
                sessionId, Timestamp.from(Instant.now().minus(Duration.ofMinutes(staleMinutes))));
        return one != null;
    }

    /** 一条在途租约。 */
    public record RunLease(
            String userId, String sessionId, String agentId, String instanceId, Instant heartbeatAt) {

        /** 距最后心跳的时长。 */
        public Duration age() {
            return Duration.between(heartbeatAt, Instant.now());
        }

        /** 心跳时刻的可读形式（恢复提示用）。 */
        public String heartbeatAtText() {
            return Timestamp.from(heartbeatAt).toLocalDateTime().toString();
        }
    }
}
