package com.opsdesk.notification.dto;

import com.opsdesk.common.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 我的通知查询入参（API 文档 §12.1）
 *
 * <pre>
 * GET /api/notifications?page=1&amp;size=10&amp;readFlag=
 * </pre>
 *
 * <p>分页继承 {@link PageQuery}（{@code page} 从 1 开始、{@code size} 上限 50）。
 *
 * <p>⚠️ {@code @EqualsAndHashCode(callSuper = true)} 不能省 —— {@link PageQuery} 的
 * {@code page} / {@code size} 也要参与比较。
 *
 * <h2>⚠️ 这里<b>没有</b> {@code userId}</h2>
 * 「只能查自己的通知」是硬约束（§12.1 的接口就叫「我的通知」），
 * 所以接收人一律取 {@code UserContext.requireUserId()}，**不接受客户端传入** ——
 * 把 userId 做成入参就等于给了一个越权查询的口子，而且这种口子很容易被漏掉。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NotificationQuery extends PageQuery {

    /**
     * 已读标记筛选；不传 = 不筛（未读 + 已读都要）。
     *
     * <p>用包装类型 {@link Boolean} 而不是 {@code boolean}：后者默认 {@code false}，
     * 会把「不传」误当成「只看未读」，前端一旦不传就再也看不到已读通知。
     */
    private Boolean readFlag;
}
