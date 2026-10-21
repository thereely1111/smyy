package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.service.notify.SessionAlertPushService;
import com.tipdm.agentplatform.web.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话级告警推送订阅（对话页推送面板的数据源）。
 *
 * <p>语义（V44）：opt-in + 显式选渠道——{@code enabled} 是总开关，
 * {@code channelIds} 是用户勾选的外发渠道白名单；enabled=true 但白名单为空
 * 时同样不外发。渠道列表本身由 {@code GET /api/channels} 提供，本接口只管
 * 「本会话推不推、推到哪几个」。</p>
 */
@RestController
public class SessionAlertPushController {

    private final SessionAlertPushService service;
    private final SessionAccessGuard accessGuard;

    public SessionAlertPushController(SessionAlertPushService service,
                                      SessionAccessGuard accessGuard) {
        this.service = service;
        this.accessGuard = accessGuard;
    }

    @GetMapping("/api/sessions/{sessionId}/alert-push")
    public Map<String, Object> get(@PathVariable("sessionId") String sessionId,
                                   HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", service.effectiveEnabled(userId, sessionId));
        body.put("explicit", service.isExplicit(userId, sessionId));
        body.put("channelIds", service.selectedChannelIds(userId, sessionId));
        return body;
    }

    @PutMapping("/api/sessions/{sessionId}/alert-push")
    public Map<String, Object> set(@PathVariable("sessionId") String sessionId,
                                   @RequestBody Map<String, Object> body,
                                   HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        Object raw = body.get("enabled");
        if (!(raw instanceof Boolean enabled)) {
            throw ApiException.badRequest("enabled 必须为布尔值");
        }
        List<String> channelIds = null;
        if (body.containsKey("channelIds") && body.get("channelIds") != null) {
            if (!(body.get("channelIds") instanceof List<?> rawList)) {
                throw ApiException.badRequest("channelIds 必须为字符串数组");
            }
            channelIds = rawList.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        service.set(userId, sessionId, enabled, channelIds);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("enabled", enabled);
        resp.put("explicit", true);
        resp.put("channelIds", service.selectedChannelIds(userId, sessionId));
        return resp;
    }
}
