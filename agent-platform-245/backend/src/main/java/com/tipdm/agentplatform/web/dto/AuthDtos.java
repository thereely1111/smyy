package com.tipdm.agentplatform.web.dto;

import com.tipdm.agentplatform.domain.User;
import com.tipdm.agentplatform.domain.UserRole;
import com.tipdm.agentplatform.service.AuthService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 认证接口 DTO。字段采用 Java 风格 camelCase（Spring Boot Jackson 默认），
 * 与仓库其余 API 契约保持一致。
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 100) String username,
            @NotBlank String password) {
    }

    public record RefreshRequest(
            @NotBlank String refreshToken) {
    }

    public record ChangePasswordRequest(
            @NotBlank String oldPassword,
            @NotBlank @Size(min = 4, max = 100) String newPassword) {
    }

    public record ForgotPasswordRequest(
            @NotBlank String principal,
            @NotBlank String password,
            @NotBlank String captcha) {
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 100) String principal,
            @NotBlank String password,
            UserRole role,
            // 注册方式：username（默认，账密注册）／phone（手机号注册）／email（邮箱注册）
            // principal：账密时是用户名，phone/email 时则是对应的手机号/邮箱
            // phone/email 时必须携带验证码 captcha
            String registerType,
            String captcha) {

    }

    public record TokenResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            String userId,
            String username,
            String role,
            String name,
            List<String> permissions) {

        public static TokenResponse of(AuthService.TokenResult tokens) {
            return new TokenResponse(tokens.accessToken(), tokens.refreshToken(), "bearer",
                    tokens.userId(), tokens.username(), tokens.role(), tokens.name(), tokens.permissions());
        }
    }

    public record UserInfoResponse(
            String userId,
            String username,
            String role,
            String createdAt,
            String name,
            String email,
            String phone,
            List<String> permissions
            ) {

        /** 手机号脱敏展示（个人中心可显示，换绑时走重新验证，不依赖回显）。 */
        public static String maskPhone(String phone) {
            if (phone == null || phone.length() < 7) {
                return phone;
            }
            return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
        }

        public static UserInfoResponse from(User user, List<String> permissions) {
            return new UserInfoResponse(
                    user.getId().toString(),
                    user.getUsername(),
                    user.getRole().name().toLowerCase(),
                    user.getCreatedAt() != null ? user.getCreatedAt().toString() : null,
                    user.getName(),
                    user.getEmail(),
                    maskPhone(user.getPhone()),
                    permissions);
        }
    }

    public record OAuthCallbackRequest(
            @NotBlank String code,
            @NotBlank String state) {
    }

    public record LoginUrlResponse(
            String loginUrl,
            String state,
            String redirectUri) {
    }
}
