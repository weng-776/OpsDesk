package com.opsdesk.common.enums;

import lombok.Getter;

/**
 * 工单分类（规格基线 §3.3）
 *
 * <p>⚠️ 这是 **AI 结构化输出的白名单**，必须固定。AI 返回的分类不在此列表内时，
 * 兜底为 {@link #OTHER}（§10.3），不得直接落库。
 *
 * <p>第一版用**固定枚举**，不建 {@code ticket_category} 字典表。
 */
@Getter
public enum TicketCategory implements CodeEnum {

    NETWORK("网络/VPN"),
    ACCOUNT("账号/权限"),
    HARDWARE("硬件/设备"),
    SOFTWARE("软件/系统"),
    EMAIL("邮箱/办公协作"),
    PERIPHERAL("外设/打印"),
    OTHER("其他");

    private final String label;

    TicketCategory(String label) {
        this.label = label;
    }
}
