package com.tipdm.agentplatform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.web.auth.Viewer;
import com.tipdm.agentplatform.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 会话级知识库关联（需求 2 的持久化层）。
 *
 * <p>「哪个会话挂了哪些知识库」存在通用扩展表
 * {@code agent_session_extension_entries} 的 {@code knowledge_base_ids} 条目
 * （值为 JSON 字符串数组），复用 {@link SessionExtensionService}，无需新迁移。</p>
 *
 * <p>读侧（运行期）由 {@code PlatformAguiRuntimeContextResolver} 每轮 run 调
 * {@link #getKnowledgeBaseIds} 注入 {@link SessionKnowledgeRef}；写侧由
 * {@code SessionKnowledgeController} 调 {@link #setKnowledgeBaseIds}。</p>
 */
@Service
public class SessionKnowledgeService {

    /** 扩展条目 key：值 = JSON 字符串数组（UUID 字符串）。 */
    public static final String KEY_KNOWLEDGE_BASE_IDS = "knowledge_base_ids";

    private static final Logger log = LoggerFactory.getLogger(SessionKnowledgeService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final SessionExtensionService extensionService;
    private final KnowledgeBaseService knowledgeBaseService;

    public SessionKnowledgeService(SessionExtensionService extensionService,
                                   KnowledgeBaseService knowledgeBaseService) {
        this.extensionService = extensionService;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /**
     * 读取该会话关联的知识库 ID；无关联返回空列表（从不抛 404）。
     */
    public List<UUID> getKnowledgeBaseIds(String agentId, String sessionId) {
        var ext = extensionService.getOrNull(agentId, sessionId);
        if (ext == null || ext.entries() == null) {
            return List.of();
        }
        String raw = ext.entries().stream()
                .filter(e -> KEY_KNOWLEDGE_BASE_IDS.equals(e.key()))
                .map(e -> e.value())
                .findFirst()
                .orElse(null);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> ids = MAPPER.readValue(raw, STRING_LIST);
            return ids.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(s -> parseUuid(s, null))
                    .filter(u -> u != null)
                    .toList();
        } catch (Exception e) {
            log.warn("Parse knowledge_base_ids failed (agent={}, session={}): {}", agentId, sessionId, raw);
            return List.of();
        }
    }

    /**
     * 覆盖式设置会话的知识库关联。空列表 = 解除全部关联。
     *
     * <p>写入前逐个校验可见性（owner/授权/群组，规则同
     * {@link KnowledgeBaseService#get}），对当前用户不可见的知识库拒绝保存，
     * 避免把不可见 KB id 固化进会话（运行期检索仍会二次校验，这里是 UX 前置）。
     * {@code viewer} 由接口层裁决（含 admin 标志），本类不做角色判定。</p>
     */
    public SessionKnowledgeSnapshot setKnowledgeBaseIds(String agentId, String sessionId,
                                                        Viewer viewer, List<UUID> knownBaseIds) {
        List<UUID> ids = knownBaseIds == null ? List.of() : knownBaseIds.stream()
                .filter(id -> id != null)
                .distinct()
                .toList();
        for (UUID id : ids) {
            // get() 内部 requireVisible：不存在或不可见 → 404
            knowledgeBaseService.get(id, viewer);
        }
        try {
            String raw = MAPPER.writeValueAsString(ids.stream().map(UUID::toString).toList());
            extensionService.putEntry(agentId, sessionId, viewer.userId().toString(),
                    KEY_KNOWLEDGE_BASE_IDS, raw);
        } catch (Exception e) {
            throw ApiException.badRequest("知识库关联保存失败");
        }
        return new SessionKnowledgeSnapshot(ids);
    }

    private static UUID parseUuid(String s, UUID fallback) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * 请求期注入快照：AG-UI resolver 与服务端自发 run（wakeup 补醒、停滞自检等）
     * 共用，保证 {@link KnowledgeSearchTool} 与 {@code SessionKnowledgeContextMiddleware}
     * 在任意 run 入口都能按会话取到挂载的知识库。缺 agentId/sessionId 时返回空快照。
     */
    public SessionKnowledgeRef refFor(String agentId, String sessionId) {
        if (agentId == null || agentId.isBlank() || sessionId == null || sessionId.isBlank()) {
            return SessionKnowledgeRef.NONE;
        }
        return new SessionKnowledgeRef(getKnowledgeBaseIds(agentId, sessionId));
    }

    /**
     * 会话知识库关联的响应/上下文快照。
     */
    public record SessionKnowledgeSnapshot(List<UUID> knowledgeBaseIds) {

        public SessionKnowledgeSnapshot {
            knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        }
    }
}
