package com.tipdm.agentplatform.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tipdm.agentplatform.service.PathDisclosureSanitizer;
import io.agentscope.core.agui.adapter.strategy.AguiEventEnricher;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEventType;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AG-UI 实时流路径脱敏拦截器（框架 {@link AguiEventEnricher} 扩展点的平台实现）。
 *
 * <p>背景：{@link PathDisclosureSanitizer} 此前只接在 transcript 读取侧
 * （{@code GET /transcript} 组装时），而前端工具调用轨迹主要来自<b>实时 SSE 流</b>
 * ——用户在运行期间看到的仍是 {@code cd "/Volumes/.../workspace/runtimes/..."}
 * 宿主绝对路径，且 mergeTranscript 对已存在的本地块不覆盖，原始路径会一直停留
 * 到刷新页面（2026-09-15 用户反馈实证）。本拦截器把脱敏前移到实时流出口，
 * 与转录侧形成同一口径。</p>
 *
 * <p>实现约束与策略：</p>
 * <ul>
 *   <li><b>TOOL_CALL_ARGS 是增量 JSON 分片</b>（模型逐 token 流式，路径常被切成
 *       跨片）——无法对单分片可靠匹配。因此本实现<b>缓冲</b>同一 toolCallId 的
 *       全部分片，在 TOOL_CALL_END 时以单条「完整已脱敏 ARGS」事件一次性重发。
 *       AG-UI 客户端按 delta 追加语义消费，单条完整分片协议兼容；副作用只是
 *       工具入参在轨迹里不再是逐字出现，而是完成时整体出现。</li>
 *   <li><b>其余事件</b>（TOOL_CALL_RESULT / TOOL_CALL_START / STATE_DELTA /
 *       文本增量等）：对序列化后的 JSON 串做字符串级脱敏。{@link
 *       PathDisclosureSanitizer} 的字符类排除引号与空白，匹配不可能跨越 JSON
 *       字符串边界，替换值 {@code workspace/} 不含 JSON 结构字符——替换后
 *       JSON 恒合法。文本/状态增量中跨片断裂的路径会漏过（尽力而为，
 *       刷新后由转录侧兜底）。</li>
 *   <li><b>失败放行</b>：任何异常（序列化/反序列化失败）原样返回事件，
 *       绝不因脱敏失败阻断实时流。</li>
 *   <li><b>顺序</b>：{@code @Order(0)} 保证先于 {@link AguiEventLogEnricher}
 *       执行——落库内容与线上输出保持同一口径（均为脱敏后）。</li>
 * </ul>
 *
 * <p>缓冲按 {@code runId:toolCallId} 键控，RUN_FINISHED / RUN_ERROR 时按 runId
 * 清理，多 run 并发互不干扰；单缓冲上限 {@value #MAX_ARGS_CHARS} 字符，超限
 * 停止追加（超长入参本就无法正常渲染，宁可截断不可撑爆内存）。</p>
 */
@Component
@Order(0)
public class AguiEventPathMaskingEnricher implements AguiEventEnricher {

    private static final Logger log = LoggerFactory.getLogger(AguiEventPathMaskingEnricher.class);

    private static final int MAX_ARGS_CHARS = 1_000_000;

    private final ObjectMapper mapper = new ObjectMapper();

    /** runId:toolCallId -> 已缓冲的完整入参 JSON。 */
    private final Map<String, StringBuilder> toolArgsBuffers = new ConcurrentHashMap<>();

    @Override
    public List<AguiEvent> enrich(
            AgentEvent source, List<AguiEvent> events, AguiStreamContext context) {
        if (events == null || events.isEmpty()) {
            return events;
        }
        List<AguiEvent> out = null;
        for (int i = 0; i < events.size(); i++) {
            AguiEvent event = events.get(i);
            List<AguiEvent> replacement;
            try {
                replacement = transform(event);
            } catch (Exception e) {
                // 失败放行：脱敏是尽力而为的展示层加固，绝不阻断实时流
                log.debug("路径脱敏失败，事件原样放行（type={}）: {}",
                        event.getType(), e.getMessage());
                replacement = List.of(event);
            }
            if (replacement.size() == 1 && replacement.get(0) == event) {
                if (out != null) {
                    out.add(event);
                }
                continue;
            }
            if (out == null) {
                out = new ArrayList<>(events.size() + 1);
                for (int j = 0; j < i; j++) {
                    out.add(events.get(j));
                }
            }
            out.addAll(replacement);
        }
        return out != null ? out : events;
    }

    /**
     * 返回替换事件列表：{@code List.of(event)} 表示原样透传；空列表表示吞并；
     * 否则为替换/追加序列。
     */
    private List<AguiEvent> transform(AguiEvent event) throws Exception {
        AguiEventType type = event.getType();
        if (type == AguiEventType.TOOL_CALL_ARGS) {
            bufferArgsDelta(event);
            return List.of();
        }
        if (type == AguiEventType.TOOL_CALL_END) {
            return flushBufferedArgs(event);
        }
        if (type == AguiEventType.RUN_FINISHED || type == AguiEventType.RUN_ERROR) {
            String runId = event.getRunId();
            if (runId != null) {
                toolArgsBuffers.keySet().removeIf(key -> key.startsWith(runId + ":"));
            }
            // fallthrough: RUN 事件本身也走默认字符串级脱敏
        }
        return maskSerialized(event);
    }

    private void bufferArgsDelta(AguiEvent event) throws Exception {
        JsonNode node = mapper.readTree(JsonUtils.getJsonCodec().toJson(event));
        String toolCallId = node.path("toolCallId").asText("");
        if (toolCallId.isEmpty()) {
            throw new IllegalStateException("TOOL_CALL_ARGS 缺少 toolCallId");
        }
        String runId = node.path("runId").asText("");
        String delta = node.path("delta").asText("");
        StringBuilder buffer = toolArgsBuffers.computeIfAbsent(
                runId + ":" + toolCallId, k -> new StringBuilder());
        synchronized (buffer) {
            if (buffer.length() + delta.length() <= MAX_ARGS_CHARS) {
                buffer.append(delta);
            }
        }
    }

    private List<AguiEvent> flushBufferedArgs(AguiEvent endEvent) throws Exception {
        JsonNode node = mapper.readTree(JsonUtils.getJsonCodec().toJson(endEvent));
        String toolCallId = node.path("toolCallId").asText("");
        String runId = node.path("runId").asText("");
        String threadId = node.path("threadId").asText("");
        StringBuilder buffer = toolArgsBuffers.remove(runId + ":" + toolCallId);
        if (buffer == null) {
            // 没有缓冲过分片（如 resume 直发完整入参）——END 原样透传
            return List.of(endEvent);
        }
        String fullArgs;
        synchronized (buffer) {
            fullArgs = buffer.toString();
        }
        ObjectNode argsNode = mapper.createObjectNode();
        argsNode.put("type", "TOOL_CALL_ARGS");
        argsNode.put("threadId", threadId);
        argsNode.put("runId", runId);
        argsNode.put("toolCallId", toolCallId);
        argsNode.put("delta", PathDisclosureSanitizer.sanitize(fullArgs));
        AguiEvent argsEvent = JsonUtils.getJsonCodec()
                .fromJson(mapper.writeValueAsString(argsNode), AguiEvent.class);
        return List.of(argsEvent, endEvent);
    }

    /** 默认策略：序列化后做字符串级脱敏，内容有变化才做 JSON 往返。 */
    private List<AguiEvent> maskSerialized(AguiEvent event) throws Exception {
        String json = JsonUtils.getJsonCodec().toJson(event);
        String masked = PathDisclosureSanitizer.sanitize(json);
        if (masked.equals(json)) {
            return List.of(event);
        }
        return List.of(JsonUtils.getJsonCodec().fromJson(masked, AguiEvent.class));
    }
}
