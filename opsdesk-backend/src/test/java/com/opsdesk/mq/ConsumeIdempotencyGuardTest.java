package com.opsdesk.mq;

import com.opsdesk.mq.support.ConsumeIdempotencyGuard;
import com.opsdesk.mq.support.OutboxPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 消费幂等守卫验收测试（工单 D9-02，规格基线 §13.6 / §23.3 / §23.6）
 *
 * <p>本类直接测 {@link ConsumeIdempotencyGuard}，不起 MQ —— 消息用
 * {@link Message} 手工构造。好处是每条断言都<b>确定</b>（不依赖容器线程的时序），
 * 「谁在什么时候抛异常」「MDC 里剩什么」这些最容易写错的地方都能精确钉住。
 * 容器 + 重试 + 死信的链路另由 {@code RabbitListenerSkeletonTest} 覆盖。
 *
 * <h2>⚠️ 每个用例用自己的 consumerName</h2>
 * 幂等键是 {@code mq:consumed:{consumerName}:{messageId}}。用例之间共用 consumerName
 * 时，同一个 messageId 会互相挡（虽然是新 UUID，但语义上更干净）。
 * {@code @AfterEach} 按前缀清掉本类造的 key。
 */
@SpringBootTest
class ConsumeIdempotencyGuardTest {

    @Autowired
    private ConsumeIdempotencyGuard guard;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 本类用过的 consumerName，跑完清掉对应的 Redis key */
    private final List<String> usedConsumers = new ArrayList<>();

    @BeforeEach
    void clearMdc() {
        // 别让上一个用例的 MDC 残留影响断言
        MDC.clear();
    }

    @AfterEach
    void cleanUp() {
        MDC.clear();
        for (String consumer : usedConsumers) {
            // 前缀删除：本类用随机 messageId，按前缀清是安全的（consumerName 带用例随机后缀）
            redisTemplate.delete(redisTemplate.keys(ConsumeIdempotencyGuard.KEY_PREFIX + consumer + ":*"));
        }
        usedConsumers.clear();
    }

    // ==================== 验收 1：traceId 透传 ====================

    @Test
    @DisplayName("验收1：消息头的 traceId 被放进 MDC，业务里读到的与生产端一致")
    void traceId透传到MDC() {
        String consumer = consumerName("trace");
        String traceId = "trace-from-producer-abc123";
        AtomicReference<String> seen = new AtomicReference<>();

        guard.consume(consumer, message("mid-1", traceId), () -> seen.set(MDC.get(OutboxPublisher.HEADER_TRACE_ID)));

        assertThat(seen.get())
                .as("§23.6：业务日志里必须能看到生产端传来的同一个 traceId")
                .isEqualTo(traceId);
    }

    @Test
    @DisplayName("MDC 生命周期：消费结束后必须清空（否则污染同一容器线程的下一条消息）")
    void 消费结束后清理MDC() {
        String consumer = consumerName("mdc");

        guard.consume(consumer, message("mid-2", "trace-xyz"), () -> { });

        assertThat(MDC.get(OutboxPublisher.HEADER_TRACE_ID))
                .as("消费者是 MQ 链路的 MDC 负责人（HTTP 链路归 TraceIdFilter）").isNull();
        assertThat(MDC.getCopyOfContextMap()).as("整体也应是空的").isNullOrEmpty();
    }

