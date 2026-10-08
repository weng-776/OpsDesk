package com.opsdesk.user.vo;

import lombok.Data;

/**
 * 创建用户出参（API 文档 §5.3）
 *
 * <pre>{ "code": 0, "message": "success", "data": { "id": 6 } }</pre>
 *
 * <p>刻意用 VO 而不是直接返回 {@code Long} —— 后者会变成 {@code "data": 6}，
 * 与 §5.3 的契约对不上，前端取 {@code data.id} 会拿到 undefined。
 */
@Data
public class UserCreatedVO {

    /** 新建用户的 ID */
    private Long id;

    public UserCreatedVO() {
    }

    public UserCreatedVO(Long id) {
        this.id = id;
    }
}
