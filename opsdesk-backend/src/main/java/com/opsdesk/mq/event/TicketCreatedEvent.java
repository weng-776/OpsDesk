package com.opsdesk.mq.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.common.enums.TicketType;
import com.opsdesk.config.RabbitMQConfig;

import java.io.Serializable;

/**
 * 工单已创建（规格基线 §13.3）→ 消费方：AI 分析、通知受理方
 *
 * <h2>⚠️ 只放消费方需要的字段，不塞整个实体</h2>
 * 规格基线 §13.3 与工单 D9-01 都要求事件是<b>扁平、按需</b>的字段集合：
 * <ul>
 *   <li>塞实体 → payload 过大，且实体一加字段所有消费方都被动受影响；</li>
 *   <li>本类<b>不继承</b> {@code Ticket}，与数据层解耦（表结构变了这里不必跟着变）。</li>
 * </ul>
 *
 * <h2>⚠️ 可容忍字段增减（{@link JsonIgnoreProperties}）</h2>
 * 生产端与消费端可能不同版本：生产端先上了新字段，老消费端收到未知键必须<b>忽略</b>而不是抛异常
 * ——否则一次「加字段」的发布就会让老消费端的消息全部进死信。
 * 反向（老生产端 → 新消费端）的缺失字段是 {@code null}，由消费方自己判空。
 *
 * <h2>字段与 {@code event_outbox.payload} 的对应</h2>
 * 本类目前由 {@code TicketCreateServiceImpl#writeOutbox} 手写的 JSON 对应，
 * 注意该 payload <b>没有</b> {@code title} / {@code category} / {@code type} 三个键
 * （见 D9-01 交接工单的「已知差异」）—— 消费方拿到的会是 {@code null}。
 *
 * @param ticketId     工单 ID
 * @param ticketNo     工单号（如 {@code OD2026100600001}）
 * @param title        标题
 * @param creatorId    创建人
 * @param departmentId 创建人所属部门（快照值）
 * @param priority     优先级（§3.7）
 * @param category     分类（§3.8）
 * @param type         类型（§3.9）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TicketCreatedEvent(Long ticketId,
                                 String ticketNo,
                                 String title,
                                 Long creatorId,
                                 Long departmentId,
                                 TicketPriority priority,
                                 TicketCategory category,
                                 TicketType type) implements Serializable {

    /** 事件类型名，写 {@code event_outbox.event_type} */
    public static final String EVENT_TYPE = "TicketCreatedEvent";

    /** MQ 路由键（复用拓扑常量，不写裸字符串） */
    public static final String ROUTING_KEY = RabbitMQConfig.RK_TICKET_CREATED;
}
