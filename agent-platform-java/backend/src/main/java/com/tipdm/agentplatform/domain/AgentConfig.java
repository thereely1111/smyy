package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "agent_configs")
public class AgentConfig extends BaseEntity {

    @Column(nullable = false, length = 128)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "system_prompt", columnDefinition = "text")
    private String systemPrompt;

    @Column(nullable = false, length = 128)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AgentStatus status = AgentStatus.draft;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skill_ids", nullable = false, columnDefinition = "jsonb")
    private List<UUID> skillIds = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_ids", nullable = false, columnDefinition = "jsonb")
    private List<UUID> toolIds = new ArrayList<>();

    /** 子 Agent 现配列表（数据库存储，V45）；装配期转换为框架 SubagentDeclaration。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inline_subagents", nullable = false, columnDefinition = "jsonb")
    private List<Subagent> inlineSubagents = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "launch_templates", nullable = false, columnDefinition = "jsonb")
    private List<LaunchTemplate> launchTemplates = new ArrayList<>();

    @Column(name = "plan_enabled", nullable = false)
    private boolean planEnabled;

    @Column(name = "skill_sedimentation", nullable = false)
    private boolean skillSedimentation;

    @Column(name = "workspace_path", length = 512)
    private String workspacePath;

    /**
     * 当前对外发布版本号：每次发布动作 +1（首次发布 = 1）。
     * 已发布智能体的修改先进 {@code draftSnapshot}，发布时覆盖主行并 +1。
     */
    @Column(nullable = false)
    private int version = 1;

    /**
     * 已发布智能体的待发布草稿快照（AgentConfigRequest 字段形状 JSONB）。
     * null = 无待发布草稿；草稿期间主行（对外发布内容）保持不变。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> draftSnapshot;

    /** 欢迎表单（进入空会话即渲染的预设表单）；null = 不启用欢迎表单。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "welcome_form", columnDefinition = "jsonb")
    private Map<String, Object> welcomeForm;

    /** 配置驱动表单（与 welcomeForm 并存的通用表单结构，触发策略由 formTrigger 决定）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "form", columnDefinition = "jsonb")
    private Map<String, Object> form;

    @Enumerated(EnumType.STRING)
    @Column(name = "form_trigger", nullable = false, length = 16)
    private FormTrigger formTrigger = FormTrigger.disabled;

    /**
     * 文件形式的agent
     */
    @Column(name = "file_based", nullable = false)
    private boolean fileBased = false;

    /**
     * 文件驱动 Agent 的标签名（AGENTS.md frontmatter {@code tags:}，2026-09-20）。
     *
     * <p>仅 fileBased=true 时由 {@code FileSystemAgentLoader} 填充；数据库 Agent
     * 恒为 null，其标签走 agent_tags 关联表。真相源是定义文件本身，不落库，
     * 管理端对其只读。</p>
     */
    @jakarta.persistence.Transient
    private List<String> fileTagNames;

    public List<String> getFileTagNames() {
        return fileTagNames;
    }

    public void setFileTagNames(List<String> fileTagNames) {
        this.fileTagNames = fileTagNames;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public AgentStatus getStatus() {
        return status;
    }

    public void setStatus(AgentStatus status) {
        this.status = status;
    }

    public List<UUID> getSkillIds() {
        return skillIds;
    }

    public void setSkillIds(List<UUID> skillIds) {
        this.skillIds = skillIds == null ? new ArrayList<>() : new ArrayList<>(skillIds);
    }

    public List<UUID> getToolIds() {
        return toolIds;
    }

    public void setToolIds(List<UUID> toolIds) {
        this.toolIds = toolIds == null ? new ArrayList<>() : new ArrayList<>(toolIds);
    }

    public List<Subagent> getInlineSubagents() {
        return inlineSubagents;
    }

    public void setInlineSubagents(List<Subagent> inlineSubagents) {
        this.inlineSubagents = inlineSubagents == null ? new ArrayList<>() : new ArrayList<>(inlineSubagents);
    }

    public List<LaunchTemplate> getLaunchTemplates() {
        return launchTemplates;
    }

    public void setLaunchTemplates(List<LaunchTemplate> launchTemplates) {
        this.launchTemplates = launchTemplates == null ? new ArrayList<>() : new ArrayList<>(launchTemplates);
    }

    public boolean isPlanEnabled() {
        return planEnabled;
    }

    public void setPlanEnabled(boolean planEnabled) {
        this.planEnabled = planEnabled;
    }

    public boolean isSkillSedimentation() {
        return skillSedimentation;
    }

    public void setSkillSedimentation(boolean skillSedimentation) {
        this.skillSedimentation = skillSedimentation;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public void setWorkspacePath(String workspacePath) {
        this.workspacePath = workspacePath;
    }

    public Map<String, Object> getWelcomeForm() {
        return welcomeForm;
    }

    public void setWelcomeForm(Map<String, Object> welcomeForm) {
        this.welcomeForm = welcomeForm;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Map<String, Object> getDraftSnapshot() {
        return draftSnapshot;
    }

    public void setDraftSnapshot(Map<String, Object> draftSnapshot) {
        this.draftSnapshot = draftSnapshot;
    }

    public Map<String, Object> getForm() {
        return form;
    }

    public void setForm(Map<String, Object> form) {
        this.form = form;
    }

    public FormTrigger getFormTrigger() {
        return formTrigger;
    }

    public void setFormTrigger(FormTrigger formTrigger) {
        this.formTrigger = formTrigger == null ? FormTrigger.disabled : formTrigger;
    }

    public boolean isFileBased() {
        return fileBased;
    }

    public void setFileBased(boolean fileBased) {
        this.fileBased = fileBased;
    }
}
