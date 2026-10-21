package com.tipdm.agentplatform.domain;

/**
 * 工具执行模式：
 * <ul>
 *   <li>internal - 由 Agent 内部直接执行（AgentScope 工具回调）</li>
 *   <li>external - 暂停等待外部执行，恢复后把执行结果回传（HITL / 人工审批入口）</li>
 * </ul>
 */
public enum ToolExecutionMode {
    internal,
    external
}
