package com.tipdm.agentplatform.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import java.util.Set;

class HitlRecoveryGuardMiddlewareTest {

    private static final String USER = "user-1";
    private static final String SESSION = "wb-session-1";

    private static ToolUseBlock hitlUse(String id, String name) {
        return new ToolUseBlock(id, name, Map.of(), Map.of());
    }

    private static ToolResultBlock result(String id, ToolResultState state) {
        return ToolResultBlock.builder()
                .id(id)
                .name("ui_gate")
                .output(TextBlock.builder().text("ok").build())
                .state(state)
                .build();
    }

    private static Msg userWith(String text) {
        return new UserMessage(text);
    }

    private static Msg assistantWith(ContentBlock... blocks) {
        return Msg.builderForRole(MsgRole.ASSISTANT).content(List.of(blocks)).build();
    }

    private static Msg toolWith(ContentBlock... blocks) {
        return Msg.builderForRole(MsgRole.TOOL).content(List.of(blocks)).build();
    }

    private static AgentState stateWith(List<Msg> context) {
        return AgentState.builder().sessionId(SESSION).userId(USER).context(context).build();
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
        public Set<String> listSessionIds(String userId) {
            return stored == null ? Set.of() : Set.of(SESSION);
        }

        @Override
        public void delete(String userId, String sessionId) {
            stored = null;
        }
    }

    @Test
    void pendingGateWithoutResultTriggersGuard() {
        MemoryStore store = new MemoryStore();
        store.stored = stateWith(List.of(assistantWith(hitlUse("g1", "ui_gate"))));
        assertTrue(HitlRecoveryGuardMiddleware.hasPendingHitl(store, USER, SESSION));
    }

    @Test
    void pendingFormAdvancedTriggersGuard() {
        MemoryStore store = new MemoryStore();
        store.stored = stateWith(List.of(assistantWith(hitlUse("f1", "ui_form_advanced"))));
        assertTrue(HitlRecoveryGuardMiddleware.hasPendingHitl(store, USER, SESSION));
    }

    @Test
    void submittedGateDoesNotTriggerGuard() {
        MemoryStore store = new MemoryStore();
        store.stored = stateWith(List.of(
                assistantWith(hitlUse("g1", "ui_gate")),
                toolWith(result("g1", ToolResultState.SUCCESS))));
        assertFalse(HitlRecoveryGuardMiddleware.hasPendingHitl(store, USER, SESSION));
    }

    @Test
    void runningResultMeansGateStillPending() {
        MemoryStore store = new MemoryStore();
        // ui_gate 挂起占位（RUNNING）＝ 正在等待主编提交，不是"已决"——任何非提交输入
        // 都可能触发框架的自动补错，守卫必须注入恢复指示（2026-09-10 实证假通过）。
        store.stored = stateWith(List.of(
                assistantWith(hitlUse("g1", "ui_gate")),
                toolWith(result("g1", ToolResultState.RUNNING))));
        assertTrue(HitlRecoveryGuardMiddleware.hasPendingHitl(store, USER, SESSION));
    }

    @Test
    void autoGenErrorResultMeansGateStillPending() {
        MemoryStore store = new MemoryStore();
        // enablePendingToolRecovery 的自动补错（ERROR）不是主编决定，仍须恢复指示。
        store.stored = stateWith(List.of(
                assistantWith(hitlUse("g1", "ui_gate")),
                toolWith(result("g1", ToolResultState.ERROR))));
        assertTrue(HitlRecoveryGuardMiddleware.hasPendingHitl(store, USER, SESSION));
    }

    /**
     * 平台表单通道提交（主编点「通过」后前端发成一条 user 消息）＝ 闸门已决。
     *
     * <p>回归（2026-09-11 实测）：提交走不到 ui_gate 自己的 tool_result（那条永远是框架
     * 自动补的 ERROR 占位），只认 SUCCESS 会把已批准的闸门永久判为未决——既反复注入恢复
     * 指示（以 user 消息泄漏成前端气泡），又让 wakeup 守卫永久延后唤醒。</p>
     */
    @Test
    void formSubmissionAfterGateMeansDecided() {
        MemoryStore store = new MemoryStore();
        store.stored = stateWith(List.of(
                assistantWith(hitlUse("g1", "ui_gate")),
                toolWith(result("g1", ToolResultState.ERROR)),
                userWith("（用户通过表单提交以下信息）\n- 审核结论：通过")));
        assertFalse(HitlRecoveryGuardMiddleware.hasPendingHitl(store, USER, SESSION));
    }

