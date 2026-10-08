package com.opsdesk.ticket.support;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 工单号生成器（规格基线 §5.3 / §23.4）
 *
 * <pre>
 * 工单号 = OD + yyyyMMdd + 5 位日序列      例：OD2026100600001
 * 日序列 = Redis INCR seq:ticket:{yyyyMMdd}，EXPIRE 2 天
 * </pre>
 *
 * <h2>为什么用 Redis INCR 而不是「查最大号 + 1」</h2>
 * 查最大值再 +1 在并发下必然重号（两个请求同时查到 5，都写 6）。
 * {@code INCR} 是原子的，天然并发安全（§5.3「并发安全」）。
 *
 * <h2>⚠️ Redis 丢数据时会重号 —— 由调用方重试兜住</h2>
 * 如果 {@code seq:ticket:{date}} 被 flush / 丢失，序列会从 1 重新开始，
 * 生成一个<b>已存在</b>的工单号 → 撞 {@code uk_ticket_no} 唯一索引。
 * 所以取号与插入要配合重试（见 {@code TicketCreateService}），
 * 这也是 §5.3「降级为 DB 唯一索引冲突重试 3 次」的实际落地形态。
 */
@Slf4j
@Component
public class TicketNoGenerator {

    /** 日序列 Redis key 前缀（§23.4：{@code seq:ticket:{yyyyMMdd}}） */
    public static final String SEQ_KEY_PREFIX = "seq:ticket:";

    /** 日序列 key 的 TTL（§23.4：2 天） */
    public static final Duration SEQ_TTL = Duration.ofDays(2);

    /** 工单号前缀（§5.3） */
    private static final String TICKET_NO_PREFIX = "OD";

    /** 日期段格式：yyyyMMdd */
    private static final DateTimeFormatter DATE_PART_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 日序列补零宽度（§5.3：5 位） */
    private static final int SEQ_WIDTH = 5;

    private final StringRedisTemplate redisTemplate;

    public TicketNoGenerator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 生成「今天」的工单号 */
    public String next() {
        return nextFor(LocalDate.now());
    }

    /**
     * 生成指定日期的工单号。
     *
     * <p><b>包内可见</b>：只给测试用 —— 这样验「同一天递增、跨天归零」时不必往生产代码里
     * 塞一个 {@code Clock} Bean，测试直接传两个不同的日期即可。
     *
     * @throws BizException Redis 不可用（50300）—— 取不到序列就不能编造工单号
     */
    String nextFor(LocalDate date) {
        String datePart = date.format(DATE_PART_FORMAT);
        String key = SEQ_KEY_PREFIX + datePart;

        Long seq = redisTemplate.opsForValue().increment(key);
        if (seq == null) {
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "工单号序列生成失败");
        }
        if (seq == 1L) {
            // ⚠️ 只在 seq == 1 时设 TTL：每次 INCR 都 EXPIRE 的话，这个 key 永远不释放，
            // 与 §23.4 的「TTL 2 天」不符
            redisTemplate.expire(key, SEQ_TTL);
        }
        return TICKET_NO_PREFIX + datePart + String.format("%0" + SEQ_WIDTH + "d", seq);
    }
}
