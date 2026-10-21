package com.tipdm.agentplatform.web.dto;

import com.tipdm.agentplatform.domain.AgentStatus;
import com.tipdm.agentplatform.domain.FormTrigger;
import com.tipdm.agentplatform.domain.LaunchTemplate;
import com.tipdm.agentplatform.web.dto.TagDtos.TagView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AgentDtos {

    private AgentDtos() {
    }

    /**
     * 子 Agent 现配项：直接在新建 Agent 表单里定义，保存时落盘为
     * workspace/agents/{agentId}/subagents/{name}.md（AgentScope 目录型约定）。
     */
    public record SubagentInline(
            String name,
            String description,
            String prompt,
            /** 子 Agent 模型（frontmatter model）；null/空 = 跟随主 Agent。 */
            String model,
            /** 工具白名单（frontmatter tools）；null/空 = 平台默认基线。 */
            List<String> tools,
            /** 单轮迭代步数上限（frontmatter steps）；null = 平台默认 max-iters。 */
            Integer steps) {

        /**
         * 面向普通登录用户的脱敏副本：保留广场展示所需的 name/description，
         * 摘掉 {@code prompt}（子 Agent 的编排提示词，与主智能体人设同级敏感）。
         */
        public SubagentInline redacted() {
            return new SubagentInline(name, description, null, model, tools, steps);
        }
    }

    /**
     * 主智能体配置请求。<b>不含 model</b>（2026-09-22 整改）：主智能体模型不允许
     * 在配置里写死，一律在对话轮次开始时指定（run forwardedProps），未指定时用
     * 平台配置 {@code agent-platform.model}（见 AgentAssemblyService）。
     *
     * <p>{@code persona} 只在<b>新建</b>时有意义：写入 AGENTS.md 的初始骨架后即丢弃，
     * 之后一律在「定义内容」侧编辑该文件。更新请求里它被忽略（服务端直接不读）。
     */
    public record AgentConfigRequest(
            @NotBlank @Size(max = 128) String name,
            String description,
            /** 新建时写入 AGENTS.md 的人设正文；更新时忽略。 */
            String persona,
            AgentStatus status,
            List<UUID> skillIds,
            List<UUID> toolIds,
            /** 子 Agent 现配列表（数据库存储于 agent_configs.inline_subagents）。 */
            List<SubagentInline> subagents,
            /**
             * 创建时当场新建/上传的子智能体正文：裸名（不含 .md）→ 完整 md 内容。
             * 落盘为该智能体目录下的 subagents/{name}.md，属于定义内容。
             * 仅在<b>新建</b>时有意义；更新请求忽略（定义内容走文件编辑入口）。
             */
            Map<String, String> subagentFiles,
            List<LaunchTemplate> launchTemplates,
            boolean planEnabled,
            boolean skillSedimentation,
            Map<String, Object> welcomeForm,
            Map<String, Object> form,
            FormTrigger formTrigger,
            /** 标签挂载（全量替换语义）：null = 本次不碰标签；空数组 = 清空。 */
            List<UUID> tagIds) {
    }

    /**
     * 目录型智能体的智能体目录快照（只读展示用，不参与写模型）。
     *
     * <p>目录型 Agent 没有智能体配置，真相源是 workspace/agents/{目录名}/ 下的
     * AGENTS.md + skills/ + subagents/。管理端禁止编辑这类 Agent，只能把它们
     * 「现在是什么样」如实呈现出来，避免用户对着一张空表单以为配置丢了。</p>
     */
    public record FileDefinition(
            /** 智能体目录相对 workspace-root 的路径，如 agents/教材开发小助手。 */
            String path,
            /** AGENTS.md 字节数（智能体目录主文件，编排契约与系统提示词）。 */
            long agentsMdBytes,
            /** AGENTS.md 最后修改时间；文件不存在时为 null。 */
            Instant agentsMdUpdatedAt,
            /** skills/ 下的技能目录数。 */
            int skillsCount,
            /** subagents/ 下的子智能体 .md 文件数。 */
            int subagentsCount) {
    }

    public record AgentConfigResponse(
            UUID id,
            String name,
            String description,
            String model,
            AgentStatus status,
            List<UUID> skillIds,
            List<UUID> toolIds,
            /** 子 Agent 现配回显（agent_configs.inline_subagents）。 */
            List<SubagentInline> subagents,
            List<LaunchTemplate> launchTemplates,
            boolean planEnabled,
            boolean skillSedimentation,
            String workspacePath,
             /** 配置侧能否编辑：false = 该智能体只有智能体目录、无配置记录，走文件编辑入口。 */
             boolean configEditable,
             /** 智能体目录快照（AGENTS.md 大小与修改时间 + skills/subagents 计数）。 */
            FileDefinition definition,
            Map<String, Object> welcomeForm,
            Map<String, Object> form,
            FormTrigger formTrigger,
            /** 标签（消费端展示字段，公开展示信息，脱敏时保留）。 */
            List<TagView> tags,
            Instant createdAt,
            Instant updatedAt,
            /** 当前发布版本号：每次发布 +1（首次发布 = 1）。 */
            int version,
            /** 已发布智能体存在待发布草稿（agent_configs.draft_snapshot 非空）。 */
            boolean hasDraft,
            /**
             * 待发布草稿内容（AgentConfigRequest 字段形状）。仅管理端可见——
             * 广场脱敏副本置 null；编辑页用它把表单切到草稿态继续编辑。
             */
            Map<String, Object> draft) {

        /**
         * 面向普通登录用户的脱敏副本。
         *
     * <p>列表接口是「智能体广场」的数据源（{@code /space/*} 与侧边栏会话树对所有
     * 登录用户都要拉），不能加管理端权限码；但子 Agent 的
     * {@code subagents[].prompt} 属编排契约，只该给管理端编辑页看，在此摘掉。
     * 其余字段（welcomeForm / formTrigger / tags / 名称描述等）都是启动会话与展示
     * 所必需的，保持原样。</p>
         */
        public AgentConfigResponse redacted() {
            return new AgentConfigResponse(id, name, description, model, status,
                    skillIds, toolIds, redactSubagents(subagents), launchTemplates, planEnabled,
                    skillSedimentation, workspacePath, configEditable, definition, welcomeForm, form,
                    formTrigger, tags, createdAt, updatedAt, version, hasDraft, null);
        }

        /** 子 Agent 列表脱敏：逐项摘掉 {@code prompt}；入参为 null 时保持 null。 */
        private static List<SubagentInline> redactSubagents(List<SubagentInline> items) {
            return items == null ? null : items.stream().map(SubagentInline::redacted).toList();
        }
    }

    public record ChatRequest(@NotBlank String message, String sessionId) {
    }

    /** 目录型智能体重载结果（POST /agents/reload-files）。 */
    public record FileReloadResponse(int total, int added, int removed) {
    }

    /**
     * 智能体目录导入结果（POST /agents/import/zip、POST /agents/import/files）。
     *
     * @param workspacePath 相对 workspace-root 的智能体目录路径（{@code agents/{名称}}）
     * @param agentId       由目录名派生的稳定 id，与目录型 agent 同一套规则
     * @param fileCount     落盘文件数
     */
    public record AgentImportResponse(String workspacePath, String agentId, int fileCount) {

        /** 由导入服务结果构造。 */
        public static AgentImportResponse from(
                com.tipdm.agentplatform.service.AgentDefinitionImportService.ImportResult r) {
            return new AgentImportResponse(r.workspacePath(), r.agentId(), r.fileCount());
        }
    }

    /**
     * 单个智能体重载结果（POST /agents/{id}/reload）。
     *
     * @param id  智能体 id
     * @param done 是否找到该 agent 并已刷新（false = id 不存在，无需处理）
     */
    public record ReloadResponse(UUID id, boolean done) {
    }

    /** 目录型智能体编辑视图（草稿优先，无草稿读磁盘智能体目录）。 */
    public record FileAgentEditView(
            String agentsMd,
            Map<String, String> subagents,
            boolean hasDraft,
            Integer version,
            String updatedBy,
            Instant updatedAt) {
    }

    /** 目录型智能体草稿保存请求（subagents = 全量期望状态，文件名 → 内容）。 */
    public record FileDraftRequest(@NotBlank String agentsMd,
            Map<String, String> subagents) {
    }

    /** 目录型智能体发布结果（新版本号）。 */
    public record FilePublishResponse(int version) {
    }

    /** 发布版本历史条目（版本历史查看 / 回滚入口用，不含快照正文）。 */
    public record AgentRevisionView(
            UUID agentId,
            int version,
            /** 发布时点（快照落库时间）。 */
            Instant publishedAt,
            String publishedBy) {
    }

    public record ChatResponse(String reply, String sessionId) {
    }

    /**
     * 装配回显。{@code model} = 未指定 run 级模型时的生效模型（平台配置默认，
     * {@code agent-platform.model}）；对话轮次开始时可通过 forwardedProps 覆盖。
     */
    public record AssembleResponse(
            UUID agentId,
            String name,
            String model,
            String workspacePath,
            List<String> tools,
            List<String> subagents,
            List<String> skills,
            String stateStore) {
    }
}
