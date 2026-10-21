package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.domain.UserRole;
import com.tipdm.agentplatform.service.UserService;
import com.tipdm.agentplatform.web.dto.AuthDtos;
import com.tipdm.agentplatform.web.dto.AuthDtos.RegisterRequest;
import com.tipdm.agentplatform.web.dto.AuthDtos.UserInfoResponse;
import com.tipdm.agentplatform.domain.User;
import com.tipdm.agentplatform.repository.UserRepository;
import com.tipdm.agentplatform.web.auth.JwtInterceptor;
import com.tipdm.agentplatform.web.dto.AuthDtos.ChangePasswordRequest;
import com.tipdm.agentplatform.web.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 用户接口
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;
    private final UserRepository userRepository;

    public UserController(UserService userService, UserRepository userRepository) {
        this.userService = userService;
        this.userRepository = userRepository;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserInfoResponse register(@Valid @RequestBody RegisterRequest body) {
        // 安全：本接口免登录（见 JwtAuthFilter#PUBLIC_PATHS），请求体里的 role 绝不可信——
        // 否则任何人都能把自己注册成 ADMIN 从而接管平台。这里强制降级为 END_USER；
        // 需要指定角色的管理端建户走 /api/auth/register（@RequirePermission("user:create")）。
        if (body.role() != null && body.role() != UserRole.END_USER) {
            log.warn("公开注册请求携带非 END_USER 角色，已强制降级: role={}", body.role());
        }
        User user = userService.register(body.principal(), body.password(), UserRole.END_USER,
                body.registerType(), body.captcha());
        // 手机号注册返回 null（未落库），调用方直接走登录；此处仅对账密注册返回信息
        // 注意：手机号分支给空 UserInfoResponse（而非 null），前端 http 层会对非 204 拿 res.json()，
        // null 响应体无法解析会抛错。前端 register 不读该返回值，仅需 body 是合法 JSON。
        return user == null
                ? new UserInfoResponse(null, null, null, null, null, null, null, null)
                : UserInfoResponse.from(user, List.of());
    }

    /**
     * 登录用户修改自己的密码（前端「设置-账号安全」入口）。
     * 身份以 JWT attribute 为准，不接受请求传 userId。
     */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest body,
            HttpServletRequest request) {
        User user = currentUser(request);
        userService.changePassword(user, body.oldPassword(), body.newPassword());
    }

    /** 未登录态重置密码（手机号/邮箱 + 验证码）。 */
    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgotPassword(@Valid @RequestBody AuthDtos.ForgotPasswordRequest body) {
        userService.resetPassword(body.principal(), body.password(), body.captcha());
    }

    /** 从 request attribute 解析当前登录用户并加载（与 AuthController 同款）。 */
    private User currentUser(HttpServletRequest request) {
        Object userId = request.getAttribute(JwtInterceptor.ATTR_USER_ID);
        if (userId == null) {
            throw ApiException.unauthorized("未登录");
        }
        return userRepository.findById(UUID.fromString(userId.toString()))
                .orElseThrow(() -> ApiException.unauthorized("用户不存在"));
    }
    /** 手机号注册：发送验证码 → 转发 tipdm。 */
    @GetMapping("/captcha/phone/{phone}")
    public Long captchaPhone(@PathVariable String phone) {
        return userService.sendPhoneRegisterCaptcha(phone);
    }

    /** 邮箱注册：发送验证码 → 转发 tipdm。 */
    @GetMapping("/captcha/email/{email}")
    public Long captchaEmail(@PathVariable String email) {
        return userService.sendEmailRegisterCaptcha(email);
    }

    /** 忘记密码：发送重置验证码（手机号或邮箱，type=resetPassword）。 */
    @GetMapping("/captcha/forgot/{principal}")
    public Long captchaForgot(@PathVariable String principal) {
        return userService.sendForgotCaptcha(principal);
    }
}
