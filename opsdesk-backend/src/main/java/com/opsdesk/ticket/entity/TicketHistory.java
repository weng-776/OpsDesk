package com.opsdesk.ticket.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;

import java.time.LocalDateTime;

import com.opsdesk.common.enums.TicketHistoryAction;
import com.opsdesk.common.enums.TicketStatus;

/**
 * 工单业务过程历史
 *
 * <p>对应表 {@code ticket_history}。
 * <p><b>由 tools/gen_entities.py 从 OpsDesk_DDL_V1.sql 生成 —— 请勿手工编辑</b>；
 * 需要改字段请先改 DDL，再重新执行本脚本。
 */
@Data
@TableName("ticket_history")
public class TicketHistory {
    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工单ID */
    private Long ticketId;

    /** 操作人 */
    private Long operatorId;

    /** 动作：CREATE/ASSIGN/TRANSFER/ACCEPT/START/HOLD/RESUME/RESOLVE/CLOSE/REJECT/CANCEL/FORCE_CLOSE/COMMENT */
    private TicketHistoryAction action;

    /** 变更前状态 */
    private TicketStatus fromStatus;

    /** 变更后状态 */
    private TicketStatus toStatus;

    /** 备注 */
    private String remark;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
