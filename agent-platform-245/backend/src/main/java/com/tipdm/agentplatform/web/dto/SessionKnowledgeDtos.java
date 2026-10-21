package com.tipdm.agentplatform.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * 会话级知识库关联 DTO。
 */
public final class SessionKnowledgeDtos {

    private SessionKnowledgeDtos() {
    }

    /**
     * 设置会话知识库关联的请求体。空数组 = 解除全部关联。
     */
    public record SessionKnowledgeRequest(
            List<@NotNull UUID> knowledgeBaseIds) {

        public SessionKnowledgeRequest {
            knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        }
    }

    /**
     * 会话知识库关联的响应（查询与保存共用）。
     */
    public record SessionKnowledgeResponse(
            List<UUID> knowledgeBaseIds) {

        public SessionKnowledgeResponse {
            knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        }
    }
}
