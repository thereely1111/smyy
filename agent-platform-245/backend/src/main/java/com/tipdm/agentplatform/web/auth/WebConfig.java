package com.tipdm.agentplatform.web.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.service.JwtService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;

    public WebConfig(JwtInterceptor jwtInterceptor) {
        this.jwtInterceptor = jwtInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 认证拦截器：解析 Bearer token 并暴露用户信息。
        // 鉴权一律由 @RequirePermission 切面按权限码完成——角色鉴权（@RequireAdmin）已移除，
        // 避免「角色」与「权限码」两套体系并存。
        registry.addInterceptor(jwtInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns("/api/auth/login", "/api/auth/refresh", "/api/auth/callback",
                    "/api/users/register", "/api/users/captcha/**");
    }

    /**
     * 全局 JWT 时效性过滤器：对 /api/** 与 /agui/** 强制要求有效 access token，
     * 不允许匿名访问。置于最高优先级，先于拦截器执行；token 缺失/无效/过期
     * 直接返回 401，由前端统一登出并跳转登录页。
     */
    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(
            ObjectMapper objectMapper, JwtService jwtService) {
        JwtAuthFilter filter = new JwtAuthFilter(jwtService, objectMapper);
        FilterRegistrationBean<JwtAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setUrlPatterns(List.of("/api/*", "/agui/*"));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
