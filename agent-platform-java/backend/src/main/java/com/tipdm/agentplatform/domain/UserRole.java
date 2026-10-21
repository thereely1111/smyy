package com.tipdm.agentplatform.domain;

/**
 * 平台用户角色（本地登录与 OAuth 同步用户共用）。
 */
public enum UserRole {
    ADMIN,
    DEVELOPER,
    END_USER;

    public static UserRole parse(String value) {
        if (value == null || value.isBlank()) {
            return END_USER;
        }
        try {
            return UserRole.valueOf(value.trim().toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return END_USER;
        }
    }
}
