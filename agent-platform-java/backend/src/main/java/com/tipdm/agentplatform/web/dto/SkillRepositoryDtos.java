package com.tipdm.agentplatform.web.dto;

import java.time.Instant;
import java.util.List;

public final class SkillRepositoryDtos {

    private SkillRepositoryDtos() {
    }

    /** 仓库状态概览（GET /api/skill-repositories、POST /api/skill-repositories/sync）。 */
    public record SkillRepoInfo(
            boolean enabled,
            String type,
            String url,
            String branch,
            Instant lastSyncedAt,
            String lastError,
            long skillCount) {
    }

    /**
     * 仓库内一个技能条目。{@code id} 是寻址句柄（不透明字符串，内部保证无 {@code /}，
     * 前端原样 encodeURIComponent 后拼路径即可）；{@code markdown} 仅详情端点返回。
     * {@code url} 为 SKILL.md 来源地址（git 型为 null，http 型为索引声明地址）。
     */
    public record SkillRepoCard(
            String id,
            String name,
            String description,
            String version,
            String author,
            List<String> tags,
            Instant updatedAt,
            String url,
            String markdown) {
    }
}
