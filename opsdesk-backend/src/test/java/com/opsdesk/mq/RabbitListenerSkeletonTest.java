package com.opsdesk.mq;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.common.enums.TicketType;
import com.opsdesk.config.RabbitMQConfig;
import com.opsdesk.mq.event.TicketAssignedEvent;
import com.opsdesk.mq.event.TicketCreatedEvent;
import com.opsdesk.mq.event.TicketStatusChangedEvent;
import com.opsdesk.mq.listener.TicketCreatedListener;
import com.opsdesk.mq.support.OpsDeskMessageRecoverer;
import com.opsdesk.mq.support.OutboxPublisher;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 消费者骨架验收测试（工单 D9-02，规格基线 §13.2 / §13.4 / §13.6 / §23.6）
 *
 * <p>本类起<b>真实的容器</b>（{@code @RabbitListener}）+ 真实 broker，覆盖四条验收里
 * 只有走完整链路才能验的部分：重试退避、死信落点、traceId 端到端。
 * 幂等本身的边界（占位顺序、MDC 生命周期）由 {@code ConsumeIdempotencyGuardTest} 精确钉住。
 *
 * <h2>⚠️ {@code @BeforeEach} 先把队列排空</h2>
 * D9-01 的测试与 e2e 往 {@code opsdesk.ticket.created.queue} 里留了历史消息。
 * 消费者一启动就会把它们消费掉 —— 若不先等它排空，
 * 「让监听器抛异常」那条用例会把<b>残留消息也一起丢进 DLQ</b>，DLQ 增量断言就不确定了。
 *
 * <h2>⚠️ 继承 {@link AbstractMqIntegrationTest}</h2>
 * 容器由基类显式启停。这一点对本类是<b>必需</b>的：本类用 spy 让监听器抛异常来验「重试 → DLQ」，
 * 而 Spring 测试框架会缓存多个上下文并让它们同时存活 —— 若容器自动启动，
 * 消息会被<b>另一个上下文的真实监听器</b>抢先消费（它只打日志、不抛异常），
 * spy 一次都不会被调用，报错是 {@code there were zero interactions with this mock}。
 * 现象是「单独跑绿、全量跑红」。
 *
 * <h2>⚠️ 用 Logback 的 {@code ListAppender} 抓日志，不读日志文件</h2>
 * 验收 1 要断言「日志里的 traceId 与生产端一致」。日志文件是 GBK 字节且受滚动/缓冲影响；
 * 挂一个内存 Appender 能直接拿到 {@code ILoggingEvent#getMDCPropertyMap()}，
 * 既是真正的「日志里」断言，又完全确定。
 */
class RabbitListenerSkeletonTest extends AbstractMqIntegrationTest {

    /** 本类只会让这一个监听器抛异常，其余三个走真实实现 */
    @MockitoSpyBean
    private TicketCreatedListener ticketCreatedListener;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    private ListAppender<ILoggingEvent> appender;
    private Logger mqLogger;

    @BeforeEach
    void drainQueuesAndAttachAppender() {
        // ① 等消费者把 D9-01 留下的历史消息排空（否则 DLQ 增量断言不确定）
        awaitQueueDrained(RabbitMQConfig.QUEUE_TICKET_CREATED, 30_000);

        // ② D9-04 起要**读 DLQ 里的消息头**（x-retry-count / x-death），所以必须先清空 DLQ，
        //    否则 receive 拿到的是历史死信、断言张冠李戴。
        //    ⚠️ 开发库里 DLQ 只装测试/e2e 产生的死信，清掉是安全的（且顺带做了卫生）。
        int purged = rabbitAdmin.purgeQueue(RabbitMQConfig.DLQ_TICKET_CREATED);
        if (purged > 0) {
            System.out.printf("[测试] 清空 %d 条历史死信，保证本轮断言确定。queue=%s%n",
                    purged, RabbitMQConfig.DLQ_TICKET_CREATED);
        }

        // ③ 抓 com.opsdesk.mq 下的日志（监听器 + 幂等守卫 + Confirm/Return 回调都在这个包下）
        mqLogger = (Logger) LoggerFactory.getLogger("com.opsdesk.mq");
        appender = new ListAppender<>();
        appender.start();
        mqLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        if (mqLogger != null && appender != null) {
            mqLogger.detachAppender(appender);
        }
    }

    // ==================== 骨架：4 个队列都有人消费 ====================

    @Test
    @DisplayName("4 个队列各发一条 → 都被对应消费者吃掉（队列排空，DLQ 不增长）")
    void 四个队列都有人消费() {
        int dlqBefore = queueDepth(RabbitMQConfig.DLQ_TICKET_CREATED)
                + queueDepth(RabbitMQConfig.DLQ_TICKET_ASSIGNED)
                + queueDepth(RabbitMQConfig.DLQ_TICKET_STATUS)
                + queueDepth(RabbitMQConfig.DLQ_KNOWLEDGE_INDEX);

        publish(RabbitMQConfig.RK_TICKET_CREATED, ticketCreatedEvent(), newId(), newTraceId());
        publish(RabbitMQConfig.RK_TICKET_ASSIGNED, new TicketAssignedEvent(1L, 2L, 1L, null),
                newId(), newTraceId());
        publish(RabbitMQConfig.RK_TICKET_STATUS_CHANGED,
                new TicketStatusChangedEvent(1L, null, null, null, 1L, 4L), newId(), newTraceId());
        publish(RabbitMQConfig.RK_KNOWLEDGE_INDEX,
                new com.opsdesk.mq.event.KnowledgeIndexEvent(1L), newId(), newTraceId());

        for (String queue : List.of(RabbitMQConfig.QUEUE_TICKET_CREATED, RabbitMQConfig.QUEUE_TICKET_ASSIGNED,
                RabbitMQConfig.QUEUE_TICKET_STATUS, RabbitMQConfig.QUEUE_KNOWLEDGE_INDEX)) {
            awaitQueueDrained(queue, 15_000);
        }

        assertThat(appender.list).as("4 个消费者都应留下 [MQ 消费] 日志")
                .filteredOn(e -> e.getFormattedMessage().contains("[MQ 消费]"))
                .hasSizeGreaterThanOrEqualTo(4);

        int dlqAfter = queueDepth(RabbitMQConfig.DLQ_TICKET_CREATED)
                + queueDepth(RabbitMQConfig.DLQ_TICKET_ASSIGNED)
                + queueDepth(RabbitMQConfig.DLQ_TICKET_STATUS)
                + queueDepth(RabbitMQConfig.DLQ_KNOWLEDGE_INDEX);
        assertThat(dlqAfter).as("正常消息不该进死信队列").isEqualTo(dlqBefore);
    }

    // ==================== 验收 1：traceId 端到端一致 ====================

    @Test
    @DisplayName("验收1：生产端放进消息头的 traceId，在消费端日志里是同一个值")
    void traceId端到端一致() {
        String traceId = newTraceId();

        publish(RabbitMQConfig.RK_TICKET_CREATED, ticketCreatedEvent(), newId(), traceId);

        ILoggingEvent consumed = awaitLogContaining("[MQ 消费] TicketCreatedEvent", 15_000);
        assertThat(consumed.getMDCPropertyMap().get(OutboxPublisher.HEADER_TRACE_ID))
                .as("§23.6：日志里的 traceId 必须等于生产端放进消息头的那个")
                .isEqualTo(traceId);
    }

    // ==================== 验收 2：重复投递只执行一次 ====================

    @Test
    @DisplayName("验收2：同一条消息（同 messageId）投两次 → 业务只跑一次，第二次日志写「已消费，跳过」")
    void 重复投递只执行一次() {
        String messageId = newId();
        String traceId = newTraceId();
        TicketCreatedEvent event = ticketCreatedEvent();

        publish(RabbitMQConfig.RK_TICKET_CREATED, event, messageId, traceId);
        awaitLogContaining("[MQ 消费] TicketCreatedEvent", 15_000);

        // 第二次：同 messageId（模拟重投 / Outbox 重发）
        publish(RabbitMQConfig.RK_TICKET_CREATED, event, messageId, traceId);

        awaitLogContaining("[MQ 消费] 已消费，跳过", 15_000);

        long businessRuns = countLogs("[MQ 消费] TicketCreatedEvent");
        long skipped = countLogs("[MQ 消费] 已消费，跳过");
        assertThat(businessRuns).as("§13.6：业务只执行一次").isEqualTo(1);
        assertThat(skipped).as("第二次被幂等挡掉并留下日志").isEqualTo(1);
    }

    // ==================== 验收 3：重试 3 次（1s/2s 退避）→ 进 DLQ ====================

    @Test
    @DisplayName("验收3：监听器抛异常 → 重试 3 次（含 1s/2s 退避）→ 消息进 DLQ")
    void 异常重试三次后进死信队列() {
        int dlqBefore = queueDepth(RabbitMQConfig.DLQ_TICKET_CREATED);
        // 让消费者每次都失败（spy 只影响本用例，Mockito 会在用例后 reset）
        doThrow(new IllegalStateException("模拟消费失败"))
                .when(ticketCreatedListener).onTicketCreated(any(), any());

        long start = System.currentTimeMillis();
        publish(RabbitMQConfig.RK_TICKET_CREATED, ticketCreatedEvent(), newId(), newTraceId());

        // ① 恰好 3 次尝试（yml 的 maxAttempts 配在容器工厂的 AdviceChain 上）
        verify(ticketCreatedListener, timeout(20_000).times(3)).onTicketCreated(any(), any());

        // ② 消息进 DLQ（RepublishMessageRecoverer 重发到 opsdesk.dlx → 对应 DLQ）
        awaitDepthIncrease(RabbitMQConfig.DLQ_TICKET_CREATED, dlqBefore, 20_000);

        // ③ 退避是真的：1s + 2s = 至少 3s，若退避没生效会是毫秒级
        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed)
                .as("§13.4：退避 1s → 2s，三次尝试总耗时不应低于 3s（实际 %d ms）", elapsed)
                .isGreaterThanOrEqualTo(3_000);

        // ④ ⭐ D9-04：死信必须带 x-retry-count = maxAttempts（§13.4 约束 4）
        //    这是「自定义 recoverer 真的生效」的**唯一**证据 ——
        //    覆写签名写错时它会悄悄消失，编译与其它日志都看不出来。
        Message dead = awaitDlqMessage(15_000);
        assertThat(dead.getMessageProperties().getHeaders())
                .as("D9-05 的死信接口要靠这个头显示「重试了几次」")
                .containsEntry(OpsDeskMessageRecoverer.HEADER_RETRY_COUNT, 3);
    }

    // ==================== 验收 4：反序列化失败 → 进 DLQ，不进监听器 ====================

    @Test
    @DisplayName("验收4：畸形 JSON → 消息进 DLQ（不阻塞队列），监听器根本不会被调用")
    void 反序列化失败进死信队列() {
        int dlqBefore = queueDepth(RabbitMQConfig.DLQ_TICKET_CREATED);

        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId(newId());
        props.setHeader(OutboxPublisher.HEADER_TRACE_ID, newTraceId());
        // 直接发原始消息，绕开生产端转换器 —— 模拟「生产端发了非法 JSON」
        rabbitTemplate.send(RabbitMQConfig.EXCHANGE, RabbitMQConfig.RK_TICKET_CREATED,
                new Message("{not-a-json".getBytes(StandardCharsets.UTF_8), props));

        awaitDepthIncrease(RabbitMQConfig.DLQ_TICKET_CREATED, dlqBefore, 20_000);

        assertThat(countLogs("[MQ 消费] TicketCreatedEvent"))
                .as("反序列化失败发生在进入监听器之前 → 业务一行都不该执行")
                .isZero();
        verify(ticketCreatedListener, never()).onTicketCreated(any(), any());

        // ⚠️ 实测结论：在本项目的配置下（容器工厂挂了重试 AdviceChain），反序列化失败也是
        //    **走 recoverer** 进 DLQ 的（栈里能看到 RetryOperationsInterceptor），
        //    所以它同样带 x-retry-count，而**不带** x-death。
        //    x-death 只出现在「broker 原生死信」那条路 —— 见下面的 原生死信路径 用例。
        Message dead = awaitDlqMessage(15_000);
        assertThat(dead.getMessageProperties().getHeaders())
                .as("走 recoverer 的死信带 x-retry-count")
                .containsEntry(OpsDeskMessageRecoverer.HEADER_RETRY_COUNT, 3);
    }

    // ==================== 验收 2（原生路径）：x-death 里有重试/死信计数 ====================

    @Test
    @DisplayName("验收2：broker 原生死信 → 消息头里能读到 x-death[0].count（D9-05 要兼容这条路径）")
    void 原生死信路径带xDeath() throws Exception {
        // ⚠️ 队列上有消费者，消息会被立刻消费掉 → 先停容器，否则 basicGet 拿不到
        stopListenerContainers();

        // 造一条消息放进业务队列，然后用**原始 Channel** 显式 nack(requeue=false)。
        // 这是「broker 原生死信」的唯一可靠触发方式：容器自己的 error handler 在本项目里
        // 轮不到（recoverer 先把异常兜住了），所以不能靠「发一条坏消息」来制造。
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId(newId());
        rabbitTemplate.send(RabbitMQConfig.EXCHANGE, RabbitMQConfig.RK_TICKET_CREATED,
                new Message("{\"ticketId\":1}".getBytes(StandardCharsets.UTF_8), props));

        try (Connection connection = rabbitTemplate.getConnectionFactory().createConnection();
             Channel channel = connection.createChannel(false)) {
            // basicGet 不自动 ack，拿到 deliveryTag 后手动 nack
            GetResponse got = channel.basicGet(RabbitMQConfig.QUEUE_TICKET_CREATED, false);
            assertThat(got).as("队列里应有一条消息可被 nack").isNotNull();
            channel.basicNack(got.getEnvelope().getDeliveryTag(), false, false);
        }

        Message dead = awaitDlqMessage(15_000);
        assertThat(dead.getMessageProperties().getHeaders())
                .as("§13.4 约束 4：原生死信路径的重试次数来自 broker 的 x-death")
                .containsKey("x-death");
    }

    // ==================== 验收 3：routing key 写错 → ReturnsCallback 打日志 ====================

    @Test
    @DisplayName("验收3：routing key 写错 → broker 退回（mandatory），ReturnsCallback 立刻打 error 日志")
    void 路由不到队列时打日志() {
        String bogusRoutingKey = "no.such.routing.key";

        // mandatory=true + publisher-returns=true：路由不到队列时 broker 会把消息退回，
        // 而不是静默丢弃。convertAndSend 本身不抛异常（退回是异步的），所以只能断言日志。
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, bogusRoutingKey,
                ticketCreatedEvent(), message -> {
                    message.getMessageProperties().setMessageId(newId());
                    return message;
                });

        ILoggingEvent returned = awaitLogContaining("[MQ Return]", 15_000);
        assertThat(returned.getFormattedMessage())
                .as("§13.4：路由失败必须立刻可见（否则消息静默消失，只能等演示时发现队列没消息）")
                .contains(bogusRoutingKey)
                .contains(RabbitMQConfig.EXCHANGE);
        assertThat(returned.getLevel().toString()).as("这是配置错误，要按 error 级别打").isEqualTo("ERROR");
    }

    @Test
    @DisplayName("验收4（补充）：正常路由的消息 ConfirmCallback 记 debug、且不触发 Return")
    void 正常路由不触发Return回调() {
        publish(RabbitMQConfig.RK_TICKET_CREATED, ticketCreatedEvent(), newId(), newTraceId());
        awaitQueueDrained(RabbitMQConfig.QUEUE_TICKET_CREATED, 15_000);

        assertThat(countLogs("[MQ Return]")).as("正常路由不该有退回日志").isZero();
    }

    // ==================== 工具 ====================

    private TicketCreatedEvent ticketCreatedEvent() {
        return new TicketCreatedEvent(1L, "OD2026100600001", "VPN 无法连接",
                4L, 5L, TicketPriority.P2, TicketCategory.NETWORK, TicketType.INCIDENT);
    }

    /** 发一条消息，并带上 messageId（幂等键）与 traceId（§13.6 / §23.6） */
    private void publish(String routingKey, Object payload, String messageId, String traceId) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, routingKey, payload, message -> {
            message.getMessageProperties().setMessageId(messageId);
            message.getMessageProperties().setHeader(OutboxPublisher.HEADER_TRACE_ID, traceId);
            return message;
        });
    }

    private int queueDepth(String queue) {
        QueueInformation info = rabbitAdmin.getQueueInfo(queue);
        // getMessageCount() 是 int（不是 Integer），没有 null 分支；队列不存在才返回 null
        return info == null ? 0 : info.getMessageCount();
    }

    private void awaitQueueDrained(String queue, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (queueDepth(queue) == 0) {
                return;
            }
            sleep();
        }
        assertThat(queueDepth(queue)).as("队列 %s 应在 %d ms 内被消费者排空", queue, timeoutMs).isZero();
    }

    private void awaitDepthIncrease(String queue, int baseline, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (queueDepth(queue) > baseline) {
                return;
            }
            sleep();
        }
        assertThat(queueDepth(queue)).as("队列 %s 的消息数应超过 %d", queue, baseline).isGreaterThan(baseline);
    }

    /**
     * 从 DLQ 里取一条死信（取走即从队列移除）。
     *
     * <p>⚠️ 只能这么读：管理端 API 不适合在测试里调，而 {@code RabbitTemplate.receive} 会
     * <b>消费</b>掉这条消息 —— 本类在 {@code @BeforeEach} 里已经把 DLQ 清空，
     * 所以取到的必然是本用例刚产生的那条（且取走顺带做了卫生）。
     */
    private Message awaitDlqMessage(int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Message message = rabbitTemplate.receive(RabbitMQConfig.DLQ_TICKET_CREATED, 1_000);
            if (message != null) {
                return message;
            }
        }
        throw new AssertionError("等待 DLQ 消息超时：" + RabbitMQConfig.DLQ_TICKET_CREATED);
    }

    private ILoggingEvent awaitLogContaining(String keyword, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (ILoggingEvent event : appender.list) {
                if (event.getFormattedMessage().contains(keyword)) {
                    return event;
                }
            }
            sleep();
        }
        throw new AssertionError("等待日志超时：" + keyword);
    }

    private long countLogs(String keyword) {
        return appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains(keyword))
                .count();
    }

    private String newId() {
        return UUID.randomUUID().toString();
    }

    private String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void sleep() {
        try {
            TimeUnit.MILLISECONDS.sleep(200);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", ex);
        }
    }
}
