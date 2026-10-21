package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.Tool;
import com.tipdm.agentplatform.domain.ToolExecutionMode;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 平台工具表驱动的工具实现：继承 ToolBase 以便标记 external（触发外部执行 HITL）。
 * builtin 工具回显入参（占位实现），mcp 工具返回"网关未接入"提示；MCP 网关接入后替换为真实调用。
 */
final class PlatformTool extends ToolBase {

    private final Tool tool;

    PlatformTool(Tool tool) {
        super(tool.getName(),
                tool.getDescription() == null ? tool.getName() : tool.getDescription(),
                toProperties(tool),
                false,
                true,
                false,
                null,
                tool.getExecutionMode() == ToolExecutionMode.external,
                false);
        this.tool = tool;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toProperties(Tool tool) {
        Map<String, Object> schema = tool.getInputSchema();
        if (schema != null && schema.get("properties") instanceof Map<?, ?> properties) {
            return (Map<String, Object>) properties;
        }
        return Map.of();
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String text;
        if ("builtin".equals(tool.getToolType())) {
            text = "[builtin 占位实现] 收到参数: " + param.getInput();
        } else {
            text = "工具「" + tool.getName() + "」为 " + tool.getToolType()
                    + " 类型，MCP 网关尚未接入，暂不可用。收到参数: " + param.getInput();
        }
        ToolResultBlock block = ToolResultBlock.builder()
                .id(param.getToolUseBlock().getId())
                .name(tool.getName())
                .output(TextBlock.builder().text(text).build())
                .state(ToolResultState.SUCCESS)
                .build();
        return Mono.just(block);
    }
}
