package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.config.AuthProperties;
import com.tipdm.agentplatform.domain.User;
import com.tipdm.agentplatform.domain.UserRole;
import com.tipdm.agentplatform.repository.UserRepository;
import com.tipdm.agentplatform.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Base64;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.Cipher;
import org.springframework.http.HttpStatus;

/**
 * 用户服务
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** 注册类型 / 账号类型 */
    private static final String TYPE_PHONE = "phone";
    private static final String TYPE_EMAIL = "email";
    private static final String TYPE_USERNAME = "username";
    /** 验证码用途类型 */
    private static final String CAPTCHA_REGISTER = "register";
    private static final String CAPTCHA_RESET = "resetPassword";
    /** 账号格式校验正则 */
    private static final String PHONE_PATTERN = "1\\d{10}";
    private static final String EMAIL_PATTERN = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$";

    private final AuthProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final RestClient restClient = RestClient.create();

    public UserService(AuthProperties properties, UserRepository userRepository) {
        this.properties = properties;
        this.userRepository = userRepository;
    }

    /**
     * 注册：先在统一认证系统(9623)注册，成功后再落本地 users 表。
     *
     * <p><b>刻意不加 @Transactional</b>（2026-09-17）：本方法持有一个事务跨越
     * {@code registerOnAuthServer} 的远程 HTTP「整个时长」，外部服务抖动时连接被长时间
     * 占用、连接池迅速耗尽；而本方法只有末尾一次 {@code save} 需要原子性，单次 JPA
     * 写本身就是事务。去掉方法级事务，DB 连接在远程调用期间不再被占。</p>
     */
    public User register(String username, String password, UserRole role, String registerType, String captcha) {

        // phone/email 都走"验证码注册"：不校验本地 username 冲突、不预落库（落库交给登录后的 syncOauthUser）
        boolean captchaReg = TYPE_PHONE.equalsIgnoreCase(registerType)
                || TYPE_EMAIL.equalsIgnoreCase(registerType);

        if (captchaReg) {
            AuthProperties.OAuth oauth = properties.getOauth();
            if (!oauth.isEnabled()) {
                throw ApiException.badRequest("手机号/邮箱注册需要启用统一认证服务");
            }
            registerOnAuthServer(registerType.toLowerCase(), username, password, captcha, oauth);
            return null; // 返回 null，前端随即走 login 落库
        }

        if (userRepository.existsByUsername(username)) {
            throw ApiException.conflict("用户名已存在");
        }
        // 1. 先到外部鉴权服务(9623)注册；OAuth 未启用时跳过，仅本地注册
        AuthProperties.OAuth oauth = properties.getOauth();
        if (oauth.isEnabled()) {
            registerOnAuthServer(TYPE_USERNAME, username, password, null, oauth);
        }
        // 2. 再落本地 users 表
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(role);
        return userRepository.save(user);
    }

    /** 写防抖表并做机会式淘汰，把表规模钉在 {@link #MAX_SEND_THROTTLE_ENTRIES} 量级。 */
    private void recordSendTime(String principal, long now) {
        if (lastSendTimeMap.size() >= MAX_SEND_THROTTLE_ENTRIES) {
            long cutoff = now - MIN_SEND_INTERVAL_MILLIS;
            // 超过 60s 的条目不再参与判定，清掉无副作用
            lastSendTimeMap.entrySet().removeIf(entry -> entry.getValue() < cutoff);
            if (lastSendTimeMap.size() >= MAX_SEND_THROTTLE_ENTRIES) {
                log.warn("发码防抖表达到上限 {}，整体重置（防抖短暂失效，避免无界增长）",
                        MAX_SEND_THROTTLE_ENTRIES);
                lastSendTimeMap.clear();
            }
        }
        lastSendTimeMap.put(principal, now);
    }

    /**
     * 调用外部鉴权服务(9623)的 /api/register。
     */
    private void registerOnAuthServer(String registerType, String principal,
                                      String password, String captcha, AuthProperties.OAuth oauth) {
        try {
            // body 要用可变 Map，条件性加 captcha
            Map<String, Object> body = new HashMap<>();
            String registerKey = oauth.effectiveRegisterPublicKey();
            body.put("registerType", registerType);
            body.put("principal", rsaEncrypt(principal, registerKey));
            body.put("password", rsaEncrypt(password, registerKey));
            body.put("clientId", oauth.getClientId());
            body.put("consentAgreement", true);
            if (captcha != null && !captcha.isBlank()) {
                body.put("captcha", captcha);
            }

            Map<String, Object> resp = restClient.post()
                    .uri(oauth.url(oauth.getRegisterPath()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
            // tipdm 业务异常（验证码错误/手机号已注册等）同样返回 HTTP 200 + {status:500, message}，
            // 必须校验 status 并把 message 透传给前端，否则用户只看到笼统的“注册失败”无从排查
            if (resp == null || !Integer.valueOf(200).equals(respNumber(resp.get("status")))) {
                log.error("外部鉴权服务注册失败：{}", resp);
                String msg = resp == null || resp.get("message") == null ? null : resp.get("message").toString();
                throw ApiException.conflict(msg == null || msg.isBlank() ? "注册失败，请稍后重试" : msg);
            }
        } catch (RestClientResponseException e) {
            log.error("外部鉴权服务注册被拒（{}）", e.getStatusCode().value(), e);
            throw ApiException.conflict("注册失败，请稍后重试");
        } catch (ResourceAccessException e) {
            log.error("外部鉴权服务不可达，放弃本地注册", e);
            throw ApiException.badRequest("注册服务不可达，请稍后重试");
        }
    }

    /** 给手机号发送注册验证码：校验 11 位手机号后转发 tipdm。 */
    public long sendPhoneRegisterCaptcha(String phone) {
        if (phone == null || !phone.matches(PHONE_PATTERN)) {
            throw ApiException.badRequest("请输入正确的 11 位手机号");
        }
        return forwardSendCaptcha(phone, CAPTCHA_REGISTER);
    }

    /** 给邮箱发送注册验证码：校验邮箱格式后转发 tipdm。 */
    public long sendEmailRegisterCaptcha(String email) {
        if (email == null || !email.matches(EMAIL_PATTERN)) {
            throw ApiException.badRequest("请输入正确的邮箱地址");
        }
        return forwardSendCaptcha(email, CAPTCHA_REGISTER);
    }

    /** 忘记密码：给手机号或邮箱发送重置验证码（type=resetPassword），账号类型由正则判断。 */
    public long sendForgotCaptcha(String principal) {
        if (principal == null || (!principal.matches(PHONE_PATTERN)
                && !principal.matches(EMAIL_PATTERN))) {
            throw ApiException.badRequest("请输入正确的手机号或邮箱");
        }
        return forwardSendCaptcha(principal, CAPTCHA_RESET);
    }

        /** 轻量防抖：记录 principal 最后成功发码时间戳（毫秒），单实例防高频刷发。 */
    private final ConcurrentHashMap<String, Long> lastSendTimeMap =
            new ConcurrentHashMap<>();
    /** 发码最短间隔（毫秒），避免短时间内高频刷验证码。 */
    private static final long MIN_SEND_INTERVAL_MILLIS = 60_000;
    /**
     * 防抖表容量上限。
     *
     * <p>key 是攻击者可控的手机号/邮箱，而发码接口在 {@code PUBLIC_PATHS} 免登录——
     * 原实现只 put 不淘汰，匿名用不同号码灌入即可把单实例堆撑爆（2026-09-17）。
     * 超过上限先按「已过防抖窗口的条目不再有意义」清理，仍超则整体丢弃
     * （防抖是尽力而为的滥用抑制，短暂失效远好过 OOM）。</p>
     */
    private static final int MAX_SEND_THROTTLE_ENTRIES = 10_000;

    /** 共用：把 principal 转发给 tipdm 发验证码，返回剩余有效期(秒)。 */
    private long forwardSendCaptcha(String principal, String captchaType) {
        AuthProperties.OAuth oauth = properties.getOauth();
        if (!oauth.isEnabled()) {
            throw ApiException.badRequest("统一认证服务未启用，无法发送验证码");
        }
        // 轻量并发防刷：同一账号 60s 内只允许发一次，避免短时间高频刷发
        long now = System.currentTimeMillis();
        Long last = lastSendTimeMap.get(principal);
        if (last != null && now - last < MIN_SEND_INTERVAL_MILLIS) {
            long wait = (MIN_SEND_INTERVAL_MILLIS - (now - last)) / 1000 + 1;
            throw ApiException.badRequest("发送太频繁，请" + wait + "秒后再试");
        }
        try {
            // principal 可能是邮箱，直接拼进 URL 会因 '@'（userinfo 分隔符）导致 URI 解析失败→500，
            // 必须先按 path segment 编码（@ → %40），http 层再按需解码。
            String encodedPrincipal = UriUtils.encodePathSegment(principal, StandardCharsets.UTF_8);
            String captchaUrl = oauth.url(oauth.getCaptchaPath() + "/" + encodedPrincipal) + "?type=" + captchaType;
            Map<String, Object> resp = restClient.get()
                    .uri(captchaUrl)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
            // tipdm 发送失败（短信/邮件未开启等）也是 HTTP 200 + {status:500, message}，
            // 不校验 status 会误判成功：前端开始倒计时但验证码根本没发出去
            if (resp == null || !Integer.valueOf(200).equals(respNumber(resp.get("status")))) {
                log.error("验证码发送失败：{}", resp);
                String msg = resp == null || resp.get("message") == null ? null : resp.get("message").toString();
                throw ApiException.badRequest(msg == null || msg.isBlank() ? "验证码发送失败，请稍后重试" : msg);
            }
            Object data = resp.get("data");
            // 成功但未给出有效期时回退 tipdm 默认 5 分钟，前端据此启动倒计时
            long remaining = 300L;
            if (data instanceof Number n) {
                remaining = n.longValue();
            } else if (data != null) {
                try {
                    remaining = Long.parseLong(data.toString());
                } catch (NumberFormatException ignore) {
                }
            }
            // 仅 tipdm 成功才更新最后发码时间戳，避免失败情况下误占用 key
            recordSendTime(principal, now);
            return remaining;
        } catch (RestClientResponseException e) {
            log.error("鉴权服务拒绝发送验证码（{}）", e.getStatusCode().value(), e);
            throw ApiException.badRequest("验证码发送失败，请稍后重试");
        } catch (ResourceAccessException e) {
            log.error("鉴权服务不可达，无法发送验证码", e);
            throw ApiException.badRequest("认证服务暂不可用，请稍后重试");
        }
    }
    private Integer respNumber(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    /**
     * 修改当前用户的密码。
     * 旧密码经 tipdm 密码模式反验（能换到 token 即密码正确），再用 tipdm 公钥加密新旧密码，
     * 携带 token 调 tipdm 改密接口。tipdm 成功后再更新本地 users 表，避免两边不一致。
     */
    public void changePassword(User user, String oldPassword, String newPassword) {
        // 认人靠传入的实体（主键/锚点），username 仅作为去 tipdm 换 token 的登录句柄
        String username = user.getUsername();
        Map<String, Object> tokenResponse;
        try {
            tokenResponse = exchangePasswordToken(username, oldPassword);
        } catch (RestClientResponseException e) {
            // tipdm 密码模式拒绝错误凭据时返回 400/401 invalid_grant，RestClient 会抛异常；
            // 不捕获会一路冒到全局兜底，被伪装成 500「服务器内部错误」，用户无从知道是旧密码错了
            int status = e.getStatusCode().value();
            if (status == 400 || status == 401) {
                // tipdm 对「账号不存在」和「密码错误」都回 invalid_grant，借 isExist 接口区分，
                // 避免本地账号与认证系统映射丢失时（如 admin_1）误报“旧密码不正确”
                if (!principalExistsInAuthServer(username)) {
                    throw ApiException.notFound("用户不存在：统一认证系统中没有该账号，请联系管理员核实");
                }
                throw ApiException.unauthorized("旧密码不正确");
            }
            log.error("改密换 token 失败（{}）", status, e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AUTH_ERROR", "认证服务异常，请稍后重试");
        } catch (ResourceAccessException e) {
            log.error("改密时鉴权服务不可达", e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTH_UNAVAILABLE", "认证服务暂不可用，请稍后重试");
        }
        Object tokenObj = tokenResponse.get("access_token");
        if (tokenObj == null || tokenObj.toString().isBlank()) {
            throw ApiException.unauthorized("旧密码校验失败");
        }
        String accessToken = tokenObj.toString();

        AuthProperties.OAuth oauth = properties.getOauth();
        String publicKey = oauth.getTipdmPublicKey();
        if (publicKey == null || publicKey.isBlank()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL", "服务端未配置改密公钥");
        }

        // 调 tipdm 改密接口（confirmPwd 与 newPassword 一致，交由 tipdm 校验）
        String url = oauth.url(oauth.getPasswordPath());
        Map<String, String> body = Map.of(
                "oldPassword", rsaEncrypt(oldPassword, publicKey),
                "newPassword", rsaEncrypt(newPassword, publicKey),
                "confirmPwd", rsaEncrypt(newPassword, publicKey));
        Map<String, Object> resp;
        try {
            resp = restClient.put()
                    .uri(url)
                    .header("Authorization", "Bearer " + accessToken)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
        } catch (RestClientResponseException e) {
            log.error("tipdm 改密接口拒绝（{}）", e.getStatusCode().value(), e);
            throw ApiException.badRequest("密码修改失败，请稍后重试");
        } catch (ResourceAccessException e) {
            log.error("改密时鉴权服务不可达", e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTH_UNAVAILABLE", "认证服务暂不可用，请稍后重试");
        }
        // tipdm 业务错误（新密码不符合策略等）也是 HTTP 200 + {status:500, message}，
        // 不解析响应体会误判成功并同步本地密码，造成两边不一致
        if (resp == null || !Integer.valueOf(200).equals(respNumber(resp.get("status")))) {
            log.error("tipdm 改密失败：{}", resp);
            String msg = resp == null || resp.get("message") == null ? null : resp.get("message").toString();
            throw ApiException.badRequest(msg == null || msg.isBlank() ? "密码修改失败" : msg);
        }

        // tipdm 改密成功 → 本地按主键同步 bcrypt 哈希（使本地回退登录与 tipdm 一致）
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }

    /** 未登录态重置密码（统一判型：手机号→phone、邮箱→email）。 */
    public void resetPassword(String principal, String newPassword, String captcha) {
        String type;
        if (principal != null && principal.matches(PHONE_PATTERN)) {
            type = TYPE_PHONE;
        } else if (principal != null && principal.matches(EMAIL_PATTERN)) {
            type = TYPE_EMAIL;
        } else {
            throw ApiException.badRequest("请输入正确的手机号或邮箱");
        }
        AuthProperties.OAuth oauth = properties.getOauth();
        if (!oauth.isEnabled()) {
            throw ApiException.badRequest("统一认证服务未启用，暂时无法重置密码");
        }
        String publicKey = oauth.effectiveRegisterPublicKey();
        if (publicKey == null || publicKey.isBlank()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL", "服务端未配置重置密码公钥");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("type", type);
        body.put("principal", rsaEncrypt(principal, publicKey));
        body.put("password", rsaEncrypt(newPassword, publicKey));
        body.put("captcha", captcha);
        Map<String, Object> resp;
        try {
            resp = restClient.put()
                    .uri(oauth.url(oauth.getResetPasswordPath()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
        } catch (RestClientResponseException e) {
            log.error("tipdm 重置密码被拒（{}）", e.getStatusCode().value(), e);
            throw ApiException.badRequest("密码重置失败，请稍后重试");
        } catch (ResourceAccessException e) {
            log.error("重置密码时鉴权服务不可达", e);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AUTH_UNAVAILABLE", "认证服务暂不可用，请稍后重试");
        }
        if (resp == null || !Integer.valueOf(200).equals(respNumber(resp.get("status")))) {
            log.error("tipdm 重置密码失败：{}", resp);
            String msg = resp == null || resp.get("message") == null ? null : resp.get("message").toString();
            throw ApiException.badRequest(msg == null || msg.isBlank() ? "密码重置失败" : msg);
        }
    }

    /** 用 username + 旧密码调 tipdm 密码模式，返回 token 响应。 */
    private Map<String, Object> exchangePasswordToken(String username, String password) {
        AuthProperties.OAuth oauth = properties.getOauth();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("username", username);
        form.add("password", password);
        form.add("client_id", oauth.getClientId());
        form.add("scope", oauth.getScopes());
        if (oauth.getClientSecret() != null && !oauth.getClientSecret().isBlank()) {
            form.add("client_secret", oauth.getClientSecret());
        }
        return restClient.post()
                .uri(oauth.url(oauth.getTokenPath()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {
                });
    }

    /**
     * 查询 principal（用户名/手机号）是否存在于统一认证服务。
     * 借用 tipdm 公开的 GET /api/user/{principal}/isExist；查询本身失败时按“存在”处理，
     * 让调用方回落到常规提示，不因辅助判断出错而误导用户。
     */
    private boolean principalExistsInAuthServer(String principal) {
        AuthProperties.OAuth oauth = properties.getOauth();
        try {
            Map<String, Object> resp = restClient.get()
                    .uri(oauth.url("/api/user/" + principal + "/isExist"))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (resp != null && Integer.valueOf(200).equals(respNumber(resp.get("status")))) {
                return Boolean.parseBoolean(String.valueOf(resp.get("data")));
            }
            log.warn("isExist 查询返回异常状态，按存在处理：{}", resp);
        } catch (Exception e) {
            log.warn("isExist 查询失败，按存在处理：{}", e.getMessage());
        }
        return true;
    }

    private String rsaEncrypt(String plain, String base64PublicKey) {
        try {
            PublicKey publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(
                            Base64.getDecoder().decode(base64PublicKey)));
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            return Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("RSA 加密失败", e);
            throw ApiException.badRequest("密码加密失败");
        }
    }
}
