package com.opsdesk.common.enums;

import lombok.Getter;

/**
 * SLA 状态（规格基线 §3.8）
 *
 * <p>用于工单列表上的超时标识，判定口径见 §9.5：
 * 按 {@code response_deadline} / {@code resolution_deadline} 与当前时间比较得出。
 */
@Getter
public enum SlaState implements CodeEnum {

    /** 剩余时间充足 */
    NORMAL("正常"),

    /** 临近超时 */
    WARNING("临近超时"),

    /** 已超时 */
    BREACHED("已超时");

    private final String label;

    SlaState(String label) {
        this.label = label;
    }
}
