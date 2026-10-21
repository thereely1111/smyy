package com.tipdm.agentplatform.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.service.PendingFormInterruptStore.PendingFormInterrupt;
import io.agentscope.core.agui.AguiInterruptConstants;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WakeupInterruptRegistrar} 的登记语义：框架映射回灌修复、存活探针、
 * 以及悬空闸门兜底对账。
 *
 * <p>背景（2026-10-05 闸门3 实测）：框架 {@code trackPendingInterrupts} 对
 * {@code pendingInterruptsByThread} 做整体 put 替换，会把 wakeup 路径 compute 合并进
 * 去的中断抹掉；登记表升为权威源后，消费方（GET form-interrupts / waitingForHuman）
 * 读取时回灌修复。这里用反射直接注入 {@code pendingInterruptsCache} 绕开
 * ApplicationContext 反射链，单测合并/幂等/降级逻辑本身。</p>
 *
 * <p>2026-10-07 增补 {@code reconcileDanglingGate} 用例：修复「run 未以 TOOL_SUSPENDED
 * 收尾 ⇒ 闸门两处登记全跳过 ⇒ 唤醒被永久延后」的死锁（main-3f077bd9 实证）。</p>
 */
class WakeupInterruptRegistrarTest {

    private static final String SESSION = "main-test";
    private static final String INTERRUPT_ID = "daf63d2d-65db-4f22-93c6-38f05e2bda20:call_gate3";
    private static final String TOOL_CALL_ID = "call_gate3";

    private WakeupInterruptRegistrar registrar;
    private ConcurrentMap<String, Map<String, AguiEvent.Interrupt>> frameworkMap;

    @BeforeEach
    void setUp() throws Exception {
        store = new com.tipdm.agentplatform.service.PendingFormInterruptStore();
        registrar = new WakeupInterruptRegistrar(
                store, new ObjectMapper(), mock(com.tipdm.agentplatform.service.notify.SessionNotifyPublisher.class));
        frameworkMap = new ConcurrentHashMap<>();
        // 绕开 ApplicationContext 反射链：resolvePendingInterrupts 首行即返回该缓存
        Field cache = WakeupInterruptRegistrar.class.getDeclaredField("pendingInterruptsCache");
        cache.setAccessible(true);
        cache.set(registrar, frameworkMap);
    }

    private static <T> T mock(Class<T> type) {
        return org.mockito.Mockito.mock(type);
    }

    private PendingFormInterrupt entry(String interruptId, String toolCallId) {
        return new PendingFormInterrupt(
                interruptId,
                "daf63d2d-65db-4f22-93c6-38f05e2bda20",
                toolCallId,
                "ui_gate",
                "{\"title\":\"闸门3 · 全书知识概要确认\",\"pages\":[]}",
                "2026-10-05T20:13:50Z");
    }

    @Test
    void repairsMissingInterruptIntoFrameworkMap() {
        int repaired = registrar.ensureFrameworkRegistration(
                SESSION, List.of(entry(INTERRUPT_ID, TOOL_CALL_ID)));

        assertEquals(1, repaired);
        Map<String, AguiEvent.Interrupt> live = frameworkMap.get(SESSION);
        assertNotNull(live);
        AguiEvent.Interrupt event = live.get(INTERRUPT_ID);
        assertNotNull(event);
        assertEquals(TOOL_CALL_ID, event.toolCallId());
        assertEquals("ui_gate",
                event.metadata().get(AguiInterruptConstants.METADATA_TOOL_NAME));
        // toolInput 从存储 JSON 串解析回 Map；toolContent 保留原始串
        Object parsed = event.metadata().get(AguiInterruptConstants.METADATA_TOOL_INPUT);
        assertTrue(parsed instanceof Map<?, ?> map && "闸门3 · 全书知识概要确认".equals(map.get("title")));
        assertEquals("daf63d2d-65db-4f22-93c6-38f05e2bda20",
                event.metadata().get(AguiInterruptConstants.METADATA_REPLY_ID));
    }

    @Test
    void isIdempotentWhenInterruptAlreadyPresent() {
        registrar.ensureFrameworkRegistration(SESSION, List.of(entry(INTERRUPT_ID, TOOL_CALL_ID)));
        Map<String, AguiEvent.Interrupt> first = frameworkMap.get(SESSION);

        int second = registrar.ensureFrameworkRegistration(
                SESSION, List.of(entry(INTERRUPT_ID, TOOL_CALL_ID)));

        assertEquals(0, second);
        assertSame(first, frameworkMap.get(SESSION));
    }

