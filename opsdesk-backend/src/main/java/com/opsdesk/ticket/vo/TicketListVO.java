package com.opsdesk.ticket.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.opsdesk.common.enums.SlaState;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.common.enums.TicketStatus;
import com.opsdesk.common.enums.TicketType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单列表出参（API 文档 §16.7「列表精简」）
 *
 * <p>字段与 §16.7 逐项对齐。<b>比详情 VO 少</b>（描述、SLA 策略、AI 分析、附件等都不出）——
 * 列表页一次可能返回 50 行，字段要克制。
 *
 * <p>⚠️ <b>不返回 Entity</b>：{@code ticket} 表有 30 列，里面有 {@code version}、
 * {@code deleted}、各种 SLA 内部标志位，都不是前端要的。
 *
 * <p>⚠️ 时间字段上的 {@link JsonFormat} 不是多余的：项目没有全局 JSR-310 序列化配置
 * （{@code spring.jackson.date-format} 只管 {@code java.util.Date}），
 * 而 §2.6 要求统一 {@code yyyy-MM-dd HH:mm:ss}。
 *
 * <p>⚠️ §16.7 只定义了 <b>一个</b> SLA 字段 {@code slaResolutionState}
 * （工单要点里写的「sla_response_state / sla_resolution_state 两个」与 §16.7 不一致，
 * 这里以 §16.7 的契约字段表为准）。
 */
@Data
public class TicketListVO {

    /** 工单 ID */
    private Long id;

    /** 工单号 */
    private String ticketNo;

    /** 标题 */
    private String title;

    /** 类型：INCIDENT / SERVICE_REQUEST（§3.2） */
    private TicketType type;

    /** 分类（§3.3） */
    private TicketCategory category;

    /** 优先级（§3.4） */
    private TicketPriority priority;

    /** 状态（§3.5） */
    private TicketStatus status;

    /** 创建人姓名 */
    private String creatorName;

    /** 处理人姓名；未分派为 {@code null} */
    private String assigneeName;

    /** 解决时限的 SLA 状态：NORMAL / WARNING / BREACHED（§3.8） */
    private SlaState slaResolutionState;

    /** 解决截止时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime resolutionDeadline;

    /** 创建时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;
}
