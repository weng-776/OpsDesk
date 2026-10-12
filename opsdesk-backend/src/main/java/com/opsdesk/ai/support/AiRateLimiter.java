package com.opsdesk.ai.support;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * AI 接口限流（工单 D11-03，规格基线 §11.3「成本失控」/ §23.4，API 文档 §11.2）
 *
 * <pre>
 * 每用户 20 次 / 分钟，Redis key = rate:ai:{userId}
 * 超过 → 42900（ErrorCode.TOO_MANY_REQUESTS）
 * </pre>
 *
 * <h2>🔴 为什么是 Lua 脚本，而不是 {@code INCR} 完再 {@code EXPIRE}</h2>
 * 分两步写会留下一个致命窗口：`INCR` 成功、`EXPIRE` 之前进程被 kill（或网络断），
 * 计数就<b>永远不过期</b> → 该用户被<b>永久锁死</b>在「已超限」状态。
 * 放进一个脚本里由 Redis 原子执行，这个窗口就不存在了。
 *
 * <p>还有更阴的一种情况：key 因为别的原因<b>存在但没有 TTL</b>（例如上一轮 EXPIRE 没执行成功）。
 * 这时计数早已 &gt; 1，只判 {@code current == 1} 的分支<b>永远不会再进</b> —— TTL 再也补不上。
 * 所以脚本里加了 <b>{@code TTL &lt; 0} 兜底补设</b>（-1 表示「存在但无过期时间」）。
 * 这正是验收 6「限流键的 TTL 正常，不会永久锁死」的保障。
 *
 * <h2>⚠️ Redis 不可用时：放行（fail-open），不拒绝</h2>
 * 限流的定位是<b>防滥用</b>，不是权限闸门 ——
 * Redis 一抖就让「AI 助手整体不可用」得不偿失；而且即使限流完全失效也<b>不会越权</b>，
 * 真正的越权边界在业务服务的数据范围校验（代码层），最多是被多调用几次。
 */
@Slf4j
@Component
public class AiRateLimiter {

    /** 窗口内允许的最大次数（API §11.2：20 次/分钟） */
    public static final int MAX_REQUESTS_PER_WINDOW = 20;

    /** 窗口长度（§23.4：TTL 1 分钟） */
    static final Duration WINDOW = Duration.ofMinutes(1);

    /** key 前缀（§23.4 固定为 {@code rate:ai:{userId}}）—— 必须带 userId，绝不能全局一个键 */
    private static final String KEY_PREFIX = "rate:ai:";

    /**
     * 原子计数 + 兜底补 TTL。
     *
     * <pre>
     * local current = redis.call('INCR', KEYS[1])
     * if current == 1 or redis.call('TTL', KEYS[1]) &lt; 0 then
     *     redis.call('EXPIRE', KEYS[1], ARGV[1])
     * end
     * return current
     * </pre>
     */
    private static final DefaultRedisScript<Long> INCR_WITH_TTL_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 or redis.call('TTL', KEYS[1]) < 0 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public AiRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 记一次调用并判定是否超限；超限抛 {@code 42900}。
     *
     * <p>⚠️ 调用点必须在<b>真正干活之前</b>（在本项目里＝进 service、调 LLM 之前），
     * 否则限流只是「事后记账」，token 该烧还是烧了。
     *
     * @param userId 当前用户；为 {@code null} 说明调用方没在请求线程上取到身份 → 直接拒绝
     * @throws BizException 42900 —— 超过 20 次/分钟
     */
    public void check(Long userId) {
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "限流需要登录用户身份");
        }

        String key = KEY_PREFIX + userId;
        long current;
        try {
            Long count = redisTemplate.execute(INCR_WITH_TTL_SCRIPT, List.of(key),
                    String.valueOf(WINDOW.toSeconds()));
            current = count == null ? 0L : count;
        }
        catch (Exception ex) {
            // fail-open：见类注释。Redis 故障不该让 AI 助手整体不可用 ——
            // 而且此刻「不做限流」也不会导致越权（数据范围校验照常生效）
            log.warn("[AI 限流] Redis 异常，本次放行（fail-open）。userId={} key={}", userId, key, ex);
            return;
        }

        if (current > MAX_REQUESTS_PER_WINDOW) {
            log.warn("[AI 限流] 超过 {} 次/分钟，拒绝。userId={} count={}",
                    MAX_REQUESTS_PER_WINDOW, userId, current);
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS,
                    "AI 助手调用过于频繁（每分钟最多 " + MAX_REQUESTS_PER_WINDOW + " 次），请稍后再试");
        }
        log.debug("[AI 限流] userId={} 本窗口第 {}/{} 次", userId, current, MAX_REQUESTS_PER_WINDOW);
    }
}
