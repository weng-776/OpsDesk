package com.opsdesk.ticket.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单评论 VO（API 文档 §16.10）。
 *
 * <p>出参刻意<b>不返回 Entity</b>：Entity 里没有多余字段，但直接把 Entity 返回会导致
 * 后续给表加列时无意识地把内部列泄露给前端（D3-02/D3-03 的一致做法）。
 */
@Data
public class TicketCommentVO {

    /** 评论 ID */
    private Long id;

    /** 评论人 ID */
    private Long userId;

    /** 评论人姓名 */
    private String userName;

    /** 内容 */
    private String content;

    /**
     * 是否仅 IT 内部可见。
     *
     * <p>⚠️ 出参里保留这个字段是有意的：AGENT / ADMIN 看到 {@code true} 才知道
     * 「这条是内部备注」；而 EMPLOYEE 根本查不到 {@code internal=1} 的行
     * （由 {@code TicketCommentServiceImpl} 在 SQL 层过滤），所以对他而言这个字段恒为 false。
     */
    private Boolean internal;

    /** 时间 */
    private LocalDateTime createdAt;
}
