package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.service.SessionExtensionService;
import com.tipdm.agentplatform.web.auth.JwtInterceptor;
import com.tipdm.agentplatform.web.dto.CommonDtos.PageResponse;
import com.tipdm.agentplatform.web.dto.SessionExtensionDtos.ExtensionResponse;
import com.tipdm.agentplatform.web.dto.SessionExtensionDtos.PutEntryRequest;
import com.tipdm.agentplatform.web.dto.SessionExtensionDtos.UpsertEntriesRequest;
import com.tipdm.agentplatform.web.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import java.util.HashMap;
import java.util.Map;

import java.util.UUID;

/**
 * 智能体会话扩展信息 RESTful 接口。
 *
 * <p>用于存储垂类智能体会话过程中的自定义数据（如教材编写智能体的章节数、目录结构等）。</p>
 *
 * <ul>
 *   <li>{@code GET    /api/agents/{agentId}/sessions/{sessionId}/extensions} — 获取全部扩展信息</li>
 *   <li>{@code PUT    /api/agents/{agentId}/sessions/{sessionId}/extensions} — 批量设置扩展条目</li>
 *   <li>{@code POST   /api/agents/{agentId}/sessions/{sessionId}/extensions/entries} — 设置单个扩展条目</li>
 *   <li>{@code DELETE /api/agents/{agentId}/sessions/{sessionId}/extensions/entries/{key}} — 删除单个扩展条目</li>
 *   <li>{@code DELETE /api/agents/{agentId}/sessions/{sessionId}/extensions} — 删除整个会话的扩展信息</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/agents/{agentId}")
public class SessionExtensionController {

    private final SessionExtensionService service;
    private final SessionAccessGuard accessGuard;

    public SessionExtensionController(SessionExtensionService service, SessionAccessGuard accessGuard) {
        this.service = service;
        this.accessGuard = accessGuard;
    }

    /**
     * 获取某会话的全部扩展信息。
     */
    @GetMapping("/sessions/{sessionId}/extensions")
    public ExtensionResponse get(
            @PathVariable UUID agentId,
            @PathVariable String sessionId,
            HttpServletRequest httpRequest) {
        String userId = requireUserId(httpRequest);
        accessGuard.requireSessionOwned(agentId, userId, sessionId, httpRequest);
        return service.get(agentId.toString(), sessionId);
    }

    /**
     * 批量设置扩展条目（覆盖已有 key，新增不存在的 key）。
     */
    @PutMapping("/sessions/{sessionId}/extensions")
    public ExtensionResponse upsertEntries(
            @PathVariable UUID agentId,
            @PathVariable String sessionId,
            @Valid @RequestBody UpsertEntriesRequest request,
            HttpServletRequest httpRequest) {
        String userId = requireUserId(httpRequest);
        return service.upsertEntries(agentId.toString(), sessionId, userId, request.entries());
    }

    /**
     * 设置单个扩展条目。
     */
    @PostMapping("/sessions/{sessionId}/extensions/entries")
    public ExtensionResponse putEntry(
            @PathVariable UUID agentId,
            @PathVariable String sessionId,
            @Valid @RequestBody PutEntryRequest request,
            HttpServletRequest httpRequest) {
        String userId = requireUserId(httpRequest);
        return service.putEntry(agentId.toString(), sessionId, userId, request.key(), request.value());
    }

    /**
     * 删除单个扩展条目。
     */
    @DeleteMapping("/sessions/{sessionId}/extensions/entries/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEntry(
            @PathVariable UUID agentId,
            @PathVariable String sessionId,
            @PathVariable String key,
            HttpServletRequest httpRequest) {
        String userId = requireUserId(httpRequest);
        accessGuard.requireSessionOwned(agentId, userId, sessionId, httpRequest);
        service.deleteEntry(agentId.toString(), sessionId, key);
    }

    /**
     * 删除整个会话的扩展信息。
     */
    @DeleteMapping("/sessions/{sessionId}/extensions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAll(
            @PathVariable UUID agentId,
            @PathVariable String sessionId,
            HttpServletRequest httpRequest) {
        String userId = requireUserId(httpRequest);
        // extension_entries 带 ON DELETE CASCADE，一次调用会连带删掉全部子表条目，
        // 不校验归属的话任何人拿到 sessionId 即可清空他人会话的扩展数据
        accessGuard.requireSessionOwned(agentId, userId, sessionId, httpRequest);
        service.deleteAll(agentId.toString(), sessionId);
    }

    /** 通用查询：按扩展条目键/值检索扩展记录，支持模糊(like.*)与精确(eq.*)组合 + 分页。要求登录。 */
    @GetMapping("/sessions/extensions/search")
    public PageResponse<ExtensionResponse> search(
            @PathVariable UUID agentId,
            @RequestParam Map<String, String> params,   // 一次收全 ?xxx=..
            HttpServletRequest request) {
        String userId = requireUserId(request);
        Map<String, String> likeMap = pickByPrefix(params, "like.");  // 拆出 like.*
        Map<String, String> eqMap   = pickByPrefix(params, "eq.");    // 拆出 eq.*
        // 原实现裸调 Integer.parseInt：?page=abc 抛 NumberFormatException，被兜成 500
        int page = intParam(params.get("page"), 0, 0, 10_000);
        int size = intParam(params.get("size"), 20, 1, 100);
        // 管理员保留跨用户检索（传 null 表示不按 user_id 过滤），普通用户强制只看自己
        String ownerFilter = accessGuard.isAdmin(request) ? null : userId;
        return service.searchByEntries(agentId.toString(), ownerFilter, likeMap, eqMap, page, size);
    }

    /** 解析分页参数：非法值返回 400 而非抛异常；结果夹在 [min,max] 内，避免 size 被放大。 */
    private static int intParam(String raw, int fallback, int min, int max) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.min(Math.max(Integer.parseInt(raw.trim()), min), max);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("分页参数必须是整数: " + raw);
        }
    }

    /** 从全参数里挑出带指定前缀的键值，并去掉前缀还原成 map */
    private static Map<String, String> pickByPrefix(Map<String, String> params, String prefix) {
        Map<String, String> out = new HashMap<>();
        params.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                out.put(k.substring(prefix.length()), v);
            }
        });
        return out;
    }

    static String requireUserId(HttpServletRequest request) {
        String userId = JwtInterceptor.resolveUserId(request);
        if (userId == null || userId.isBlank()) {
            throw ApiException.unauthorized("未登录或登录已过期，请重新登录");
        }
        return userId;
    }
}

