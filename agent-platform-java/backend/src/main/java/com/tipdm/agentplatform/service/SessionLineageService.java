package com.tipdm.agentplatform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
/**
 * 会话血缘：给定会话，找出它经 {@code agent_spawn} 派生的**子会话**（子智能体运行所在会话）。
 *
 * <p><b>数据来源是框架自己记录的 spawn registry</b>：harness 的 {@code agent_spawn} 每次派发都会
 * 把 {@code SpawnEntry(key, agent_id, session_id, label, depth)} 写进<b>父会话</b>的 tool context
 * （{@code tool_context.spawn_registry}），随会话状态一起持久化。因此血缘是框架的一等数据，
 * 不需要平台注入任何文本标记，也不需要扫描子会话的产物内容。</p>
 *
 * <p><b>为什么平台需要它</b>：平台按会话隔离文件空间（父会话 = {@code runtimes/{agentId}/{main-…}}，
 * 每个子智能体 = 同级 {@code runtimes/{agentId}/sub-…}），而子会话目录名不携带父会话 ID。
 * 产物面板聚合、删除会话时联动清理子目录、子任务汇报列表，都依赖"这个会话有哪些子会话"。</p>
 *
 * <p>查询写成递归 CTE 并带深度上限：当前用法只有一层（子智能体工具集里没有 {@code agent_spawn}），
 * 但嵌套派发时也不会漏掉孙会话或留下孤儿目录。</p>
 */
@Service
public class SessionLineageService {

    private static final Logger log = LoggerFactory.getLogger(SessionLineageService.class);

    /** 单次查询的子会话上限：面板是交互接口，避免异常数据把响应拖垮。 */
    private static final int MAX_SUB_SESSIONS = 200;

    /** 派发深度上限（递归 CTE 的终止条件之一，防异常数据自环）。 */
    private static final int MAX_DEPTH = 5;

    private static final String STATE_TABLE = "agentscope_sessions";

    /**
     * 递归读取 spawn registry。{@code session_id} 是该表主键，形如 {@code {userId}:{sessionId}}，
     * 因此子会话状态行可用 {@code userId + ':' + subSessionId} 定位。
     */
    private static final String LINEAGE_SQL =
            "with recursive tree(sid, aid, lbl, depth) as ("
                    + "  select e.value ->> 'session_id', e.value ->> 'agent_id', e.value ->> 'label',"
                    + "         coalesce((e.value ->> 'depth')::int, 1)"
                    + "    from " + STATE_TABLE + " s,"
                    + "         lateral jsonb_each(coalesce(s.state_data::jsonb -> 'tool_context'"
                    + "              -> 'spawn_registry', '{}'::jsonb)) e"
                    + "   where s.session_id = ?"
                    + "  union"
                    + "  select e.value ->> 'session_id', e.value ->> 'agent_id', e.value ->> 'label',"
                    + "         t.depth + coalesce((e.value ->> 'depth')::int, 1)"
                    + "    from tree t, " + STATE_TABLE + " s,"
                    + "         lateral jsonb_each(coalesce(s.state_data::jsonb -> 'tool_context'"
                    + "              -> 'spawn_registry', '{}'::jsonb)) e"
                    + "   where t.depth < " + MAX_DEPTH
                    + "     and s.session_id = ? || t.sid"
                    + ")"
                    + " select sid, min(aid) as aid, min(lbl) as lbl, min(depth) as depth"
                    + "   from tree"
                    + "  where sid is not null and sid <> ''"
                    + "  group by sid order by min(depth), sid limit ?";

    private final JdbcTemplate jdbc;

    /**
     * 血缘查询结果缓存（userId:parentSessionId → 结果）。
     *
     * <p>spawn registry 只在新派发发生时变化；产物树 / 文件内容 / 会话清理在
     * 短时间内会反复取同一份血缘，而递归 CTE 要逐个解压全部子会话的完整
     * state_data（实测 152 个子会话 ≈1.7s），不做缓存的话每次请求都重付。
     * TTL 内容忍「刚派发的子会话最多延迟 TTL 才出现在面板」，符合面板场景。</p>
     */
    private static final long CACHE_TTL_MILLIS = 30_000L;
    private static final int CACHE_MAX_ENTRIES = 256;

    private final Map<String, CachedLineage> cache =
            new LinkedHashMap<String, CachedLineage>(CACHE_MAX_ENTRIES + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedLineage> eldest) {
                    return size() > CACHE_MAX_ENTRIES;
                }
            };

    private record CachedLineage(List<SubSession> subs, long expiresAtMillis) { }

    public SessionLineageService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * 该用户分区下、由指定父会话派生的子会话（按派发深度与 ID 排序）。
     *
     * <p>fail-soft：查询异常（表结构变更、连不上库、状态行不是合法 JSON）返回空表——
     * 面板退化为"只显示本会话产物"，不影响会话运行。</p>
     */
    public List<SubSession> subSessions(String userId, String parentSessionId) {
        if (userId == null || userId.isBlank() || parentSessionId == null || parentSessionId.isBlank()) {
            return List.of();
        }
        String key = userId + ":" + parentSessionId;
        long now = System.currentTimeMillis();
        synchronized (cache) {
            CachedLineage cached = cache.get(key);
            if (cached != null && cached.expiresAtMillis() > now) {
                return cached.subs();
            }
        }
        List<SubSession> subs = querySubSessions(userId, parentSessionId);
        synchronized (cache) {
            cache.put(key, new CachedLineage(subs, System.currentTimeMillis() + CACHE_TTL_MILLIS));
        }
        return subs;
    }

    /** 实际执行递归 CTE（fail-soft：异常返回空表，不缓存失败结果——下个请求重试）。 */
    private List<SubSession> querySubSessions(String userId, String parentSessionId) {
        String userPrefix = userId + ":";
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    LINEAGE_SQL, userPrefix + parentSessionId, userPrefix, MAX_SUB_SESSIONS);
            Map<String, SubSession> out = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                Object sid = row.get("sid");
                if (sid == null || sid.toString().isBlank()) {
                    continue;
                }
                String id = sid.toString();
                out.putIfAbsent(id, new SubSession(
                        id,
                        str(row.get("aid")),
                        str(row.get("lbl")),
                        row.get("depth") instanceof Number n ? n.intValue() : 1));
            }
            return List.copyOf(out.values());
        } catch (RuntimeException e) {
            log.warn("Session lineage lookup failed (user={}, parent={}): {}",
                    userId, parentSessionId, e.getMessage());
            return List.of();
        }
    }

    /** 子会话 ID 列表（{@code sub-…}），供产物面板白名单与目录清理使用。 */
    public List<String> subSessionsOf(String userId, String parentSessionId) {
        return subSessions(userId, parentSessionId).stream().map(SubSession::sessionId).toList();
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * 一条派发记录（来自框架 spawn registry）。
     *
     * @param sessionId 子会话 ID（{@code sub-…}），同时是子智能体产物目录名
     * @param agentId   子智能体声明名（如 {@code 目录生成智能体}）
     * @param label     派发时给的标签（可空）
     * @param depth     派发深度，1 = 主编会话直接派生
     */
    public record SubSession(String sessionId, String agentId, String label, int depth) { }
}
