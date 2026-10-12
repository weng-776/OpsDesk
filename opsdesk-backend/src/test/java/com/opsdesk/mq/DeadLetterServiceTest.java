package com.opsdesk.mq;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.PageResult;
import com.opsdesk.config.RabbitMQConfig;
import com.opsdesk.mq.dto.DeadLetterQuery;
import com.opsdesk.mq.service.DeadLetterService;
import com.opsdesk.mq.support.ConsumeIdempotencyGuard;
import com.opsdesk.mq.support.OutboxPublisher;
import com.opsdesk.mq.vo.DeadLetterVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 死信管理接口验收测试（工单 D9-05，API 文档 §15.1 / §15.2，规格基线 §21.12）
 *
 * <h2>⚠️ 必须在<b>真实 RabbitMQ</b> 上验</h2>
 * 死信在 MQ 里、不在数据库 —— 单测 mock 不出「消息真的进了 DLQ」「重投真的回到业务队列」。
 * 所以本类连真实 broker，并且继承 {@link AbstractMqIntegrationTest} 让消费者容器跑起来
 * （重投之后要靠消费者把消息吃掉，才能验证「回到业务队列」）。
 *
 * <h2>⚠️ {@code @BeforeEach} 清空 DLQ</h2>
 * 列表断言要「恰好一条」，所以先清历史死信（开发库里 DLQ 只装测试/e2e 产生的死信）。
 *
 * <h2>⚠️ 不加 {@code @Transactional}</h2>
 * 死信在 MQ 里，事务管不着；而 requeue 之后消息会被消费者在<b>自己的事务</b>里处理掉。
 * 加事务只会让断言看不清边界。
 */
class DeadLetterServiceTest extends AbstractMqIntegrationTest {

    @Autowired private DeadLetterService deadLetterService;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private RabbitAdmin rabbitAdmin;
    @Autowired private StringRedisTemplate redisTemplate;

    @BeforeEach
    void purgeDlqs() {
        for (String dlq : List.of(RabbitMQConfig.DLQ_TICKET_CREATED, RabbitMQConfig.DLQ_TICKET_ASSIGNED,
                RabbitMQConfig.DLQ_TICKET_STATUS, RabbitMQConfig.DLQ_KNOWLEDGE_INDEX,
                RabbitMQConfig.DLQ_NOTIFICATION)) {
            rabbitAdmin.purgeQueue(dlq);
        }
    }

    // ==================== 验收 1：列表字段完整 ====================

    @Test
    @DisplayName("验收1：DLQ 里的消息能在列表里看到，messageId / queue / eventType / retryCount 都完整")
    void 列表字段完整() {
        String messageId = deadLetterByMalformedJson();

        PageResult<DeadLetterVO> result = deadLetterService.page(new DeadLetterQuery());

        assertThat(result.getTotal()).as("DLQ 里恰好一条").isEqualTo(1);
        DeadLetterVO vo = result.getList().get(0);
        assertThat(vo.getId()).as("§16.17：1-based 序号").isEqualTo(1L);
        assertThat(vo.getMessageId()).isEqualTo(messageId);
        assertThat(vo.getQueue()).isEqualTo(RabbitMQConfig.DLQ_TICKET_CREATED);
        assertThat(vo.getEventType()).as("取自消息头 __TypeId__").isEqualTo("TicketCreatedEvent");
        assertThat(vo.getPayload()).as("消息体要能看见（否则没法判断这条死信是什么）").contains("not-a-json");
        assertThat(vo.getRetryCount())
                .as("§13.4 约束 4：recoverer 路径读 x-retry-count（恒 = maxAttempts = 3）")
                .isEqualTo(3);
        assertThat(vo.getFailedAt()).as("recoverer 路径从 D9-04 的 x-failed-at 取失败时间").isNotBlank();
    }

    @Test
    @DisplayName("retryCount 另一条路径：broker 原生死信读 x-death[0].count")
    void 原生死信的retryCount() throws Exception {
        // 与 D9-04 同一手法：停容器 → 放一条消息 → 原始 Channel nack(requeue=false)
        stopListenerContainers();
        String messageId = newId();
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId(messageId);
        rabbitTemplate.send(RabbitMQConfig.EXCHANGE, RabbitMQConfig.RK_TICKET_CREATED,
                new Message("{\"ticketId\":1}".getBytes(StandardCharsets.UTF_8), props));

        try (org.springframework.amqp.rabbit.connection.Connection connection =
                     rabbitTemplate.getConnectionFactory().createConnection();
             com.rabbitmq.client.Channel channel = connection.createChannel(false)) {
            com.rabbitmq.client.GetResponse got = channel.basicGet(RabbitMQConfig.QUEUE_TICKET_CREATED, false);
            assertThat(got).isNotNull();
            channel.basicNack(got.getEnvelope().getDeliveryTag(), false, false);
        }

        awaitDlqDepth(RabbitMQConfig.DLQ_TICKET_CREATED, 1);

        DeadLetterVO vo = deadLetterService.page(new DeadLetterQuery()).getList().get(0);
        assertThat(vo.getMessageId()).isEqualTo(messageId);
        assertThat(vo.getRetryCount())
                .as("原生路径没有 x-retry-count，要从 x-death[0].count 取（此时 = 1）")
                .isEqualTo(1);
        assertThat(vo.getFailedAt()).as("原生路径的 x-death[0].time 能还原出时间").isNotNull();
    }

