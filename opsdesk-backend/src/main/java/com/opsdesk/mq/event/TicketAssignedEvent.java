package com.opsdesk.mq.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.opsdesk.config.RabbitMQConfig;

import java.io.Serializable;

/**
 * 工单已分派 / 转派（规格基线 §13.3）→ 消费方：通知处理人
 *
 * <h2>⚠️ 本事件当前【不投递】（见工单 D9-03）</h2>
 * 「分派通知处理人」这条链路在 Day 6 已经由本地 {@code NotificationEvent} 走通了
 * （{@code TicketFlowServiceImpl#publishAssignedNotification}）。D9-03 明确：
 * {@code TicketAssignedEvent} / {@code TicketStatusChangedEvent} <b>暂不投递</b>，
 * 否则同一件事会被通知两遍。
 *
 * <p>那为什么还要定义这个类？—— 它是 §13.3 事件清单的一部分，
 * 后续若要给分派挂别的消费方（例如企业微信推送），直接往 {@code event_outbox} 写一条即可，
 * 投递器不需要改。本单只定义模型 + 通用投递器，<b>不写生产端</b>。
 *
 * @param ticketId      工单 ID
 * @param assigneeId    新处理人
 * @param operatorId    操作人
 * @param oldAssigneeId 原处理人（首次分派时为 {@code null}）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TicketAssignedEvent(Long ticketId,
                                  Long assigneeId,
                                  Long operatorId,
                                  Long oldAssigneeId) implements Serializable {

    /** 事件类型名，写 {@code event_outbox.event_type} */
    public static final String EVENT_TYPE = "TicketAssignedEvent";

    /** MQ 路由键（复用拓扑常量，不写裸字符串） */
    public static final String ROUTING_KEY = RabbitMQConfig.RK_TICKET_ASSIGNED;
}
