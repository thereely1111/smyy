package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.AgentConfig;
import com.tipdm.agentplatform.domain.AgentStatus;
import com.tipdm.agentplatform.domain.Subagent;
import com.tipdm.agentplatform.web.dto.AgentDtos.AgentConfigRequest;

import java.util.List;

/**
 * AgentConfigRequest → AgentConfig 的字段映射（静态、无状态）。
 *
 * <p>两处消费共用一份映射，避免漂移：</p>
 * <ul>
 * <li>{@code AgentConfigService.apply}：创建/更新/回滚时写主行（原地变更）；</li>
 * <li>{@code AgentAssemblyService.assembleForRun}：试运行（{@code forwardedProps.debug=true}）
 * 时基于发布配置克隆一份「草稿生效实例」——不动主行、不进发布缓存，装配缓存
 * 键带草稿版本与发布实例隔离。同会话试运行/发布切换时，会话历史由
 * PostgresAgentStateStore（按 sessionId）承载，不丢上下文。</li>
 * </ul>
 */
public final class AgentConfigApplier {

    private AgentConfigApplier() {
    }

    /**
     * 把请求体字段写入目标实体（原地变更）。
     *
     * <p>主智能体模型不允许在配置里写死（2026-09-22 整改）：不接收/覆盖 model，
     * 一律在对话轮次开始时指定（run forwardedProps），未指定时用平台配置
     * agent-platform.model。请求 DTO 已无 model 字段；DB 列仅存历史遗留值。</p>
     */
    public static void apply(AgentConfig config, AgentConfigRequest request) {
        config.setName(request.name().trim());
        config.setDescription(request.description());
        config.setSystemPrompt(request.systemPrompt());
        config.setStatus(request.status() == null ? AgentStatus.draft : request.status());
        config.setSkillIds(request.skillIds());
        config.setToolIds(request.toolIds());
        // 子 Agent 模板链路已下线（V46），现配一律走 subagents（inline_subagents 列）。
        config.setInlineSubagents(request.subagents() == null ? List.of()
                : request.subagents().stream()
                        .map(s -> new Subagent(
                                s.name(), s.description(), s.prompt(), s.model(), s.tools(), s.steps()))
                        .toList());
        config.setLaunchTemplates(request.launchTemplates());
        config.setPlanEnabled(request.planEnabled());
        config.setSkillSedimentation(request.skillSedimentation());
        config.setWelcomeForm(request.welcomeForm());
        config.setForm(request.form());
        config.setFormTrigger(request.formTrigger());
    }

    /**
     * 试运行草稿生效实例：以发布配置为底克隆，再覆盖请求体字段。
     * 身份字段（id / workspacePath / fileBased）必须沿用发布配置——运行态目录
     * 契约、状态库、AG-UI 注册都以它们为键；状态强制 active（draft 值不参与
     * 装配，但避免任何按状态分支的下游误判）。
     */
    public static AgentConfig overlay(AgentConfig published, AgentConfigRequest request) {
        AgentConfig copy = new AgentConfig();
        copy.setId(published.getId());
        copy.setWorkspacePath(published.getWorkspacePath());
        copy.setFileBased(published.isFileBased());
        copy.setFileTagNames(published.getFileTagNames());
        copy.setVersion(published.getVersion());
        apply(copy, request);
        copy.setStatus(AgentStatus.active);
        return copy;
    }
}
