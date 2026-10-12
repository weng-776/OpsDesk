package com.opsdesk.mq.listener;

import com.opsdesk.config.RabbitMQConfig;
import com.opsdesk.mq.event.TicketAssignedEvent;
import com.opsdesk.mq.support.ConsumeIdempotencyGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * {@code ticket.assigned} 队列消费者（工单 D9-02，规格基线 §13.2 / §13.4 / §13.6）
 *
 * <h2>⚠️ 本队列当前【没有生产者】</h2>
 * 按 D9-03 的约定，{@code TicketAssignedEvent} <b>暂不投递</b> ——
 * 「分派通知处理人」已由本地 {@code NotificationEvent} 路径走通（D6-04），
 * 再投一份 MQ 事件会让处理人收到两条通知。
 *
 * <p>那为什么还要有这个消费者？—— ① 它是 §13.2 拓扑的一部分，队列与绑定已经声明好了；
 * ② D9-02 的交付物是「4 个队列的消费者骨架」，缺一个就不完整；
 * ③ 将来若要给分派挂别的消费方（企业微信推送等），只要往 {@code event_outbox} 写一条即可，
 * 消费者这边不用改。
 *
 * <p>本类只做骨架 + 日志，业务接线是 D9-03 的事。
 */
@Slf4j
@Component
public class TicketAssignedListener {

    private final ConsumeIdempotencyGuard guard;

    public TicketAssignedListener(ConsumeIdempotencyGuard guard) {
        this.guard = guard;
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_TICKET_ASSIGNED)
    public void onTicketAssigned(TicketAssignedEvent event, Message message) {
        guard.consume(RabbitMQConfig.QUEUE_TICKET_ASSIGNED, message, () ->
                log.info("[MQ 消费] TicketAssignedEvent ticketId={} assigneeId={} operatorId={} oldAssigneeId={}",
                        event.ticketId(), event.assigneeId(), event.operatorId(), event.oldAssigneeId()));

        // TODO(D9-03): 若要给分派挂 MQ 侧消费方，在这里接线。
        //   ⚠️ 同时必须确保「通知处理人」只有一条路径（本地 NotificationEvent 或 MQ），不要两边都发。
    }
}
