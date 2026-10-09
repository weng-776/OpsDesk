package com.opsdesk.ticket.service;

import com.opsdesk.common.PageResult;
import com.opsdesk.ticket.dto.CommentCreateDTO;
import com.opsdesk.ticket.dto.TicketQuery;
import com.opsdesk.ticket.vo.TicketCommentVO;

/**
 * 工单评论业务（工单 D3-04）
 *
 * <p>规格依据：API 文档 §8.7（评论列表）/ §8.8（添加评论）；
 * DDL {@code ticket_comment.internal}（0 否 / 1 是）。
 *
 * <p><b>命名说明</b>：本接口是<b>业务层</b>，不继承 {@code IService}。
 * 表能力（纯 CRUD）在生成的 {@link TicketCommentService}（{@code extends IService<TicketComment>}）上，
 * 由本接口的实现<b>注入</b>使用。这与 D3-01 的 {@code TicketCreateService}、
 * D3-02 的 {@code TicketQueryService} 一致 —— 业务方法不往生成物上挂，
 * 否则每次重跑 {@code gen_entities.py} 都要担心它会覆盖掉什么。
 *
 * <p><b>本接口的核心是「内部可见性」这一条</b>：{@code internal = 1} 的评论只对
 * AGENT / ADMIN 可见，EMPLOYEE 查询时必须在 <b>SQL 层</b>过滤掉（§8.7）。
 */
public interface TicketCommentBizService {

    /**
     * 评论列表（§8.7）—— 分页，按 {@code created_at} 升序（先说的先出现）。
     *
     * <p>两道可见性门槛：
     * <ol>
     *   <li><b>工单可见性</b>：能看这张工单才能看它的评论 —— 复用
     *       {@code TicketDataScopeHelper.assertVisible}，不可见抛 40301。
     *       （已与用户确认：评论本身没有独立于工单的数据范围）</li>
     *   <li><b>内部备注过滤</b>：当前用户<b>不是 AGENT 也不是 ADMIN</b> 时，
     *       追加 {@code internal = 0}（§8.7「EMPLOYEE 查询时服务端自动过滤」）</li>
     * </ol>
     *
     * @throws com.opsdesk.common.BizException 工单不存在（40400）；数据范围外（40301）
     */
    PageResult<TicketCommentVO> list(Long ticketId, TicketQuery query);

    /**
     * 添加评论（§8.8）。
     *
     * <p>同事务写 {@code ticket_comment} + {@code ticket_history}
     * （{@code action = COMMENT}、{@code from_status = to_status = 工单当前状态}）。
     *
     * <p>{@code internal = true} 仅 AGENT / ADMIN 可设；EMPLOYEE 传 {@code true} 会被
     * <b>静默降级</b>为 {@code false}（已与用户确认）。
     *
     * @throws com.opsdesk.common.BizException 工单不存在（40400）；数据范围外（40301）
     */
    TicketCommentVO add(Long ticketId, CommentCreateDTO dto);
}
