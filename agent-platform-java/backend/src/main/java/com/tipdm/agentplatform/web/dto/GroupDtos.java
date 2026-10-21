package com.tipdm.agentplatform.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class GroupDtos {

    private GroupDtos() {
    }

    public record CreateGroupRequest(
            @NotBlank @Size(max = 120) String name,
            List<UUID> memberUserIds) {
    }

    public record AddMemberRequest(
            @NotNull UUID userId) {
    }

    public record GroupMemberView(
            UUID userId,
            String userName) {
    }

    public record GroupView(
            UUID id,
            String name,
            UUID createdBy,
            String createdByName,
            List<GroupMemberView> members,
            Instant createdAt) {
    }

    /** 授权选择 / 列表展示用的精简结构（id + name）。 */
    public record GroupSummary(
            UUID id,
            String name) {
    }

    public record ListGroupsResponse(
            List<GroupSummary> groups) {
    }
}
