package com.opsdesk.ticket.vo;

import lombok.Data;

/**
 * 创建工单出参（API 文档 §8.1）
 *
 * <pre>{ "code": 0, "message": "success", "data": { "id": 6, "ticketNo": "OD2026100700001" } }</pre>
 *
 * <p>这个 VO 还有一个额外身份：<b>幂等命中时它会被序列化成 JSON 存进 Redis</b>
 * （{@code idem:ticket:{key}}），重复提交时反序列化回来原样返回。
 * 所以它必须有<b>无参构造 + setter</b>（{@code @Data} 提供）才能被 Jackson 还原。
 */
@Data
public class TicketCreatedVO {

    /** 工单主键 */
    private Long id;

    /** 工单号：OD + yyyyMMdd + 5 位日序列 */
    private String ticketNo;

    public TicketCreatedVO() {
    }

    public TicketCreatedVO(Long id, String ticketNo) {
        this.id = id;
        this.ticketNo = ticketNo;
    }
}
