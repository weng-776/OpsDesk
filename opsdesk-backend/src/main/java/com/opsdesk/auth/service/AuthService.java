package com.opsdesk.auth.service;

import com.opsdesk.auth.dto.LoginRequest;
import com.opsdesk.auth.vo.LoginVO;

/**
 * 认证 Service（规格基线 §21.1）
 *
 * <p>D1-01 只含登录；{@code /me} 与 {@code /logout} 归 D1-02。
 */
public interface AuthService {

    /**
     * 登录：BCrypt 校验密码 → 签发 JWT → 返回 token + 当前用户信息。
     *
     * <p>失败语义（API 文档 §4.1 错误表）：
     * <ul>
     *   <li>用户名或密码错误 → {@code 40100}</li>
     *   <li>账号已禁用（{@code status = 0}）→ {@code 40100}</li>
     *   <li>账号处于失败锁定中 → {@code 42900}</li>
     * </ul>
     *
     * @param request 登录请求（已通过 Bean Validation）
     * @return token 与用户信息
     */
    LoginVO login(LoginRequest request);
}
