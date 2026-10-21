package com.tipdm.agentplatform.web.dto;

import com.tipdm.agentplatform.domain.ExternalProvider;
import com.tipdm.agentplatform.domain.GrantPrincipalType;
import com.tipdm.agentplatform.domain.KnowledgeBasePermission;
import com.tipdm.agentplatform.domain.KnowledgeBaseScope;
import com.tipdm.agentplatform.domain.KnowledgeBaseType;
import com.tipdm.agentplatform.domain.KnowledgeDocumentStatus;
import com.tipdm.agentplatform.web.dto.GroupDtos.GroupSummary;
import com.tipdm.agentplatform.web.dto.TagDtos.TagView;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库模块的线上契约 DTO。
 *
 * <p><b>字段一律 camelCase</b>（Spring Boot Jackson 默认命名）：设计文档
 * （《知识库管理模块 — 技术方案》第 8 节 API 契约表）明确约定后端 DTO 字段
 * 采用 Java 风格 camelCase，即 `documentId` / `principalType` / `topK` /
 * `chunkCount` / `createdAt`。record 组件名即线上字段名，不额外标注
 * {@code @JsonNaming}，与仓库其余模块的 Spring 默认 camelCase 一致。
 */
public final class KnowledgeBaseDtos {

    private KnowledgeBaseDtos() {
    }

    public record OwnerView(UUID id, String name) {
    }

    public record KnowledgeBaseView(
            UUID id,
            String name,
            String description,
            KnowledgeBaseType type,
            /** 归属范围：PLATFORM=平台库 / PERSONAL=个人库（仅分类，不影响可见性）。 */
            KnowledgeBaseScope scope,
            OwnerView owner,
            Map<String, Object> embeddingModelConfig,
            ExternalProvider externalProvider,
            String externalEndpoint,
            Integer chunkSize,
            Integer chunkOverlap,
            boolean editable,
            String permission,
            /** 标签（精简视图 id/name/color，2026-09-20 标签系统）。 */
            List<TagView> tags,
            Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * 创建请求。不含 scope——归属范围由路由决定（POST /api/knowledge-bases =
     * PERSONAL；POST /api/knowledge-bases/platform = PLATFORM），不作为可变造的
     * 请求字段进入契约；创建后不可变更。
     */
    public record CreateKnowledgeBaseRequest(
            @NotBlank @Size(max = 200) String name,
            String description,
            KnowledgeBaseType type,
            Map<String, Object> embeddingModelConfig,
            ExternalProvider externalProvider,
            String externalEndpoint,
            String externalApiKey,
            Map<String, Object> externalExtra,
            Integer chunkSize,
            Integer chunkOverlap,
            /** 标签挂载（全量替换语义）：null = 不挂；空数组 = 不挂标签。 */
            List<UUID> tagIds) {
    }

    public record UpdateKnowledgeBaseRequest(
            @Size(max = 200) String name,
            String description,
            Map<String, Object> embeddingModelConfig,
            ExternalProvider externalProvider,
            String externalEndpoint,
            String externalApiKey,
            Map<String, Object> externalExtra,
            Integer chunkSize,
            Integer chunkOverlap,
            /** 标签挂载（全量替换语义）：null = 本次不碰标签；空数组 = 清空标签。 */
            List<UUID> tagIds) {
    }

    public record UploadKnowledgeDocumentResponse(
            UUID documentId,
            String filename,
            KnowledgeDocumentStatus status) {
    }

    public record KnowledgeDocumentStatusView(
            UUID id,
            KnowledgeDocumentStatus status,
            int chunkCount,
            String error) {
    }

    /**
     * 文档清单项，对应 `GET /api/knowledge-bases/{id}/documents`。
     *
     * <p>`size` 由服务端读取落盘文件实时计算（文件缺失时记 0）；
     * `contentType` 先按扩展名推断，无法推断时回退为 `application/octet-stream`。
     */
    public record KnowledgeDocumentView(
            UUID id,
            String filename,
            long size,
            String contentType,
            KnowledgeDocumentStatus status,
            String error,
            int chunkCount,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record ListKnowledgeDocumentsResponse(
            List<KnowledgeDocumentView> documents,
            long total) {
    }

    /** `GET /api/knowledge-bases/{id}/documents/status` 的响应包装。 */
    public record ListKnowledgeDocumentStatusResponse(
            List<KnowledgeDocumentStatusView> items) {
    }

    public record ListKnowledgeBasesResponse(
            List<KnowledgeBaseView> knowledgeBases,
            long total) {
    }

    // ---- 授权 ----

    public record GrantView(
            UUID id,
            GrantPrincipalType principalType,
            UUID principalId,
            String principalName,
            KnowledgeBasePermission permission,
            UUID grantedBy,
            Instant createdAt) {
    }

    public record GrantRequest(
            @NotNull GrantPrincipalType principalType,
            @NotNull UUID principalId,
            @NotNull KnowledgeBasePermission permission) {
    }

    public record ListGrantsResponse(
            List<GrantView> grants,
            List<GroupSummary> groups) {
    }

    // ---- 检索（P3 rag / P3.5 text / P4 external 统一为 SearchResult） ----
    // rag/external 走 VectorSearchResult 形态（documentId/chunkIndex/score/content）；
    // text 走 TextSearchResult 形态（file/line/content/context），见设计稿 §7.3。
    // 两者合并为统一 SearchResult 记录，前端按 type 分支读取对应字段。

    public record SearchResult(
            UUID documentId,
            int chunkIndex,
            double score,
            String content,
            String file,
            Integer line,
            List<String> context) {

        /** rag / external：向量化命中的归一化结果。 */
        public static SearchResult vector(UUID documentId, int chunkIndex, double score, String content) {
            return new SearchResult(documentId, chunkIndex, score, content, null, null, null);
        }

        /** text：服务端 grep 兜底命中的文本行。 */
        public static SearchResult text(String file, int line, String content, List<String> context) {
            return new SearchResult(null, 0, 0.0, content, file, line, context);
        }
    }

    public record SearchKnowledgeBaseRequest(
            @NotBlank String query,
            Integer topK) {
    }

    public record SearchKnowledgeBaseResponse(
            KnowledgeBaseType type,
            List<SearchResult> results) {
    }
}
