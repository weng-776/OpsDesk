package com.opsdesk.notification.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;

import java.time.LocalDateTime;

import com.opsdesk.common.enums.NotificationType;

/**
 * 站内通知（第一版仅站内信，不做邮件/IM）
 *
 * <p>对应表 {@code notification}。
 * <p><b>由 tools/gen_entities.py 从 OpsDesk_DDL_V1.sql 生成 —— 请勿手工编辑</b>；
 * 需要改字段请先改 DDL，再重新执行本脚本。
 */
@Data
@TableName("notification")
public class Notification {
    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 接收人 */
    private Long userId;

    /** 类型，见规格基线 §3.10 */
    private NotificationType type;

    /** 标题 */
    private String title;

    /** 正文 */
    private String content;

    /** 业务类型：TICKET 等（用于前端跳转） */
    private String bizType;

    /** 业务ID（用于前端跳转） */
    private Long bizId;

    /** 是否已读：0未读 1已读 */
    private Integer readFlag;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
