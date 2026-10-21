package com.tipdm.agentplatform.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HitlTimeoutReaperTest {

    private static final String USER = "user-1";
    private static final String SESSION = "wb-session-1";

    private static ToolUseBlock hitlUse(String id, String name) {
        return new ToolUseBlock(id, name, Map.of(), Map.of());
    }

    private static Msg assistantWith(ContentBlock... blocks) {
        return Msg.builderForRole(MsgRole.ASSISTANT).content(List.of(blocks)).build();
    }

    private static AgentState stateWith(List<Msg> context) {
        return AgentState.builder().sessionId(SESSION).userId(USER).context(context).build();
    }

    /** 内存版 state store：支持 getVersioned / saveIfVersion（CAS 语义与 Postgres 实现一致）。 */
    private static final class VersionedMemoryStore implements AgentStateStore {
        AgentState stored;
        long version = 1;
        /** 置为 true 时下一次 saveIfVersion 返回 -1（模拟版本冲突），随后恢复。 */
        int conflictOnce = 0;

        @Override
        public void save(String userId, String sessionId, String key, State value) {
            stored = (AgentState) value;
            version++;
        }

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
        public <T extends State> java.util.List<T> getList(
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
            if (conflictOnce > 0) {
                conflictOnce--;
                return -1;
            }
            stored = (AgentState) value;
            version++;
            return version;
        }
    }

    @Test
    void patchAddsTimeoutResultAndFinishesToolUse() {
        VersionedMemoryStore store = new VersionedMemoryStore();
        store.stored = stateWith(List.of(assistantWith(hitlUse("g1", "ui_gate"))));

        HitlTimeoutReaper.PatchOutcome outcome = HitlTimeoutReaper.patchAgentState(
                store, USER, SESSION, "g1", "ui_gate", 30, "[平台留痕] 测试留痕");

        assertEquals(HitlTimeoutReaper.PatchOutcome.PATCHED, outcome);
        List<ContentBlock> content = store.stored.getContext().get(0).getContent();
        ToolUseBlock use = content.stream()
                .filter(ToolUseBlock.class::isInstance).map(ToolUseBlock.class::cast)
                .findFirst().orElseThrow();
        assertEquals(ToolCallState.FINISHED, use.getState());
        ToolResultBlock result = content.stream()
                .filter(ToolResultBlock.class::isInstance).map(ToolResultBlock.class::cast)
                .findFirst().orElseThrow();
        assertEquals("g1", result.getId());
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertTrue(ToolResultBlockText.text(result).contains("[HITL_TIMEOUT]"));
        assertTrue(ToolResultBlockText.text(result).contains("不是主编的批准"));
    }

    @Test
    void patchAppendsTranscriptTraceMessage() {
        // 留痕消息与 tool_result 同一次 CAS 保存：追加在上下文末尾、user 角色、
        // [平台留痕] 前缀（前端按注脚行渲染），超时事实进转录不依赖模型复述。
        VersionedMemoryStore store = new VersionedMemoryStore();
        store.stored = stateWith(List.of(assistantWith(hitlUse("g1", "ui_gate"))));

        HitlTimeoutReaper.patchAgentState(store, USER, SESSION, "g1", "ui_gate", 30,
                "[平台留痕] 审批超时自动取消：闸门/表单「目录」（toolCallId=g1）…");

        List<Msg> context = store.stored.getContext();
        assertEquals(2, context.size());
        Msg trace = context.get(1);
        assertEquals(MsgRole.USER, trace.getRole());
        String text = trace.getContent().stream()
                .filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                .map(TextBlock::getText).findFirst().orElse("");
        assertTrue(text.startsWith("[平台留痕]"));
    }

    @Test
    void patchDoesNotTraceWhenResultAlreadyExists() {
        // 并发提交场景：闸门已被主编消费，不得追加「超时取消」留痕。
        VersionedMemoryStore store = new VersionedMemoryStore();
        store.stored = stateWith(List.of(
                assistantWith(hitlUse("g1", "ui_gate"),
                        ToolResultBlock.of("g1", "ui_gate",
                                TextBlock.builder().text("主编已提交").build())
                                .withState(ToolResultState.SUCCESS))));

        HitlTimeoutReaper.PatchOutcome outcome = HitlTimeoutReaper.patchAgentState(
                store, USER, SESSION, "g1", "ui_gate", 30, "[平台留痕] 不应出现");

        assertEquals(HitlTimeoutReaper.PatchOutcome.ALREADY_RESOLVED, outcome);
        assertEquals(1, store.stored.getContext().size());
    }

    @Test
    void patchedGateIsDecidedForHitlRecoveryGuard() {
        // 口径联动：取消后的闸门在 HitlRecoveryGuardMiddleware 眼里必须是「已决」，
        // 否则下次输入会被注入「重新渲染闸门」恢复指示，取消立刻复活。
        VersionedMemoryStore store = new VersionedMemoryStore();
        store.stored = stateWith(List.of(assistantWith(hitlUse("g1", "ui_gate"))));
        HitlTimeoutReaper.patchAgentState(store, USER, SESSION, "g1", "ui_gate", 30,
                "[平台留痕] 测试留痕");

        // 结果追加在同一条 assistant 消息内（与现网折叠形态一致），守卫可按 id 命中。
        assertFalse(HitlRecoveryGuardMiddleware.hasPendingHitl(store, USER, SESSION));
    }

    @Test
    void patchIsIdempotentWhenResultAlreadyExists() {
        VersionedMemoryStore store = new VersionedMemoryStore();
        long versionBefore = 0;
        store.stored = stateWith(List.of(
                assistantWith(hitlUse("g1", "ui_gate"),
                        ToolResultBlock.of("g1", "ui_gate",
                                TextBlock.builder().text("[HITL_TIMEOUT] x").build())
                                .withState(ToolResultState.SUCCESS))));
        versionBefore = store.version;

        HitlTimeoutReaper.PatchOutcome outcome = HitlTimeoutReaper.patchAgentState(
                store, USER, SESSION, "g1", "ui_gate", 30, "[平台留痕] 不应出现");

        assertEquals(HitlTimeoutReaper.PatchOutcome.ALREADY_RESOLVED, outcome);
        assertEquals(versionBefore, store.version); // 未写状态（幂等 no-op）
    }

    @Test
    void patchRetriesOnCasConflict() {
        VersionedMemoryStore store = new VersionedMemoryStore();
        store.stored = stateWith(List.of(assistantWith(hitlUse("g1", "ui_gate"))));
        store.conflictOnce = 1; // 第一次保存返回 -1，第二次成功

        HitlTimeoutReaper.PatchOutcome outcome = HitlTimeoutReaper.patchAgentState(
                store, USER, SESSION, "g1", "ui_gate", 30, "[平台留痕] 测试留痕");

        // 关键契约：CAS 冲突不得落到 FAILED（FAILED 才保留登记重试）。注意内存桩的
        // contextMutable() 与存储对象共享同一列表——首次尝试已原地补上 tool_result，
        // 第二次重读因此看到「已有结果」走 ALREADY_RESOLVED；真实 Postgres 存储每次
        // 读取反序列化新副本，冲突后会走完整重试并 PATCHED。两者均非 FAILED 即合规。
        assertTrue(outcome == HitlTimeoutReaper.PatchOutcome.PATCHED
                || outcome == HitlTimeoutReaper.PatchOutcome.ALREADY_RESOLVED);
        assertTrue(store.stored.getContext().get(0).getContent().stream()
                .anyMatch(ToolResultBlock.class::isInstance));
    }

    @Test
    void missingStateTreatedAsStaleRegistration() {
        VersionedMemoryStore store = new VersionedMemoryStore();
        assertEquals(HitlTimeoutReaper.PatchOutcome.STALE,
                HitlTimeoutReaper.patchAgentState(
                        store, USER, SESSION, "g1", "ui_gate", 30, "[平台留痕] 不应出现"));
    }

    @Test
    void missingToolUseTreatedAsStaleRegistration() {
        // 上下文已被压缩/清理掉该 tool_use：不抛错，报告成功让调用方清登记。
        VersionedMemoryStore store = new VersionedMemoryStore();
        store.stored = stateWith(List.of(assistantWith(hitlUse("other", "read_file"))));
        assertEquals(HitlTimeoutReaper.PatchOutcome.STALE,
                HitlTimeoutReaper.patchAgentState(
                        store, USER, SESSION, "g1", "ui_gate", 30, "[平台留痕] 不应出现"));
    }

    @Test
    void traceMessageIsSingleFactLine() {
        // 定版文案（2026-09-20）：一句事实，时间由前端从 createdAt 渲染；
        // 完整审计信息落 agui_events，防误判口径在 tool_result，不进转录条。
        Instant at = Instant.parse("2026-09-20T06:00:00Z");
        String text = HitlTimeoutReaper.traceMessageText(
                "目录 v1.1 暂停裁决", "ui_gate", "g-12", 32, 30, at);
        assertEquals("[平台留痕] 等待用户审核超时，表单/闸门「目录 v1.1 暂停裁决」已自动关闭",
                text);
        // 标题缺失回退 toolName
        String fallback = HitlTimeoutReaper.traceMessageText(
                null, "ui_form_advanced", "f-1", 30, 30, at);
        assertEquals("[平台留痕] 等待用户审核超时，ui_form_advanced已自动关闭",
                fallback);
    }

    @Test
    void extractGateTitleParsesTitleField() {
        assertEquals("目录 v1.1 暂停裁决",
                HitlTimeoutReaper.extractGateTitle("{\"title\":\"目录 v1.1 暂停裁决\"}"));
        assertNull(HitlTimeoutReaper.extractGateTitle(null));
        assertNull(HitlTimeoutReaper.extractGateTitle(""));
        assertNull(HitlTimeoutReaper.extractGateTitle("not-json"));
        assertNull(HitlTimeoutReaper.extractGateTitle("{\"pages\":[]}"));
    }

    @Test
    void parseCreatedAtAcceptsInstantFormat() {
        Instant parsed = HitlTimeoutReaper.parseCreatedAt("2026-09-19T04:00:00Z");
        assertEquals(Instant.parse("2026-09-19T04:00:00Z"), parsed);
        assertNull(HitlTimeoutReaper.parseCreatedAt(null));
        assertNull(HitlTimeoutReaper.parseCreatedAt(""));
        assertNull(HitlTimeoutReaper.parseCreatedAt("2026-09-19 12:00:00"));
    }

    @Test
    void timeoutTextMarksNonDecision() {
        String text = HitlTimeoutReaper.timeoutResultText(45);
        assertTrue(text.contains("[HITL_TIMEOUT]"));
        assertTrue(text.contains("45 分钟"));
        // 关键口径：不得被模型解读为主编批准。
        assertTrue(text.contains("不是主编的批准"));
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
}
