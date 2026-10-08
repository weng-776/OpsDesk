package com.opsdesk.auth.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 从 {@code Authorization} 头解析 Bearer token（API 文档 §2.2）
 *
 * <pre>{@code Authorization: Bearer <token>}</pre>
 *
 * <p><b>为什么单独一个类</b>：解析规则只允许有一处实现 ——
 * 鉴权拦截器（{@link AuthInterceptor}）与登出（{@code AuthSessionServiceImpl}）
 * 都要从同一个头里取 token。两处各写一遍「去前缀 + trim」迟早会不一致。
 *
 * <p>按 RFC 6750，scheme 名大小写不敏感，所以 {@code bearer x} 也接受。
 * 解析失败统一返回 {@code null}，由调用方决定怎么报错（拦截器转 40100）。
 */
@Component
public class BearerTokenResolver {

    /** scheme 前缀，注意尾部有一个空格 */
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * 从请求头取 token。
     *
     * @return token 原文；缺失 / 不是 Bearer / 只有空白 时返回 {@code null}
     */
    public String resolve(HttpServletRequest request) {
        return request == null ? null : extract(request.getHeader(HttpHeaders.AUTHORIZATION));
    }

    /**
     * 从 {@code Authorization} 头的值里取 token。
     *
     * @return token 原文；缺失 / 不是 Bearer / 只有空白 时返回 {@code null}
     */
    public String extract(String authorizationHeader) {
        if (!StringUtils.hasText(authorizationHeader)) {
            return null;
        }
        String header = authorizationHeader.trim();
        if (!header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return StringUtils.hasText(token) ? token : null;
    }
}
