package com.opsdesk.organization.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建部门入参（API 文档 §6.2）
 *
 * <p>⚠️ 字段表里<b>没有 {@code status}</b> —— 所以本工单不提供「禁用部门」的写入口，
 * 新建部门恒为启用（{@code department.status} 取 DDL 默认值 1）。
 * VO（§16.6）虽然暴露了 {@code status}，但没有接口能改它，这是规格现状，不是遗漏。
 */
@Data
public class DepartmentCreateDTO {

    /** 父部门 ID，{@code 0} 表示根部门 */
    @NotNull(message = "父部门不能为空（根部门传 0）")
    @Min(value = 0, message = "父部门 ID 不合法")
    private Long parentId;

    /** 部门名称，≤64 */
    @NotBlank(message = "部门名称不能为空")
    @Size(max = 64, message = "部门名称长度不能超过 64")
    private String name;

    /** 同级排序，缺省 0（§6.2：非必填，默认 0） */
    private Integer sort;
}
