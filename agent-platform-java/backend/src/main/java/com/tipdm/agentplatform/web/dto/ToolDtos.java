package com.tipdm.agentplatform.web.dto;

import com.tipdm.agentplatform.domain.ResourceStatus;
import com.tipdm.agentplatform.domain.ToolExecutionMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class ToolDtos {

    private ToolDtos() {
    }

    public record ToolRequest(
            @NotBlank @Size(max = 128) String name,
            String description,
            @Size(max = 64) String toolType,
            ToolExecutionMode executionMode,
            boolean askConfirmation,
            Map<String, Object> inputSchema,
            @Size(max = 512) String mcpEndpoint,
            ResourceStatus status) {
    }

    public record ToolResponse(
            UUID id,
            String name,
            String description,
            String toolType,
            ToolExecutionMode executionMode,
            boolean askConfirmation,
            Map<String, Object> inputSchema,
            String mcpEndpoint,
            ResourceStatus status,
            Instant createdAt,
            Instant updatedAt) {
    }
}
