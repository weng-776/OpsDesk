package com.opsdesk.auth.config;

import com.opsdesk.auth.interceptor.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 鉴权拦截器注册（工单 D1-02）
 *
 * <p>用 {@link WebMvcConfigurer} 而不是 {@code @EnableWebMvc} —— 后者会关掉
 * Spring Boot 的 WebMvc 自动配置（消息转换器、静态资源、错误处理都得自己配）。
 */
@Configuration
public class AuthWebMvcConfig implements WebMvcConfigurer {

    /**
     * 免登录白名单（工单 D1-02 指定）。
     *
     * <p>注意 {@code /actuator/health} 本来就不在 {@link #PROTECTED_PATTERN} 的匹配范围内
     * （actuator 的 base path 是 {@code /actuator}），写在这里只是<b>自文档化</b>，
     * 让「哪些接口免登录」一眼可见，是一条空操作。
     */
    public static final String[] WHITELIST = {"/api/auth/login", "/actuator/health"};

    /**
     * 受保护范围。
     *
     * <p>取 {@code /api/**} 而<b>不是</b> {@code /**}：后者会把 {@code /error}、
     * 静态资源一起拦，还会在 ERROR dispatch 上再跑一遍拦截器。
     */
    private static final String PROTECTED_PATTERN = "/api/**";

    private final AuthInterceptor authInterceptor;

    public AuthWebMvcConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns(PROTECTED_PATTERN)
                .excludePathPatterns(WHITELIST)
                // 本单只有这一个拦截器；D2-02 的权限校验拦截器排它后面
                .order(0);
    }
}