    // ==================== 验收 3：重投 + 幂等 ====================

    @Test
    @DisplayName("验收3：重投 → 消息回到业务队列并被消费；已消费过的重投被幂等挡掉、业务不重复执行")
    void 重投与幂等() {
        // ① 先造一条「已成功消费过」的消息：正常发一条 → 消费者处理掉 → 幂等键已写入
        String messageId = newId();
        publishValid(messageId);
        String consumedKey = ConsumeIdempotencyGuard.KEY_PREFIX
                + RabbitMQConfig.QUEUE_TICKET_CREATED + ":" + messageId;
        awaitRedisKey(consumedKey);
        assertThat(redisTemplate.hasKey(consumedKey)).as("前置：该 messageId 已被成功消费").isTrue();

        // ② 把「同一条消息」再丢进 DLQ（模拟「消费成功过、但又在死信队列里出现」）
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId(messageId);
        rabbitTemplate.send(RabbitMQConfig.DLX, RabbitMQConfig.RK_TICKET_CREATED,
                new Message(validPayload().getBytes(StandardCharsets.UTF_8), props));
        awaitDlqDepth(RabbitMQConfig.DLQ_TICKET_CREATED, 1);

        long id = deadLetterService.page(new DeadLetterQuery()).getList().get(0).getId();

        // ③ 重投 → §15.2 要求「已成功消费过 → 标记为已处理，不重复执行业务」
        assertThat(deadLetterService.requeue(id))
                .as("被幂等挡掉，没有真的重新投递").isFalse();
        assertThat(queueDepth(RabbitMQConfig.DLQ_TICKET_CREATED))
                .as("已处理过的死信要从 DLQ 清掉，否则会一直干扰排查").isZero();

        // ④ 未消费过的死信 → 真的重投回业务队列，并被消费者吃掉
        rabbitAdmin.purgeQueue(RabbitMQConfig.QUEUE_TICKET_CREATED);
        String freshId = newId();
        rabbitTemplate.send(RabbitMQConfig.DLX, RabbitMQConfig.RK_TICKET_CREATED,
                new Message(validPayload().getBytes(StandardCharsets.UTF_8), props(freshId)));
        awaitDlqDepth(RabbitMQConfig.DLQ_TICKET_CREATED, 1);
        long freshSeq = deadLetterService.page(new DeadLetterQuery()).getList().get(0).getId();

        assertThat(deadLetterService.requeue(freshSeq)).as("这次应该真的重投").isTrue();
        assertThat(queueDepth(RabbitMQConfig.DLQ_TICKET_CREATED)).as("重投后 DLQ 应清空").isZero();
        awaitRedisKey(ConsumeIdempotencyGuard.KEY_PREFIX
                + RabbitMQConfig.QUEUE_TICKET_CREATED + ":" + freshId);
        assertThat(redisTemplate.hasKey(ConsumeIdempotencyGuard.KEY_PREFIX
                + RabbitMQConfig.QUEUE_TICKET_CREATED + ":" + freshId))
                .as("§15.2：重投后消息应回到业务队列并被消费").isTrue();
    }

    @Test
    @DisplayName("重投其它消息时不能丢：取出批次里非目标的消息要原样回写 DLQ")
    void 重投不丢其它消息() {
        // 放两条进 DLQ，重投第 2 条 → 第 1 条必须还在
        rabbitTemplate.send(RabbitMQConfig.DLX, RabbitMQConfig.RK_TICKET_CREATED,
                new Message(validPayload().getBytes(StandardCharsets.UTF_8), props(newId())));
        rabbitTemplate.send(RabbitMQConfig.DLX, RabbitMQConfig.RK_TICKET_CREATED,
                new Message(validPayload().getBytes(StandardCharsets.UTF_8), props(newId())));
        awaitDlqDepth(RabbitMQConfig.DLQ_TICKET_CREATED, 2);

        assertThat(deadLetterService.requeue(2)).isTrue();

        // ⚠️ 回写是 basic.publish（异步），立刻读深度会读到 0 —— 先等它落地再断言
        awaitDlqDepth(RabbitMQConfig.DLQ_TICKET_CREATED, 1);
        assertThat(queueDepth(RabbitMQConfig.DLQ_TICKET_CREATED))
                .as("SOP 禁止「看一眼消息就丢了」—— 非目标消息必须回写").isEqualTo(1);
    }

