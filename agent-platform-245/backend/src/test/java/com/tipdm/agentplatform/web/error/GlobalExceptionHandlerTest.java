package com.tipdm.agentplatform.web.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unauthorized_includesBearerChallenge() {
        ResponseEntity<Map<String, String>> response =
                handler.handleApiException(ApiException.unauthorized("请先登录"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer realm=\"agent-platform\"");
        assertThat(response.getBody()).containsEntry("code", "UNAUTHORIZED");
    }

    @Test
    void forbidden_doesNotIncludeBearerChallenge() {
        ResponseEntity<Map<String, String>> response =
                handler.handleApiException(ApiException.forbidden("缺少权限"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(response.getBody()).containsEntry("code", "FORBIDDEN");
    }

    @Test
    void notFound_preservesOriginalSemantics() {
        ResponseEntity<Map<String, String>> response =
                handler.handleApiException(ApiException.notFound("资源不存在"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("code", "NOT_FOUND");
    }

    @Test
    void permissionServiceUnavailable_doesNotBecomeInternalError() {
        ApiException exception = new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "PERMISSION_SERVICE_UNAVAILABLE", "权限服务暂时不可用");

        ResponseEntity<Map<String, String>> response = handler.handleApiException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("code", "PERMISSION_SERVICE_UNAVAILABLE");
    }
}
