package com.opsdesk.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

import java.util.List;

/**
 * 修改用户入参（API 文档 §5.4）
 *
 * <p><b>全量更新</b>（V1 不做局部更新），字段同 §5.3，但 {@code password} 可空 ——
 * 「为空表示不修改」。
 *
 * <p>关于 {@code password} 的校验方式与 §5.3 不同：这里只声明上界 {@code ≤64}，
 * 长度下界（≥6）放到 Service 里、**仅在密码非空白时**校验。
 * 原因：`@Size(min = 6)` 会把空字符串 `""` 判成非法，而 §5.4 的语义是「为空即不修改」——
 * 声明式校验表达不了「空则跳过」，硬用会逼前端把「不改密码」写成「省略字段」。
 * 这不是「移除校验」：非空密码仍会被校验，只是校验点从注解挪到了 Service。
 */
@Data
public class UserUpdateDTO {

    /** 登录名，4–64，唯一（排除自己） */
    @NotBlank(message = "用户名不能为空")
    @Size(min = 4, max = 64, message = "用户名长度需在 4-64 之间")
    private String username;

    /** 明文密码，6–64；<b>null / 空串 / 纯空白都表示不修改</b> */
    @ToString.Exclude
    @Size(max = 64, message = "密码长度不能超过 64")
    private String password;

    /** 姓名，≤64 */
    @NotBlank(message = "姓名不能为空")
    @Size(max = 64, message = "姓名长度不能超过 64")
    private String nickname;

    /** 邮箱，≤128，可选 */
    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱长度不能超过 128")
    private String email;

    /** 所属部门，必须存在且未删除 */
    @NotNull(message = "所属部门不能为空")
    private Long departmentId;

    /** 角色 ID 列表，非空；<b>全量覆盖</b>（§5.4「字段同 5.3」） */
    @NotEmpty(message = "至少需要分配一个角色")
    private List<Long> roleIds;
}
