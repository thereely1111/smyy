package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "tools")
public class Tool extends BaseEntity {

    @Column(nullable = false, unique = true, length = 128)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "tool_type", nullable = false, length = 64)
    private String toolType = "mcp";

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_mode", nullable = false, length = 16)
    private ToolExecutionMode executionMode = ToolExecutionMode.internal;

    @Column(name = "ask_confirmation", nullable = false)
    private boolean askConfirmation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_schema", columnDefinition = "jsonb")
    private Map<String, Object> inputSchema;

    @Column(name = "mcp_endpoint", length = 512)
    private String mcpEndpoint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ResourceStatus status = ResourceStatus.active;

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

    public String getToolType() {
        return toolType;
    }

    public void setToolType(String toolType) {
        this.toolType = toolType;
    }

    public ToolExecutionMode getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(ToolExecutionMode executionMode) {
        this.executionMode = executionMode == null ? ToolExecutionMode.internal : executionMode;
    }

    public boolean isAskConfirmation() {
        return askConfirmation;
    }

    public void setAskConfirmation(boolean askConfirmation) {
        this.askConfirmation = askConfirmation;
    }

    public Map<String, Object> getInputSchema() {
        return inputSchema;
    }

    public void setInputSchema(Map<String, Object> inputSchema) {
        this.inputSchema = inputSchema;
    }

    public String getMcpEndpoint() {
        return mcpEndpoint;
    }

    public void setMcpEndpoint(String mcpEndpoint) {
        this.mcpEndpoint = mcpEndpoint;
    }

    public ResourceStatus getStatus() {
        return status;
    }

    public void setStatus(ResourceStatus status) {
        this.status = status;
    }
}