    @Test
    @DisplayName("业务抛异常时也要清 MDC（清理必须在 finally 里）")
    void 业务异常也清理MDC() {
        String consumer = consumerName("mdc-ex");

        assertThatThrownBy(() -> guard.consume(consumer, message("mid-3", "trace-boom"), () -> {
            throw new IllegalStateException("模拟业务失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(OutboxPublisher.HEADER_TRACE_ID)).as("异常路径同样不能漏清").isNull();
    }

    @Test
    @DisplayName("消息头没有 traceId → 自己生成一个（日志仍可串联），且同样会清理")
    void 没有traceId时自生成() {
        String consumer = consumerName("trace-missing");
        AtomicReference<String> seen = new AtomicReference<>();

        guard.consume(consumer, message("mid-4", null), () -> seen.set(MDC.get(OutboxPublisher.HEADER_TRACE_ID)));

        assertThat(seen.get()).as("自生成的 traceId 应非空").isNotBlank();
        assertThat(MDC.get(OutboxPublisher.HEADER_TRACE_ID)).isNull();
    }

    // ==================== 验收 2：重复投递只执行一次 ====================

    @Test
    @DisplayName("验收2：同一 messageId 投递两次 → 业务只执行一次")
    void 重复投递只执行一次() {
        String consumer = consumerName("dup");
        AtomicInteger runs = new AtomicInteger();
        Message first = message("mid-dup", "trace-dup");

        guard.consume(consumer, first, runs::incrementAndGet);
        // 第二次用**同一条消息**（同 messageId）——模拟重投
        guard.consume(consumer, first, runs::incrementAndGet);

        assertThat(runs.get()).as("§13.6：占位失败 → 跳过业务").isEqualTo(1);
    }

    @Test
    @DisplayName("⚠️ 4 个消费者不能共用幂等前缀：A 队列消费过的 messageId 不应挡住 B 队列")
    void 不同消费者互不干扰() {
        String consumerA = consumerName("a");
        String consumerB = consumerName("b");
        AtomicInteger runs = new AtomicInteger();
        Message sameMessage = message("mid-shared", "trace-shared");

        guard.consume(consumerA, sameMessage, runs::incrementAndGet);
        guard.consume(consumerB, sameMessage, runs::incrementAndGet);

        assertThat(runs.get())
                .as("幂等键含 consumerName，两个消费者各执行一次（共 2 次）")
                .isEqualTo(2);
    }

    // ==================== 占位顺序：失败必须释放 ====================

    @Test
    @DisplayName("业务失败 → 释放占位 → 重试时能真正重跑（不被自己的占位挡掉）")
    void 业务失败释放占位以便重试() {
        String consumer = consumerName("release");
        AtomicInteger attempts = new AtomicInteger();

        // 第 1 次：业务失败
        assertThatThrownBy(() -> guard.consume(consumer, message("mid-retry", "trace-r"), () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("第一次失败");
        })).isInstanceOf(IllegalStateException.class);

        // 第 2 次（框架重试）：占位已释放 → 必须真的再执行一次
        guard.consume(consumer, message("mid-retry", "trace-r"), attempts::incrementAndGet);

        assertThat(attempts.get())
                .as("若占位没释放，这里会是 1（业务永远跑不成）；正确实现应是 2")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("业务成功 → 占位保留 → 后续重复投递被挡（释放绝不能放 finally）")
    void 业务成功保留占位() {
        String consumer = consumerName("keep");
        AtomicInteger runs = new AtomicInteger();

        guard.consume(consumer, message("mid-keep", "trace-k"), runs::incrementAndGet);
        guard.consume(consumer, message("mid-keep", "trace-k"), runs::incrementAndGet);

        assertThat(runs.get()).as("成功那次必须把占位留着，否则幂等失效").isEqualTo(1);
        assertThat(redisTemplate.hasKey(ConsumeIdempotencyGuard.KEY_PREFIX + consumer + ":mid-keep"))
                .as("占位键应存在且带 24h TTL").isTrue();
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("消息没有 messageId → fail-open：记 warn 但照常消费（丢事件比重复更糟）")
    void 没有messageId时failOpen() {
        String consumer = consumerName("no-mid");
        AtomicInteger runs = new AtomicInteger();

        guard.consume(consumer, message(null, "trace-n"), runs::incrementAndGet);
        guard.consume(consumer, message(null, "trace-n"), runs::incrementAndGet);

        assertThat(runs.get()).as("无法去重 → 两次都执行（有日志告警可查）").isEqualTo(2);
    }

    // ==================== 工具 ====================

    /** consumerName 带随机后缀：用例之间不会互相挡 */
    private String consumerName(String tag) {
        String consumer = "test-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8);
        usedConsumers.add(consumer);
        return consumer;
    }

    private Message message(String messageId, String traceId) {
        MessageProperties props = new MessageProperties();
        if (messageId != null) {
            props.setMessageId(messageId);
        }
        if (traceId != null) {
            props.setHeader(OutboxPublisher.HEADER_TRACE_ID, traceId);
        }
        return new Message("{}".getBytes(StandardCharsets.UTF_8), props);
    }
}
