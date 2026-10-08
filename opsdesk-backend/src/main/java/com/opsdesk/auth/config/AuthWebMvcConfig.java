package com.opsdesk.auth.config;

import com.opsdesk.auth.interceptor.AuthInterceptor;
import com.opsdesk.auth.interceptor.PermissionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 认证 / 授权拦截器注册（D1-02 建立认证，D2-02 追加授权）
 *
 * <p>用 {@link WebMvcConfigurer} 而不是 {@code @EnableWebMvc} —— 后者会关掉
 * Spring Boot 的 WebMvc 自动配置（消息转换器、静态资源、错误处理都得自己配）。
 *
 * <p><b>两个拦截器的顺序是本文件最关键的一行</b>：
 * <pre>
 * order 0  AuthInterceptor      认证：token 有效吗？→ 失败 40100
 * order 1  PermissionInterceptor 授权：有权限码吗？→ 失败 40300
 * </pre>
 * 顺序反了就会「先判权限、再判登录」，未登录用户会收到 40300 而不是 40100 ——
 * 与 API 文档 §2.4 的错误码语义对不上。D1-02 写这个类时已预留该位置。
 */
@Configuration
public class AuthWebMvcConfig implements WebMvcConfigurer {

    /**
     * 免登录白名单（工单 D1-02 指定）。两个拦截器<b>共用同一份</b>白名单 ——
     * 白名单接口既不需要登录，也就不需要权限码。
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
    private final PermissionInterceptor permissionInterceptor;

    public AuthWebMvcConfig(AuthInterceptor authInterceptor,
                            PermissionInterceptor permissionInterceptor) {
        this.authInterceptor = authInterceptor;
        this.permissionInterceptor = permissionInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // ① 认证：未登录 / token 无效 / 已登出 → 40100
        registry.addInterceptor(authInterceptor)
                .addPathPatterns(PROTECTED_PATTERN)
                .excludePathPatterns(WHITELIST)
                .order(0);

        // ② 授权：已登录但缺权限码 → 40300。必须排在认证之后
        registry.addInterceptor(permissionInterceptor)
                .addPathPatterns(PROTECTED_PATTERN)
                .excludePathPatterns(WHITELIST)
                .order(1);
    }
}
