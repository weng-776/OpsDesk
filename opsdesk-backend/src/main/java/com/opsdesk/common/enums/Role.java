package com.opsdesk.common.enums;

import lombok.Getter;

/**
 * 角色（规格基线 §3.1）
 *
 * <p>第一版**固定三个系统角色**，不开放自定义角色创建。
 */
@Getter
public enum Role implements CodeEnum {

    /** 普通员工：提交并跟踪自己的工单 */
    EMPLOYEE("普通员工"),

    /** IT 服务人员：受理、处理、分派工单 */
    AGENT("IT 服务人员"),

    /** 管理员：管理企业组织、权限、SLA、知识库、审计 */
    ADMIN("管理员");

    private final String label;

    Role(String label) {
        this.label = label;
    }
}
