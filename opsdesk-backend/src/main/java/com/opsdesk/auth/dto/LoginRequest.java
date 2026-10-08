package com.opsdesk.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/**
 * 登录请求（API 文档 §4.1）
 *
 * <p>字段约束来自 API 文档 §4.1 的请求表：{@code username} / {@code password} 均必填且 ≤64。
 * 校验失败由 {@code GlobalExceptionHandler} 统一转成 40001 + 字段级明细。
 *
 * <p>⚠️ {@code password} 打了 {@link ToString#exclude()}：Lombok 生成的 {@code toString()}
 * 不会带明文密码，避免有人顺手 {@code log.debug("req={}", request)} 把密码写进日志
 * （工单禁止项：不得把密码明文写进日志或响应）。
 */
@Data
public class LoginRequest {

    /** 登录名，≤64 */
    @NotBlank(message = "用户名不能为空")
    @Size(max = 64, message = "用户名长度不能超过 64")
    private String username;

    /** 明文密码，≤64 —— 只在本次校验中使用，不落库、不进日志、不进响应 */
    @ToString.Exclude
    @NotBlank(message = "密码不能为空")
    @Size(max = 64, message = "密码长度不能超过 64")
    private String password;
}
