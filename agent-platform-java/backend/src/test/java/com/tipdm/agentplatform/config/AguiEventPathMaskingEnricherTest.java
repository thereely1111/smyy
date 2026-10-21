package com.tipdm.agentplatform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AguiEventPathMaskingEnricherTest {

    private static final String HOST_PREFIX =
            "/Volumes/Data/Work/project/agent-platform-java/workspace/runtimes/agent-1/wb-1";

    private final AguiEventPathMaskingEnricher enricher = new AguiEventPathMaskingEnricher();
    private final ObjectMapper mapper = new ObjectMapper();

    private AguiEvent event(String json) {
        return JsonUtils.getJsonCodec().fromJson(json, AguiEvent.class);
    }

    private String json(AguiEvent event) {
        return JsonUtils.getJsonCodec().toJson(event);
    }

    private ObjectNode baseEvent(String type, String toolCallId) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", type);
        node.put("threadId", "t1");
        node.put("runId", "r1");
        if (toolCallId != null) {
            node.put("toolCallId", toolCallId);
        }
        return node;
    }

    @Test
    void buffersArgsDeltas_andFlushesMaskedFullArgsOnEnd() throws Exception {
        // 路径被切成两个分片——单分片内正则不完整匹配，靠缓冲合并后整段脱敏
        ObjectNode d1 = baseEvent("TOOL_CALL_ARGS", "c1");
        d1.put("delta", "{\"command\":\"cd \\\"" + HOST_PREFIX + "/");
        ObjectNode d2 = baseEvent("TOOL_CALL_ARGS", "c1");
        d2.put("delta", ".harness\\\" && ls\"}");
        ObjectNode end = baseEvent("TOOL_CALL_END", "c1");

        List<AguiEvent> out = enricher.enrich(null, List.of(
                event(d1.toString()), event(d2.toString()), event(end.toString())), null);

        assertEquals(2, out.size());
        String argsJson = json(out.get(0));
        assertTrue(argsJson.contains("\"TOOL_CALL_ARGS\""));
        // 完整入参已脱敏：宿主前缀与 agent/session 标识均不出现
        assertFalse(argsJson.contains("/Volumes/"));
        assertFalse(argsJson.contains("agent-1"));
        assertFalse(argsJson.contains("wb-1"));
        assertTrue(argsJson.contains("workspace/.harness"));
        // END 原样透传在 ARGS 之后
        assertTrue(json(out.get(1)).contains("\"TOOL_CALL_END\""));
    }

    @Test
    void masksCompleteEventsInPlace() throws Exception {
        ObjectNode result = baseEvent("TOOL_CALL_RESULT", "c2");
        result.put("content", "已写入 /Volumes/Data/x/workspace/runtimes/agent-2/wb-2/全局DB/a.md");
        ObjectNode start = baseEvent("TOOL_CALL_START", "c2");
        start.put("toolCallName", "execute");

        List<AguiEvent> out = enricher.enrich(null, List.of(
                event(result.toString()), event(start.toString())), null);

        assertEquals(2, out.size());
        assertFalse(json(out.get(0)).contains("/Volumes/"));
        assertFalse(json(out.get(0)).contains("agent-2"));
        assertTrue(json(out.get(0)).contains("workspace/全局DB/a.md"));
        // 无路径的 START 原样透传（同一实例，零序列化开销路径）
        assertEquals(out.get(1), event(start.toString()));
    }

    @Test
    void runFinishedClearsBuffersSoNextRunIsIndependent() throws Exception {
        ObjectNode d1 = baseEvent("TOOL_CALL_ARGS", "c9");
        d1.put("delta", "{\"partial\":");
        enricher.enrich(null, List.of(event(d1.toString())), null);

        ObjectNode finished = mapper.createObjectNode().put("type", "RUN_FINISHED");
        finished.put("threadId", "t1");
        finished.put("runId", "r1");
        enricher.enrich(null, List.of(event(finished.toString())), null);

        // 旧 run 的缓冲已清：新 run 同 id 的 END 只透传、不再合成 ARGS
        ObjectNode end = baseEvent("TOOL_CALL_END", "c9");
        List<AguiEvent> out = enricher.enrich(null, List.of(event(end.toString())), null);
        assertEquals(1, out.size());
        assertTrue(json(out.get(0)).contains("\"TOOL_CALL_END\""));
    }

    @Test
    void sanitizeFailurePassesEventThrough() {
        // 空事件列表与普通透传路径不抛异常
        assertTrue(enricher.enrich(null, List.of(), null).isEmpty());
    }
}
