package com.tipdm.agentplatform.util;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * Redis 通用操作工具类：
 * 提供基础的键值读写、过期时间控制、删除等便捷方法。
 */
@Component
public class RedisUtils {

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisUtils(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // ── 基础 String / Object 键值操作 ──────────────────────────────────────

    /**
     * 写入普通缓存
     */
    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }

    /**
     * 写入缓存并设置过期时间
     */
    public void set(String key, Object value, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    /**
     * 获取缓存值（泛型安全转换）
     */
    @SuppressWarnings("unchecked")
    public <T>T get(String key) {
        return (T) redisTemplate.opsForValue().get(key);
    }

    /**
     * 判断 Key 是否存在
     */
    public boolean hashKey(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * 删除单个 Key
     */
    public boolean delete(String key) {
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }

    /**
     * 批量删除 Key
     */
    public long delete(Collection<String> keys) {
        Long count = redisTemplate.delete(keys);
        return count != null ? count : 0;
    }

    /**
     * 设置 Key 的过期时间
     */
    public boolean expire(String key, long timeout, TimeUnit unit) {
        return Boolean.TRUE.equals(redisTemplate.expire(key, timeout, unit));
    }

    /**
     * 获取 Key 的剩余过期时间（秒）
     */
    public long getExpire(String key) {
        Long expire = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        return expire != null ? expire : -1;
    }

    /**
     * 获取底层的原生 RedisTemplate（用于特殊高级操作）
     */
    public RedisTemplate<String, Object> getRedisTemplate() {
        return redisTemplate;
    }




}
