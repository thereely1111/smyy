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

    /** 产物下载模块前缀 */
    public static final String MODULE_DOWNLOAD = PREFIX + ":download";

    /**
     * 已消费的下载票据标记Key 前缀。
     *
     * <p>票据本体是HS256 签名串（无状态、自带过期），Redis 只存「这个 jti 已被用掉」这一个事实，
     * 用于实现**真单次**：原子写入（SETNX）成功才允许放行，失败说明已被并发消费过。
     * TTL 与票据有效期一致，到期自动清理，无需额外回收任务。</p>
     *
     * <p>为什么不用「先 GETDEL 再验签」：那样 Redis 里的内容才是票据本体，
     * 等于把状态与凭据耦合在一条记录上；拆成「签名串 + 已用标记」后，
     * 票据本体可以随时自校验，Redis 只承担并发闸门这一个职责。</p>
     */
    public static final String DOWNLOAD_TICKET_USED_PREFIX = MODULE_DOWNLOAD + ":ticket-used:";

    /**
     * 生成「票据已消费」标记的完整 Key。
     *
     * @param jti 票据唯一 ID（JWT claim {@code jti}）
     * @return 完整的 Redis Key
     */
    public static String downloadTicketUsedKey(String jti) {
        return DOWNLOAD_TICKET_USED_PREFIX + jti;
    }

    /** 文档预览模块前缀 */
    public static final String MODULE_PREVIEW = PREFIX + ":preview";

    /**
     * 已消费的预览回拉票据标记 Key 前缀。
     *
     * <p>与下载票据同构但<b>刻意分开命名空间</b>：两者 claim 里的 {@code type} 不同
     * （{@code download} / {@code preview}），签名密钥虽同，但 Key 复用会让
     * 两种票据的「已消费」标记互相干扰。</p>
     */
    public static final String PREVIEW_TICKET_USED_PREFIX = MODULE_PREVIEW + ":ticket-used:";

    /**
     * 生成「预览票据已消费」标记的完整 Key。
     *
     * @param jti 票据唯一 ID（JWT claim {@code jti}）
     * @return 完整的 Redis Key
     */
    public static String previewTicketUsedKey(String jti) {
        return PREVIEW_TICKET_USED_PREFIX + jti;
    }
}
