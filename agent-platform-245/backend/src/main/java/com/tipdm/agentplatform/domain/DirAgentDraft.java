package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/**
 * 目录型智能体的编辑草稿（file_agent_drafts）。
 *
 * <p>目录型智能体的真相源是 {@code workspace/agents/{名}/} 定义目录，本表承载
 * 「在线编辑的待发布草稿」：编辑页保存草稿只写这里（不动磁盘），试运行按草稿
 * 内容装配临时定义目录，发布时把草稿写回定义目录并存档
 * {@link AgentConfigRevision}（version = 该智能体历史最大版本 + 1）。每个智能体
 * 至多一条草稿（agent_id 唯一），后写覆盖。</p>
 */
@Entity
@Table(name = "file_agent_drafts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_file_agent_draft_agent",
                columnNames = {"agent_id"}))
public class DirAgentDraft extends BaseEntity {

    @Column(name = "agent_id", nullable = false)
    private java.util.UUID agentId;

    /** AGENTS.md 完整内容（含 frontmatter）。 */
    @Column(nullable = false, columnDefinition = "text")
    private String agentsMd;

    /** subagents/*.md 全量期望状态（文件名 → 内容），发布时按此同步目录。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> subagents;

    /** 草稿编辑序号：每次保存 +1，用作试运行装配缓存键的隔离部分。 */
    @Column(name = "draft_seq", nullable = false)
    private int draftSeq = 1;

    /** 最后编辑人（JWT userId）。试运行安全闸据此判断（仅作者本人/发布链路可用草稿）。 */
    @Column(name = "updated_by", length = 128)
    private String updatedBy;

    public java.util.UUID getAgentId() {
        return agentId;
    }

    public void setAgentId(java.util.UUID agentId) {
        this.agentId = agentId;
    }

    public String getAgentsMd() {
        return agentsMd;
    }

    public void setAgentsMd(String agentsMd) {
        this.agentsMd = agentsMd;
    }

    public Map<String, String> getSubagents() {
        return subagents;
    }

    public void setSubagents(Map<String, String> subagents) {
        this.subagents = subagents;
    }

    public int getDraftSeq() {
        return draftSeq;
    }

    public void setDraftSeq(int draftSeq) {
        this.draftSeq = draftSeq;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
