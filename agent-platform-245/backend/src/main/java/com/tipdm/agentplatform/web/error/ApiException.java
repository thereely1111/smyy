package com.tipdm.agentplatform.web.error;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    /**
     * 410 Gone：资源已被删除且不会恢复。
     *
     * <p>与 404 的区别是本项目的一条产品约定——<b>会话删除是终态，不支持复活</b>，
     * 前端据此可与「不存在」区分：404 让用户重新创建，410 则明确告知
     * 「这个会话已被删除，请新建会话」，不必让用户对同一 sessionId 反复重试。</p>
     */
    public static ApiException gone(String message) {
        return new ApiException(HttpStatus.GONE, "GONE", message);
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
