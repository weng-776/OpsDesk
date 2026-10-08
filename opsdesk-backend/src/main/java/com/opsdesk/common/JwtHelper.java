package com.opsdesk.common;

import com.opsdesk.config.properties.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与解析（规格基线 §23.1 / §23.2）
 *
 * <p>约定：
 * <ul>
 *   <li>算法 <b>HS256</b>，密钥来自 {@code jwt.token.tokenSignKey}（环境变量 {@code JWT_SECRET}）</li>
 *   <li>载荷<b>只有</b> {@code sub}(userId) / {@code jti} / {@code iat} / {@code exp}，
 *       <b>不放权限列表</b> —— 权限每次请求读 Redis {@code auth:perms:{userId}}，
 *       这样管理员改了角色，用户不重新登录也立即生效</li>
 *   <li>有效期 {@code jwt.token.tokenExpiration} 分钟（V1 = 480 = 8 小时，不做 refresh token）</li>
 * </ul>
 *
 * <p>登出时把 {@code jti} 写进 Redis 黑名单 {@code auth:token:{jti}}，
 * TTL 用 {@link JwtPayload#remainingMillis()}（即 token 剩余有效期）。
 */
@Slf4j
@Component
public class JwtHelper {

    /** HS256 要求密钥至少 32 字节 */
    private static final int MIN_KEY_BYTES = 32;

    private final SecretKey secretKey;

    /** 有效期，单位分钟（§23.1） */
    private final long expirationMinutes;

    public JwtHelper(JwtProperties jwtProperties) {
        String signKey = jwtProperties.getTokenSignKey();
        if (!StringUtils.hasText(signKey)) {
            throw new IllegalStateException(
                    "jwt.token.tokenSignKey 未配置 —— 请设置环境变量 JWT_SECRET");
        }
        byte[] keyBytes = signKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_KEY_BYTES) {
            // 早失败、说清楚：否则 jjwt 抛的 WeakKeyException 很难定位
            throw new IllegalStateException(String.format(
                    "jwt.token.tokenSignKey 太短：HS256 要求至少 %d 字节，当前 %d 字节",
                    MIN_KEY_BYTES, keyBytes.length));
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);

        Long minutes = jwtProperties.getTokenExpiration();
        if (minutes == null || minutes <= 0) {
            throw new IllegalStateException("jwt.token.tokenExpiration 必须是正整数（单位：分钟）");
        }
        this.expirationMinutes = minutes;
    }

    /** 配置的有效期（分钟） */
    public long getExpirationMinutes() {
        return expirationMinutes;
    }

    // ==================== 签发 ====================

    /** 签发 token，自动生成新的 jti */
    public String createToken(Long userId) {
        return createToken(userId, UUID.randomUUID().toString());
    }

    /** 签发 token 并指定 jti（一般不用，留作测试与重放场景） */
    public String createToken(Long userId, String jti) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expirationMinutes, ChronoUnit.MINUTES)))
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    // ==================== 解析 ====================

    /**
     * 解析并校验 token（签名 + 有效期）。
     *
     * <p><b>任何失败都返回 {@code null}</b>（格式错 / 签名不对 / 已过期），
     * 由调用方决定怎么处理 —— 鉴权拦截器统一转成 {@code 40100}。
     * 这里不抛异常，是为了避免把 jjwt 的异常类型泄漏到业务层。
     */
    public JwtPayload parse(String token) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            //获取用户id
            Long userId = parseUserId(claims.getSubject());
            if (userId == null) {
                log.debug("[JWT] sub 缺失或不是数字：{}", claims.getSubject());
                return null;
            }
            return new JwtPayload(userId, claims.getId(),
                    toInstant(claims.getIssuedAt()), toInstant(claims.getExpiration()));
        }
        catch (JwtException | IllegalArgumentException ex) {
            // 签名错 / 已过期 / 格式错，统一当无效
            log.debug("[JWT] token 无效：{}", ex.getMessage());
            return null;
        }
    }

    /** token 是否有效（签名正确且未过期） */
    public boolean isValid(String token) {
        return parse(token) != null;
    }

    /** 取 userId；无效返回 null */
    public Long getUserId(String token) {
        JwtPayload payload = parse(token);
        return payload == null ? null : payload.userId();
    }

    /** 取 jti；无效返回 null */
    public String getJti(String token) {
        JwtPayload payload = parse(token);
        return payload == null ? null : payload.jti();
    }

    private static Long parseUserId(String subject) {
        if (!StringUtils.hasText(subject)) {
            return null;
        }
        try {
            return Long.valueOf(subject.trim());
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }

    /**
     * token 载荷（§23.2：只有 sub / jti / iat / exp，不放权限）。
     *
     * @param userId    来自 {@code sub}
     * @param jti       来自 {@code jti}，登出黑名单的 key
     * @param issuedAt  来自 {@code iat}
     * @param expiresAt 来自 {@code exp}
     */
    public record JwtPayload(Long userId, String jti, Instant issuedAt, Instant expiresAt) {

        /**
         * 剩余有效期（毫秒），已过期返回 0。
         * 登出时用它给 Redis 黑名单 {@code auth:token:{jti}} 设 TTL。
         */
        public long remainingMillis() {
            if (expiresAt == null) {
                return 0L;
            }
            return Math.max(Duration.between(Instant.now(), expiresAt).toMillis(), 0L);
        }
    }
}
