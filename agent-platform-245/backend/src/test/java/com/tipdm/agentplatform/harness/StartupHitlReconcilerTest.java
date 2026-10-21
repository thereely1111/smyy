package com.tipdm.agentplatform.harness;

import com.tipdm.agentplatform.config.HitlTimeoutProperties;
import com.tipdm.agentplatform.domain.AgentSessionOwner;
import com.tipdm.agentplatform.service.AgentAssemblyService;
import com.tipdm.agentplatform.service.PendingFormInterruptStore;
import com.tipdm.agentplatform.service.SessionOwnerService;
import com.tipdm.agentplatform.service.notify.AlertNotifier;
import com.tipdm.agentplatform.service.notify.SessionNotifyPublisher;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StartupHitlReconcilerTest {

    private static final String USER = "user-1";
    private static final String AGENT = "agent-1";
    private static final String SESSION = "main-1";

    private SessionOwnerService sessionOwnerService;
    private AgentAssemblyService assemblyService;
    private SessionActivityTracker activityTracker;
    private PendingFormInterruptStore formStore;
    private AlertNotifier alertNotifier;
    private SessionNotifyPublisher notifyPublisher;
    private StartupHitlReconciler reconciler;

    @BeforeEach
    void setUp() {
        sessionOwnerService = mock(SessionOwnerService.class);
        assemblyService = mock(AgentAssemblyService.class);
        activityTracker = mock(SessionActivityTracker.class);
        formStore = new PendingFormInterruptStore();
        alertNotifier = mock(AlertNotifier.class);
        notifyPublisher = mock(SessionNotifyPublisher.class);
        reconciler = new StartupHitlReconciler(
                sessionOwnerService, assemblyService, activityTracker, formStore,
                new HitlTimeoutProperties(), alertNotifier, notifyPublisher);
        when(activityTracker.isRunning(anyString())).thenReturn(false);
    }

    /** 真实持久化形态：TOOL_SUSPENDED 落盘后 state=allowed，且无 tool_result。 */
    private static ToolUseBlock askingGate(String id, String name) {
        return new ToolUseBlock(id, name, Map.of(), Map.of())
                .withState(ToolCallState.ALLOWED);
    }

    private static Msg assistantWith(ContentBlock... blocks) {
        return Msg.builderForRole(MsgRole.ASSISTANT)
                .content(List.of(blocks))
                .build();
    }

    private static Msg userMsg(String text) {
        return Msg.builderForRole(MsgRole.USER)
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    private MemoryStore givenAgentWithState(AgentState state) {
        MemoryStore store = new MemoryStore(SESSION);
        store.stored = state;
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.getStateStore()).thenReturn(store);
        when(assemblyService.requireAssembledById(AGENT)).thenReturn(agent);
        when(sessionOwnerService.all()).thenReturn(List.of(owner(SESSION)));
        return store;
    }

    private static AgentSessionOwner owner(String sessionId) {
        AgentSessionOwner o = new AgentSessionOwner();
        o.setAgentId(AGENT);
        o.setUserId(USER);
        o.setSessionId(sessionId);
        return o;
    }

    private static AgentState stateWith(List<Msg> context) {
        return AgentState.builder().sessionId(SESSION).userId(USER).context(context).build();
    }

    private static String textOf(Msg msg) {
        return msg.getContent().stream()
                .filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                .map(TextBlock::getText).findFirst().orElse("");
    }

    @Test
    void destroysOrphanGateWithTimeoutTrace() {
        MemoryStore store = givenAgentWithState(
                stateWith(List.of(assistantWith(askingGate("g1", "ui_gate")))));

        reconciler.sweep();

        // tool_use 置 FINISHED + [HITL_TIMEOUT] 结果 + 留痕消息，三者同次 CAS 保存
        List<Msg> context = store.stored.getContext();
        assertEquals(2, context.size());
        List<ContentBlock> content = context.get(0).getContent();
        ToolUseBlock use = content.stream()
                .filter(ToolUseBlock.class::isInstance).map(ToolUseBlock.class::cast)
                .findFirst().orElseThrow();
        assertEquals(ToolCallState.FINISHED, use.getState());
        ToolResultBlock result = content.stream()
                .filter(ToolResultBlock.class::isInstance).map(ToolResultBlock.class::cast)
                .findFirst().orElseThrow();
        assertEquals("g1", result.getId());
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertTrue(ToolResultBlockText.text(result).startsWith("[HITL_TIMEOUT]"));
        // 留痕说明渲染在智能体输出下方：等待用户审核超时
        Msg trace = context.get(1);
        assertEquals(MsgRole.USER, trace.getRole());
        assertTrue(textOf(trace).startsWith("[平台留痕] 等待用户审核超时"));
        verify(alertNotifier).publish(any(AlertNotifier.AlertRequest.class));
        verify(notifyPublisher).publish(eq(USER), eq(SESSION), eq(AGENT), eq(null),
                eq(SessionNotifyPublisher.REASON_INTERRUPT));
    }

    @Test
    void traceIncludesGateTitleWhenAvailable() {
        ToolUseBlock gate = new ToolUseBlock("g1", "ui_gate",
                Map.of("title", "目录 v1.1 确认"), Map.of())
                .withState(ToolCallState.ASKING);
        MemoryStore store = givenAgentWithState(stateWith(List.of(assistantWith(gate))));

        reconciler.sweep();

        Msg trace = store.stored.getContext().get(1);
        assertTrue(textOf(trace).contains("「目录 v1.1 确认」"));
    }

    @Test
    void skipsSubSessions() {
        MemoryStore store = new MemoryStore("sub-1");
        store.stored = stateWith(List.of(assistantWith(askingGate("g1", "ui_gate"))));
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.getStateStore()).thenReturn(store);
        when(assemblyService.requireAssembledById(AGENT)).thenReturn(agent);
        when(sessionOwnerService.all()).thenReturn(List.of(owner("sub-1")));

        reconciler.sweep();

        assertEquals(ToolCallState.ALLOWED, ((ToolUseBlock) store.stored.getContext()
                .get(0).getContent().get(0)).getState());
        verify(alertNotifier, never()).publish(any());
    }

    @Test
    void skipsRunningSessions() {
        MemoryStore store = new MemoryStore(SESSION);
        store.stored = stateWith(List.of(assistantWith(askingGate("g1", "ui_gate"))));
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.getStateStore()).thenReturn(store);
        when(assemblyService.requireAssembledById(AGENT)).thenReturn(agent);
        when(sessionOwnerService.all()).thenReturn(List.of(owner(SESSION)));
        when(activityTracker.isRunning(SESSION)).thenReturn(true);

        reconciler.sweep();

        assertEquals(ToolCallState.ALLOWED, ((ToolUseBlock) store.stored.getContext()
                .get(0).getContent().get(0)).getState());
        verify(alertNotifier, never()).publish(any());
    }

    @Test
    void skipsWhenUserAlreadyRepliedAfterGate() {
        // 闸门之后主编已发言（user-message 降级答复）→ 线程已推进，不销毁
        MemoryStore store = givenAgentWithState(stateWith(List.of(
                assistantWith(askingGate("g1", "ui_gate")),
                userMsg("不用了，换方案"))));

        reconciler.sweep();

        assertEquals(ToolCallState.ALLOWED, ((ToolUseBlock) store.stored.getContext()
                .get(0).getContent().get(0)).getState());
        verify(alertNotifier, never()).publish(any());
    }

    @Test
    void skipsGateThatAlreadyHasResult() {
        // 闸门已有 tool_result（主编提交回填 / reaper 已补写）→ 已消费，不销毁
        MemoryStore store = givenAgentWithState(stateWith(List.of(
                assistantWith(askingGate("g1", "ui_gate"),
                        ToolResultBlock.of("g1", "ui_gate",
                                TextBlock.builder().text("主编已提交").build())
                                .withState(ToolResultState.SUCCESS)))));

        reconciler.sweep();

        assertEquals(ToolCallState.ALLOWED, ((ToolUseBlock) store.stored.getContext()
                .get(0).getContent().get(0)).getState());
        verify(alertNotifier, never()).publish(any());
    }

    @Test
    void skipsWhenRegistrationStillExists() {
        // 登记仍在 → 不是重启孤儿，交给 reaper 正常流程
        formStore.put(AGENT, USER, SESSION, new PendingFormInterruptStore.PendingFormInterrupt(
                "reply:g1", "reply", "g1", "ui_gate", "{}", "2026-09-20T00:00:00Z"));
        MemoryStore store = givenAgentWithState(
                stateWith(List.of(assistantWith(askingGate("g1", "ui_gate")))));

        reconciler.sweep();

        assertEquals(ToolCallState.ALLOWED, ((ToolUseBlock) store.stored.getContext()
                .get(0).getContent().get(0)).getState());
        verify(alertNotifier, never()).publish(any());
    }

    @Test
    void nonHitlAskingToolsAreIgnored() {
        MemoryStore store = givenAgentWithState(
                stateWith(List.of(assistantWith(askingGate("t1", "browser_click")))));

        reconciler.sweep();

        assertEquals(ToolCallState.ALLOWED, ((ToolUseBlock) store.stored.getContext()
                .get(0).getContent().get(0)).getState());
        verify(alertNotifier, never()).publish(any());
    }

    @Test
    void noAssistantMessageMeansNoop() {
        MemoryStore store = new MemoryStore(SESSION);
        store.stored = stateWith(List.of(userMsg("你好")));
        HarnessAgent agent = mock(HarnessAgent.class);
        when(agent.getStateStore()).thenReturn(store);
        when(assemblyService.requireAssembledById(AGENT)).thenReturn(agent);
        when(sessionOwnerService.all()).thenReturn(List.of(owner(SESSION)));

        reconciler.sweep();

        verify(alertNotifier, never()).publish(any());
    }

    /** 工具方法：扁平化 ToolResultBlock 的文本输出。 */
    private static final class ToolResultBlockText {
        static String text(ToolResultBlock block) {
            StringBuilder sb = new StringBuilder();
            for (ContentBlock b : block.getOutput()) {
                if (b instanceof TextBlock tb && tb.getText() != null) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                    sb.append(tb.getText());
                }
            }
            return sb.toString();
        }
    }

    /** 内存版 state store（CAS 语义与 Postgres 实现一致）。 */
    private static final class MemoryStore implements AgentStateStore {
        final String sessionId;
        AgentState stored;
        long version = 1;

        MemoryStore(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void save(String userId, String sessionId, String key, State value) {
            stored = (AgentState) value;
            version++;
        }

        @Override
        public void save(String userId, String sessionId, String key, List<? extends State> values) {
        }

        @Override
        public <T extends State> Optional<T> get(
                String userId, String sessionId, String key, Class<T> type) {
            if (stored != null && type.isInstance(stored)) {
                return Optional.of(type.cast(stored));
            }
            return Optional.empty();
        }

        @Override
        public <T extends State> List<T> getList(
                String userId, String sessionId, String key, Class<T> itemType) {
            return List.of();
        }

        @Override
        public boolean exists(String userId, String sessionId) {
            return stored != null;
        }

        @Override
        public Set<String> listSessionIds(String userId) {
            return stored == null ? Set.of() : Set.of(sessionId);
        }

        @Override
        public void delete(String userId, String sessionId) {
            stored = null;
        }

        @Override
        public <T extends State> VersionedState<T> getVersioned(
                String userId, String sessionId, String key, Class<T> type) {
            if (stored == null || !type.isInstance(stored)) {
                return new VersionedState<>(null, -1);
            }
            return new VersionedState<>(type.cast(stored), version);
        }

        @Override
        public long saveIfVersion(
                String userId, String sessionId, String key, State value, long expectedVersion) {
            if (expectedVersion != version) {
                return -1;
            }
            stored = (AgentState) value;
            version++;
            return version;
        }
    }
}
