package com.opsdesk.sla.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;

import java.time.LocalDateTime;

import com.opsdesk.common.enums.TicketPriority;

/**
 * SLA 策略（版本化：修改=停用旧版本+新增版本）
 *
 * <p>对应表 {@code sla_policy}。
 * <p><b>由 tools/gen_entities.py 从 OpsDesk_DDL_V1.sql 生成 —— 请勿手工编辑</b>；
 * 需要改字段请先改 DDL，再重新执行本脚本。
 */
@Data
@TableName("sla_policy")
public class SlaPolicy {
    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 优先级：P1/P2/P3 */
    private TicketPriority priority;

    /** 响应时限（分钟） */
    private Integer responseMinutes;

    /** 解决时限（分钟） */
    private Integer resolutionMinutes;

    /** 状态：ACTIVE/INACTIVE */
    private String status;

    /** 生效时间 */
    private LocalDateTime effectiveFrom;

    /** 生成列，仅用于约束「同一优先级只能有一条 ACTIVE」 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String activePriority;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