    @Test
    void mergesWithoutDroppingSiblings() {
        // 框架映射已有同线程的另一条中断：回灌必须合并保留，不得覆盖
        AguiEvent.Interrupt sibling = new AguiEvent.Interrupt(
                "other-reply:call_other", "tool_call", null, "call_other", null, null, Map.of());
        frameworkMap.put(SESSION, Map.of("other-reply:call_other", sibling));

        registrar.ensureFrameworkRegistration(SESSION, List.of(entry(INTERRUPT_ID, TOOL_CALL_ID)));

        Map<String, AguiEvent.Interrupt> live = frameworkMap.get(SESSION);
        assertEquals(2, live.size());
        assertSame(sibling, live.get("other-reply:call_other"));
        assertNotNull(live.get(INTERRUPT_ID));
    }

    @Test
    void skipsEntryWithoutInterruptId() {
        PendingFormInterrupt blank = new PendingFormInterrupt(
                "", null, TOOL_CALL_ID, "ui_gate", "{}", "2026-10-05T20:13:50Z");

        int repaired = registrar.ensureFrameworkRegistration(SESSION, List.of(blank));

        assertEquals(0, repaired);
        assertNull(frameworkMap.get(SESSION));
    }

    @Test
    void degradesToZeroWhenReflectionUnwired() throws Exception {
        Field cache = WakeupInterruptRegistrar.class.getDeclaredField("pendingInterruptsCache");
        cache.setAccessible(true);
        cache.set(registrar, null); // applicationContext 也为 null ⇒ 反射链不可达

        int repaired = registrar.ensureFrameworkRegistration(
                SESSION, List.of(entry(INTERRUPT_ID, TOOL_CALL_ID)));

        assertEquals(0, repaired); // 降级：不抛异常，调用方仍返回登记表
    }

    @Test
    void filterLiveRemainsProbeOnly() {
        // 探针语义不被回灌改动影响：框架映射没有该 id ⇒ 判为陈旧剔除
        registrar.ensureFrameworkRegistration(SESSION, List.of(entry(INTERRUPT_ID, TOOL_CALL_ID)));
        frameworkMap.remove(SESSION);

        List<PendingFormInterrupt> live = registrar.filterLive(
                SESSION, List.of(entry(INTERRUPT_ID, TOOL_CALL_ID)));

        assertTrue(live.isEmpty());
    }

    // ---------- reconcileDanglingGate：悬空闸门兜底补登记（2026-10-07） ----------

    /**
     * 根因回归：run 未以 TOOL_SUSPENDED 收尾（工具转后台 / run 中断）时，闸门 tool_use
     * 留在上下文却没有任何内存登记。补登记必须把它写进框架映射 + 平台登记表，
     * 否则三方矛盾（表单无人可提交、30min reaper 扫不到、唤醒守卫永久延后）。
     */
    @Test
    void reconcileDanglingGate_registersGateThatRunNeverRegistered() {
        int repaired = registrar.reconcileDanglingGate(
                danglingGateStore("call_dangling"), "agent-1", "user-1", SESSION);

        assertEquals(1, repaired);
        // 平台登记表：可被前端查回并恢复表单
        List<PendingFormInterrupt> stored = store.listBySession("agent-1", SESSION);
        assertEquals(1, stored.size());
        assertEquals("call_dangling", stored.get(0).toolCallId());
        assertEquals("ui_gate", stored.get(0).toolName());
        // 框架映射：resume 契约成立，且 filterLive 认它存活
        List<PendingFormInterrupt> live =
                registrar.filterLive(SESSION, store.listBySession("agent-1", SESSION));
        assertEquals(1, live.size());
    }

    /** 幂等：重复对账不重复登记（否则 resume 会被要求覆盖多条同 id 中断）。 */
    @Test
    void reconcileDanglingGate_isIdempotent() {
        assertEquals(1, registrar.reconcileDanglingGate(
                danglingGateStore("call_dangling"), "agent-1", "user-1", SESSION));
        assertEquals(0, registrar.reconcileDanglingGate(
                danglingGateStore("call_dangling"), "agent-1", "user-1", SESSION));
        assertEquals(1, store.listBySession("agent-1", SESSION).size());
    }

    /**
     * 已提交（SUCCESS）的闸门不得被补登记——否则主编已经拍过的闸门会重新弹表单，
     * 且 reaper 会在 30 分钟后把它当超时取消掉（取消动作会改写 agent state）。
     */
    @Test
    void reconcileDanglingGate_skipsDecidedGate() {
        AgentStateStore store = memoryStore(List.of(
                assistantWith(hitlUse("call_done", "ui_gate")),
                toolWith(result("call_done", ToolResultState.SUCCESS))));

        assertEquals(0, registrar.reconcileDanglingGate(store, "agent-1", "user-1", SESSION));
        assertTrue(store().listBySession("agent-1", SESSION).isEmpty());
    }

