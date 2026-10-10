package com.opsdesk.ticket.service.impl;

import com.opsdesk.common.BizException;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.datascope.TicketDataScopeHelper;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.mapper.TicketHistoryMapper;
import com.opsdesk.ticket.service.TicketHistoryBizService;
import com.opsdesk.ticket.service.TicketService;
import com.opsdesk.ticket.vo.TicketHistoryVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 工单历史查询实现（工单 D4-05）
 *
 * <p>规格依据：API 文档 §8.5 / §16.9。
 *
 * <h2>⚠️ 取数前必须先过数据范围</h2>
 * 历史里含有 {@code operatorId} / {@code remark}（内部备注、撤销原因等），
 * 属于「只有看得见这张工单的人才该看到」的信息。所以顺序与 D3-03 详情完全一致：
 * <pre>
 * ① getById 查不到 → 40400
 * ② assertVisible 不可见 → 40301
 * ③ 才去查 ticket_history
 * </pre>
 * 顺序不能反：反了会让「不存在的 id」也返回 40301，攻击者据此能推断 id 是否存在（§2.7）。
 *
 * <p>⚠️ 不做「按 action 过滤」之类的裁剪：历史是审计性质的数据，
 * 看得见工单就看得见它的完整流转轨迹（EMPLOYEE 也能看到内部备注动作 —— 与 §8.7
 * 评论的 {@code internal} 过滤不同，那条规则只针对评论正文）。
 */
@Slf4j
@Service
public class TicketHistoryBizServiceImpl implements TicketHistoryBizService {

    private final TicketService ticketService;
    private final TicketHistoryMapper ticketHistoryMapper;
    private final TicketDataScopeHelper dataScopeHelper;

    public TicketHistoryBizServiceImpl(TicketService ticketService,
                                       TicketHistoryMapper ticketHistoryMapper,
                                       TicketDataScopeHelper dataScopeHelper) {
        this.ticketService = ticketService;
        this.ticketHistoryMapper = ticketHistoryMapper;
        this.dataScopeHelper = dataScopeHelper;
    }

    @Override
    public List<TicketHistoryVO> list(Long ticketId) {
        // ① 存在性 → 40400
        Ticket ticket = ticketService.getById(ticketId);
        if (ticket == null) {
            throw BizException.notFound("工单不存在");
        }
        // ② 数据范围 → 40301
        dataScopeHelper.assertVisible(ticket, UserContext.get());

        // ③ 取数（排序与 operatorName 装配都在 SQL 里，见 TicketHistoryMapper.xml）
        List<TicketHistoryVO> list = ticketHistoryMapper.selectByTicketIdWithOperator(ticketId);
        log.debug("[工单历史] ticketId={} 命中 {} 条", ticketId, list.size());
        return list;
    }
}
