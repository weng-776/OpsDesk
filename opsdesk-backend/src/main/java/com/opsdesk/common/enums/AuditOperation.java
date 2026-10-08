package com.opsdesk.common.enums;

import lombok.Getter;

/**
 * 审计操作码（规格基线 §3.11）
 *
 * <p>写 {@code audit_log.operation}。与工单历史（{@link TicketHistoryAction}）的区别见 §14.4：
 * 审计覆盖**全系统**的敏感操作，工单历史只记工单内的流转。
 */
@Getter
public enum AuditOperation implements CodeEnum {

    // ---- 工单 ----
    TICKET_CREATE("创建工单"),
    TICKET_ASSIGN("分派工单"),
    TICKET_TRANSFER("转派工单"),
    TICKET_STATUS_CHANGE("工单状态变更"),
    TICKET_PRIORITY_CHANGE("工单优先级变更"),
    TICKET_CLOSE("关闭工单"),
    TICKET_CANCEL("撤销工单"),
    TICKET_DELETE("删除工单"),

    // ---- 用户 ----
    USER_CREATE("创建用户"),
    USER_UPDATE("更新用户"),
    USER_STATUS_CHANGE("用户状态变更"),
    USER_ROLE_CHANGE("用户角色变更"),
    USER_DELETE("删除用户"),

    // ---- 角色 ----
    ROLE_PERMISSION_CHANGE("角色权限变更"),

    // ---- 部门 ----
    DEPARTMENT_CREATE("创建部门"),
    DEPARTMENT_UPDATE("更新部门"),
    DEPARTMENT_DELETE("删除部门"),

    // ---- SLA ----
    SLA_POLICY_CREATE("创建 SLA 策略"),
    SLA_POLICY_UPDATE("更新 SLA 策略"),

    // ---- 知识库 ----
    KNOWLEDGE_DOC_UPLOAD("上传知识文档"),
    KNOWLEDGE_DOC_DELETE("删除知识文档");

    private final String label;

    AuditOperation(String label) {
        this.label = label;
    }
}
