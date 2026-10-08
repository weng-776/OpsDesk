package com.opsdesk.common.enums;

import lombok.Getter;

/**
 * 工单历史动作（规格基线 §3.12）
 *
 * <p>写 {@code ticket_history.action}。只用于记录，**不参与业务判定**。
 * 与 {@link TicketStatus} 的区别：状态是「结果」，动作是「怎么变过去的」。
 */
@Getter
public enum TicketHistoryAction implements CodeEnum {

    CREATE("创建"),
    ASSIGN("分派"),
    TRANSFER("转派"),
    ACCEPT("受理"),
    START("开始处理"),
    HOLD("挂起"),
    RESUME("恢复"),
    RESOLVE("标记解决"),
    CLOSE("关闭"),
    REJECT("驳回重新打开"),
    CANCEL("撤销"),
    FORCE_CLOSE("强制关闭"),
    COMMENT("评论");

    private final String label;

    TicketHistoryAction(String label) {
        this.label = label;
    }
}
