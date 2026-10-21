package com.tipdm.agentplatform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.domain.AguiEventRecord;
import com.tipdm.agentplatform.repository.AguiEventRecordRepository;
import com.tipdm.agentplatform.web.auth.JwtInterceptor;
import com.tipdm.agentplatform.web.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AG-UI 事件落库只读视图。
 *
 * <p>{@code GET /api/agui-events?sessionId=main-xxx&afterSequence=0&limit=500}
 * 按序号升序返回该会话已落库的 AG-UI 协议事件（payload 为线上 JSON 原样），
 * 供事件回放 / 审计 / 前端断点续传。</p>
 *
 * <p>带 {@code latest=true} 时只返回序号游标、不返回事件，供调用方建立增量
 * 起点（见 {@link #watermark}）。</p>
 *
 * <p>权限：查询强制按当前登录用户过滤（写入时的 userId 即归属），他人会话查不到
 * 任何行；未登录直接 401。</p>
 */
@RestController
@RequestMapping("/api/agui-events")
public class AguiEventController {

    private static final int MAX_LIMIT = 1000;

    private final AguiEventRecordRepository repository;
    private final ObjectMapper objectMapper;

    public AguiEventController(AguiEventRecordRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam("sessionId") String sessionId,
            @RequestParam(value = "afterSequence", required = false, defaultValue = "0") long afterSequence,
            @RequestParam(value = "limit", required = false, defaultValue = "500") int limit,
            @RequestParam(value = "latest", required = false, defaultValue = "false") boolean latest,
            HttpServletRequest request) {
        if (sessionId == null || sessionId.isBlank()) {
            throw ApiException.badRequest("sessionId 不能为空");
        }
        String userId = JwtInterceptor.resolveUserId(request);
        if (userId == null || userId.isBlank() || JwtInterceptor.ANONYMOUS_USER.equals(userId)) {
            throw ApiException.unauthorized("未登录或登录已过期，请重新登录");
        }
        if (latest) {
            return watermark(userId, sessionId);
        }
        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        Pageable pageable = PageRequest.of(0, effectiveLimit);

        List<AguiEventRecord> records =
                afterSequence > 0
                        ? repository.findByUserIdAndSessionIdAndEventSequenceGreaterThanOrderByEventSequenceAsc(
                                userId, sessionId, afterSequence, pageable)
                        : repository.findByUserIdAndSessionIdOrderByEventSequenceAsc(
                                userId, sessionId, pageable);

        List<Map<String, Object>> events = new ArrayList<>(records.size());
        for (AguiEventRecord record : records) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("eventSequence", record.getEventSequence());
            item.put("eventType", record.getEventType());
            item.put("runId", record.getRunId());
            item.put("recordedAt", record.getCreatedAt() == null ? null : record.getCreatedAt().toString());
            item.put("payload", parsePayload(record.getPayload()));
            events.add(item);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("count", events.size());
        body.put("nextAfterSequence", events.isEmpty()
                ? afterSequence
                : records.get(records.size() - 1).getEventSequence());
        body.put("events", events);
        return body;
    }

    /**
     * 水位线查询（{@code latest=true}）：不返回事件，只回两个序号游标。
     *
     * <ul>
     *   <li>{@code nextAfterSequence} —— 已落库的最大序号，作为增量起点；</li>
     *   <li>{@code lastRunStartSequence} —— 最后一条 {@code RUN_STARTED} 的序号
     *       （0 = 尚无）。</li>
     * </ul>
     *
     * <p>两者都是跟随「服务端自行发起的 run」所需的起点：只有最大序号才能不重放
     * 历史；而客户端对这类 run 的发现天生滞后（转录轮询 30s/120s），只按最大序号
     * 起跟会把 run 开头整段漏掉，所以同时给出本轮起点，由调用方按场景取用。</p>
     *
     * <p>{@code latest} 字段是能力标记：忽略该参数的旧版本后端会按普通查询返回
     * 最早的一页事件，调用方见到 {@code latest != true} 即应放弃增量跟随，
     * 避免把历史事件当增量重放。</p>
     */
    private Map<String, Object> watermark(String userId, String sessionId) {
        Long max = repository.findMaxSequenceByUserIdAndSessionId(userId, sessionId);
        Long lastRunStart = repository.findMaxRunStartSequenceByUserIdAndSessionId(userId, sessionId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("count", 0);
        body.put("nextAfterSequence", max == null ? 0L : max);
        body.put("lastRunStartSequence", lastRunStart == null ? 0L : lastRunStart);
        body.put("latest", true);
        body.put("events", List.of());
        return body;
    }

    /** payload 列是 JSONB 文本，解析为 JsonNode 让响应里保持结构化 JSON。 */
    private JsonNode parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            // JSONB 不应出现坏 JSON；防御性兜底为字符串节点
            return objectMapper.getNodeFactory().textNode(payload);
        }
    }
}
