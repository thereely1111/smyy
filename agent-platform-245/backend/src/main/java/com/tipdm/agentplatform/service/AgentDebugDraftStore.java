package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.web.dto.AgentDtos.AgentConfigRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 智能体在线调试的「试运行草稿」内存仓库（2026-09-24）。
 *
 * <p>管理端编辑页点「试运行」时，把表单当前内容经 PUT /agents/{id}/debug-draft
 * （agent:update 权限）存到这里；其后该用户对该智能体的 run 请求携带
 * {@code forwardedProps.debug=true} 时，装配期用这份草稿覆盖发布配置——
 * 验证的是编辑器里的内容而非线上版本，广场与既有会话完全不受影响。</p>
 *
 * <p>归属键 = userId + agentId：伪造 debug 标志只能激活调用者本人的草稿，
 * 而写入需要 agent:update 权限，普通用户无面。进程内存态、可随时覆盖/清除，
 * 发布动作会按 agentId 全量清（防陈旧草稿在发布后被误跑）。</p>
 */
@Service
public class AgentDebugDraftStore {

    private static final Logger log = LoggerFactory.getLogger(AgentDebugDraftStore.class);

    /** 单条试运行草稿：配置请求体 + 递增版本号（进装配缓存键，改一次草稿即换新实例）。 */
    public record Entry(AgentConfigRequest request, long version) {
    }

    private final Map<String, Entry> drafts = new ConcurrentHashMap<>();
    private final AtomicLong versions = new AtomicLong();

    private static String key(String userId, UUID agentId) {
        return userId + "|" + agentId;
    }

    /** 登记试运行草稿（同 user+agent 重复登记即覆盖，版本递增）。 */
    public void put(String userId, UUID agentId, AgentConfigRequest request) {
        drafts.put(key(userId, agentId), new Entry(request, versions.incrementAndGet()));
        log.info("Debug draft stored: agent={} user={}", agentId, userId);
    }

    /** 取回试运行草稿；无登记返回 null（装配按发布配置走）。 */
    public Entry get(String userId, UUID agentId) {
        return drafts.get(key(userId, agentId));
    }

    /** 清除本人对该智能体的试运行草稿。 */
    public void evict(String userId, UUID agentId) {
        drafts.remove(key(userId, agentId));
    }

    /** 智能体发布/删除时清掉所有人挂着的试运行草稿（防陈旧内容被继续试跑）。 */
    public void evictAgent(UUID agentId) {
        String suffix = "|" + agentId;
        drafts.keySet().removeIf(k -> k.endsWith(suffix));
    }

    /** 全量条目（运维排查用，勿在请求路径遍历）。 */
    public List<Map.Entry<String, Entry>> snapshot() {
        return List.copyOf(drafts.entrySet());
    }
}
