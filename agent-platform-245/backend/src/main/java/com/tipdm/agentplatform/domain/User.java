package com.tipdm.agentplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 平台用户：支持本地密码登录（auth_type=password）与 OAuth2.0 登录（auth_type=oauth，
 * 外部鉴权服务验证后同步到本地并签发本地 JWT）。
 */
@Entity
@Table(name = "users", indexes = @Index(name = "idx_users_oauth", columnList = "oauth_user_id"))
public class User extends BaseEntity {

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    /** bcrypt 哈希；OAuth 用户为空串。 */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role = UserRole.END_USER;

    @Column(name = "auth_type", nullable = false, length = 16)
    private String authType = "password";

    @Column(name = "oauth_user_id", length = 128)
    private String oauthUserId;

    @Column(name = "oauth_provider", length = 64)
    private String oauthProvider;

    @Column(length = 255)
    private String email;

    /** 手机号：登录同步时从 tipdm userinfo 的 phone 字段刷新。 */
    @Column(length = 20)
    private String phone;

    @Column(length = 128)
    private String name;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public String getOauthUserId() {
        return oauthUserId;
    }

    public void setOauthUserId(String oauthUserId) {
        this.oauthUserId = oauthUserId;
    }

    public String getOauthProvider() {
        return oauthProvider;
    }

    public void setOauthProvider(String oauthProvider) {
        this.oauthProvider = oauthProvider;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
