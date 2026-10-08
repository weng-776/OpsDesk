package com.opsdesk.audit.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;

import java.time.LocalDateTime;

import com.opsdesk.common.enums.AuditOperation;
import com.opsdesk.common.enums.AuditResourceType;

/**
 * 操作审计（不提供修改/删除接口）
 *
 * <p>对应表 {@code audit_log}。
 * <p><b>由 tools/gen_entities.py 从 OpsDesk_DDL_V1.sql 生成 —— 请勿手工编辑</b>；
 * 需要改字段请先改 DDL，再重新执行本脚本。
 */
@Data
@TableName("audit_log")
public class AuditLog {
    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人 */
    private Long userId;

    /** 操作码，见规格基线 §3.11 */
    private AuditOperation operation;

    /** 资源类型：TICKET/USER/ROLE/DEPARTMENT/SLA_POLICY/KNOWLEDGE_DOC */
    private AuditResourceType resourceType;

    /** 资源ID */
    private Long resourceId;

    /** 变更前快照（JSON，长文本截断至 2000 字符） */
    private String beforeData;

    /** 变更后快照（JSON） */
    private String afterData;

    /** 来源 IP（兼容 IPv6） */
    private String ip;

    /** 客户端 UA */
    private String userAgent;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
