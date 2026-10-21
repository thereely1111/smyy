package com.tipdm.agentplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.domain.AgentConfig;
import com.tipdm.agentplatform.web.dto.RuntimeDtos.SessionTranscriptResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话转录（{@link AgentRuntimeService#transcript}）映射契约测试。
 *
 * <p>覆盖：AgentScope Msg/ContentBlock → 前端 AG-UI Msg 形状的转换（role 小写、
 * 时间戳归一化、tool_call/tool_result 的 state/input/output 字段），以及
 * 会话不存在 / 上下文为空时的空列表兜底。</p>
 */
class AgentRuntimeServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AgentRuntimeService newService(AgentStateStore store) {
        AgentAssemblyService assembly = mock(AgentAssemblyService.class);
        HarnessAgent agent = mock(HarnessAgent.class);
        when(assembly.requireAssembled(any())).thenReturn(agent);
        when(agent.getStateStore()).thenReturn(store);
        return new AgentRuntimeService(
                assembly,
                mock(SessionWorkspaceManager.class),
                mock(SessionOwnerService.class),
                new com.tipdm.agentplatform.harness.SessionActivityTracker(),
                new PendingFormInterruptStore(),
                mock(SessionCleanupService.class),
                new com.tipdm.agentplatform.harness.RunSubscriptionRegistry(),
                objectMapper);
    }

    private Msg userMsg(String id, String text) {
        return Msg.builder()
                .id(id)
                .role(MsgRole.USER)
                .name("user")
                .timestamp("2026-09-04 17:25:36.470")
                .content(List.of(new TextBlock.Builder().text(text).build()))
                .build();
    }

    private Msg assistantMsg(String id) {
        return Msg.builder()
                .id(id)
                .role(MsgRole.ASSISTANT)
                .name("assistant")
                .timestamp("2026-09-04 17:25:36.500")
                .content(List.of(
                        new ThinkingBlock.Builder().thinking("thinking...").build(),
                        new TextBlock.Builder().text("now spawn").build(),
                        new ToolUseBlock.Builder()
                                .id("call_abc")
                                .name("agent_spawn")
                                .input(Map.of("task", "t1"))
                                .state(ToolCallState.FINISHED)
                                .build()))
                .build();
    }

    private Msg toolMsg(String id) {
        return Msg.builder()
                .id(id)
                .role(MsgRole.TOOL)
                .name("tool")
                .timestamp("2026-09-04 17:25:36.600")
                .content(List.of(ToolResultBlock.builder()
                        .id("call_abc")
                        .name("agent_spawn")
                        .output(List.of(new TextBlock.Builder().text("status: ok").build()))
                        .state(ToolResultState.SUCCESS)
                        .build()))
                .build();
    }

    /**
     * 历史快照里的 running tool_result（异步后台化占位符）应归一为 success，
     * 否则前端把已完成的 agent_send 永久显示为 spinner（2026-09-12 实测）。
     */
    @Test
    void normalizesRunningToolResultToSuccessInTranscript() {
        AgentStateStore store = mock(AgentStateStore.class);
        Msg assistant = Msg.builder()
                .id("a-1")
                .role(MsgRole.ASSISTANT)
                .name("assistant")
                .timestamp("2026-09-04 17:25:36.500")
                .content(List.of(
                        new TextBlock.Builder().text("now spawn").build(),
                        new ToolUseBlock.Builder()
                                .id("call_abc")
                                .name("agent_send")
                                .input(Map.of("task", "t1"))
                                .state(ToolCallState.FINISHED)
                                .build()))
                .build();
        Msg tool = Msg.builder()
                .id("t-1")
                .role(MsgRole.TOOL)
                .name("tool")
                .timestamp("2026-09-04 17:25:36.600")
                .content(List.of(ToolResultBlock.builder()
                        .id("call_abc")
                        .name("agent_send")
                        .output(List.of(new TextBlock.Builder()
                                .text("<system-reminder>Tool 'agent_send' is running in background")
                                .build()))
                        .state(ToolResultState.RUNNING)
                        .build()))
                .build();
        when(store.get(eq("u1"), eq("s1"), eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.of(stateWith(List.of(assistant, tool))));

        SessionTranscriptResponse response =
                newService(store).transcript(mock(AgentConfig.class), "u1", "s1", null, null, null);

        // tool_result 折叠进 assistant 消息且 state 归一为 success
        Map<String, Object> result = response.messages().get(0).content().stream()
                .filter(b -> "tool_result".equals(b.get("type")))
                .findFirst()
                .orElse(null);
        assertNotNull(result);
        assertEquals("success", result.get("state"));
    }

    private AgentState stateWith(List<Msg> context) {
        return AgentState.builder()
                .sessionId("s1")
                .userId("u1")
                .context(context)
                .build();
    }

    @Test
    void mapsContextToTranscriptShape() {
        AgentStateStore store = mock(AgentStateStore.class);
        AgentState state = stateWith(List.of(userMsg("u-1", "开始"), assistantMsg("a-1"), toolMsg("t-1")));
        when(store.get(eq("u1"), eq("s1"), eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.of(state));

        SessionTranscriptResponse response =
                newService(store).transcript(mock(AgentConfig.class), "u1", "s1", null, null, null);

        assertEquals("s1", response.sessionId());
        // user + assistant 两条：独立 role=tool 消息的 tool_result 已折叠进
        // assistant（搬空后丢弃），与实时流结构一致
        assertEquals(2, response.messages().size());

        // USER：role 小写 + text 块 + 时间戳归一化为 ISO（含 T、Z 结尾）
        SessionTranscriptResponse.Message user = response.messages().get(0);
        assertEquals("user", user.role());
        assertEquals("u-1", user.id());
        assertEquals("开始", user.content().get(0).get("text"));
        assertTrue(((String) user.createdAt()).contains("T"));
        assertTrue(((String) user.createdAt()).endsWith("Z"));
        assertEquals("text", user.content().get(0).get("type"));

        // ASSISTANT：thinking / text / tool_call 三块 + 折叠进来的 tool_result
        SessionTranscriptResponse.Message assistant = response.messages().get(1);
        assertEquals("assistant", assistant.role());
        assertEquals("thinking", assistant.content().get(0).get("type"));
        assertEquals("text", assistant.content().get(1).get("type"));
        Map<String, Object> toolCall = assistant.content().get(2);
        assertEquals("tool_call", toolCall.get("type"));
        assertEquals("agent_spawn", toolCall.get("name"));
        assertEquals("finished", toolCall.get("state"));
        assertTrue(((String) toolCall.get("input")).contains("t1"));
        // tool_result 折叠进同一消息：前端按单消息配对 call↔result，
        // 不折叠则恢复的历史里已完成的工具永久 spinner（2026-09-12 实测）
        Map<String, Object> folded = assistant.content().get(3);
        assertEquals("tool_result", folded.get("type"));
        assertEquals("call_abc", folded.get("id"));
        assertEquals("status: ok", folded.get("output"));
        assertEquals(4, assistant.content().size());
        // 独立 role=tool 消息已被搬空丢弃
        assertEquals(2, response.messages().size());
    }

    /**
     * 时间戳格式回归：框架 {@code Msg.getTimestamp()} 用
     * {@code "yyyy-MM-dd HH:mm:ss.SSS"}（空格分隔、含本地时区），
     * 只按 ISO 解析会失败并让 thinking 时长恒为 0（2026-09-12 实测）。
     */
    @Test
    void computesThinkingDurationFromSpaceSeparatedTimestamp() {
        AgentStateStore store = mock(AgentStateStore.class);
        Msg first = Msg.builder()
                .id("u-1")
                .role(MsgRole.USER)
                .name("user")
                .timestamp("2026-09-04 17:25:36.470")
                .content(List.of(new TextBlock.Builder().text("开始").build()))
                .build();
        Msg second = Msg.builder()
                .id("a-1")
                .role(MsgRole.ASSISTANT)
                .name("assistant")
                .timestamp("2026-09-04 17:26:06.470") // 间隔 30s
                .content(List.of(
                        new ThinkingBlock.Builder().thinking("thinking...").build(),
                        new TextBlock.Builder().text("答复").build()))
                .build();
        when(store.get(eq("u1"), eq("s1"), eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.of(stateWith(List.of(first, second))));

        SessionTranscriptResponse response =
                newService(store).transcript(mock(AgentConfig.class), "u1", "s1", null, null, null);

        Map<String, Object> thinking = response.messages().get(1).content().get(0);
        assertEquals("thinking", thinking.get("type"));
        assertEquals(30L, ((Number) thinking.get("durationSeconds")).longValue());
    }

    @Test
    void returnsEmptyMessagesWhenSessionMissing() {
        AgentStateStore store = mock(AgentStateStore.class);
        when(store.get(eq("u1"), eq("s1"), eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.empty());

        SessionTranscriptResponse response =
                newService(store).transcript(mock(AgentConfig.class), "u1", "s1", null, null, null);

        assertEquals("s1", response.sessionId());
        assertTrue(response.messages().isEmpty());
    }

    @Test
    void returnsEmptyMessagesWhenContextNull() {
        AgentStateStore store = mock(AgentStateStore.class);
        when(store.get(eq("u1"), eq("s1"), eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.of(AgentState.builder().sessionId("s1").userId("u1").build()));

        SessionTranscriptResponse response =
                newService(store).transcript(mock(AgentConfig.class), "u1", "s1", null, null, null);

        assertTrue(response.messages().isEmpty());
        assertFalse(response.sessionId().isBlank());
    }

    // ---------------- 分批加载（2026-09-30） ----------------

    /** 五条 user 消息的 store，供分页测试使用。 */
    private AgentStateStore storeWithFive() {
        AgentStateStore store = mock(AgentStateStore.class);
        when(store.get(eq("u1"), eq("s1"), eq("agent_state"), eq(AgentState.class)))
                .thenReturn(Optional.of(stateWith(List.of(
                        userMsg("u-1", "一"),
                        userMsg("u-2", "二"),
                        userMsg("u-3", "三"),
                        userMsg("u-4", "四"),
                        userMsg("u-5", "五")))));
        return store;
    }

    @Test
    void limitReturnsTailWindowWithTotal() {
        SessionTranscriptResponse response =
                newService(storeWithFive()).transcript(mock(AgentConfig.class), "u1", "s1", 2, null, null);

        assertEquals(5, response.total());
        assertFalse(response.stale());
        assertEquals(2, response.messages().size());
        assertEquals("u-4", response.messages().get(0).id());
        assertEquals("u-5", response.messages().get(1).id());
    }

    @Test
    void afterCursorReturnsDeltaOnly() {
        AgentRuntimeService service = newService(storeWithFive());

        SessionTranscriptResponse delta =
                service.transcript(mock(AgentConfig.class), "u1", "s1", null, "u-3", null);
        assertEquals(5, delta.total());
        assertFalse(delta.stale());
        assertEquals(2, delta.messages().size());
        assertEquals("u-4", delta.messages().get(0).id());
        assertEquals("u-5", delta.messages().get(1).id());

        // 无新消息：空增量
        SessionTranscriptResponse empty =
                service.transcript(mock(AgentConfig.class), "u1", "s1", null, "u-5", null);
        assertTrue(empty.messages().isEmpty());
        assertEquals(5, empty.total());
    }

    @Test
    void staleWhenAfterCursorMissing() {
        // after 游标在服务端全量列表中不存在（压缩重写历史等）→ stale=true，
        // 客户端应全量重恢复；服务端列表为空时不算 stale（无事可做）。
        SessionTranscriptResponse response =
                newService(storeWithFive()).transcript(mock(AgentConfig.class), "u1", "s1", null, "bogus", null);
        assertTrue(response.stale());
        assertTrue(response.messages().isEmpty());
        assertEquals(5, response.total());
    }

    @Test
    void beforeCursorReturnsPrecedingPage() {
        SessionTranscriptResponse response =
                newService(storeWithFive()).transcript(mock(AgentConfig.class), "u1", "s1", 2, null, "u-4");

        assertEquals(5, response.total());
        assertFalse(response.stale());
        assertEquals(2, response.messages().size());
        assertEquals("u-2", response.messages().get(0).id());
        assertEquals("u-3", response.messages().get(1).id());
    }

    @Test
    void staleWhenBeforeCursorMissingFallsBackToTail() {
        SessionTranscriptResponse response =
                newService(storeWithFive()).transcript(mock(AgentConfig.class), "u1", "s1", 2, null, "bogus");

        assertTrue(response.stale());
        assertEquals(2, response.messages().size());
        assertEquals("u-4", response.messages().get(0).id());
    }
}
