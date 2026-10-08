package com.opsdesk.user.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 分配角色入参（API 文档 §5.6）
 *
 * <p>{@code roleIds} 是<b>全量覆盖</b>语义，不是增量追加。
 */
@Data
public class UserRoleDTO {

    /** 角色 ID 列表，非空，全量覆盖 */
    @NotEmpty(message = "至少需要分配一个角色")
    private List<Long> roleIds;
}
