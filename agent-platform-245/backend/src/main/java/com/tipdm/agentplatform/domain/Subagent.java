package com.tipdm.agentplatform.domain;

import java.util.List;

/**
 * 子 Agent 现配项（存储于 agent_configs.inline_subagents JSONB 列，随主配置整体读写）。
 * 保存时不再落盘 .md 文件；装配期直接转换为框架 {@code SubagentDeclaration}。
 *
 * @param name        子 Agent 名称（装配期作为声明名，父 Agent 据此派发）
 * @param description 职责描述（父 Agent 依据它决定何时派发）
 * @param prompt      子 Agent 的 system prompt
 * @param model       模型；null/空 = 跟随主 Agent
 * @param tools       工具白名单；null/空 = 仅平台 UI 工具（无文件操作工具）
 * @param steps       单轮迭代次数上限；null = 平台默认 max-iters
 */
public record Subagent(
        String name,
        String description,
        String prompt,
        String model,
        List<String> tools,
        Integer steps) {

    public Subagent {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
