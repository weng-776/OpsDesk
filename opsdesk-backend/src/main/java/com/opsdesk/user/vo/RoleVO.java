package com.opsdesk.user.vo;

import lombok.Data;

/**
 * 角色出参（API 文档 §16.5）
 *
 * <p>{@code code} 是字符串（{@code EMPLOYEE} / {@code AGENT} / {@code ADMIN}），
 * 不是枚举 —— 出参 VO 与实体 {@code com.opsdesk.user.entity.Role} 分离，
 * 实体里那个 {@code Role} 是 {@code com.opsdesk.common.enums.Role} 枚举类型。
 */
@Data
public class RoleVO {

    /** 角色 ID */
    private Long id;

    /** 角色名称 */
    private String name;

    /** 角色码：EMPLOYEE / AGENT / ADMIN（规格基线 §3.1） */
    private String code;

    /** 说明 */
    private String description;
}
