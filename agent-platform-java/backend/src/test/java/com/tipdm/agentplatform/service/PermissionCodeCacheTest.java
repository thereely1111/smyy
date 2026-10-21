package com.tipdm.agentplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.util.RedisKeyConstants;
import com.tipdm.agentplatform.util.RedisUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 权限码缓存组件测试：核心是「读写对称」——写入 JSON 字符串格式后必须能读回。
 */
class PermissionCodeCacheTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String KEY = RedisKeyConstants.authPermKey(USER_ID);

    private final RedisUtils redisUtils = mock(RedisUtils.class);
    private final PermissionCodeCache cache = new PermissionCodeCache(redisUtils, new ObjectMapper());

    @Test
    void writeThenReadRoundTrips() {
        cache.write(USER_ID, List.of("admin:console", "agent:execute"), 7);

        // 捕获写入的实际值（JSON 字符串），再走读取路径验证对称性
        org.mockito.ArgumentCaptor<Object> captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(redisUtils).set(eq(KEY), captor.capture(), eq(7L), eq(TimeUnit.DAYS));
        String stored = (String) captor.getValue();
        assertTrue(stored.startsWith("[\"admin:console\""), "应存为 JSON 数组文本，实际: " + stored);

        when(redisUtils.get(KEY)).thenReturn(stored);
        assertEquals(Optional.of(List.of("admin:console", "agent:execute")), cache.read(USER_ID));
    }

    @Test
    void readsLegacyCollectionFormat() {
        when(redisUtils.get(KEY)).thenReturn(List.of("agent:create"));

        assertEquals(Optional.of(List.of("agent:create")), cache.read(USER_ID));
    }

    @Test
    void returnsEmptyOnMiss() {
        when(redisUtils.get(KEY)).thenReturn(null);

        assertTrue(cache.read(USER_ID).isEmpty());
    }

    @Test
    void returnsEmptyOnUnparseableContent() {
        when(redisUtils.get(KEY)).thenReturn("{invalid-json");

        assertTrue(cache.read(USER_ID).isEmpty());
    }

    @Test
    void returnsEmptyWhenRedisReadFails() {
        when(redisUtils.get(KEY)).thenThrow(new IllegalStateException("Redis unavailable"));

        assertTrue(cache.read(USER_ID).isEmpty());
    }

    @Test
    void writeFailureDoesNotThrow() {
        org.mockito.Mockito.doThrow(new IllegalStateException("Redis down"))
                .when(redisUtils).set(any(), any(), anyLong(), any());

        cache.write(USER_ID, List.of("agent:create"), 7);
    }
}
