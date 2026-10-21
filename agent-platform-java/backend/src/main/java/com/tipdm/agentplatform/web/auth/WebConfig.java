package com.tipdm.agentplatform.web.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.config.SmyyCaseContentProperties;
import com.tipdm.agentplatform.service.JwtService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;
    private final SmyyCaseContentProperties caseContentProperties;

    public WebConfig(JwtInterceptor jwtInterceptor, SmyyCaseContentProperties caseContentProperties) {
        this.jwtInterceptor = jwtInterceptor;
        this.caseContentProperties = caseContentProperties;
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
     * 案例正文静态映射：库表只存相对路径，文件落在项目 {@code public/cases}（内容根）。
     * 暴露在 {@code /case-files/**} 而不是 {@code /api/**}，与 JWT 强制登录的路径隔开，
     * 便于直接以 URL 引用（和前端同源时由 Vite 代理转发）。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = caseContentProperties.root().toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/case-files/**")
            .addResourceLocations(location)
            .setCachePeriod(3600);
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
