package com.opsdesk.notification.vo;

import lombok.Data;

/**
 * 未读数量出参（API 文档 §12.2）
 *
 * <pre>
 * GET /api/notifications/unread-count  →  data: { "count": 3 }
 * </pre>
 *
 * <p>字段名就是 {@code count} —— 前端顶栏的红点直接读它。
 */
@Data
public class UnreadCountVO {

    /** 未读通知条数 */
    private long count;

    public UnreadCountVO() {
    }

    public UnreadCountVO(long count) {
        this.count = count;
    }
}
