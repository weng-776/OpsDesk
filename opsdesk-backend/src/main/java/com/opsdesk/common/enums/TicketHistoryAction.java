package com.opsdesk.common.enums;

import lombok.Getter;

import java.util.Locale;

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

    /**
     * 接口动作名（API 文档 §16.8 的 {@code canOperate} 用它，如 {@code "assign"} / {@code "force-close"}）。
     *
     * <p>规则：枚举名小写、下划线转连字符 —— 与 §8.12 的接口路径段一致
     * （{@code POST /{id}/force-close} ↔ {@code FORCE_CLOSE}）。
     *
     * <p>⚠️ 只有「状态流转动作」才有对应的接口路径；{@link #CREATE} / {@link #COMMENT}
     * 不是流转动作（建单走 {@code POST /api/tickets}，评论走 {@code POST /{id}/comments}），
     * 所以它们不会被放进 {@code canOperate}（那里只遍历状态机的流转表）。
     */
    public String apiName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
