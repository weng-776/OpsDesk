package com.opsdesk.common.enums;

import lombok.Getter;

/**
 * 审计资源类型（规格基线 §3.12）
 *
 * <p>写 {@code audit_log.resource_type}，与 {@code resource_id} 一起定位被操作的对象。
 * 索引 {@code idx_audit_query(resource_type, resource_id, created_at)} 依赖该字段。
 */
@Getter
public enum AuditResourceType implements CodeEnum {

    TICKET("工单"),
    USER("用户"),
    ROLE("角色"),
    DEPARTMENT("部门"),
    SLA_POLICY("SLA 策略"),
    KNOWLEDGE_DOC("知识文档");

    private final String label;

    AuditResourceType(String label) {
        this.label = label;
    }
}
