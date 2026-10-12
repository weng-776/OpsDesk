package com.opsdesk.mq.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.opsdesk.common.enums.TicketHistoryAction;
import com.opsdesk.common.enums.TicketStatus;
import com.opsdesk.config.RabbitMQConfig;

import java.io.Serializable;

/**
 * 工单状态已变更（规格基线 §13.3）→ 消费方：通知创建人
 *
 * <h2>⚠️ 本事件当前【不投递】（见工单 D9-03）</h2>
 * 同 {@link TicketAssignedEvent}：状态变更通知已由本地 {@code NotificationEvent} 负责，
 * 再投一份 MQ 事件会让创建人收到两条通知。本单只定义模型。
 *
 * @param ticketId   工单 ID
 * @param fromStatus 变更前状态（§3.5）
 * @param toStatus   变更后状态（§3.5）
 * @param action     触发本次变更的动作（§3.6）
 * @param operatorId 操作人
 * @param creatorId  创建人（消费方「通知创建人」直接用，省一次查库）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TicketStatusChangedEvent(Long ticketId,
                                       TicketStatus fromStatus,
                                       TicketStatus toStatus,
                                       TicketHistoryAction action,
                                       Long operatorId,
                                       Long creatorId) implements Serializable {

    /** 事件类型名，写 {@code event_outbox.event_type} */
    public static final String EVENT_TYPE = "TicketStatusChangedEvent";

    /** MQ 路由键（复用拓扑常量，不写裸字符串） */
    public static final String ROUTING_KEY = RabbitMQConfig.RK_TICKET_STATUS_CHANGED;
}
