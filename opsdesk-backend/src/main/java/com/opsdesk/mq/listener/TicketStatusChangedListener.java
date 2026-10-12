package com.opsdesk.mq.listener;

import com.opsdesk.config.RabbitMQConfig;
import com.opsdesk.mq.event.TicketStatusChangedEvent;
import com.opsdesk.mq.support.ConsumeIdempotencyGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * {@code ticket.status.changed} 队列消费者（工单 D9-02，规格基线 §13.2 / §13.4 / §13.6）
 *
 * <h2>⚠️ 本队列当前【没有生产者】</h2>
 * 同 {@link TicketAssignedListener}：按 D9-03 的约定，{@code TicketStatusChangedEvent}
 * 暂不投递（「状态变更通知创建人」已由本地 {@code NotificationEvent} 路径负责，避免重复发）。
 *
 * <p>本类只做骨架 + 日志，业务接线是 D9-03 的事。
 */
@Slf4j
@Component
public class TicketStatusChangedListener {

    private final ConsumeIdempotencyGuard guard;

    public TicketStatusChangedListener(ConsumeIdempotencyGuard guard) {
        this.guard = guard;
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_TICKET_STATUS)
    public void onTicketStatusChanged(TicketStatusChangedEvent event, Message message) {
        guard.consume(RabbitMQConfig.QUEUE_TICKET_STATUS, message, () ->
                log.info("[MQ 消费] TicketStatusChangedEvent ticketId={} action={} {} -> {} "
                                + "operatorId={} creatorId={}",
                        event.ticketId(), event.action(), event.fromStatus(), event.toStatus(),
                        event.operatorId(), event.creatorId()));

        // TODO(D9-03): 若要给状态变更挂 MQ 侧消费方，在这里接线（注意与本地通知路径二选一）。
    }
}
