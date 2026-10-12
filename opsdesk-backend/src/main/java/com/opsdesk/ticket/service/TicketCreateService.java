package com.opsdesk.ticket.service;

import com.opsdesk.common.enums.TicketSource;
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
     * 创建工单（Web 入口，来源固定 {@code WEB}）。
     *
     * @param dto            创建入参
     * @param idempotencyKey 请求头 {@code Idempotency-Key} 的值；<b>可空</b>（不传就退化成普通创建）
     * @return 新工单的 id 与工单号
     * @throws com.opsdesk.common.BizException 幂等键被占且首次仍在处理中（40900）
     */
    TicketCreatedVO create(TicketCreateDTO dto, String idempotencyKey);

    /**
     * 创建工单，<b>显式指定来源</b>（§6.1「来源按入口写入」）。
     *
     * <p>为什么不把 {@code source} 放进 {@link TicketCreateDTO}：API 文档 §8.1 的请求字段表里
     * <b>没有</b> {@code source} —— 来源由<b>入口</b>决定，不由客户端指定。
     * 让客户端能传 source 等于开放「伪造来源」的口子。
     *
     * <p>所以来源做成<b>方法参数</b>：Web 入口（{@link #create(TicketCreateDTO, String)}）传 {@code WEB}，
     * AI 助手工具（D11-01）传 {@code AI_ASSISTANT}，管理端代建传 {@code ADMIN}。
     *
     * @param source 来源；传 {@code null} 时退回 {@code WEB}
     */
    TicketCreatedVO create(TicketCreateDTO dto, String idempotencyKey, TicketSource source);
}
