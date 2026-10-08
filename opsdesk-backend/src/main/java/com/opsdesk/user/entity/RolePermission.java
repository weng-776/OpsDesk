package com.opsdesk.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色-权限
 *
 * <p>对应表 {@code role_permission}。
 * <p><b>由 tools/gen_entities.py 从 OpsDesk_DDL_V1.sql 生成 —— 请勿手工编辑</b>；
 * 需要改字段请先改 DDL，再重新执行本脚本。
 *
 * <p>⚠️ 本表是<b>复合主键</b>（role_id + permission_id），MyBatis-Plus 不支持，
 * 因此 {@code getById / updateById / removeById / saveOrUpdate} 系列方法不可用，
 * 请用 {@code list / count / saveBatch} 或自定义 SQL。
 */
@Data
@TableName("role_permission")
public class RolePermission {
    /** 角色ID */
    private Long roleId;

    /** 权限ID */
    private Long permissionId;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
