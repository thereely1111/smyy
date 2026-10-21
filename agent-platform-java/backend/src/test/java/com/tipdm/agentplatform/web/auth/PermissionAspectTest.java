package com.tipdm.agentplatform.web.auth;

import com.tipdm.agentplatform.service.PermissionCodeCache;
import com.tipdm.agentplatform.web.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 权限切面测试。
 */
class PermissionAspectTest {

    private static final UUID USER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    /** 非法用户 id（非 UUID），新切面应在鉴权层直接拒绝。 */
    private static final String INVALID_USER_ID = "user-1";

    private final PermissionCodeCache permissionCodeCache = mock(PermissionCodeCache.class);

    @Test
    void allowsMethodWithoutPermissionAnnotation() {
        assertDoesNotThrow(() -> service(new MockHttpServletRequest()).open());
        verifyNoInteractions(permissionCodeCache);
    }

    @Test
    void rejectsRequestWithoutCurrentUser() {
        ApiException exception = assertThrows(ApiException.class,
                () -> service(new MockHttpServletRequest()).create());

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        assertEquals("UNAUTHORIZED", exception.getCode());
        verifyNoInteractions(permissionCodeCache);
    }

    @Test
    void rejectsRequestWithInvalidUserId() {
        ApiException exception = assertThrows(ApiException.class,
                () -> service(requestWithUser(INVALID_USER_ID)).create());

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatus());
        verifyNoInteractions(permissionCodeCache);
    }

    @Test
    void allowsUserWithPermission() {
        when(permissionCodeCache.read(USER_UUID)).thenReturn(Optional.of(List.of("agent:create")));

        assertDoesNotThrow(() -> service(requestWithUser(USER_UUID.toString())).create());
    }

    @Test
    void rejectsUserWithoutPermission() {
        when(permissionCodeCache.read(USER_UUID)).thenReturn(Optional.of(List.of()));

        ApiException exception = assertThrows(ApiException.class,
                () -> service(requestWithUser(USER_UUID.toString())).create());

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
        assertEquals("FORBIDDEN", exception.getCode());
    }

    @Test
    void returnsServiceUnavailableWhenPermissionCacheIsMissing() {
        when(permissionCodeCache.read(USER_UUID)).thenReturn(Optional.empty());

        assertPermissionServiceUnavailable(assertThrows(ApiException.class,
                () -> service(requestWithUser(USER_UUID.toString())).create()));
    }

    private ProtectedService service(MockHttpServletRequest request) {
        AspectJProxyFactory factory = new AspectJProxyFactory(new ProtectedService());
        factory.addAspect(new PermissionAspect(permissionCodeCache, request));
        return factory.getProxy();
    }

    private MockHttpServletRequest requestWithUser(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtInterceptor.ATTR_USER_ID, userId);
        return request;
    }

    private void assertPermissionServiceUnavailable(ApiException exception) {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatus());
        assertEquals("PERMISSION_SERVICE_UNAVAILABLE", exception.getCode());
    }

    static class ProtectedService {

        public void open() {
        }

        @RequirePermission("agent:create")
        public void create() {
        }
    }
}
