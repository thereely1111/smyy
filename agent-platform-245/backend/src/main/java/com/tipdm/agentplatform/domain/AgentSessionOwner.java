package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 会话归属：某智能体下某会话属于哪个用户。
 *
 * <p>产物目录 {@code runtimes/{agentId}/{sessionId}} 是两层、不含 userId
 * （原因见 {@link com.tipdm.agentplatform.service.SessionWorkspaceManager} 类注释），
 * 因此「产物属于谁」这条信息由本实体承载，供产物视图做归属校验、
 * 供按用户维度的清理与统计使用。</p>
 *
 * <p>{@code lastSeenAt} 由 {@code SessionOwnerService} 显式维护：
 * 首次记录等于创建时间，之后每次会话请求刷新（用于判断会话是否仍活跃）。
 * 这里不定义 {@code @PrePersist} 回调，避免与 {@link BaseEntity} 的同名回调冲突。</p>
 */
@Entity
@Table(name = "agent_session_owners")
public class AgentSessionOwner extends BaseEntity {

    /** 智能体 id。子智能体的运行期 id 不保证是合法 UUID，故用字符串。 */
    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    /** 会话 id（前端生成的 {@code main-{uuid}}，AG-UI 的 threadId）。 */
    @Column(name = "session_id", nullable = false, length = 255)
    private String sessionId;

    /** 归属用户 id（JWT payload 的 userId，字符串形态）。 */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 最近一次该会话活跃的时间。 */
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /**
     * 归档时间；{@code null} 表示未归档。
     *
     * <p>归档只是把会话从默认列表隐藏，状态行与产物目录
     * {@code runtimes/{agentId}/{sessionId}} 全部保留，恢复即把本字段置空。
     * 不可逆的清除走 {@code SessionOwnerService#deleteSession} + 产物目录删除。</p>
     */
    @Column(name = "archived_at")
    private Instant archivedAt;

    /**
     * 删除时间；{@code null} 表示未删除。
     *
     * <p><b>为什么删除后保留行而不是物理删除</b>：删除会话需要让后台唤醒链路
     * 「知道它已被删」，而唤醒链路原先读的三道抑制判据载体恰好就是本行与
     * {@code agent_session_progress} 行——纯删除把载体一起清掉后，
     * {@code findBySessionId().orElse(false)} 会把「已删」读成「未归档 ⇒ 放行」，
     * 唤醒随即重建产物目录与归属行，会话在列表里复活并继续产出。
     * 因此删除改为置本列留墓碑，唤醒链路统一先读本列。
     *
     * <p>与 {@link #archivedAt} 语义不同、不可复用同一列：归档可逆（数据全在），
     * 删除不可逆（状态行与产物目录已物理删除）。
     * 查询一律走 {@code deleted_at IS NULL}，此处供业务判断复用。</p>
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "title_source", length = 16)
    private String titleSource;

    public String getTitle() {
        return title;
    }
    public void setTitle(String title) {
        this.title = title;
    }
    public String getTitleSource() {
        return titleSource;
    }
    public void setTitleSource(String titleSource) {
        this.titleSource = titleSource;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(Instant archivedAt) {
        this.archivedAt = archivedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    /** 会话是否已被删除（墓碑判定，见 {@link #deletedAt}）。 */
    public boolean isDeleted() {
        return deletedAt != null;
    }
}
