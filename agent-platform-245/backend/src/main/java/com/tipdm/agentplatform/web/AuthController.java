package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.domain.User;
import com.tipdm.agentplatform.domain.UserRole;
import com.tipdm.agentplatform.repository.UserRepository;
import com.tipdm.agentplatform.service.AuthService;
import com.tipdm.agentplatform.service.UserService;
import com.tipdm.agentplatform.web.auth.JwtInterceptor;
import com.tipdm.agentplatform.web.auth.RequirePermission;
import com.tipdm.agentplatform.web.dto.AuthDtos.LoginRequest;
import com.tipdm.agentplatform.web.dto.AuthDtos.LoginUrlResponse;
import com.tipdm.agentplatform.web.dto.AuthDtos.OAuthCallbackRequest;
import com.tipdm.agentplatform.web.dto.AuthDtos.RefreshRequest;
import com.tipdm.agentplatform.web.dto.AuthDtos.RegisterRequest;
import com.tipdm.agentplatform.web.dto.AuthDtos.TokenResponse;
import com.tipdm.agentplatform.web.dto.AuthDtos.UserInfoResponse;
import com.tipdm.agentplatform.web.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 认证接口（契约与参考项目 agent-platform /auth 前缀一致，本平台挂在 /api/auth）：
 * login / me / refresh / logout / register / oauth/login / callback。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;
    private final UserService userService;

    public AuthController(AuthService authService, UserRepository userRepository, UserService userService) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest body) {
        return TokenResponse.of(authService.login(body.username(), body.password()));
    }

    @GetMapping("/me")
    public UserInfoResponse me(HttpServletRequest request) {
        User user = currentUser(request);
        List<String> permissions = authService.getCachedPermissions(user.getId());
        return UserInfoResponse.from(user, permissions);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest body) {
        return TokenResponse.of(authService.refresh(body.refreshToken()));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        // JWT 无状态，服务端无法真正吊销 token；此处为契约协调点，
        // 客户端调用后清除本地存储（与参考项目行为一致）。
        String userId = JwtInterceptor.resolveUserId(request);
        if (userId != null && !userId.isBlank()) {
            authService.logout(userId);
        }
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("user:create")
    public UserInfoResponse register(@Valid @RequestBody RegisterRequest body) {
        UserRole role = body.role() == null ? UserRole.END_USER : body.role();
        User user = userService.register(body.principal(), body.password(), role,
                body.registerType(), body.captcha());
        // 手机号注册不在本地落库（返回 null），管理端建户只支持账密方式，防 NPE
        if (user == null) {
            throw ApiException.badRequest("管理端创建账号仅支持用户名方式，请使用公开注册入口做手机号注册");
        }
        return UserInfoResponse.from(user, List.of());
    }

    @GetMapping("/oauth/login")
    public LoginUrlResponse oauthLogin() {
        AuthService.LoginUrlResult result = authService.generateLoginUrl();
        return new LoginUrlResponse(result.loginUrl(), result.state(), result.redirectUri());
    }

    @PostMapping("/callback")
    public TokenResponse oauthCallback(@Valid @RequestBody OAuthCallbackRequest body) {
        return TokenResponse.of(authService.exchangeCallback(body.code(), body.state()));
    }

    /**
     * 从 request attribute（JwtInterceptor 写入）解析当前登录用户并加载。
     */
    private User currentUser(HttpServletRequest request) {
        Object userId = request.getAttribute(JwtInterceptor.ATTR_USER_ID);
        if (userId == null) {
            throw ApiException.unauthorized("未登录");
        }
        return userRepository.findById(UUID.fromString(userId.toString()))
                .orElseThrow(() -> ApiException.unauthorized("用户不存在"));
    }
}
