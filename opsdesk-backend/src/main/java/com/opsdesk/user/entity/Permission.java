package com.opsdesk.user.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限（MENU + API）
 *
 * <p>对应表 {@code permission}。
 * <p><b>由 tools/gen_entities.py 从 OpsDesk_DDL_V1.sql 生成 —— 请勿手工编辑</b>；
 * 需要改字段请先改 DDL，再重新执行本脚本。
 */
@Data
@TableName("permission")
public class Permission {
    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 父权限ID，0=根 */
    private Long parentId;

    /** 权限名称 */
    private String name;

    /** 权限码，见规格基线 §3.6 */
    private String code;

    /** 类型：MENU菜单 / API接口 */
    private String type;

    /** 排序 */
    private Integer sort;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
