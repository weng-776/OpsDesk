package com.opsdesk.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;

import java.time.LocalDateTime;

import com.opsdesk.common.enums.AiAnalysisStatus;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;

/**
 * AI 工单分析结果（不塞进 ticket 主表）
 *
 * <p>对应表 {@code ai_analysis}。
 * <p><b>由 tools/gen_entities.py 从 OpsDesk_DDL_V1.sql 生成 —— 请勿手工编辑</b>；
 * 需要改字段请先改 DDL，再重新执行本脚本。
 */
@Data
@TableName("ai_analysis")
public class AiAnalysis {
    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工单ID */
    private Long ticketId;

    /** 模型标识 */
    private String model;

    /** AI 建议类型 */
    private String type;

    /** AI 建议分类 */
    private TicketCategory category;

    /** AI 建议优先级 */
    private TicketPriority priority;

    /** 问题摘要 */
    private String summary;

    /** 处理建议数组 */
    private String suggestions;

    /** 模型原始返回（排查用） */
    private String rawResponse;

    /** 状态：SUCCESS/FAILED */
    private AiAnalysisStatus status;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
