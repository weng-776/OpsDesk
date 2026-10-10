package com.opsdesk.notification.vo;

import lombok.Data;

/**
 * 全部标记已读的出参（API 文档 §12.4）
 *
 * <pre>
 * PUT /api/notifications/read-all  →  data: { "affected": 3 }
 * </pre>
 *
 * <p>{@code affected} 是本次**实际**被改成已读的条数（本来就已读的不计入）——
 * 前端可以据此提示「已把 N 条标为已读」，N 为 0 时就不必弹提示。
 */
@Data
public class ReadAllResultVO {

    /** 本次实际标记为已读的条数 */
    private int affected;

    public ReadAllResultVO() {
    }

    public ReadAllResultVO(int affected) {
        this.affected = affected;
    }
}
