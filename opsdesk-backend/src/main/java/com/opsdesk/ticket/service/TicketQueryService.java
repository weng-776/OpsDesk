package com.opsdesk.ticket.service;

import com.opsdesk.common.PageResult;
import com.opsdesk.ticket.dto.TicketQuery;
import com.opsdesk.ticket.vo.TicketListVO;

/**
 * 工单查询（工单 D3-02）
 *
 * <p>规格依据：API 文档 §8.2 / §8.3、规格基线 §8.3（可见范围）、§18.4（{@code idx_ticket_scope}）。
 *
 * <p><b>本接口的两个方法数据范围不同</b>，这是最容易做错的地方：
 * <ul>
 *   <li>{@link #page} —— 按<b>角色</b>叠加（EMPLOYEE→SELF，AGENT→ASSIGNED∪公共池∪DEPARTMENT，ADMIN→ALL）</li>
 *   <li>{@link #mine} —— <b>强制 SELF</b>（§8.2 抬头明确写「数据范围：SELF」），
 *       ADMIN 调它也只能看到自己创建的</li>
 * </ul>
 */
public interface TicketQueryService {

    /**
     * 工单列表（API 文档 §8.3）—— 数据范围按当前用户角色自动叠加。
     */
    PageResult<TicketListVO> page(TicketQuery query);

    /**
     * 我的工单（API 文档 §8.2）—— 只看自己创建的（{@code creator_id = 当前用户}）。
     */
    PageResult<TicketListVO> mine(TicketQuery query);
}
