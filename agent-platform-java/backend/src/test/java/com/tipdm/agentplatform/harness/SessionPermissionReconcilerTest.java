package com.tipdm.agentplatform.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 会话权限对账回归：平台后加的 ALLOW 规则必须能补进既有会话。
 *
 * <p>事故背景（2026-09-11）：框架把权限上下文按会话持久化、只在新会话播种，因此把
 * {@code todo_write} 加进白名单后，既有会话仍"未匹配到规则"→ 框架默认 ASK → 回合结束
 * 且无工具结果 → AG-UI 下无人应答 → 会话卡死。</p>
 */
class SessionPermissionReconcilerTest {

    private static final String USER = "user-1";
    private static final String SESSION = "main-1";

    private static PermissionRule allow(String name) {
        return new PermissionRule(name, null, PermissionBehavior.ALLOW, "platform-policy");
    }

    /** 平台策略：放行读文件与 todo_write。 */
    private static PermissionContextState policy() {
        return PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT)
                .addAllowRule("read_file", allow("read_file"))
                .addAllowRule("todo_write", allow("todo_write"))
                .build();
    }

    /** 最小内存 state store（只支撑 get/save agent_state）。 */
    private static final class MemoryStore implements AgentStateStore {
        AgentState stored;
        int saveCount;

        @Override
        public void save(String userId, String sessionId, String key, State value) {
            stored = (AgentState) value;
            saveCount++;
        }

        @Override
        public void save(String userId, String sessionId, String key, List<? extends State> values) {
        }

        @Override
        public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
            if (stored != null && type.isInstance(stored)) return Optional.of(type.cast(stored));
            return Optional.empty();
        }

        @Override
        public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> itemType) {
            return List.of();
        }

        @Override
        public void delete(String userId, String sessionId) {
            stored = null;
        }

        @Override
        public void delete(String userId, String sessionId, String key) {
            stored = null;
        }

        @Override
        public java.util.Set<String> listSessionIds(String userId) {
            return java.util.Set.of();
        }

        @Override
        public boolean exists(String userId, String sessionId) {
            return stored != null;
        }
    }

    /** 会话状态：权限上下文缺 todo_write，另有会话内累积的自定义规则。 */
    private static MemoryStore sessionMissingTodo() {
        MemoryStore store = new MemoryStore();
        store.stored = AgentState.builder()
                .sessionId(SESSION)
                .userId(USER)
                .permissionContext(PermissionContextState.builder()
                        .mode(PermissionMode.DEFAULT)
                        .addAllowRule("read_file", allow("read_file"))
                        // 会话内通过人工批准累积的规则，不能被平台策略覆盖/抹掉
                        .addAllowRule("custom_approved_tool", allow("custom_approved_tool"))
                        .build())
                .build();
        return store;
    }

    @Test
    void mergeInto_addsMissingPolicyRuleAndKeepsSessionRules() {
        MemoryStore store = sessionMissingTodo();

        assertTrue(SessionPermissionReconciler.mergeInto(store, USER, SESSION, policy()));

        var allow = Objects.requireNonNull(store.stored.getPermissionContext()).getAllowRules();
        assertTrue(allow.containsKey("todo_write"), "缺失的平台规则必须补入");
        assertTrue(allow.containsKey("read_file"), "已有规则必须保留");
        assertTrue(allow.containsKey("custom_approved_tool"), "会话内累积规则必须保留");
        assertEquals(1, store.saveCount);
    }

    @Test
    void mergeInto_noWriteWhenNothingMissing() {
        MemoryStore store = new MemoryStore();
        store.stored = AgentState.builder()
                .sessionId(SESSION)
                .userId(USER)
                .permissionContext(PermissionContextState.builder()
                        .mode(PermissionMode.DEFAULT)
                        .addAllowRule("read_file", allow("read_file"))
                        .addAllowRule("todo_write", allow("todo_write"))
                        .build())
                .build();

        assertFalse(SessionPermissionReconciler.mergeInto(store, USER, SESSION, policy()),
                "无缺失时不应写库（每轮 run 都调用，必须幂等且零开销）");
        assertEquals(0, store.saveCount);
    }

    @Test
    void mergeInto_firstRunWithNoPersistedState_isNoop() {
        MemoryStore store = new MemoryStore();
        assertFalse(SessionPermissionReconciler.mergeInto(store, USER, SESSION, policy()));
        assertEquals(0, store.saveCount);
    }
}
