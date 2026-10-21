package com.tipdm.agentplatform.service;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.builtin.TodoTools;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * 探针：框架自动注册的工具（如 {@code todo_write}）在平台权限上下文下到底得到什么判定。
 *
 * <p>背景：plan_exit 之后模型调用 todo_write 时调用被持久化为 ASK 状态、回合结束且无结果
 * （AG-UI 下无人应答 → 会话卡死）。把 todo_write 加进平台 ALLOW 白名单后仍复现，因此需要
 * 确认 ASK 是「未匹配到 ALLOW 规则」还是「工具自身/plan 模式的判定」，再决定修哪一层。</p>
 */
class PermissionDecisionProbeTest {

    /** 复刻 {@link AgentAssemblyService} 的权限上下文（ALLOW todo_write，模式 DEFAULT）。 */
    private static PermissionContextState contextAllowing(String toolName) {
        PermissionContextState.Builder builder =
                PermissionContextState.builder().mode(PermissionMode.DEFAULT);
        builder.addAllowRule(toolName,
                new PermissionRule(toolName, null, PermissionBehavior.ALLOW, "platform-policy"));
        return builder.build();
    }

    /** 取框架自动注册的 todo_write 工具定义。 */
    private static ToolBase todoTool() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new TodoTools());
        var tool = toolkit.getTool("todo_write");
        if (!(tool instanceof ToolBase base)) {
            throw new AssertionError("框架未把 todo_write 注册为 ToolBase，测试前提不成立");
        }
        return base;
    }

    @Test
    void probe() {
        ToolBase tool = todoTool();
        System.out.println("[PROBE] todo_write readOnly=" + tool.isReadOnly());

        PermissionDecision allowed = new PermissionEngine(contextAllowing("todo_write"))
                .checkPermission(tool, Map.of())
                .block();
        PermissionDecision bare = new PermissionEngine(
                PermissionContextState.builder().mode(PermissionMode.DEFAULT).build())
                .checkPermission(tool, Map.of())
                .block();

        System.out.println("[PROBE] 有 ALLOW 规则: " + allowed.getBehavior()
                + " / " + allowed.getMessage());
        System.out.println("[PROBE] 无规则:      " + bare.getBehavior()
                + " / " + bare.getMessage());
    }
}