    /** 空表单提交（「未填写字段」）同样表示主编已作决定。 */
    @Test
    void emptyFormSubmissionAfterGateMeansDecided() {
        MemoryStore store = new MemoryStore();
        store.stored = stateWith(List.of(
                assistantWith(hitlUse("g1", "ui_gate")),
                toolWith(result("g1", ToolResultState.RUNNING)),
                userWith("（用户提交了表单，但未填写字段）")));
        assertFalse(HitlRecoveryGuardMiddleware.hasPendingHitl(store, USER, SESSION));
    }

    /**
     * 闸门**之前**的表单提交不算（那是上一道闸门的提交，不能给当前未决闸门开后门）。
     */
    @Test
    void formSubmissionBeforeCurrentGateStillPending() {
        MemoryStore store = new MemoryStore();
        store.stored = stateWith(List.of(
                userWith("（用户通过表单提交以下信息）\n- 审核结论：通过"),
                assistantWith(hitlUse("g1", "ui_gate")),
                toolWith(result("g1", ToolResultState.ERROR))));
        assertTrue(HitlRecoveryGuardMiddleware.hasPendingHitl(store, USER, SESSION));
    }

    /** 提交本身作为本次输入送达时也不注入：此刻闸门正在被决定，指示会与之矛盾。 */
    @Test
    void formSubmissionAsInputSkipsInjection() {
        MemoryStore store = new MemoryStore();
        store.stored = stateWith(List.of(
                assistantWith(hitlUse("g1", "ui_gate")),
                toolWith(result("g1", ToolResultState.ERROR))));
        AgentInput submission = new AgentInput(
                List.of(userWith("（用户通过表单提交以下信息）\n- 审核结论：通过")));
        assertFalse(HitlRecoveryGuardMiddleware.hasPendingHitl(store, USER, SESSION, submission));
    }

    @Test
    void submissionCarriedInInputSkipsInjection() {
        MemoryStore store = new MemoryStore();
        // 上下文里闸门仍是挂起占位，但本次输入携带了该闸门的提交结果（AG-UI resume）——
        // 视为主编正在提交，由框架 resume 通道处理，不注入恢复指示。
        store.stored = stateWith(List.of(
                assistantWith(hitlUse("g1", "ui_gate")),
                toolWith(result("g1", ToolResultState.RUNNING))));
        AgentInput resumeInput =
                new AgentInput(List.of(toolWith(result("g1", ToolResultState.SUCCESS))));
        assertFalse(HitlRecoveryGuardMiddleware.hasPendingHitl(store, USER, SESSION, resumeInput));
    }

    @Test
    void ordinaryToolPendingDoesNotTriggerGuard() {
        MemoryStore store = new MemoryStore();
        store.stored = stateWith(List.of(assistantWith(hitlUse("r1", "read_file"))));
        assertFalse(HitlRecoveryGuardMiddleware.hasPendingHitl(store, USER, SESSION));
    }

    @Test
    void noPersistedStateDoesNotTriggerGuard() {
        assertFalse(HitlRecoveryGuardMiddleware.hasPendingHitl(
                new MemoryStore(), USER, SESSION));
    }

    @Test
    void recoveryInstructionInjectedOnlyOnce() {
        AgentInput base = new AgentInput(List.of(new UserMessage("继续审核")));
        AgentInput guarded = HitlRecoveryGuardMiddleware.withRecoveryInstruction(base);
        assertEquals(2, guarded.msgs().size());
        assertTrue(guarded.msgs().get(0).getTextContent()
                .startsWith(HitlRecoveryGuardMiddleware.RECOVERY_INSTRUCTION));
        // 幂等：已注入过则不再重复
        AgentInput twice = HitlRecoveryGuardMiddleware.withRecoveryInstruction(guarded);
        assertEquals(2, twice.msgs().size());
    }

    @Test
    void ordinaryInputUntouchedWhenNoPending() {
        AgentInput base = new AgentInput(List.of(new UserMessage("继续审核")));
        AgentInput out = HitlRecoveryGuardMiddleware.withRecoveryInstruction(base);
        // withRecoveryInstruction 总是注入；onAgent 的路由（无挂起则不调用）另行覆盖。
        assertSame(base.msgs().get(0), out.msgs().get(1));
    }

    // ---------- findPendingHitlGate：补登记与唤醒守卫的同源判据 ----------

