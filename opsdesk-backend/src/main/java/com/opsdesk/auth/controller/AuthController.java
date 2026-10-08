package com.opsdesk.auth.controller;

import com.opsdesk.auth.dto.LoginRequest;
import com.opsdesk.auth.service.AuthService;
import com.opsdesk.auth.service.AuthSessionService;
import com.opsdesk.auth.vo.LoginUserVO;
import com.opsdesk.auth.vo.LoginVO;
import com.opsdesk.common.Result;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（API 文档 §4）
 *
 * <ul>
 *   <li>{@code POST /api/auth/login} —— D1-01，免登录（在 {@code AuthWebMvcConfig} 白名单里）</li>
 *   <li>{@code GET /api/auth/me}、{@code POST /api/auth/logout} —— D1-02，需登录</li>
 * </ul>
 *
 * <p>Controller 保持「薄」：只做参数绑定与结果包装，业务与异常都在 Service 层
 * —— 失败一律由 {@code BizException} + {@code GlobalExceptionHandler} 转成
 * {@code Result} 与对齐的 HTTP 状态码（§20.2），这里不写 try/catch。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthSessionService authSessionService;

    public AuthController(AuthService authService, AuthSessionService authSessionService) {
        this.authService = authService;
        this.authSessionService = authSessionService;
    }

    /**
     * 登录（API 文档 §4.1）。
     *
     * <p>鉴权：无需登录。请求体字段非法 → 40001；账号密码错 / 账号禁用 → 40100；
     * 失败次数超限处于锁定中 → 42900。
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    /**
     * 当前用户（API 文档 §4.2）。
     *
     * <p>鉴权：需登录 —— 未带 token / token 无效 / 已登出都会被
     * {@code AuthInterceptor} 拦成 40100，走不到这里。
     */
    @GetMapping("/me")
    public Result<LoginUserVO> me() {
        return Result.ok(authSessionService.currentUser());
    }

    /**
     * 登出（API 文档 §4.3）。
     *
     * <p>鉴权：需登录。把当前 {@code jti} 写进 Redis 黑名单，TTL = token 剩余有效期。
     * 重复登出会先被拦截器按黑名单拒掉（40100），见
     * {@code AuthSessionService#logout} 的说明。
     *
     * @param authorization 原始 {@code Authorization} 头。声明 {@code required = false}
     *                      只是防御性写法 —— 拦截器已保证它合法。
     */
    @PostMapping("/logout")
    public Result<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        authSessionService.logout(authorization);
        return Result.ok();
    }
}
