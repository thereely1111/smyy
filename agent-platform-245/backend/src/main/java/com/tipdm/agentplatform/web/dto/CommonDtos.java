package com.tipdm.agentplatform.web.dto;

import com.tipdm.agentplatform.domain.ResourceStatus;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public final class CommonDtos {

    private CommonDtos() {
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }

    public record StatusRequest(@NotNull ResourceStatus status) {
    }
}
