package com.opsdesk.common.enums;

import lombok.Getter;

/**
 * 工单来源（规格基线 §3.9）
 */
@Getter
public enum TicketSource implements CodeEnum {

    /** 员工在页面创建 */
    WEB("页面创建"),

    /** 通过 AI 助手创建 */
    AI_ASSISTANT("AI 助手创建"),

    /** 管理员代建 */
    ADMIN("管理员代建");

    private final String label;

    TicketSource(String label) {
        this.label = label;
    }
}
