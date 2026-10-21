package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.service.SessionExtensionService;
import com.tipdm.agentplatform.service.SessionKnowledgeService;
import com.tipdm.agentplatform.web.auth.Viewer;
import com.tipdm.agentplatform.web.dto.SessionKnowledgeDtos.SessionKnowledgeRequest;
import com.tipdm.agentplatform.web.dto.SessionKnowledgeDtos.SessionKnowledgeResponse;
import com.tipdm.agentplatform.web.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 会话级知识库关联 RESTful 接口（需求 2）。
 *
 * <ul>
 *   <li>{@code GET /api/agents/{agentId}/sessions/{sessionId}/knowledge} — 查询当前用户的关联</li>
 *   <li>{@code PUT /api/agents/{agentId}/sessions/{sessionId}/knowledge} — 覆盖式设置（空数组 = 取消全部）</li>
 * </ul>
 *
 * <p>数据落 {@code agent_session_extensions}（{@link SessionKnowledgeService}），
 * 运行期由 {@code PlatformAguiRuntimeContextResolver} 读入请求上下文。</p>
 *
 * <p>归属约束与标题/归档一致：已存在的扩展记录只允许归属用户改写（404 不暴露他人会话）；
 * 尚无记录（首轮对话前）按当前用户创建。</p>
 */
@RestController
@RequestMapping("/api/agents/{agentId}/sessions/{sessionId}/knowledge")
public class SessionKnowledgeController {

    private final SessionKnowledgeService service;
    private final SessionExtensionService extensionService;
    private final SessionAccessGuard accessGuard;

    public SessionKnowledgeController(SessionKnowledgeService service,
                                      SessionExtensionService extensionService,
                                      SessionAccessGuard accessGuard) {
        this.service = service;
        this.extensionService = extensionService;
        this.accessGuard = accessGuard;
    }

    @GetMapping
    public SessionKnowledgeResponse get(
            @PathVariable UUID agentId,
            @PathVariable String sessionId,
            HttpServletRequest request) {
        Viewer viewer = accessGuard.viewer(request);
        requireOwnerOrCreate(agentId.toString(), sessionId, viewer.userId().toString());
        return new SessionKnowledgeResponse(service.getKnowledgeBaseIds(agentId.toString(), sessionId));
    }

    @PutMapping
    public SessionKnowledgeResponse put(
            @PathVariable UUID agentId,
            @PathVariable String sessionId,
            @Valid @RequestBody SessionKnowledgeRequest body,
            HttpServletRequest request) {
        Viewer viewer = accessGuard.viewer(request);
        requireOwnerOrCreate(agentId.toString(), sessionId, viewer.userId().toString());
        var snapshot = service.setKnowledgeBaseIds(
                agentId.toString(), sessionId, viewer, body.knowledgeBaseIds());
        return new SessionKnowledgeResponse(snapshot.knowledgeBaseIds());
    }

    /**
     * 会话归属前置校验：扩展记录归属用户 ≠ 请求用户 → 404；无记录则放行
     * （由后续写操作按请求用户创建）。
     */
    private void requireOwnerOrCreate(String agentId, String sessionId, String userId) {
        var ext = extensionService.getOrNull(agentId, sessionId);
        if (ext == null || ext.userId() == null || ext.userId().isBlank()) {
            return;
        }
        if (!ext.userId().equals(userId)) {
            throw ApiException.notFound("会话不存在");
        }
    }
}
