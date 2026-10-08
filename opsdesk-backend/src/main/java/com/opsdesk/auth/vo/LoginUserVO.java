package com.opsdesk.auth.vo;

import lombok.Data;

import java.util.List;

/**
 * 登录用户信息（API 文档 §16.3）
 *
 * <p>字段与 §16.3 逐项对齐，顺序即文档顺序。
 * 同时用于 {@code POST /api/auth/login} 的 {@code data.user}
 * 与 {@code GET /api/auth/me}（D1-02）的 {@code data}。
 *
 * <p>⚠️ 出参 VO 与 {@code User} 实体分离，实体不直接返回给前端 ——
 * 实体里有 {@code password}（BCrypt 密文）与 {@code deleted}，都不该出现在响应里。
 */
@Data
public class LoginUserVO {

    /** 用户 ID */
    private Long id;

    /** 登录名 */
    private String username;

    /** 姓名 */
    private String nickname;

    /** 邮箱 */
    private String email;

    /** 部门 ID */
    private Long departmentId;

    /** 部门名称 */
    private String departmentName;

    /** 状态：1 启用 / 0 禁用 */
    private Integer status;

    /** 角色码列表，如 {@code ["AGENT"]}（规格基线 §3.1） */
    private List<String> roles;

    /** 权限码列表，前端用于菜单与按钮控制（规格基线 §3.6） */
    private List<String> permissions;
}
