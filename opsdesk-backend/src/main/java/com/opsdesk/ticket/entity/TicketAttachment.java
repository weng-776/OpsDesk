package com.opsdesk.ticket.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工单附件
 *
 * <p>对应表 {@code ticket_attachment}。
 * <p><b>由 tools/gen_entities.py 从 OpsDesk_DDL_V1.sql 生成 —— 请勿手工编辑</b>；
 * 需要改字段请先改 DDL，再重新执行本脚本。
 */
@Data
@TableName("ticket_attachment")
public class TicketAttachment {
    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工单ID */
    private Long ticketId;

    /** 上传人 */
    private Long uploaderId;

    /** 原始文件名（仅用于展示，不参与路径拼接） */
    private String fileName;

    /** 存储路径（文件名 UUID 化，防路径穿越） */
    private String filePath;

    /** 字节数 */
    private Long fileSize;

    /** MIME 类型 */
    private String contentType;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
