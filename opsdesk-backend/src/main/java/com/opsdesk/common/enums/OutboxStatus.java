package com.opsdesk.common.enums;

import lombok.Getter;

/**
 * Outbox 事件发送状态（规格基线 §3.12 / §13.5）
 *
 * <p>写 {@code event_outbox.status}。业务事务内写 PENDING，定时任务扫描投递 MQ，
 * 成功置 SENT；失败 {@code retry_count++}，超过阈值置 FAILED 并告警。
 */
@Getter
public enum OutboxStatus implements CodeEnum {

    PENDING("待发送"),
    SENT("已发送"),
    FAILED("发送失败");

    private final String label;

    OutboxStatus(String label) {
        this.label = label;
    }
}
