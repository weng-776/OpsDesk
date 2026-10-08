package com.opsdesk.ticket.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

import com.opsdesk.common.enums.SlaState;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.common.enums.TicketSource;
import com.opsdesk.common.enums.TicketStatus;
import com.opsdesk.common.enums.TicketType;

/**
 * 工单（核心业务对象）
 *
 * <p>对应表 {@code ticket}。
 * <p><b>由 tools/gen_entities.py 从 OpsDesk_DDL_V1.sql 生成 —— 请勿手工编辑</b>；
 * 需要改字段请先改 DDL，再重新执行本脚本。
 */
@Data
@TableName("ticket")
public class Ticket {
    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工单号：OD + yyyyMMdd + 5位日序列 */
    private String ticketNo;

    /** 标题 */
    private String title;

    /** 描述 */
    private String description;

    /** 类型：INCIDENT/SERVICE_REQUEST */
    private TicketType type;

    /** 分类：NETWORK/ACCOUNT/HARDWARE/SOFTWARE/EMAIL/PERIPHERAL/OTHER */
    private TicketCategory category;

    /** 优先级：P1/P2/P3 */
    private TicketPriority priority;

    /** 状态：OPEN/ASSIGNED/IN_PROGRESS/WAITING_USER/WAITING_CONFIRM/REOPENED/CLOSED/CANCELLED */
    private TicketStatus status;

    /** 来源：WEB/AI_ASSISTANT/ADMIN */
    private TicketSource source;

    /** 创建人 */
    private Long creatorId;

    /** 创建人所属部门（创建时快照，用于统计与部门范围过滤） */
    private Long departmentId;

    /** 处理人，NULL 表示未分派 */
    private Long assigneeId;

    /** 创建时适用的 SLA 策略版本（用于追溯） */
    private Long slaPolicyId;

    /** 响应截止 = created_at + response_minutes */
    private LocalDateTime responseDeadline;

    /** 解决截止 = created_at + resolution_minutes（暂停会顺延） */
    private LocalDateTime resolutionDeadline;

    /** 首次受理时间（首次 OPEN→ASSIGNED 时写入） */
    private LocalDateTime firstResponseAt;

    /** 响应 SLA 状态：NORMAL/WARNING/BREACHED */
    private SlaState slaResponseState;

    /** 解决 SLA 状态：NORMAL/WARNING/BREACHED */
    private SlaState slaResolutionState;

    /** 当前暂停起点；非暂停为 NULL */
    private LocalDateTime slaPausedAt;

    /** 累计暂停时长（分钟） */
    private Integer slaPausedMinutes;

    /** 临近超时提醒已发送（幂等标志） */
    private Integer slaWarningNotified;

    /** 超时提醒已发送（幂等标志） */
    private Integer slaBreachNotified;

    /** 重新打开次数 */
    private Integer reopenCount;

    /** 撤销原因 */
    private String cancelReason;

    /** 进入 WAITING_CONFIRM 的时间 */
    private LocalDateTime resolvedAt;

    /** 关闭时间 */
    private LocalDateTime closedAt;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 逻辑删除：0正常 1已删除 */
    @TableLogic
    private Integer deleted;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
