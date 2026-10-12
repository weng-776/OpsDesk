package com.opsdesk.mq;

import com.opsdesk.common.enums.OutboxStatus;
import com.opsdesk.config.RabbitMQConfig;
import com.opsdesk.mq.entity.EventOutbox;
import com.opsdesk.mq.service.EventOutboxService;
import com.opsdesk.mq.support.OutboxPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * Outbox 投递器验收测试（工单 D9-01，规格基线 §13.5 / §13.6）
 *
 * <h2>⚠️ 本类刻意不加 {@code @Transactional}</h2>
 * {@link OutboxPublisher#publishPending()} 自己开事务、自己提交，而且「认领」的语义
 * 依赖 {@code FOR UPDATE} 的行锁<b>持有到提交</b>。若测试挂一个外层事务，
 * 服务会加入测试事务 → 行锁与状态回写都被测试的回滚吃掉，
 * 「投递后置 SENT」「并发跳过已锁行」两条都验不出来。
 *
 * <p>所以照 {@code TicketCreateServiceTest} 的老办法：<b>非事务 + 按 id 基线自造自清</b>。
 *
 * <h2>为什么要 {@code @MockitoSpyBean} 一个 RabbitTemplate</h2>
 * 验收 2 要求「把 RabbitMQ 停掉」。测试不该去停共享的 broker（会影响同一台机器上的其它用例），
 * 所以用 spy 让 {@code convertAndSend} 抛 {@link AmqpConnectException} ——
 * 这与 broker 真的连不上时抛出的异常<b>完全同一类型</b>，走的是同一条重试分支。
 */
@SpringBootTest
class OutboxPublisherTest {

    /** 与 publisher 的常量对齐 */
    private static final int MAX_RETRY = OutboxPublisher.MAX_RETRY;

    @MockitoSpyBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private EventOutboxService eventOutboxService;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager txManager;

    private long outboxIdBaseline;

    @BeforeEach
    void setUp() {
        outboxIdBaseline = maxId("event_outbox");
    }

    @AfterEach
    void tearDown() {
        // 自造自清：只删本次用例新产生的行，绝不碰别的（真实业务写的 PENDING 行要留着）
        jdbcTemplate.update("DELETE FROM event_outbox WHERE id > ?", outboxIdBaseline);
    }

    // ==================== 验收 1：PENDING → 投递 → SENT ====================

    @Test
    @DisplayName("验收1：PENDING 记录被投递后置 SENT、sent_at 非空，且消息真的进了对应队列")
    void 投递成功后置SENT且消息入队() {
        int queueBefore = queueCount(RabbitMQConfig.QUEUE_TICKET_CREATED);
        long id = insertPending(RabbitMQConfig.RK_TICKET_CREATED, ticketCreatedPayload(1L));

        OutboxPublisher.PublishResult result = outboxPublisher.publishPending();

        assertThat(result.sent()).as("至少投出本用例这条").isGreaterThanOrEqualTo(1);
        assertThat(result.brokerUnreachable()).isFalse();

        Map<String, Object> row = rowOf(id);
        assertThat(row.get("status")).isEqualTo(OutboxStatus.SENT.name());
        assertThat(row.get("sent_at")).as("§13.5：成功要写 sent_at").isNotNull();
        assertThat(row.get("retry_count")).as("成功不增加重试次数").isEqualTo(0);
        assertThat(row.get("last_error")).as("成功时不留失败原因").isNull();

        // ⭐ 消息真的到了 MQ：队列长度增加（用增量而不是绝对值，避免受历史残留影响）
        assertThat(queueCount(RabbitMQConfig.QUEUE_TICKET_CREATED))
                .as("§13.5：投递成功 → 对应队列有消息")
                .isGreaterThanOrEqualTo(queueBefore + 1);
    }

    @Test
    @DisplayName("验收3：已置 SENT 的记录不会被第二轮再投一次（messageId 不变、sent_at 不变）")
    void 不会重复投递() {
        long id = insertPending(RabbitMQConfig.RK_TICKET_CREATED, ticketCreatedPayload(2L));
        String messageId = (String) rowOf(id).get("message_id");

        outboxPublisher.publishPending();
        Map<String, Object> afterFirst = rowOf(id);
        assertThat(afterFirst.get("status")).isEqualTo(OutboxStatus.SENT.name());

        // 第二轮：这条已经是 SENT，认领条件 status = 'PENDING' 不会再选中它
        outboxPublisher.publishPending();

        Map<String, Object> afterSecond = rowOf(id);
        assertThat(afterSecond.get("status")).isEqualTo(OutboxStatus.SENT.name());
        assertThat(afterSecond.get("sent_at"))
                .as("sent_at 变了说明被重投并重新置位").isEqualTo(afterFirst.get("sent_at"));
        assertThat(afterSecond.get("message_id"))
                .as("§13.6 禁止每次投递重新生成 messageId（否则消费端幂等失效）")
                .isEqualTo(messageId);
    }

    // ==================== 验收 2：broker 不可用 → 重试而不是 SENT ====================

    @Test
    @DisplayName("验收2：broker 不可达 → retry_count 递增、status 仍是 PENDING（不是 SENT）")
    void broker不可达时只累加重试() {
        long id = insertPending(RabbitMQConfig.RK_TICKET_CREATED, ticketCreatedPayload(3L));
        stubBrokerUnreachable();

        OutboxPublisher.PublishResult result = outboxPublisher.publishPending();

        assertThat(result.brokerUnreachable()).as("应识别为整轮级故障并中止").isTrue();
        assertThat(result.sent()).isZero();

        Map<String, Object> row = rowOf(id);
        assertThat(row.get("status")).as("失败不能置 SENT（否则事件永远丢了）")
                .isEqualTo(OutboxStatus.PENDING.name());
        assertThat(row.get("retry_count")).isEqualTo(1);
        assertThat(row.get("sent_at")).isNull();
        assertThat((String) row.get("last_error")).as("要留下失败原因便于排查").contains("AmqpConnectException");
    }

    @Test
    @DisplayName("重试耗尽 → 置 FAILED 并留告警日志（不再无限重投）")
    void 重试耗尽置FAILED() {
        long id = insertPendingWithRetry(RabbitMQConfig.RK_TICKET_CREATED,
                ticketCreatedPayload(4L), MAX_RETRY - 1);
        stubBrokerUnreachable();

        outboxPublisher.publishPending();

        Map<String, Object> row = rowOf(id);
        assertThat(row.get("retry_count")).isEqualTo(MAX_RETRY);
        assertThat(row.get("status")).as("§13.5：超过 N 次置 FAILED").isEqualTo(OutboxStatus.FAILED.name());
        assertThat(row.get("sent_at")).isNull();
    }

    // ==================== 失败隔离：单条坏数据不拖垮整轮 ====================

    @Test
    @DisplayName("单条失败隔离：脏 payload 只重试它自己，同批的正常记录照常投出")
    void 单条失败不影响同批其它记录() {
        long badId = insertPending(RabbitMQConfig.RK_TICKET_CREATED, "这不是合法 JSON");
        long goodId = insertPending(RabbitMQConfig.RK_TICKET_CREATED, ticketCreatedPayload(5L));

        OutboxPublisher.PublishResult result = outboxPublisher.publishPending();

        assertThat(result.sent()).as("正常的那条要投出去").isGreaterThanOrEqualTo(1);
        assertThat(result.retried()).as("脏数据那条计入重试").isGreaterThanOrEqualTo(1);
        assertThat(result.brokerUnreachable())
                .as("脏 JSON 抛的是 JsonProcessingException，不能被误判成 broker 不可达而中止整轮")
                .isFalse();

        assertThat(rowOf(badId).get("status")).isEqualTo(OutboxStatus.PENDING.name());
        assertThat(rowOf(goodId).get("status")).isEqualTo(OutboxStatus.SENT.name());
    }

    // ==================== 并发安全：FOR UPDATE SKIP LOCKED ====================

    @Test
    @DisplayName("并发安全：已认领（行锁未释放）的记录不会被另一个事务重复认领")
    void 已认领的行被跳过() throws Exception {
        long id = insertPending(RabbitMQConfig.RK_TICKET_CREATED, ticketCreatedPayload(6L));

        // 事务 1：认领 → 行锁持有到提交
        TransactionStatus holding = txManager.getTransaction(new DefaultTransactionDefinition());
        try {
            assertThat(eventOutboxService.claimPendingBatch(OutboxPublisher.BATCH_SIZE, MAX_RETRY))
                    .extracting(EventOutbox::getId)
                    .as("本事务应认领到刚插入的那条").contains(id);

            // 事务 2（另一个线程 = 另一条连接）：必须跳过已被锁住的行，而不是排队等锁
            List<Long> otherIds = new ArrayList<>();
            Thread other = new Thread(() -> {
                TransactionStatus tx2 = txManager.getTransaction(new DefaultTransactionDefinition());
                try {
                    otherIds.addAll(eventOutboxService
                            .claimPendingBatch(OutboxPublisher.BATCH_SIZE, MAX_RETRY)
                            .stream().map(EventOutbox::getId).toList());
                }
                finally {
                    txManager.commit(tx2);
                }
            }, "outbox-claim-other-tx");
            other.start();
            other.join(10_000);

            assertThat(other.isAlive()).as("SKIP LOCKED 不该阻塞，另一个事务应立刻返回").isFalse();
            assertThat(otherIds).as("被锁住的行必须被跳过").doesNotContain(id);
        }
        finally {
            txManager.commit(holding);
        }

        // 提交后行锁释放 → 现在可以认领到了
        TransactionStatus afterRelease = txManager.getTransaction(new DefaultTransactionDefinition());
        try {
            assertThat(eventOutboxService.claimPendingBatch(OutboxPublisher.BATCH_SIZE, MAX_RETRY))
                    .extracting(EventOutbox::getId)
                    .as("锁随事务提交释放，之后应能正常认领").contains(id);
        }
        finally {
            // 只读校验，回滚即可（不真的投递）
            txManager.rollback(afterRelease);
        }
    }

    // ==================== 工具 ====================

    /** 造一条 PENDING 记录（模拟 TicketCreateServiceImpl#writeOutbox 的写入） */
    private long insertPending(String routingKey, String payload) {
        return insertPendingWithRetry(routingKey, payload, 0);
    }

    private long insertPendingWithRetry(String routingKey, String payload, int retryCount) {
        String messageId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO event_outbox (event_type, message_id, routing_key, payload, status, retry_count) "
                        + "VALUES (?, ?, ?, ?, 'PENDING', ?)",
                "TicketCreatedEvent", messageId, routingKey, payload, retryCount);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM event_outbox WHERE message_id = ?", Long.class, messageId);
        assertThat(id).as("插入后应能按 messageId 查回").isNotNull();
        return id;
    }

    /** 与 {@code TicketCreateServiceImpl#writeOutbox} 同构的 payload（含 D9-01 事件类所需字段） */
    private String ticketCreatedPayload(long ticketId) {
        return """
                {"ticketId":%d,"ticketNo":"OD2026100600001","title":"VPN 无法连接",
                 "creatorId":4,"departmentId":5,"priority":"P2","category":"NETWORK","type":"INCIDENT"}
                """.formatted(ticketId);
    }

    private Map<String, Object> rowOf(long id) {
        return jdbcTemplate.queryForMap(
                "SELECT status, retry_count, last_error, sent_at, message_id FROM event_outbox WHERE id = ?", id);
    }

    private int queueCount(String queueName) {
        QueueInformation info = rabbitAdmin.getQueueInfo(queueName);
        assertThat(info).as("队列 %s 应已由 RabbitMQConfig 声明", queueName).isNotNull();
        Integer count = info.getMessageCount();
        return count == null ? 0 : count;
    }

    /** 让投递抛「连不上 broker」——与真停机时抛出的异常同一类型 */
    private void stubBrokerUnreachable() {
        doThrow(new AmqpConnectException(new ConnectException("Connection refused (模拟 broker 停机)")))
                .when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class));
    }

    private long maxId(String table) {
        Long max = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM " + table, Long.class);
        return max == null ? 0L : max;
    }
}
