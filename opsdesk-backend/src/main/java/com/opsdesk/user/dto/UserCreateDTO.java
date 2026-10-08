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
 * 创建用户入参（API 文档 §5.3）
 *
 * <p>字段约束逐条对齐 §5.3 的表格。校验失败由 {@code GlobalExceptionHandler} 统一转成
 * 40001 + 字段级明细。
 *
 * <p>⚠️ {@code password} 打 {@link ToString#exclude()}：避免有人顺手
 * {@code log.debug("dto={}", dto)} 把明文密码写进日志。
 */
@Data
public class UserCreateDTO {

    /** 登录名，4–64，唯一 */
    @NotBlank(message = "用户名不能为空")
    @Size(min = 4, max = 64, message = "用户名长度需在 4-64 之间")
    private String username;

    /** 明文密码，6–64 —— 服务端 BCrypt 后落库，不落日志、不进响应 */
    @ToString.Exclude
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度需在 6-64 之间")
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

    /** 角色 ID 列表，非空 */
    @NotEmpty(message = "至少需要分配一个角色")
    private List<Long> roleIds;
}
