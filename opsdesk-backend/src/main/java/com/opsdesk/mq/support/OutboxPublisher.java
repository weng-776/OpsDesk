package com.opsdesk.mq.support;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsdesk.common.TraceIdFilter;
import com.opsdesk.common.enums.OutboxStatus;
import com.opsdesk.config.RabbitMQConfig;
import com.opsdesk.mq.entity.EventOutbox;
import com.opsdesk.mq.service.EventOutboxService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Outbox 投递器（工单 D9-01，规格基线 §13.5 / §13.6）
 *
 * <pre>
 * ① 认领一批 PENDING（{@code FOR UPDATE SKIP LOCKED}，见 EventOutboxService#claimPendingBatch）
 * ② 逐条：payload(JSON) → rabbitTemplate.convertAndSend(opsdesk.topic, routingKey, ...)
 *      成功 → status = SENT, sent_at = now, last_error = null
 *      失败 → retry_count++、last_error = 异常摘要；retry_count &gt;= {@value #MAX_RETRY} → FAILED + 告警
 * ③ 提交 → 行锁释放
 * </pre>
 *
 * <h2>⚠️ 事务边界：一轮 = 一批 = 一个事务</h2>
 * {@code FOR UPDATE} 的行锁持有到事务提交，这正是「认领」的实现方式。
 * 所以本方法<b>必须</b>带 {@code @Transactional}，且由 {@code OutboxPublishJob}（另一个 bean）
 * 调用 —— 同类内部 {@code this.publishPending()} 会绕过代理，注解静默失效。
 *
 * <h2>⚠️ 只投递，不生产</h2>
 * 「谁往 {@code event_outbox} 写 PENDING 就投谁」—— 本类不认识任何具体事件类型，
 * 也不关心 payload 里有什么字段。哪些事件真的会被生产，由 D9-03 决定。
 *
 * <h2>⚠️ 失败隔离的两档（这是本类最容易写错的地方）</h2>
 * <ol>
 *   <li><b>单条失败</b>（payload 不是合法 JSON、路由键错误等）：只记这一条的
 *       {@code retry_count++} 与日志，<b>继续处理下一条</b> —— 一条坏数据不该卡住整个队列。</li>
 *   <li><b>broker 不可达</b>（{@link AmqpConnectException} / {@link AmqpIOException}）：
 *       <b>中止本轮</b>。理由：这是「整轮级」故障，剩下的 99 条必然同样失败，
 *       而每条失败都要等一次连接超时（默认 60s）—— 硬撑着走完会让事务与行锁被占住几十分钟。
 *       中止后剩余记录保持 PENDING，下一轮自然重投。</li>
 * </ol>
 *
 * <h2>⚠️ messageId 必须复用 Outbox 里的值（§13.6）</h2>
 * 消费端幂等键是 {@code mq:consumed:{consumer}:{messageId}}。若每次投递都新生成一个 UUID，
 * 那么「投递成功但事务回滚 → 重投」时消息会带着<b>新 id</b> 进来，消费端幂等直接失效 → 重复业务。
 * 所以这里用 {@code event_outbox.message_id}（建单时生成的那个），
 * 并覆盖掉 {@code Jackson2JsonMessageConverter} 的自动 messageId。
 *
 * <h2>⚠️ traceId 透传（§23.6）</h2>
 * 定时任务没有 HTTP 请求上下文，MDC 里通常没有 traceId → 本轮自己生成一个放进 MDC（日志可见）
 * 并写进消息头，消费端取出后重新 {@code MDC.put}，异步链路才能串起来。
 */
@Slf4j
@Component
public class OutboxPublisher {

    /**
     * MQ 消息头名：traceId。
     *
     * <p>刻意与 MDC 的 key 同名（{@link TraceIdFilter#TRACE_ID}），消费端
     * {@code MDC.put(OutboxPublisher.HEADER_TRACE_ID, header)} 就能接上，不必两套命名。
     */
    public static final String HEADER_TRACE_ID = TraceIdFilter.TRACE_ID;

    /**
     * MQ 消息头名：事件类型（= {@code event_outbox.event_type}）。
     *
     * <p>D9-05 起设置：死信管理接口（§15.1）要展示 {@code eventType}，而消息体里没有这个字段
     * （payload 就是事件对象本身，不含类型名）—— 从消息头取最准，且与 outbox 记录一致。
     */
    public static final String HEADER_EVENT_TYPE = "eventType";

    /** 批大小：一次认领并投递的上限（§13.5 未规定，取 100 控制事务时长） */
    public static final int BATCH_SIZE = 100;

    /** 重试上限：{@code retry_count} 达到它就置 FAILED 并告警（§13.5 的「N 次」） */
    public static final int MAX_RETRY = 5;

    /** {@code event_outbox.last_error} 是 VARCHAR(512) */
    private static final int MAX_ERROR_LENGTH = 512;

    private final EventOutboxService eventOutboxService;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(EventOutboxService eventOutboxService,
                           RabbitTemplate rabbitTemplate,
                           ObjectMapper objectMapper) {
        this.eventOutboxService = eventOutboxService;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 跑一轮投递：认领一批 → 逐条投递 → 回写状态。
     *
     * <p>本方法<b>不抛异常</b>（broker 挂了也只记日志并返回结果）—— 它由定时任务调用，
     * 抛出去只会变成一行 Spring 的调度日志，不如自己记清楚。
     *
     * @return 本轮统计
     */
    @Transactional(rollbackFor = Exception.class)
    public PublishResult publishPending() {
        String traceId = currentOrNewTraceId();
        MDC.put(HEADER_TRACE_ID, traceId);
        try {
            List<EventOutbox> batch = eventOutboxService.claimPendingBatch(BATCH_SIZE, MAX_RETRY);
            if (batch.isEmpty()) {
                log.debug("[Outbox 投递] 本轮无待投递记录");
                return PublishResult.empty();
            }

            int sent = 0;
            int retried = 0;
            int failed = 0;
            boolean brokerUnreachable = false;

            for (EventOutbox outbox : batch) {
                try {
                    send(outbox, traceId);
                    markSent(outbox);
                    sent++;
                }
                catch (Exception ex) {
                    boolean unreachable = isBrokerUnreachable(ex);
                    boolean exhausted = markFailed(outbox, ex);
                    if (exhausted) {
                        failed++;
                    }
                    else {
                        retried++;
                    }
                    if (unreachable) {
                        // 整轮级故障：剩下的必然同样失败，不再逐条等连接超时
                        brokerUnreachable = true;
                        log.warn("[Outbox 投递] broker 不可达，本轮中止。已投递={} 剩余未处理={}",
                                sent, batch.size() - sent - retried - failed);
                        break;
                    }
                }
            }

            log.info("[Outbox 投递] 完成：认领={} 已发送={} 待重试={} 已失败={} broker不可达={}",
                    batch.size(), sent, retried, failed, brokerUnreachable);
            return new PublishResult(batch.size(), sent, retried, failed, brokerUnreachable);
        }
        finally {
            // MDC 生命周期归本方法：别把 traceId 泄漏给调度线程的后续任务
            MDC.remove(HEADER_TRACE_ID);
        }
    }

    // ==================== 私有 ====================

    /**
     * 投递一条。
     *
     * <p>payload 先解析成 {@link JsonNode} 再交给 {@code convertAndSend} ——
     * 不能直接把 JSON 字符串当 payload 发（那样会被序列化成「带引号的字符串」，消费端要解两层）。
     * 解析失败（脏数据）会抛 {@code JsonProcessingException}，由调用方计入该条的重试。
     */
    private void send(EventOutbox outbox, String traceId) throws Exception {
        JsonNode payload = objectMapper.readTree(outbox.getPayload());
        CorrelationData correlationData = new CorrelationData(outbox.getMessageId());

        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, outbox.getRoutingKey(), payload,
                message -> {
                    message.getMessageProperties().setMessageId(outbox.getMessageId());
                    message.getMessageProperties().setHeader(HEADER_TRACE_ID, traceId);
                    // D9-05：死信管理接口要显示 eventType；消息体里没有这个字段，
                    // 从 event_outbox.event_type 复制到消息头
                    message.getMessageProperties().setHeader(HEADER_EVENT_TYPE, outbox.getEventType());
                    // §13.4 消息不丢：持久化（Spring AMQP 默认也是 PERSISTENT，这里显式写出来）
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return message;
                },
                // D9-04 原先已有：ConfirmCallback 用 CorrelationData 关联 nack 与具体 outbox.message_id
                correlationData);

        // publisher-confirm-type=correlated：convertAndSend 返回只表示「已入本地 channel」，
        //    不代表 broker 已 ack。要等待 confirm，才能保证只有拿到 broker ack 才把 outbox 标 SENT。
        //    否则 broker 拒收时 ConfirmCallback 虽记了 error，publishPending 仍会 markSent → 事件丢失。
        CorrelationData.Confirm confirm = correlationData.getFuture().get(10, TimeUnit.SECONDS);
        if (!confirm.isAck()) {
            throw new AmqpException("broker NACK: " + confirm.getReason());
        }
        // mandatory=true + returns callback：路由不到队列时 broker 会退回。
        // 必须在 markSent 之前查 returned；否则 Confirm 可能 ack（交换机收到了）、但无 binding，
        // 回调另线程才到 → 我们先置 SENT → 事件静默丢失。ReturnedMessage 与 correlation 一起关联。
        if (correlationData.getReturned() != null) {
            var returned = correlationData.getReturned();
            throw new AmqpException("broker returned unroutable message: exchange="
                    + returned.getExchange() + " routingKey=" + returned.getRoutingKey()
                    + " replyCode=" + returned.getReplyCode() + " replyText=" + returned.getReplyText());
        }

        log.debug("[Outbox 投递] 已发送并获 broker confirm id={} eventType={} routingKey={} messageId={}",
                outbox.getId(), outbox.getEventType(), outbox.getRoutingKey(), outbox.getMessageId());
    }

    /** 成功：置 SENT + sent_at；同时把 last_error 清掉（重试后成功不该留旧错误） */
    private void markSent(EventOutbox outbox) {
        eventOutboxService.update(new LambdaUpdateWrapper<EventOutbox>()
                .eq(EventOutbox::getId, outbox.getId())
                .set(EventOutbox::getStatus, OutboxStatus.SENT)
                .set(EventOutbox::getSentAt, LocalDateTime.now())
                // ⚠️ 必须显式 set(null)：MP 默认的 NOT_NULL 更新策略会跳过 null 字段，
                //    光靠 updateById 是清不掉 last_error 的
                .set(EventOutbox::getLastError, null));
    }

    /**
     * 失败：{@code retry_count++}、记 {@code last_error}；达到上限则置 FAILED 并告警。
     *
     * <p>⚠️ 这里用「读到的值 + 1」而不是 SQL 自增：行锁由 {@code FOR UPDATE} 持有到提交，
     * 同一行不可能被两个事务同时读到，所以读改写在锁的保护下是安全的。
     * 好处是能<b>一次 UPDATE</b> 同时决定 PENDING / FAILED，不必再查一次。
     *
     * @return 是否已耗尽重试（置为 FAILED）
     */
    private boolean markFailed(EventOutbox outbox, Exception ex) {
        int retryCount = (outbox.getRetryCount() == null ? 0 : outbox.getRetryCount()) + 1;
        boolean exhausted = retryCount >= MAX_RETRY;
        String error = summarize(ex);

        eventOutboxService.update(new LambdaUpdateWrapper<EventOutbox>()
                .eq(EventOutbox::getId, outbox.getId())
                .set(EventOutbox::getRetryCount, retryCount)
                .set(EventOutbox::getLastError, error)
                .set(EventOutbox::getStatus, exhausted ? OutboxStatus.FAILED : OutboxStatus.PENDING));

        if (exhausted) {
            log.error("[Outbox 投递] 重试 {} 次仍失败，已置 FAILED（需人工介入或死信页重投）。"
                            + "id={} eventType={} routingKey={} messageId={}",
                    MAX_RETRY, outbox.getId(), outbox.getEventType(), outbox.getRoutingKey(),
                    outbox.getMessageId(), ex);
        }
        else {
            log.warn("[Outbox 投递] 投递失败，将在下一轮重试（{}/{}）。id={} eventType={} 原因={}",
                    retryCount, MAX_RETRY, outbox.getId(), outbox.getEventType(), error);
        }
        return exhausted;
    }

    /**
     * 是否「broker 不可达」这类整轮级故障。
     *
     * <p>注意不能只看外层类型：Spring AMQP 会把底层的 {@code ConnectException} /
     * {@code SocketException}（都是 {@link IOException}）包进 {@link AmqpConnectException} 或
     * {@link AmqpIOException}，所以这里把两种都算上，并额外看一眼 cause。
     */
    private boolean isBrokerUnreachable(Exception ex) {
        // ⚠️ 必须先限定在 AmqpException 里：payload 不是合法 JSON 时抛的是
        //    JsonProcessingException —— 它**也是** IOException 的子类，
        //    只看 cause 会把它误判成「broker 不可达」，从而让一条脏数据中止整轮投递。
        if (!(ex instanceof AmqpException)) {
            return false;
        }
        return ex instanceof AmqpConnectException
                || ex instanceof AmqpIOException
                || ex.getCause() instanceof IOException;
    }

    /** 异常摘要（{@code last_error} 只有 512 字符，超了会让 UPDATE 直接失败） */
    private String summarize(Exception ex) {
        String text = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }

    /** 取当前 traceId；没有（定时任务场景）就生成一个 */
    private String currentOrNewTraceId() {
        String existing = MDC.get(HEADER_TRACE_ID);
        if (StringUtils.hasText(existing)) {
            return existing;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 一轮投递的统计（供定时任务日志与测试断言）。
     *
     * @param claimed           本轮认领的记录数
     * @param sent              成功投递并置 SENT 的条数
     * @param retried           失败但未耗尽重试（仍 PENDING）的条数
     * @param failed            失败且已耗尽重试（置 FAILED）的条数
     * @param brokerUnreachable 本轮是否因 broker 不可达而提前中止
     */
    public record PublishResult(int claimed, int sent, int retried, int failed, boolean brokerUnreachable) {

        /** 本轮没有待投递记录 */
        public static PublishResult empty() {
            return new PublishResult(0, 0, 0, 0, false);
        }
    }
}
