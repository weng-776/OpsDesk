package com.opsdesk.notification.service;

import com.opsdesk.common.PageResult;
import com.opsdesk.notification.dto.NotificationQuery;
import com.opsdesk.notification.vo.NotificationVO;
import com.opsdesk.notification.vo.ReadAllResultVO;
import com.opsdesk.notification.vo.UnreadCountVO;

/**
 * 站内通知的查询与已读（工单 D6-03，API 文档 §12.1 ~ §12.4）
 *
 * <p>规格依据：API 文档 §12.1（我的通知）/ §12.2（未读数量）/ §12.3（标记已读）/ §12.4（全部已读）、
 * §16.15（出参字段）；规格基线 §3.10（通知类型）。
 *
 * <h2>⚠️ 「只能查 / 改自己的通知」是本接口的硬约束</h2>
 * 四个方法<b>没有一个</b>接受 {@code userId} 参数 —— 接收人一律取
 * {@code UserContext.requireUserId()}。这是刻意的：
 * <ul>
 *   <li>一旦把 {@code userId} 做成入参，就等于开了一个「查别人通知」的口子；</li>
 *   <li>这种口子最容易漏 —— 因为接口看起来「很自然」，评审时不会有人反对。</li>
 * </ul>
 * {@link #markRead(Long)} 还要额外校验「这条通知是不是我的」：id 是客户端给的，
 * 传别人的 id 必须 <b>40301</b>（§12.3 的越权口径）。
 *
 * <h2>与 {@link NotificationService#notify} 的分工</h2>
 * 写入口在生成接口上（全项目唯一），查询/已读在这里 —— 同
 * {@code TicketCommentService} + {@code TicketCommentBizService} 的分工。
 */
public interface NotificationBizService {

    /**
     * 我的通知（§12.1）—— 按时间倒序。
     *
     * @param query 分页 + {@code readFlag} 筛选（不传 = 未读已读都要）
     * @return 分页结果；查不到时是空列表而不是 40400
     */
    PageResult<NotificationVO> page(NotificationQuery query);

    /** 未读数量（§12.2）—— 顶栏红点用 */
    UnreadCountVO unreadCount();

    /**
     * 标记单条已读（§12.3）。
     *
     * <p>幂等：已经读过再标一次也返回成功（不报 40900）—— 前端可能重复点击，
     * 让它失败没有意义。
     *
     * @param notificationId 通知 id
     * @throws com.opsdesk.common.BizException 通知不存在（40400）；不是自己的通知（40301）
     */
    void markRead(Long notificationId);

    /**
     * 全部标记已读（§12.4）。
     *
     * @return 本次**实际**被改为已读的条数（本来就已读的不计入）
     */
    ReadAllResultVO readAll();
}
