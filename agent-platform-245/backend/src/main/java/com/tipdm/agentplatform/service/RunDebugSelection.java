package com.tipdm.agentplatform.service;

import java.util.UUID;

/**
 * 一次 AG-UI run 的「草稿试运行」标记（智能体在线调试，2026-09-24）。
 *
 * <p><b>传递机制</b>：与 {@link RunModelSelection} 同款 ThreadLocal 通道——
 * {@code PlatformAguiRuntimeContextResolver.resolve()} 每 run 无条件 set/clear：
 * run 请求 forwardedProps 携带 {@code debug=true} 时 set(登录用户, 智能体 id)，
 * 否则 clear()，旧值不会跨 run 泄漏；{@code AgentAssemblyService.assembleForRun}
 * 在装配期消费，命中时用调试草稿覆盖发布配置装配覆盖实例。</p>
 *
 * <p><b>安全边界</b>：debug 标志可被任意登录用户伪造，但它只激活
 * {@link AgentDebugDraftStore} 里「以调用者本人 userId」登记的草稿——而草稿
 * 只能经管理端 PUT /agents/{id}/debug-draft（agent:update 权限）写入。普通
 * 用户伪造标志时仓库必然无本人草稿，装配按发布配置走，无越权面。</p>
 *
 * @param userId 登录用户（JWT payload），调试草稿按 (userId, agentId) 归属
 * @param agentId 本次 run 的目标智能体
 */
public record RunDebugSelection(String userId, UUID agentId) {

    private static final ThreadLocal<RunDebugSelection> CURRENT = new ThreadLocal<>();

    public static void set(RunDebugSelection selection) {
        CURRENT.set(selection);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static RunDebugSelection current() {
        return CURRENT.get();
    }
}
