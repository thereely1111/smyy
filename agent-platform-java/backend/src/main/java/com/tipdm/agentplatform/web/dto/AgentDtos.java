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
     * workspace/agents/{agentId}/subagents/{name}.md（AgentScope 文件驱动约定）。
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
         * 摘掉 {@code prompt}（子 Agent 的编排提示词，与主 Agent 的 systemPrompt 同级敏感）。
         */
        public SubagentInline redacted() {
            return new SubagentInline(name, description, null, model, tools, steps);
        }
    }

    /**
     * 主智能体配置请求。<b>不含 model</b>（2026-09-22 整改）：主智能体模型不允许
     * 在配置里写死，一律在对话轮次开始时指定（run forwardedProps），未指定时用
     * 平台配置 {@code agent-platform.model}（见 AgentAssemblyService）。
     */
    public record AgentConfigRequest(
            @NotBlank @Size(max = 128) String name,
            String description,
            String systemPrompt,
            AgentStatus status,
            List<UUID> skillIds,
            List<UUID> toolIds,
            /** 子 Agent 现配列表（数据库存储于 agent_configs.inline_subagents）。 */
            List<SubagentInline> subagents,
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
     * 文件驱动智能体的定义区快照（只读展示用，不参与写模型）。
     *
     * <p>文件型 Agent 没有数据库配置行，真相源是 workspace/agents/{目录名}/ 下的
     * AGENTS.md + skills/ + subagents/。管理端禁止编辑这类 Agent，只能把它们
     * 「现在是什么样」如实呈现出来，避免用户对着一张空表单以为配置丢了。</p>
     */
    public record FileDefinition(
            /** 定义区相对 workspace-root 的路径，如 agents/教材开发小助手。 */
            String path,
            /** AGENTS.md 字节数（定义区主文件，编排契约与系统提示词）。 */
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
            String systemPrompt,
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
            boolean fileBased,
            /** 仅文件驱动 Agent（fileBased=true）返回定义区快照，其余为 null。 */
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
         * 登录用户都要拉），不能加管理端权限码；但有两类内容是编排契约与提示词，只该
         * 给管理端编辑页看：主 Agent 的 {@code systemPrompt}，以及子 Agent 的
         * {@code subagents[].prompt}（同为 system prompt 性质）。两者一并摘掉。
         * 其余字段（welcomeForm / formTrigger / tags / 名称描述等）都是启动会话与展示
         * 所必需的，保持原样。</p>
         */
        public AgentConfigResponse redacted() {
            return new AgentConfigResponse(id, name, description, null, model, status,
                    skillIds, toolIds, redactSubagents(subagents), launchTemplates, planEnabled,
                    skillSedimentation, workspacePath, fileBased, definition, welcomeForm, form,
                    formTrigger, tags, createdAt, updatedAt, version, hasDraft, null);
        }

        /** 子 Agent 列表脱敏：逐项摘掉 {@code prompt}；入参为 null 时保持 null。 */
        private static List<SubagentInline> redactSubagents(List<SubagentInline> items) {
            return items == null ? null : items.stream().map(SubagentInline::redacted).toList();
        }
    }

    public record ChatRequest(@NotBlank String message, String sessionId) {
    }

    /** 文件驱动智能体重载结果（POST /agents/reload-files）。 */
    public record FileReloadResponse(int total, int added, int removed) {
    }

    /** 文件驱动智能体编辑视图（草稿优先，无草稿读磁盘定义区）。 */
    public record FileAgentEditView(
            String agentsMd,
            java.util.Map<String, String> subagents,
            boolean hasDraft,
            Integer version,
            String updatedBy,
            Instant updatedAt) {
    }

    /** 文件驱动智能体草稿保存请求（subagents = 全量期望状态，文件名 → 内容）。 */
    public record FileDraftRequest(@jakarta.validation.constraints.NotBlank String agentsMd,
            java.util.Map<String, String> subagents) {
    }

    /** 文件驱动智能体发布结果（新版本号）。 */
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
