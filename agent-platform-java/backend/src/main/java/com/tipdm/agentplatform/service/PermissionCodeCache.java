package com.tipdm.agentplatform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.util.RedisKeyConstants;
import com.tipdm.agentplatform.util.RedisUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 用户权限码缓存读写组件。
 *
 * <p>背景（2026-09-15 实证）：{@code GenericJackson2JsonRedisSerializer}
 * 对顶层 {@code List<String>} 序列化时不写多态类型提示（落库为裸 JSON 数组），
 * 反序列化时却要求类型 id —— 「自己写的值自己读不出」，每次读取抛
 * {@code SerializationException: Could not resolve type id 'admin:console'}。
 *
 * <p>本组件统一存储格式为 JSON 字符串：String 是 final 类型，序列化恒定
 * 不带类型包装，读写对称。读取时兼容旧集合格式（历史遗留的带类型包装值），
 * 任何异常都降级为「缓存未命中」而非抛出，绝不阻塞登录/鉴权主流程。
 */
@Component
public class PermissionCodeCache {

    private static final Logger log = LoggerFactory.getLogger(PermissionCodeCache.class);

    /** 管理端准入权限码：全平台「管理员」判定的唯一口径（{@link #isAdmin} 与 @RequirePermission 同源）。 */
    public static final String ADMIN_ACCESS = "admin:access";

    private final RedisUtils redisUtils;
    private final ObjectMapper objectMapper;

    public PermissionCodeCache(RedisUtils redisUtils, ObjectMapper objectMapper) {
        this.redisUtils = redisUtils;
        this.objectMapper = objectMapper;
    }

    /**
     * 请求人是否为管理员：以 {@link #ADMIN_ACCESS} 权限码判定，与
     * {@code @RequirePermission} 切面同一数据源与口径。
     *
     * <p>fail-close：缓存未命中、读取失败或身份无法解析一律按非管理员处理，
     * 宁可让管理视角暂时不可用，也不能错放。</p>
     */
    public boolean isAdmin(UUID userId) {
        if (userId == null) {
            return false;
        }
        return read(userId).map(codes -> codes.contains(ADMIN_ACCESS)).orElse(false);
    }

    /**
     * 读取用户权限码缓存。
     *
     * @return 空表示未命中、格式无法识别或读取失败，由调用方决定兜底策略
     */
    public Optional<List<String>> read(UUID userId) {
        String key = RedisKeyConstants.authPermKey(userId);
        try {
            Object cached = redisUtils.get(key);
            if (cached == null) {
                return Optional.empty();
            }
            if (cached instanceof String json) {
                return parse(json, key);
            }
            if (cached instanceof Collection<?> list) {
                // 兼容旧格式（带类型包装的 ArrayList 反序列化产物）
                return Optional.of(list.stream().filter(Objects::nonNull).map(Object::toString).toList());
            }
            log.warn("权限码缓存格式无法识别，按未命中处理 key={} actual={}", key, cached.getClass().getName());
        } catch (RuntimeException e) {
            log.warn("读取权限码缓存失败，按未命中处理 key={}", key, e);
        }
        return Optional.empty();
    }

    /**
     * 写入用户权限码缓存（JSON 字符串格式，过期时间与 RefreshToken 一致）。
     */
    public void write(UUID userId, List<String> permissions, long days) {
        String key = RedisKeyConstants.authPermKey(userId);
        try {
            redisUtils.set(key, objectMapper.writeValueAsString(permissions), days, TimeUnit.DAYS);
        } catch (Exception e) {
            // 序列化与 Redis 写失败都不阻断登录主流程——缓存只是加速手段
            log.error("权限码缓存写入失败 key={}", key, e);
        }
    }

    /** 解析 JSON 数组文本；解析失败按未命中处理（返回 empty）。 */
    private Optional<List<String>> parse(String json, String key) {
        try {
            List<String> permissions = objectMapper.readValue(json, new TypeReference<List<String>>() { });
            return Optional.of(permissions.stream().filter(s -> s != null && !s.isBlank()).toList());
        } catch (JsonProcessingException e) {
            log.warn("权限码缓存内容解析失败，按未命中处理 key={}", key, e);
            return Optional.empty();
        }
    }
}
