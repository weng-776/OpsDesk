package com.opsdesk.organization.vo;

import lombok.Data;

/**
 * 创建部门出参（API 文档 §6.2）
 *
 * <pre>{ "code": 0, "message": "success", "data": { "id": 7 } }</pre>
 *
 * <p>刻意用 VO 而不是直接返回 {@code Long} —— 后者会变成 {@code "data": 7}，
 * 与 §6.2 的契约对不上，前端取 {@code data.id} 会拿到 undefined。
 */
@Data
public class DepartmentCreatedVO {

    /** 新建部门的 ID */
    private Long id;

    public DepartmentCreatedVO() {
    }

    public DepartmentCreatedVO(Long id) {
        this.id = id;
    }
}