    /** 表单提交消息在闸门之后 ⇒ 已决，不补登记。 */
    @Test
    void reconcileDanglingGate_skipsGateAfterFormSubmission() {
        AgentStateStore st = memoryStore(List.of(
                assistantWith(hitlUse("call_sub", "ui_gate")),
                toolWith(result("call_sub", ToolResultState.ERROR)),
                new UserMessage("（用户通过表单提交以下信息）\n- 审核结论：通过")));

        assertEquals(0, registrar.reconcileDanglingGate(st, "agent-1", "user-1", SESSION));
    }

    /** 无未决闸门 / store 为 null / sessionId 为空：一律 0 条，不抛异常。 */
    @Test
    void reconcileDanglingGate_toleratesNoGateAndBadArgs() {
        assertEquals(0, registrar.reconcileDanglingGate(
                memoryStore(List.of(assistantWith(hitlUse("call_r", "read_file")))),
                "agent-1", "user-1", SESSION));
        assertEquals(0, registrar.reconcileDanglingGate(
                memoryStore(List.of()), "agent-1", "user-1", SESSION));
        assertEquals(0, registrar.reconcileDanglingGate(null, "agent-1", "user-1", SESSION));
        assertEquals(0, registrar.reconcileDanglingGate(
                danglingGateStore("call_x"), "agent-1", "user-1", " "));
        assertEquals(0, registrar.reconcileDanglingGate(
                danglingGateStore("call_x"), null, "user-1", SESSION));
        assertEquals(0, registrar.reconcileDanglingGate(
                danglingGateStore("call_x"), "agent-1", null, SESSION));
    }

    /** 闸门 tool_use 已有 RUNNING 占位结果（挂起中）也补登记——那是「等待主编」而非已决。 */
    @Test
    void reconcileDanglingGate_registersRunningPlaceholderGate() {
        AgentStateStore st = memoryStore(List.of(
                assistantWith(hitlUse("call_run", "ui_gate")),
                toolWith(result("call_run", ToolResultState.RUNNING))));

        assertEquals(1, registrar.reconcileDanglingGate(st, "agent-1", "user-1", SESSION));
        assertEquals("call_run", store().listBySession("agent-1", SESSION).get(0).toolCallId());
    }

    // ---------- 测试脚手架 ----------

    private com.tipdm.agentplatform.service.PendingFormInterruptStore store;

    private com.tipdm.agentplatform.service.PendingFormInterruptStore store() {
        return store;
    }

    private static ToolUseBlock hitlUse(String id, String name) {
        return new ToolUseBlock(id, name, Map.of(), Map.of());
    }

    private static ToolResultBlock result(String id, ToolResultState state) {
        return ToolResultBlock.builder()
                .id(id)
                .name("ui_gate")
                .output(io.agentscope.core.message.TextBlock.builder().text("x").build())
                .state(state)
                .build();
    }

    private static Msg assistantWith(ToolUseBlock block) {
        return Msg.builderForRole(MsgRole.ASSISTANT).content(List.of(block)).build();
    }

    private static Msg toolWith(ToolResultBlock block) {
        return Msg.builderForRole(MsgRole.TOOL).content(List.of(block)).build();
    }

    private AgentStateStore memoryStore(List<Msg> context) {
        AgentState state = AgentState.builder()
                .sessionId(SESSION).userId("user-1").context(context).build();
        AgentStateStore st = new MemoryStore();
        ((MemoryStore) st).stored = state;
        return st;
    }

    /** 悬空闸门：只有 tool_use，没有配对的 tool_result。 */
    private AgentStateStore danglingGateStore(String toolCallId) {
        return memoryStore(List.of(assistantWith(hitlUse(toolCallId, "ui_gate"))));
    }

    /** 最小内存版 state store：只支撑 get(userId, sessionId, "agent_state", AgentState.class)。 */
    private static final class MemoryStore implements AgentStateStore {
        AgentState stored;

        @Override
        public void save(String userId, String sessionId, String key, State value) {}

        @Override
        public void save(String userId, String sessionId, String key, List<? extends State> values) {}

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
        public java.util.Set<String> listSessionIds(String userId) {
            return stored == null ? java.util.Set.of() : java.util.Set.of(SESSION);
        }

        @Override
        public void delete(String userId, String sessionId) {
            stored = null;
        }
    }
}
