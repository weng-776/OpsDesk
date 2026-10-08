package com.opsdesk.common.enums;

import lombok.Getter;

/**
 * 工单类型（规格基线 §3.2）
 */
@Getter
public enum TicketType implements CodeEnum {

    /** 故障 / 异常：VPN 连不上、电脑蓝屏 */
    INCIDENT("故障异常"),

    /** 服务请求：申请账号、申请软件授权 */
    SERVICE_REQUEST("服务请求");

    private final String label;

    TicketType(String label) {
        this.label = label;
    }
}
