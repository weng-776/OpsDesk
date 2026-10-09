package com.opsdesk.ticket.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.opsdesk.common.enums.SlaState;
import com.opsdesk.common.enums.TicketSource;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 工单详情出参（API 文档 §16.8）
 *
 * <p>§16.8 的原文是「<b>在 {@code TicketListVO} 基础上增加</b>」，所以这里用继承 ——
 * 列表的 12 个字段（含 {@code resolutionDeadline}）全部继承，本类只声明真正新增的 15 个。
 *
 * <p>⚠️ <b>不返回 Entity</b>：{@code ticket} 表有 30 列，里面有 {@code version}（乐观锁内部字段）、
 * {@code deleted}（逻辑删除标志）、{@code sla_paused_at} / {@code sla_warning_notified} /
 * {@code sla_breach_notified}（SLA 内部标志位）—— 都不是前端要的，也不该暴露。
 *
 * <h2>⚠️ §16.8 里还有三个字段，本单<b>刻意不做</b></h2>
 * <ul>
 *   <li>{@code attachments} —— 归 D3-05（附件上传/列表/下载）</li>
 *   <li>{@code aiAnalysis} —— 归 AI 模块（{@code ai_analysis} 表已有种子数据，但 VO 由那边定）</li>
 *   <li>{@code canOperate} —— 依赖 <b>D4-01 的 {@code TicketStateMachine}</b>，现在还不存在。
 *       它算不出来，<b>返回 {@code []} 会被前端读成「当前没有任何可执行动作」</b> ——
 *       假值比缺字段更误导，所以宁可先不出现</li>
 * </ul>
 * 三个字段各自等自己的工单来加。加字段是向后兼容的，不影响已联调的前端。
 *
 * <p>⚠️ 时间字段上的 {@link JsonFormat} 不是多余的：项目没有全局 JSR-310 序列化配置
 * （{@code spring.jackson.date-format} 只管 {@code java.util.Date}），而 §2.6 要求
 * 统一 {@code yyyy-MM-dd HH:mm:ss}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TicketDetailVO extends TicketListVO {

    /** 描述（§5.1 的 {@code description}） */
    private String description;

    /** 来源：WEB / AI_ASSISTANT / ADMIN（§3.9） */
    private TicketSource source;

    /** 创建人 ID */
    private Long creatorId;

    /** 创建人所属部门 ID（<b>创建时的快照</b>，§6.1） */
    private Long departmentId;

    /** 处理人 ID；未分派为 {@code null} */
    private Long assigneeId;

    /** 创建人部门名称 */
    private String departmentName;

    /** 创建时适用的 SLA 策略版本 id（§9.1 追溯用） */
    private Long slaPolicyId;

    /** 响应截止时间（§9.2：{@code created_at + response_minutes}） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime responseDeadline;

    /** 首次受理时间（§9.3：首次 {@code OPEN → ASSIGNED} 时写入）；未受理为 {@code null} */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime firstResponseAt;

    /** 响应 SLA 状态：NORMAL / WARNING / BREACHED（§3.8） */
    private SlaState slaResponseState;

    /** 累计暂停时长（分钟）—— 挂起期间 SLA 暂停并顺延（§9.4） */
    private Integer slaPausedMinutes;

    /** 重新打开次数（§7.3 的 REOPENED 是持久状态） */
    private Integer reopenCount;

    /** 撤销原因；仅 CANCELLED 时有值 */
    private String cancelReason;

    /** 进入 {@code WAITING_CONFIRM} 的时间；未解决为 {@code null} */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime resolvedAt;

    /** 关闭时间；未关闭为 {@code null} */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime closedAt;
}
