package com.opsdesk.user.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 设置角色权限入参（API 文档 §7.3）
 *
 * <p><b>全量覆盖</b>语义：传什么就是什么，不是增量追加。
 *
 * <p>关于空数组：{@code @NotNull} 只要求字段<b>存在</b>，不要求非空 ——
 * 所以 {@code {"permissionIds": []}} 是合法的，含义是「清空该角色的全部权限」。
 * 这与 §7.3「全量覆盖」的字面语义一致（撤销全部权限是正当的管理动作）。
 * <p>⚠️ 注意与 D1-03 的 {@code UserRoleDTO} 区别：那里 {@code roleIds} 用了 {@code @NotEmpty}，
 * 因为<b>用户必须至少有一个角色</b>（否则登录后什么都做不了）；
 * 而<b>角色可以有零个权限</b>，所以这里不拦。
 */
@Data
public class RolePermissionDTO {

    /** 权限 ID 列表，全量覆盖；允许空数组（= 清空） */
    @NotNull(message = "权限列表不能为空（清空请传空数组）")
    private List<Long> permissionIds;
}
