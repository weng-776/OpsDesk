package com.opsdesk.organization.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 部门树节点（API 文档 §16.6）
 *
 * <p>字段与 §16.6 逐项对齐。{@code children} 递归，{@code GET /api/departments/tree}
 * 返回的是本类型的<b>数组</b>（可含多个根）。
 *
 * <p>⚠️ 出参 VO 与实体 {@code Department} 分离：实体里还有 {@code path} / {@code deleted}，
 * {@code path} 是实现细节（子树前缀匹配用），不该出现在对外契约里。
 */
@Data
public class DepartmentNodeVO {

    /** 部门 ID */
    private Long id;

    /** 父部门 ID，0 为根 */
    private Long parentId;

    /** 部门名称 */
    private String name;

    /** 同级排序 */
    private Integer sort;

    /** 状态：1 启用 / 0 禁用 */
    private Integer status;

    /** 子部门；叶子节点为空列表（不是 null，前端不必判空） */
    private List<DepartmentNodeVO> children = new ArrayList<>();
}
