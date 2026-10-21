package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.domain.PlatformNotification;
import com.tipdm.agentplatform.repository.PlatformNotificationRepository;
import com.tipdm.agentplatform.web.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 平台通知只读 + 已读标记。
 *
 * <p>与 SSE 通道 {@code GET /api/notify/stream} 互补：SSE 负责在线即时感知，
 * 本接口负责「进站时补齐离线期间错过的告警」与「已读状态维护」。
 * 所有查询强制按当前登录用户过滤，他人通知查不到任何行。</p>
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final PlatformNotificationRepository repository;
    private final SessionAccessGuard accessGuard;

    public NotificationController(PlatformNotificationRepository repository,
                                  SessionAccessGuard accessGuard) {
        this.repository = repository;
        this.accessGuard = accessGuard;
    }

    /**
     * 最近通知列表。
     *
     * @param unreadOnly 只返回未读（铃铛面板默认按全量展示、角标取未读数）
     */
    @GetMapping
    public Map<String, Object> list(
            @RequestParam(value = "unreadOnly", defaultValue = "false") boolean unreadOnly,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        Pageable pageable = PageRequest.of(0, effectiveLimit);
        List<PlatformNotification> items = unreadOnly
                ? repository.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(userId, pageable)
                : repository.findByUserIdOrderByCreatedAtDesc(userId, pageable);

        List<Map<String, Object>> notifications = new ArrayList<>(items.size());
        for (PlatformNotification n : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", n.getId() == null ? null : n.getId().toString());
            row.put("level", n.getLevel());
            row.put("title", n.getTitle());
            row.put("body", n.getBody());
            row.put("sessionId", n.getSessionId());
            row.put("agentId", n.getAgentId());
            row.put("readAt", n.getReadAt() == null ? null : n.getReadAt().toString());
            row.put("createdAt", n.getCreatedAt() == null ? null : n.getCreatedAt().toString());
            notifications.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", notifications.size());
        body.put("unreadCount", repository.countByUserIdAndReadAtIsNull(userId));
        body.put("notifications", notifications);
        return body;
    }

    /** 未读数量（铃铛角标；前端也用它做 SSE 之外的高频兜底轮询）。 */
    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount(HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("unreadCount", repository.countByUserIdAndReadAtIsNull(userId));
        return body;
    }

    /** 标记单条已读（越权 id 查不到，按 404 处理）。 */
    @PostMapping("/{id}/read")
    public Map<String, Object> markRead(@PathVariable("id") String id, HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        UUID parsed = parseUuid(id);
        int updated = repository.markRead(userId, parsed, Instant.now());
        if (updated == 0) {
            throw ApiException.notFound("通知不存在或已读");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", parsed.toString());
        body.put("unreadCount", repository.countByUserIdAndReadAtIsNull(userId));
        return body;
    }

    /** 全部标记已读。 */
    @PostMapping("/read-all")
    public Map<String, Object> markAllRead(HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        int updated = repository.markAllRead(userId, Instant.now());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("updated", updated);
        body.put("unreadCount", 0);
        return body;
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("通知 id 格式不合法");
        }
    }
}
