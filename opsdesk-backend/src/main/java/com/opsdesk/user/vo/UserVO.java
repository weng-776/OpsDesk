package com.opsdesk.user.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户出参（API 文档 §16.4）
 *
 * <p>字段与 §16.4 逐项对齐。
 *
 * <p>⚠️ <b>没有 {@code password} 字段，也不会有</b> —— 出参 VO 与实体分离的意义就在这里：
 * 实体 {@code User} 带着 BCrypt 密文和 {@code deleted} 标志，直接返回等于把密文吐给前端。
 *
 * <p>⚠️ {@code createdAt} 上的 {@link JsonFormat} 不是多余的：项目里
 * {@code spring.jackson.date-format} 只作用于 {@code java.util.Date}，
 * <b>管不到 JSR-310 的 {@code LocalDateTime}</b>（会被序列化成 ISO-8601 的 {@code 2026-10-07T19:00:00}）。
 * 而规格基线 §2.6 要求统一 {@code yyyy-MM-dd HH:mm:ss}。全局缺一个
 * {@code Jackson2ObjectMapperBuilderCustomizer}，本工单「修改：无」加不了，先在这里局部兜住。
 */
@Data
public class UserVO {

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

    /** 角色列表 */
    private List<RoleVO> roles;

    /** 创建时间（§2.6 格式：yyyy-MM-dd HH:mm:ss） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;
}
