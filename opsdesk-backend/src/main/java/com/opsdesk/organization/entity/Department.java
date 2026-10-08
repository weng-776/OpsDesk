package com.opsdesk.organization.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部门
 *
 * <p>对应表 {@code department}。
 * <p><b>由 tools/gen_entities.py 从 OpsDesk_DDL_V1.sql 生成 —— 请勿手工编辑</b>；
 * 需要改字段请先改 DDL，再重新执行本脚本。
 */
@Data
@TableName("department")
public class Department {
    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 父部门ID，0=根节点 */
    private Long parentId;

    /** 部门名称 */
    private String name;

    /** 祖先路径（含自身），如 /1/3/；用于子树前缀匹配 */
    private String path;

    /** 同级排序 */
    private Integer sort;

    /** 状态：1启用 0禁用 */
    private Integer status;

    /** 逻辑删除：0正常 1已删除 */
    @TableLogic
    private Integer deleted;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
