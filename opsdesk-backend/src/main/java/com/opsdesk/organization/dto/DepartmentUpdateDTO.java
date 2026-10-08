package com.opsdesk.organization.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 修改部门入参（API 文档 §6.3）
 *
 * <p>§6.3 明确「<b>字段同 6.2</b>」，所以这里直接继承，不重复抄一遍字段
 * —— 将来两个契约真的分叉了再拆开。
 *
 * <p>⚠️ 变更 {@code parentId} 会<b>级联重算整棵子树的 {@code path}</b>；
 * 把自己移动到自己的子部门下会成环，服务端返回 40900（§6.3）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DepartmentUpdateDTO extends DepartmentCreateDTO {
}
