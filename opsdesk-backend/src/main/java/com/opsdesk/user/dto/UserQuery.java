package com.opsdesk.user.dto;

import com.opsdesk.common.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户分页查询（API 文档 §5.1）
 *
 * <p>{@code page} / {@code size} 继承 {@link PageQuery} —— 基类已把 {@code page} 收敛到 ≥1、
 * {@code size} 收敛到 [1, 50]（§20.1 硬约束），所以 {@code size=999} 会被静默收敛成 50。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserQuery extends PageQuery {

    /** 模糊匹配 {@code username} / {@code nickname} */
    private String keyword;

    /** 按部门筛选，<b>含子部门</b>（靠 {@code department.path} 前缀匹配） */
    private Long departmentId;

    /** 状态：1 启用 / 0 禁用 */
    private Integer status;
}
