package com.opsdesk.common.enums;

import lombok.Getter;

/**
 * 数据范围（规格基线 §3.7）
 *
 * <p>⚠️ 实现注意（§8.4 / §8.3）：
 * <ul>
 *   <li>{@code DEPARTMENT} 指**本部门含子部门（递归）**，靠 {@code department.path} 前缀匹配实现</li>
 *   <li>{@code ASSIGNED} 用于 AGENT 时，**受理范围必须叠加「公共池」**
 *       （{@code status = OPEN 且 assignee_id IS NULL}），不能只按 {@code assignee_id} 过滤，
 *       否则跨部门工单无人处理</li>
 * </ul>
 */
@Getter
public enum DataScope implements CodeEnum {

    /** 仅本人创建的数据 */
    SELF("仅本人创建"),

    /** 分配给本人的数据（+ 公共池，见类注释） */
    ASSIGNED("分配给本人"),

    /** 本部门（含子部门，递归）数据 */
    DEPARTMENT("本部门含子部门"),

    /** 企业全部数据 */
    ALL("全部数据");

    private final String label;

    DataScope(String label) {
        this.label = label;
    }
}
