package com.opsdesk.mq.listener;

import com.opsdesk.config.RabbitMQConfig;
import com.opsdesk.mq.event.TicketCreatedEvent;
import com.opsdesk.mq.support.ConsumeIdempotencyGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * {@code ticket.created} 队列消费者（工单 D9-02，规格基线 §13.2 / §13.4 / §13.6）
 *
 * <h2>⚠️ 本单只做骨架 + 日志</h2>
 * 真正的业务接线（AI 分析、通知受理方）是 <b>D9-03</b> 的交付物。
 * 本单的目标是让链路先通起来：消息能进来、幂等生效、traceId 串得上、失败能进 DLQ。
 *
 * <h2>⚠️ 消费端只抛异常，绝不碰 Channel（§13.4 约束 1）</h2>
 * 禁止 {@code basicAck} / {@code basicNack} / {@code basicReject}。
 * 失败就让它抛出去 —— 框架按 {@code maxAttempts=3} 重试（1s/2s 退避），
 * 耗尽后 {@code RepublishMessageRecoverer} republish 到 {@code opsdesk.dlx} → 对应 DLQ。
 * 手写 ack 会绕过这整条链（yml 的 {@code acknowledge-mode=auto} 已配好）。
 *
 * <h2>⚠️ 参数里的 {@code Message} 不能省</h2>
 * 它是 {@link ConsumeIdempotencyGuard} 取 {@code messageId}（幂等键）与 {@code traceId}（日志串联）
 * 的唯一来源。事件对象本身只带业务字段，没有消息头。
 *
 * <h2>⚠️ 反序列化失败进不了本方法</h2>
 * {@code Jackson2JsonMessageConverter} 抛异常发生在<b>进入监听器之前</b>，
 * 由队列的 {@code x-dead-letter-exchange} 参数（broker 原生死信）兜底 —— 见 {@code RabbitMQConfig}。
 */
@Slf4j
@Component
public class TicketCreatedListener {

    private final ConsumeIdempotencyGuard guard;

    public TicketCreatedListener(ConsumeIdempotencyGuard guard) {
        this.guard = guard;
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_TICKET_CREATED)
    public void onTicketCreated(TicketCreatedEvent event, Message message) {
        // consumerName 用队列名：4 个消费者互不相同（§13.6 幂等键前缀），且与拓扑一眼对得上
        guard.consume(RabbitMQConfig.QUEUE_TICKET_CREATED, message, () -> {
            log.info("[MQ 消费] TicketCreatedEvent ticketId={} ticketNo={} title={} creatorId={} "
                            + "departmentId={} priority={} category={} type={}",
                    event.ticketId(), event.ticketNo(), event.title(), event.creatorId(),
                    event.departmentId(), event.priority(), event.category(), event.type());

            // TODO(D9-03): 业务接线 —— 触发 AI 分析（§10）、通知受理方。
            //   注意「通知受理方」在 §13.3 里就挂在本事件上；建单时进公共池、没有确定收件人，
            //   由 D9-03 决定订阅者（对应 TicketCreateServiceImpl:226 的遗留 TODO）。
        });
    }
}
