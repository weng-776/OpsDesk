package com.opsdesk.ticket.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单号生成器验收测试（工单 D3-01）
 *
 * <p>规格依据：规格基线 §5.3（{@code OD + yyyyMMdd + 5 位日序列}）、§23.4（{@code seq:ticket:{date}} TTL 2 天）。
 *
 * <p>用**未来的固定日期**（2099-01-01 / 2099-01-02）当测试日期：这样 key 一定不存在，
 * 「首号是 00001」和「跨天归零」都能精确断言，也不会跟真实数据抢号。
 * 跑完清掉这两个 key。
 */
@SpringBootTest
class TicketNoGeneratorTest {

    private static final LocalDate DAY_ONE = LocalDate.of(2099, 1, 1);
    private static final LocalDate DAY_TWO = LocalDate.of(2099, 1, 2);

    @Autowired
    private TicketNoGenerator ticketNoGenerator;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @AfterEach
    void tearDown() {
        List.of(DAY_ONE, DAY_TWO).forEach(date ->
                redisTemplate.delete(TicketNoGenerator.SEQ_KEY_PREFIX + date.format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))));
    }

    @Test
    @DisplayName("首号是 OD{date}00001，同一天连续生成递增")
    void 同一天序列递增() {
        assertThat(ticketNoGenerator.nextFor(DAY_ONE)).isEqualTo("OD2099010100001");
        assertThat(ticketNoGenerator.nextFor(DAY_ONE)).isEqualTo("OD2099010100002");
        assertThat(ticketNoGenerator.nextFor(DAY_ONE)).isEqualTo("OD2099010100003");
    }

    @Test
    @DisplayName("跨天归零：换一天从 00001 重新开始（日期是 key 的一部分）")
    void 跨天归零() {
        assertThat(ticketNoGenerator.nextFor(DAY_ONE)).isEqualTo("OD2099010100001");
        assertThat(ticketNoGenerator.nextFor(DAY_ONE)).isEqualTo("OD2099010100002");

        assertThat(ticketNoGenerator.nextFor(DAY_TWO)).as("新的一天 → 新 key → 从 1 开始")
                .isEqualTo("OD2099010200001");
        assertThat(ticketNoGenerator.nextFor(DAY_ONE)).as("回到第一天，序列继续接上")
                .isEqualTo("OD2099010100003");
    }

    @Test
    @DisplayName("序列号补零到 5 位；key 是 seq:ticket:{yyyyMMdd} 且 TTL 为 2 天")
    void 补零与key规范() {
        ticketNoGenerator.nextFor(DAY_ONE);

        String key = TicketNoGenerator.SEQ_KEY_PREFIX + "20990101";
        assertThat(redisTemplate.hasKey(key)).isTrue();
        Long ttlSeconds = redisTemplate.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(ttlSeconds).isNotNull();
        assertThat((long) ttlSeconds)
                .as("§23.4 规定 TTL 2 天").isBetween(
                        Duration.ofDays(2).toSeconds() - 60, Duration.ofDays(2).toSeconds());
    }

    @Test
    @DisplayName("⚠️ TTL 只在首次（seq==1）设置：连续取号不会把 key 的过期时间一直往后推")
    void 连续取号不刷新TTL() {
        ticketNoGenerator.nextFor(DAY_ONE);
        String key = TicketNoGenerator.SEQ_KEY_PREFIX + "20990101";

        // 人为把 TTL 改小，再连续取号 —— 如果实现里每次都 EXPIRE，TTL 会被刷回 2 天
        redisTemplate.expire(key, Duration.ofSeconds(30));
        ticketNoGenerator.nextFor(DAY_ONE);
        ticketNoGenerator.nextFor(DAY_ONE);

        Long ttlSeconds = redisTemplate.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(ttlSeconds).as("仍是 30 秒左右，说明没有每次刷新 TTL").isLessThanOrEqualTo(30L);
    }
}
