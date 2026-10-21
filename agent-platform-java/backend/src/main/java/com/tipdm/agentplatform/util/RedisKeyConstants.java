package com.tipdm.agentplatform.util;

/**
 * Redis Key 统一命名规范与常量管理：
 * 规则：[项目名]:[模块名]:[功能/数据类型]:[唯一标识]
 */
public final class RedisKeyConstants {

    private RedisKeyConstants() { }

    /** 平台统一前缀 */
    public static final String PREFIX = "agent-platform";

    /** 认证授权模块前缀 */
    public static final String MODULE_AUTH = PREFIX + ":auth";

    /**
     * 用户权限码缓存 Key 前缀
     * 完整 Key 格式示例：agent-platform:auth:perm:123e4567-e89b-12d3-a456-426614174000
     */
    public static final String AUTH_PERM_PREFIX = MODULE_AUTH + ":perm:";

    /**
     * 生成用户权限缓存的完整 Key
     *
     * @param userId 用户 ID
     * @return 完整的 Redis Key
     */
    public static String authPermKey(Object userId) {
        return AUTH_PERM_PREFIX + userId;
    }
}
