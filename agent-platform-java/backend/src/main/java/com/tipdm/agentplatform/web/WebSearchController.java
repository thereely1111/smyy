package com.tipdm.agentplatform.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 联网搜索可用性状态（只读）：对话页输入框「联网搜索」开关的置灰依据。
 *
 * <p>只暴露「平台是否已配置博查搜索 Key」这一布尔事实，<b>绝不回显 Key 本身</b>
 * （同 external_api_key 不回显的既有约定）。未登录不可访问（JwtAuthFilter 已覆盖
 * /api/*，此处 requireUserId 为第二道防线）。</p>
 */
@RestController
public class WebSearchController {

    private final SessionAccessGuard accessGuard;
    private final boolean bochaConfigured;

    public WebSearchController(SessionAccessGuard accessGuard,
                               @Value("${agent-platform.bocha.api-key:}") String bochaApiKey) {
        this.accessGuard = accessGuard;
        this.bochaConfigured = bochaApiKey != null && !bochaApiKey.isBlank();
    }

    @GetMapping("/api/web-search/status")
    public Map<String, Object> status(HttpServletRequest request) {
        accessGuard.requireUserId(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configured", bochaConfigured);
        return body;
    }
}