    /**
     * 悬空闸门（tool_use 无任何结果块）必须被定位到——这正是后台工具转异步 /
     * run 被中断留下的形态，也是 {@code reconcileDanglingGate} 要救的那一类。
     */
    @Test
    void findPendingHitlGate_locatesDanglingGateWithoutResult() {
        MemoryStore store = new MemoryStore();
        store.stored = stateWith(List.of(assistantWith(hitlUse("g1", "ui_gate"))));

        Optional<HitlRecoveryGuardMiddleware.PendingHitlGate> gate =
                HitlRecoveryGuardMiddleware.findPendingHitlGate(store, USER, SESSION);

        assertTrue(gate.isPresent());
        assertEquals("g1", gate.get().toolCallId());
        assertEquals("ui_gate", gate.get().toolUse().getName());
    }

    /**
     * 口径同源回归：定位结果与 {@code hasPendingHitl} 必须一致。
     *
     * <p>两者分叉的后果是「唤醒守卫认定有未决闸门、补登记认定无此闸门」的三方矛盾，
     * 会话被永久延后且超时取消失效（2026-10-07 main-3f077bd9 实证）。
     * 遍历几组形态逐一断言两者一致。</p>
     */
    @Test
    void findPendingHitlGate_agreesWithHasPendingHitl() {
        // ① 无结果块：都判未决
        MemoryStore dangling = new MemoryStore();
        dangling.stored = stateWith(List.of(assistantWith(hitlUse("g1", "ui_gate"))));
        assertEquals(
                HitlRecoveryGuardMiddleware.hasPendingHitl(dangling, USER, SESSION),
                HitlRecoveryGuardMiddleware.findPendingHitlGate(dangling, USER, SESSION).isPresent());

        // ② RUNNING 占位：都判未决
        MemoryStore running = new MemoryStore();
        running.stored = stateWith(List.of(
                assistantWith(hitlUse("g1", "ui_gate")),
                toolWith(result("g1", ToolResultState.RUNNING))));
        assertEquals(
                HitlRecoveryGuardMiddleware.hasPendingHitl(running, USER, SESSION),
                HitlRecoveryGuardMiddleware.findPendingHitlGate(running, USER, SESSION).isPresent());

        // ③ SUCCESS：都判已决
        MemoryStore decided = new MemoryStore();
        decided.stored = stateWith(List.of(
                assistantWith(hitlUse("g1", "ui_gate")),
                toolWith(result("g1", ToolResultState.SUCCESS))));
        assertEquals(
                HitlRecoveryGuardMiddleware.hasPendingHitl(decided, USER, SESSION),
                HitlRecoveryGuardMiddleware.findPendingHitlGate(decided, USER, SESSION).isPresent());

        // ④ 表单提交消息在闸门之后：都判已决
        MemoryStore submitted = new MemoryStore();
        submitted.stored = stateWith(List.of(
                assistantWith(hitlUse("g1", "ui_gate")),
                toolWith(result("g1", ToolResultState.ERROR)),
                userWith("（用户通过表单提交以下信息）\n- 审核结论：通过")));
        assertEquals(
                HitlRecoveryGuardMiddleware.hasPendingHitl(submitted, USER, SESSION),
                HitlRecoveryGuardMiddleware.findPendingHitlGate(submitted, USER, SESSION).isPresent());

        // ⑤ 只有普通工具：都判无
        MemoryStore ordinary = new MemoryStore();
        ordinary.stored = stateWith(List.of(assistantWith(hitlUse("r1", "read_file"))));
        assertEquals(
                HitlRecoveryGuardMiddleware.hasPendingHitl(ordinary, USER, SESSION),
                HitlRecoveryGuardMiddleware.findPendingHitlGate(ordinary, USER, SESSION).isPresent());
    }

    /** 只认上下文里最后一个 HITL 闸门：更早的闸门已决与否不影响当前判定。 */
    @Test
    void findPendingHitlGate_locatesTheLatestGateOnly() {
        MemoryStore store = new MemoryStore();
        store.stored = stateWith(List.of(
                assistantWith(hitlUse("g1", "ui_gate")),
                toolWith(result("g1", ToolResultState.SUCCESS)),
                assistantWith(hitlUse("g2", "ui_gate"))));

        Optional<HitlRecoveryGuardMiddleware.PendingHitlGate> gate =
                HitlRecoveryGuardMiddleware.findPendingHitlGate(store, USER, SESSION);

        assertTrue(gate.isPresent());
        assertEquals("g2", gate.get().toolCallId());
    }

    /** 空 store / null store 不抛异常，返回空（尽力而为语义）。 */
    @Test
    void findPendingHitlGate_toleratesMissingState() {
        assertTrue(HitlRecoveryGuardMiddleware.findPendingHitlGate(
                new MemoryStore(), USER, SESSION).isEmpty());
        assertTrue(HitlRecoveryGuardMiddleware.findPendingHitlGate(null, USER, SESSION).isEmpty());
    }
}