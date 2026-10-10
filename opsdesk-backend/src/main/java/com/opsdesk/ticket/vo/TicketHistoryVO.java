package com.opsdesk.ticket.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.opsdesk.common.enums.TicketHistoryAction;
import com.opsdesk.common.enums.TicketStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单历史 VO（API 文档 §16.9）
 *
 * <p>规格依据：API 文档 §8.5（工单历史）/ §16.9；规格基线 §3.12（动作枚举）。
 *
 * <p>§16.9 的 8 个字段：{@code id} / {@code operatorId} / {@code operatorName} /
 * {@code action} / {@code fromStatus} / {@code toStatus} / {@code remark} / {@code createdAt}。
 *
 * <p>⚠️ 出参不返回 Entity（D3-02 起的一致做法）：{@code ticket_history} 目前没有内部列，
 * 但直接把 Entity 返回会导致将来给表加列时无意识泄露。
 *
 * <p>⚠️ 时间字段的 {@link JsonFormat} 不是多余的：项目没有全局 JSR-310 序列化配置，
 * 而 §2.6 要求统一 {@code yyyy-MM-dd HH:mm:ss}（与 {@code TicketListVO} 一致）。
 */
@Data
public class TicketHistoryVO {

    /** 记录 ID */
    private Long id;

    /** 操作人 ID */
    private Long operatorId;

    /** 操作人姓名（昵称优先，无昵称回落用户名 —— 与列表/评论的显示名口径一致） */
    private String operatorName;

    /** 动作（§3.12），JSON 里是枚举名，如 {@code ASSIGN} / {@code REJECT} */
    private TicketHistoryAction action;

    /** 变更前状态 */
    private TicketStatus fromStatus;

    /** 变更后状态（动作不改变状态时与 {@code fromStatus} 相同，如 {@code COMMENT}） */
    private TicketStatus toStatus;

    /** 备注 */
    private String remark;

    /** 时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;
}
