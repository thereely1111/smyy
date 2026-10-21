package com.tipdm.agentplatform.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 智能体会话扩展信息 DTO。
 */
public final class SessionExtensionDtos {

    private SessionExtensionDtos() {
    }

    /**
     * 单个扩展条目（key-value）。
     */
    public record EntryItem(
            @NotBlank String key,
            String value) {
    }

    /**
     * 扩展信息完整响应（含所有条目）。
     */
    public record ExtensionResponse(
            UUID id,
            String agentId,
            String sessionId,
            String userId,
            List<EntryItem> entries,
            Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * 批量设置扩展条目的请求体。
     */
    public record UpsertEntriesRequest(
            List<EntryItem> entries) {
    }

    /**
     * 设置单个扩展条目的请求体。
     */
    public record PutEntryRequest(
            @NotBlank String key,
            String value) {
    }

}
