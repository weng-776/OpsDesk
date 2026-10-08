package com.opsdesk.ticket.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsdesk.ticket.vo.TicketCreatedVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 创建工单的幂等守卫（规格基线 §23.3 / API 文档 §2.5）
 *
 * <pre>
 * 请求头 Idempotency-Key: &lt;uuid&gt;
 * Redis SETNX idem:ticket:{key}  TTL 24h
 * </pre>
 *
 * <h2>为什么是「先占位、后写结果」而不是直接 SETNX 结果</h2>
 * 如果先创建、再 SETNX，两个并发的重复请求会<b>都创建成功</b>（各自 SETNX 各自成功），
 * 幂等就白做了。所以必须先用 {@code SETNX} 抢一个「PENDING」占位：
 * 抢到的那个才去创建，抢不到的直接读结果或被告知「处理中」。
 *
 * <h2>占位必须能释放</h2>
 * 首次请求失败时如果不删占位，这个 key 会在 24h 内一直挡着后续重试 ——
 * 用户会看到「重试也一直是处理中」，比没有幂等还糟。
 * 所以调用方要在**事务提交后**写结果、**事务回滚后**释放占位
 * （见 {@code TicketCreateService} 里注册的 {@code TransactionSynchronization}）。
 *
 * <h2>只针对「创建工单」</h2>
 * key 前缀 {@code idem:ticket:} 是工单专属的（§23.3 的幂等键表就这么定的），
 * 所以本类直接返回 {@link TicketCreatedVO}，不做泛型化 —— 没有第二个调用方，泛型只是噪音。
 */
@Slf4j
@Component
public class IdempotencyGuard {

    /** 幂等键前缀（§23.3：{@code idem:ticket:{key}}） */
    public static final String KEY_PREFIX = "idem:ticket:";

    /** 幂等键请求头名（API 文档 §2.5） */
    public static final String HEADER_NAME = "Idempotency-Key";

    /** 幂等记录 TTL（§23.3：24 小时） */
    public static final Duration TTL = Duration.ofHours(24);

    /** 「首次请求还在处理中」的占位值 —— 与「已完成的 JSON 结果」区分开 */
    private static final String IN_PROGRESS_MARKER = "PENDING";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyGuard(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 抢幂等键。
     *
     * @return {@link Reservation.Status#FIRST} 表示抢到了、调用方继续创建；
     *         {@link Reservation.Status#CACHED} 表示重复请求、直接返回首次结果；
     *         {@link Reservation.Status#IN_PROGRESS} 表示首次还在处理中
     */
    public Reservation reserve(String idempotencyKey) {
        String key = redisKey(idempotencyKey);
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(key, IN_PROGRESS_MARKER, TTL);
        if (Boolean.TRUE.equals(acquired)) {
            return Reservation.first();
        }

        String existing = redisTemplate.opsForValue().get(key);
        if (!StringUtils.hasText(existing)) {
            // 极窄的竞态：抢失败但读不到值（刚好过期）→ 当首次处理，让调用方继续
            log.warn("[幂等] 占位存在但读不到值，按首次处理。key={}", key);
            return Reservation.first();
        }
        if (IN_PROGRESS_MARKER.equals(existing)) {
            return Reservation.inProgress();
        }
        try {
            return Reservation.cached(objectMapper.readValue(existing, TicketCreatedVO.class));
        }
        catch (Exception ex) {
            // 结果缓存坏了：宁可当「处理中」也不要重复创建一张工单
            log.warn("[幂等] 缓存的结果解析失败，按处理中返回。key={} value={}", key, existing, ex);
            return Reservation.inProgress();
        }
    }

    /**
     * 事务<b>提交后</b>写回首次结果。
     *
     * <p>只记 WARN 不抛异常：此刻事务已经提交，抛出去会让调用方以为创建失败。
     * 最坏情况是这条幂等记录没写成功 → 后续重复请求拿到 40900（而不是缓存结果），
     * 但绝不会多建工单。
     */
    public void complete(String idempotencyKey, TicketCreatedVO result) {
        String key = redisKey(idempotencyKey);
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(result), TTL);
        }
        catch (Exception ex) {
            log.warn("[幂等] 结果写回失败（不影响已提交的工单）。key={}", key, ex);
        }
    }

    /** 事务<b>回滚后</b>释放占位，让用户能重新提交 */
    public void release(String idempotencyKey) {
        String key = redisKey(idempotencyKey);
        try {
            redisTemplate.delete(key);
            log.debug("[幂等] 已释放占位 key={}", key);
        }
        catch (Exception ex) {
            log.warn("[幂等] 释放占位失败（该 key 会自然过期）。key={}", key, ex);
        }
    }

    private String redisKey(String idempotencyKey) {
        return KEY_PREFIX + idempotencyKey;
    }

    /**
     * 占位结果。
     *
     * @param status {@code FIRST} / {@code CACHED} / {@code IN_PROGRESS}
     * @param cached 仅 {@code CACHED} 时非空：首次请求的响应体
     */
    public record Reservation(Status status, TicketCreatedVO cached) {

        public enum Status {
            /** 抢到了幂等键，调用方继续创建 */
            FIRST,
            /** 重复请求，且首次已完成 —— 直接返回 cached */
            CACHED,
            /** 重复请求，但首次还在处理中 */
            IN_PROGRESS
        }

        static Reservation first() {
            return new Reservation(Status.FIRST, null);
        }

        static Reservation inProgress() {
            return new Reservation(Status.IN_PROGRESS, null);
        }

        static Reservation cached(TicketCreatedVO result) {
            return new Reservation(Status.CACHED, result);
        }
    }
}
