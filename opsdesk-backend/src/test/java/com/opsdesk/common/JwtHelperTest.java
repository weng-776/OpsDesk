package com.opsdesk.common;

import com.opsdesk.config.properties.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtHelper 单元测试（规格基线 §23.1 / §23.2）
 *
 * <p>纯单元测试，不起 Spring 上下文。
 */
class JwtHelperTest {

    /** 40 字符，满足 HS256 的 32 字节下限 */
    private static final String SECRET = "opsdesk-test-secret-0123456789-abcdef-XYZ";

    private static JwtProperties props(String key, Long minutes) {
        JwtProperties p = new JwtProperties();
        p.setTokenSignKey(key);
        p.setTokenExpiration(minutes);
        return p;
    }

    private static JwtHelper helper() {
        return new JwtHelper(props(SECRET, 480L));
    }

    // ==================== 正常路径 ====================

    @Test
    void 签发与解析能正确往返() {
        JwtHelper jwt = helper();
        Instant before = Instant.now();

        String token = jwt.createToken(42L);
        assertThat(token).as("JWT 是三段式").contains(".").isNotBlank();

        JwtHelper.JwtPayload payload = jwt.parse(token);
        assertThat(payload).isNotNull();
        assertThat(payload.userId()).isEqualTo(42L);
        assertThat(payload.jti()).as("jti 必须存在（登出黑名单靠它）").isNotBlank();
        assertThat(payload.issuedAt()).isNotNull();

        // 有效期 = tokenExpiration 分钟（§23.1：8 小时）
        long minutes = Duration.between(before, payload.expiresAt()).toMinutes();
        assertThat(minutes).as("exp - iat ≈ 480 分钟").isBetween(478L, 481L);
        assertThat(jwt.getExpirationMinutes()).isEqualTo(480L);
    }

    @Test
    void 每次签发的jti都不同() {
        JwtHelper jwt = helper();
        String jti1 = jwt.getJti(jwt.createToken(1L));
        String jti2 = jwt.getJti(jwt.createToken(1L));
        assertThat(jti1).isNotEqualTo(jti2);
    }

    @Test
    void 剩余有效期用于登出黑名单TTL() {
        JwtHelper jwt = helper();
        JwtHelper.JwtPayload payload = jwt.parse(jwt.createToken(1L));

        long remaining = payload.remainingMillis();
        long expected = Duration.ofMinutes(480).toMillis();
        assertThat(remaining).as("接近 8 小时").isBetween(expected - 60_000, expected + 60_000);
    }

    // ==================== 无效 token ====================

    @Test
    void 被篡改的token解析失败() {
        JwtHelper jwt = helper();
        String token = jwt.createToken(1L);
        // 改掉 payload 段的一个字符 → 签名校验必然失败
        String tampered = token.substring(0, token.length() - 3) + "abc";

        assertThat(jwt.parse(tampered)).isNull();
        assertThat(jwt.isValid(tampered)).isFalse();
        assertThat(jwt.getUserId(tampered)).isNull();
    }

    @Test
    void 已过期的token解析失败() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder()
                .subject("1")
                .id("expired-jti")
                .issuedAt(Date.from(Instant.now().minusSeconds(7200)))
                .expiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThat(helper().parse(expired)).as("过期 → 无效").isNull();
    }

    @Test
    void 别的密钥签的token解析失败() {
        JwtHelper other = new JwtHelper(
                props("a-totally-different-secret-key-1234567890-X", 480L));
        String tokenFromOther = other.createToken(1L);

        assertThat(helper().parse(tokenFromOther)).as("签名对不上 → 无效").isNull();
    }

    @Test
    void 空串与乱串都安全返回null() {
        JwtHelper jwt = helper();
        assertThat(jwt.parse(null)).isNull();
        assertThat(jwt.parse("")).isNull();
        assertThat(jwt.parse("   ")).isNull();
        assertThat(jwt.parse("not-a-jwt")).isNull();
    }

    // ==================== 配置错误要早失败、说清楚 ====================

    @Test
    void 密钥未配置时构造即报错() {
        assertThatThrownBy(() -> new JwtHelper(props(null, 480L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void 密钥过短时构造即报错() {
        assertThatThrownBy(() -> new JwtHelper(props("too-short", 480L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("至少 32 字节");
    }

    @Test
    void 有效期非法时构造即报错() {
        assertThatThrownBy(() -> new JwtHelper(props(SECRET, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tokenExpiration");
        assertThatThrownBy(() -> new JwtHelper(props(SECRET, 0L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tokenExpiration");
    }
}
