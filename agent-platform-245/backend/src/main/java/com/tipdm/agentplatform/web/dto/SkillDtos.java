package com.tipdm.agentplatform.web.dto;

import com.tipdm.agentplatform.domain.ResourceStatus;
import com.tipdm.agentplatform.domain.SkillScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class SkillDtos {

    private SkillDtos() {
    }

    public record SkillRequest(
            @NotBlank @Size(max = 128) String name,
            String description,
            @Size(max = 64) String implementationType,
            ResourceStatus status,
            @Size(max = 128) String createdBy,
            /** 技能正文（markdown，SKILL.md 去 frontmatter 后的 body）。 */
            String content,
            /** 文本资源（相对路径 → 文本内容，如 scripts/foo.py）。 */
            Map<String, String> resources,
            /** 磁盘源目录（绝对路径）；一般由仓库导入生成，手工编辑通常不改。 */
            String originDir) {
    }

    public record SkillResponse(
            UUID id,
            String name,
            String description,
            String implementationType,
            ResourceStatus status,
            String createdBy,
            /** 归属范围：PLATFORM 平台库 / PERSONAL 个人库（V54）。 */
            SkillScope scope,
            /** 个人库归属人；平台库为 null。 */
            UUID ownerUserId,
            Instant createdAt,
            Instant updatedAt,
            String content,
            Map<String, String> resources,
            String originDir) {
    }
}
