package com.tipdm.agentplatform.web.auth;

import java.util.UUID;

/**
 * 已裁决的调用者上下文：由接口层（Controller / Guard）构造，service 层只消费。
 *
 * <p>权限统一模型（2026-09-20）：角色判定（{@code UserRole.ADMIN}）不再出现在
 * service 层——接口层经 {@link SessionAccessGuard#viewer} 按 <b>admin:access
 * 权限码</b>（与 {@code @RequirePermission} 切面同一数据源）裁决出
 * {@code admin} 标志后随请求下传；service 内部只做资源级 ACL
 * （owner / 显式授权 / 群组授权），叠加 {@link #admin()} 分支。</p>
 *
 * @param userId 当前登录用户（来自 JWT，接口层已保证非空）
 * @param admin  接口层按权限码裁决出的管理端视角（fail-close：缓存未命中 = false）
 */
public record Viewer(UUID userId, boolean admin) {

    /**
     * 非 HTTP 入口（运行时中间件、智能体工具等）专用：无接口层裁决环节，
     * 按非管理员处理（fail-close）——运行期资源访问依赖显式授权/归属，
     * 不因「恰好是管理员」放大权限。
     */
    public static Viewer runtime(UUID userId) {
        return new Viewer(userId, false);
    }
}
