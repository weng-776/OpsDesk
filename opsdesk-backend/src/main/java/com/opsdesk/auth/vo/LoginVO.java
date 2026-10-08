package com.opsdesk.auth.vo;

import lombok.Data;

/**
 * 登录响应（API 文档 §4.1）
 *
 * <pre>
 * { "token": "eyJhbGciOiJIUzI1NiJ9...", "user": { ...LoginUserVO } }
 * </pre>
 */
@Data
public class LoginVO {

    /** 三段式 JWT（HS256，exp = 8 小时，§23.1） */
    private String token;

    /** 当前登录用户信息 */
    private LoginUserVO user;

    public LoginVO() {
    }

    public LoginVO(String token, LoginUserVO user) {
        this.token = token;
        this.user = user;
    }
}