    // ==================== 验收 5：payload 截断 ====================

    @Test
    @DisplayName("验收5：超长消息体被截断，响应体积可控")
    void payload超长被截断() {
        // 造一条超大 body 的畸形消息 → 消费失败 → 进 DLQ
        byte[] huge = ("{\"x\":\"" + "A".repeat(9000)).getBytes(StandardCharsets.UTF_8);
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId(newId());
        rabbitTemplate.send(RabbitMQConfig.EXCHANGE, RabbitMQConfig.RK_TICKET_CREATED, new Message(huge, props));
        awaitDlqDepth(RabbitMQConfig.DLQ_TICKET_CREATED, 1);

        String payload = deadLetterService.page(new DeadLetterQuery()).getList().get(0).getPayload();

        assertThat(payload).as("截断到上限附近（含截断标记）").hasSizeLessThan(2100);
        assertThat(payload).contains("truncated");
    }

    // ==================== 参数校验 ====================

    @Test
    @DisplayName("队列名写错 → 40001（不静默返回空列表，那会让人以为死信队列是空的）")
    void 未知队列报参数错误() {
        DeadLetterQuery query = new DeadLetterQuery();
        query.setQueue("opsdesk.nope.dlq");

        assertThatThrownBy(() -> deadLetterService.page(query))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("序号超出当前列表 → 40400（列表已变化时提示刷新）")
    void 序号越界报不存在() {
        assertThatThrownBy(() -> deadLetterService.requeue(9999))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ==================== 工具 ====================

    /** 造一条畸形消息 → 消费失败重试 3 次 → 进 DLQ；返回它的 messageId */
    private String deadLetterByMalformedJson() {
        String messageId = newId();
        MessageProperties props = props(messageId);
        // 模拟投递器写的头（D9-05 起 OutboxPublisher 会带 eventType）
        props.setHeader(OutboxPublisher.HEADER_EVENT_TYPE, "TicketCreatedEvent");
        rabbitTemplate.send(RabbitMQConfig.EXCHANGE, RabbitMQConfig.RK_TICKET_CREATED,
                new Message("{not-a-json".getBytes(StandardCharsets.UTF_8), props));
        awaitDlqDepth(RabbitMQConfig.DLQ_TICKET_CREATED, 1);
        return messageId;
    }

    private void publishValid(String messageId) {
        // ⚠️ 必须发**对象**而不是 JSON 字符串：Jackson2JsonMessageConverter 会把字符串
        //    再序列化一层（变成「带引号的 JSON 字符串」），消费端反序列化直接失败 →
        //    消息进 DLQ，永远等不到幂等键（踩过）
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.RK_TICKET_CREATED,
                validPayloadNode(), message -> {
                    message.getMessageProperties().setMessageId(messageId);
                    return message;
                });
    }

    /** 与生产端投递器同构的 payload（对象形式，不是字符串） */
    private Object validPayloadNode() {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(validPayload());
        }
        catch (Exception ex) {
            throw new IllegalStateException("构造测试载荷失败", ex);
        }
    }

    private String validPayload() {
        return "{\"ticketId\":1,\"ticketNo\":\"OD2026100600001\",\"title\":\"D9-05\","
                + "\"creatorId\":4,\"departmentId\":5,\"priority\":\"P2\","
                + "\"category\":\"NETWORK\",\"type\":\"INCIDENT\"}";
    }

    private MessageProperties props(String messageId) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId(messageId);
        return props;
    }

    private void awaitDlqDepth(String dlq, int expected) {
        long deadline = System.currentTimeMillis() + 25_000;
        while (System.currentTimeMillis() < deadline) {
            if (queueDepth(dlq) >= expected) {
                return;
            }
            sleep();
        }
        assertThat(queueDepth(dlq)).as("等待 %s 达到 %d 条超时", dlq, expected).isGreaterThanOrEqualTo(expected);
    }

    private void awaitRedisKey(String key) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                return;
            }
            sleep();
        }
        assertThat(redisTemplate.hasKey(key)).as("等待幂等键出现超时：%s", key).isTrue();
    }

    private int queueDepth(String queue) {
        org.springframework.amqp.core.QueueInformation info = rabbitAdmin.getQueueInfo(queue);
        return info == null ? 0 : info.getMessageCount();
    }

    private String newId() {
        return UUID.randomUUID().toString();
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
