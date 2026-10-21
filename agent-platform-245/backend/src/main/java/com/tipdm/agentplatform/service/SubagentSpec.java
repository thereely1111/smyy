package com.tipdm.agentplatform.service;

import java.util.List;

/**
 * 子 Agent 规格：装配层消费的中间值对象，由现配数据（{@code agent_configs.inline_subagents}）
 * 直接构造，最终映射为 AgentScope 的 {@code io.agentscope.harness.agent.subagent.SubagentDeclaration}。
 *
 * @param name          子 Agent 名称
 * @param description   职责描述（父 Agent 据此决定何时派发）
 * @param body          定义正文（= 子 Agent 的 system prompt）
 * @param tools         工具白名单
 * @param model         模型；null = 跟随主 Agent
 * @param workspaceMode 工作区模式（预留，现配不使用）
 * @param workspacePath 工作区路径（预留，现配不使用）
 * @param temperature   采样温度（预留）
 * @param topP          采样 top-p（预留）
 * @param steps         单轮迭代次数上限；null = 平台默认 max-iters
 * @param hidden        是否对用户隐藏
 * @param mode          声明模式（subagent/primary/all）
 * @param exposeToUser  是否暴露给用户
 */
public record SubagentSpec(
        String name,
        String description,
        String body,
        List<String> tools,
        String model,
        String workspaceMode,
        String workspacePath,
        Double temperature,
        Double topP,
        Integer steps,
        Boolean hidden,
        String mode,
        Boolean exposeToUser) {
}
