package com.opsdesk.user.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 权限树节点（API 文档 §16.5）
 *
 * <p>字段与 §16.5 逐项对齐。{@code children} 递归，{@code GET /api/permissions/tree}
 * 返回的是本类型的<b>数组</b>（可含多个根）。
 *
 * <p>⚠️ 按种子数据，{@code permission} 表里 <b>33 个 API 权限的 {@code parent_id} 都是 0</b>
 * —— 也就是说它们本来就是平铺的顶层节点，「分层」只体现在 15 个 MENU 权限上
 * （{@code menu:ticket} 与 {@code menu:system} 各有一层子节点）。
 * 所以树接口会返回 <b>4 + 33 = 37 个根节点</b>，节点总数 48。
 * <p>如果将来希望把 API 权限挂在对应 MENU 下，要改的是<b>种子数据的 {@code parent_id}</b>，
 * 不是本类的组装逻辑 —— 本类严格按 {@code parent_id} 还原数据本来的形状。
 */
@Data
public class PermissionNodeVO {

    /** 权限 ID */
    private Long id;

    /** 父权限 ID，0 为根 */
    private Long parentId;

    /** 权限名称 */
    private String name;

    /** 权限码（规格基线 §3.6） */
    private String code;

    /** 类型：MENU 菜单 / API 接口 */
    private String type;

    /** 子节点；叶子节点为空列表（不是 null，前端不必判空） */
    private List<PermissionNodeVO> children = new ArrayList<>();
}
