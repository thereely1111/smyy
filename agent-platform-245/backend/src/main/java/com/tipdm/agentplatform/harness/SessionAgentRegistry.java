package com.tipdm.agentplatform.harness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * sessionId → agentId 注册表（保留每个会话的最近注册历史，最新在后）。
 *
 * <p>Wakeup payload 中携带 agentId，但框架 {@code WakeupDispatcher.dispatch} 只把
 * userId + sessionId 传给 target，因此唤醒目标必须自行解析会话所属智能体。
 * 映射来源：
 * <ol>
 *   <li>AG-UI 每次请求（{@link PlatformAguiRuntimeContextResolver}）</li>
 *   <li>智能体每次调用（{@link SessionActivityMiddleware}，含平台 chat 路径）</li>
 *   <li>启动时从旧/新总线 wakeup 队列文件回填（跨重启恢复）</li>
 * </ol>
 *
 * <p>为什么保留历史而不是单一映射：子智能体运行同样会流经
 * {@link SessionActivityMiddleware}（Harness 会把父智能体的 middleware 复制给子智能体），
 * 若采用"后注册覆盖先注册"，会话映射可能被子智能体的运行期 UUID 覆盖，而该 UUID
 * 不是平台可装配的智能体（{@code requireAssembledById} 解析不到）。唤醒器需要
 * 依序回退到该会话仍然可解析的（父）智能体，因此这里按注册顺序保留候选列表。</p>
 */
public class SessionAgentRegistry {

    /** sessionId → 有序 agentId 集合（LinkedHashSet：注册顺序即插入顺序，重新注册移到末尾）。 */
    private final ConcurrentHashMap<String, LinkedHashSet<String>> sessionToAgents =
            new ConcurrentHashMap<>();

    public void register(String agentId, String sessionId) {
        if (agentId == null || agentId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessionToAgents.compute(
                sessionId,
                (k, existing) -> {
                    LinkedHashSet<String> set = existing == null ? new LinkedHashSet<>() : existing;
                    // remove+add 保证重复注册也体现"最新在后"的顺序
                    set.remove(agentId);
                    set.add(agentId);
                    return set;
                });
    }

    /**
     * 返回会话最近一次注册的 agentId（保持向后兼容），无则 {@code null}。
     */
    public String agentFor(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        LinkedHashSet<String> set = sessionToAgents.get(sessionId);
        if (set == null || set.isEmpty()) {
            return null;
        }
        String last = null;
        for (String id : set) {
            last = id;
        }
        return last;
    }

    /**
     * 返回会话的全部候选 agentId，最新注册在前。
     *
     * <p>供唤醒器按序尝试解析：最近的注册（可能是子智能体运行期 UUID）解析不到时，
     * 回退到更早注册的、平台可装配的父智能体。</p>
     */
    public List<String> agentCandidates(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> set = sessionToAgents.get(sessionId);
        if (set == null || set.isEmpty()) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>(set);
        Collections.reverse(candidates);
        return candidates;
    }

    public void registerAll(Map<String, String> entries) {
        if (entries == null) {
            return;
        }
        entries.forEach(this::register);
    }
}
