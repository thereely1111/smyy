package com.tipdm.agentplatform.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 子智能体会话维护的 pending 工具调用修复判据回归。
 *
 * <p>事故背景（2026-09-11 13:09 实测）：sub 会话在工具调用「已发出未落结果」时被重启打断，
 * 重进该会话时框架前置校验直接抛 {@code Pending tool calls exist without results}
 * （平台只给父 agent 开了 enablePendingToolRecovery），本次重进的产出全部作废。</p>
 */
class SubagentSessionMaintenanceTest {

    private static ToolUseBlock use(String id, String name) {
        return new ToolUseBlock(id, name, Map.of(), Map.of());
    }

    private static Msg assistantWith(ContentBlock... blocks) {
        return Msg.builderForRole(MsgRole.ASSISTANT).content(List.of(blocks)).build();
    }

    private static Msg toolWith(ContentBlock... blocks) {
        return Msg.builderForRole(MsgRole.TOOL).content(List.of(blocks)).build();
    }

    private static ToolResultBlock result(String id, ToolResultState state) {
        return ToolResultBlock.builder()
                .id(id)
                .name("execute")
                .output(TextBlock.builder().text("ok").build())
                .state(state)
                .build();
    }

    @Test
    void pendingToolCalls_detectsUsesWithoutResults() {
        List<Msg> context = List.of(
                assistantWith(use("c1", "write_file"), use("c2", "execute")),
                toolWith(result("c1", ToolResultState.SUCCESS)));
        List<ToolUseBlock> pending = SubagentSessionMaintenance.pendingToolCalls(context);
        assertEquals(1, pending.size());
        assertEquals("c2", pending.get(0).getId());
    }

    @Test
    void pendingToolCalls_noneWhenAllHaveResults() {
        List<Msg> context = List.of(
                assistantWith(use("c1", "execute")),
                toolWith(result("c1", ToolResultState.SUCCESS)));
        assertTrue(SubagentSessionMaintenance.pendingToolCalls(context).isEmpty());
    }

    /** 修复：为每个 pending 调用补一条中断结果，且保留状态其余字段。 */
    @Test
    void planRepair_addsInterruptedResultsAndKeepsState() {
        AgentState state = AgentState.builder()
                .sessionId("sub-1")
                .userId("user-1")
                .summary("summary")
                .context(List.of(
                        assistantWith(use("c1", "write_file"), use("c2", "execute")),
                        toolWith(result("c1", ToolResultState.SUCCESS))))
                .build();

        SubagentSessionMaintenance.Repair repair = SubagentSessionMaintenance.planRepair(state);

        assertNotNull(repair);
        assertEquals(1, repair.count());
        assertEquals(List.of("c2"), repair.pendingIds());

        AgentState patched = repair.state();
        assertEquals("sub-1", patched.getSessionId());
        assertEquals("user-1", patched.getUserId());
        assertEquals("summary", patched.getSummary());
        assertEquals(3, patched.getContext().size());

        Msg added = patched.getContext().get(2);
        assertEquals(MsgRole.TOOL, added.getRole());
        List<ToolResultBlock> results = added.getContentBlocks(ToolResultBlock.class);
        assertEquals(1, results.size());
        ToolResultBlock r = results.get(0);
        assertEquals("c2", r.getId());
        assertEquals("execute", r.getName());
        assertEquals(ToolResultState.ERROR, r.getState());
        // 文案与框架自动补的一致，子智能体行为与父会话相同
        assertTrue(r.getOutput().toString().contains("Previous tool execution failed or was interrupted"));
        // 修复后不再有 pending
        assertTrue(SubagentSessionMaintenance.pendingToolCalls(patched.getContext()).isEmpty());
    }

    /** 幂等：没有 pending 时不做任何变更（返回 null → 不写库）。 */
    @Test
    void planRepair_noPendingReturnsNull() {
        AgentState state = AgentState.builder()
                .sessionId("sub-1")
                .userId("user-1")
                .context(List.of(
                        assistantWith(use("c1", "execute")),
                        toolWith(result("c1", ToolResultState.SUCCESS))))
                .build();
        assertNull(SubagentSessionMaintenance.planRepair(state));
    }

    /**
     * 扫描覆盖面回归：子会话总量超过扫描上限时，迭代序靠后的会话不能沦为永久盲区。
     *
     * <p>会话集合的迭代顺序每轮固定，上限一旦小于总量，超出部分就被整段截断：其中的悬挂调用
     * 永远不会被对账，只能落到框架硬失败上，而日志上看不出守卫曾跑过。</p>
     */
    @Test
    void repairPendingSubSessionsForUser_scansSessionsBeyondFormerLimit() {
        String userId = "user-1";
        String late = "sub-zzz-late";
        Set<String> sessionIds = new LinkedHashSet<>();
        for (int i = 0; i < 120; i++) {
            sessionIds.add(String.format("sub-%03d", i));
        }
        sessionIds.add(late);

        AgentState lateState = AgentState.builder()
                .sessionId(late)
                .userId(userId)
                .context(List.of(assistantWith(use("c-late", "edit_file"))))
                .build();

        AgentStateStore store = mock(AgentStateStore.class);
        when(store.listSessionIds(userId)).thenReturn(sessionIds);
        when(store.get(eq(userId), anyString(), eq("agent_state"), eq(AgentState.class)))
                .thenAnswer(invocation -> {
                    String sessionId = invocation.getArgument(1);
                    if (late.equals(sessionId)) {
                        return Optional.of(lateState);
                    }
                    return Optional.<AgentState>empty();
                });

        SessionActivityTracker tracker = mock(SessionActivityTracker.class);
        when(tracker.isRunning(anyString())).thenReturn(false);

        SubagentSessionMaintenance maintenance = new SubagentSessionMaintenance(tracker);

        int repaired = maintenance.repairPendingSubSessionsForUser(store, userId);

        assertEquals(1, repaired, "排在迭代序末尾的会话也必须被对账");
        verify(store).save(eq(userId), eq(late), eq("agent_state"), any(AgentState.class));
    }
}
