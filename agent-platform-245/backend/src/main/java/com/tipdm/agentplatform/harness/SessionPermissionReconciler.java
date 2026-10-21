package com.tipdm.agentplatform.harness;

import com.tipdm.agentplatform.service.AgentAssemblyService;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话权限策略对账：把平台策略里<b>缺失</b>的 ALLOW 规则补进会话持久化的权限上下文。
 *
 * <p><b>为什么需要</b>：框架把权限上下文按会话存在 {@code AgentState.permissionContext} 里，
 * 只在会话首次创建时用装配上下文播种（{@code freshState(initialPermissionContext, …)}），
 * 之后每个 run 的 {@code PermissionEngine} 都只读这份持久化副本。于是平台<b>后来新增</b>
 * 的 ALLOW 规则对已存在的会话永远不生效，工具会因"未匹配到任何规则"落回框架默认 ASK。</p>
 *
 * <p>实证（2026-09-11）：把 {@code todo_write} 加入框架内部工具白名单（a6578b9）后，
 * 新会话可用，但既有会话（如 main-62737cbe，创建于该提交之前）仍以 ASK 结束回合、
 * 无工具结果 → AG-UI 下无人应答 → 会话卡死；其持久化 allow_rules 里 32 条规则唯独缺
 * {@code todo_write}（{@code ask_rules} 为空，正是"未匹配 → 默认 ASK"的形态）。</p>
 *
 * <p><b>边界</b>：只补齐缺失项，不覆盖/不移除会话里已有的规则——会话内可能通过人工批准
 * 累积了额外规则（框架支持把 ASK 结论写回上下文），那是会话状态，不属于平台策略。
 * 幂等：无缺失时不写库。</p>
 */
@Component
public class SessionPermissionReconciler {

    private static final Logger log = LoggerFactory.getLogger(SessionPermissionReconciler.class);

    private static final String STATE_KEY = "agent_state";

    private final AgentAssemblyService assemblyService;

    public SessionPermissionReconciler(AgentAssemblyService assemblyService) {
        this.assemblyService = assemblyService;
    }

    /** 按 agentId 解析装配策略后对账；会话无持久化状态（首次运行）时无事可做。 */
    public void reconcile(UUID agentId, AgentStateStore store, String userId, String sessionId) {
        reconcile(agentId, store, userId, sessionId, assemblyService.policyPermissions(agentId));
    }

    void reconcile(
            UUID agentId,
            AgentStateStore store,
            String userId,
            String sessionId,
            PermissionContextState policy) {
        if (store == null || policy == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            if (mergeInto(store, userId, sessionId, policy)) {
                log.info("Session permission reconciled: agent={}, session={} (平台策略里缺失的 "
                        + "ALLOW 规则已补入会话权限上下文)", agentId, sessionId);
            }
        } catch (RuntimeException e) {
            // 对账是兜底：失败不得阻断 run（最坏情况回到修复前的行为）
            log.warn("Session permission reconcile failed (agent={}, session={}): {}",
                    agentId, sessionId, e.getMessage());
        }
    }

    /**
     * 把策略中缺失的 ALLOW 规则合并进会话状态；有变更才写库。
     *
     * @return 是否写入了状态
     */
    static boolean mergeInto(
            AgentStateStore store,
            String userId,
            String sessionId,
            PermissionContextState policy) {
        AgentState state = store.get(userId, sessionId, STATE_KEY, AgentState.class).orElse(null);
        if (state == null) {
            return false;
        }
        PermissionContextState current = state.getPermissionContext();
        Map<String, List<PermissionRule>> currentAllow =
                current == null ? Map.of() : current.getAllowRules();

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, List<PermissionRule>> entry : policy.getAllowRules().entrySet()) {
            List<PermissionRule> existing = currentAllow.get(entry.getKey());
            if (existing == null || existing.isEmpty()) {
                missing.add(entry.getKey());
            }
        }
        if (missing.isEmpty()) {
            return false;
        }

        PermissionContextState.Builder builder = PermissionContextState.builder()
                .mode(current == null ? policy.getMode() : current.getMode());
        if (current != null) {
            current.getWorkingDirectories().forEach(builder::addWorkingDirectory);
            current.getAllowRules().forEach((name, rules) -> rules.forEach(r -> builder.addAllowRule(name, r)));
            current.getDenyRules().forEach((name, rules) -> rules.forEach(r -> builder.addDenyRule(name, r)));
            current.getAskRules().forEach((name, rules) -> rules.forEach(r -> builder.addAskRule(name, r)));
        }
        for (String name : missing) {
            for (PermissionRule rule : policy.getAllowRules().get(name)) {
                builder.addAllowRule(name, rule);
            }
        }
        state.setPermissionContext(builder.build());
        store.save(userId, sessionId, STATE_KEY, state);
        return true;
    }
}
