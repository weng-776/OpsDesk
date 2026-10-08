package com.opsdesk.mq.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;

import java.time.LocalDateTime;

import com.opsdesk.common.enums.OutboxStatus;

/**
 * 本地消息表（保证「工单存了，事件一定最终发出」）
 *
 * <p>对应表 {@code event_outbox}。
 * <p><b>由 tools/gen_entities.py 从 OpsDesk_DDL_V1.sql 生成 —— 请勿手工编辑</b>；
 * 需要改字段请先改 DDL，再重新执行本脚本。
 */
@Data
@TableName("event_outbox")
public class EventOutbox {
    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 事件类型：TicketCreatedEvent 等 */
    private String eventType;

    /** 消息唯一ID（消费端幂等键） */
    private String messageId;

    /** MQ 路由键 */
    private String routingKey;

    /** 事件体（JSON） */
    private String payload;

    /** 状态：PENDING/SENT/FAILED */
    private OutboxStatus status;

    /** 投递重试次数 */
    private Integer retryCount;

    /** 最近一次失败原因 */
    private String lastError;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /** 投递成功时间 */
    private LocalDateTime sentAt;
}
