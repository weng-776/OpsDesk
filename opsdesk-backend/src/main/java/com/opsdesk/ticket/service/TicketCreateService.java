package com.opsdesk.ticket.service;

import com.opsdesk.ticket.dto.TicketCreateDTO;
import com.opsdesk.ticket.vo.TicketCreatedVO;

/**
 * 创建工单（工单 D3-01）
 *
 * <p>规格依据：API 文档 §8.1、规格基线 §5.3 / §6.1 / §9.2 / §23.3 / §13.5 / §17.1。
 *
 * <p><b>本接口的实现是一个事务边界</b>（SOP §5 红区）：`ticket` / `ticket_history` /
 * `event_outbox` 三张表要么都写、要么都不写。
 */
public interface TicketCreateService {

    /**
     * 创建工单。
     *
     * @param dto            创建入参
     * @param idempotencyKey 请求头 {@code Idempotency-Key} 的值；<b>可空</b>（不传就退化成普通创建）
     * @return 新工单的 id 与工单号
     * @throws com.opsdesk.common.BizException 幂等键被占且首次仍在处理中（40900）
     */
    TicketCreatedVO create(TicketCreateDTO dto, String idempotencyKey);
}
