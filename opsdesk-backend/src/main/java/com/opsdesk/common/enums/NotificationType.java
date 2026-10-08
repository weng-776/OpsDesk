package com.opsdesk.common.enums;

import lombok.Getter;

/**
 * 通知类型（规格基线 §3.10）
 *
 * <p>对应 MQ 事件 {@code TicketCreatedEvent} / {@code TicketAssignedEvent} /
 * {@code TicketStatusChangedEvent} 的消费结果（§13.3）。
 */
@Getter
public enum NotificationType implements CodeEnum {

    /** 工单创建（通知受理方） */
    TICKET_CREATED("工单创建"),

    /** 工单被分派 / 转派 */
    TICKET_ASSIGNED("工单分派"),

    /** 状态变更（通知创建人） */
    TICKET_STATUS_CHANGED("状态变更"),

    /** 新增评论 */
    TICKET_COMMENT("新增评论"),

    /** 临近超时提醒 */
    SLA_WARNING("临近超时"),

    /** 已超时提醒 */
    SLA_BREACHED("已超时");

    private final String label;

    NotificationType(String label) {
        this.label = label;
    }
}
