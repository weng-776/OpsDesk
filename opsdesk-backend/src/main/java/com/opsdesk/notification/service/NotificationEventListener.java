package com.opsdesk.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsdesk.common.TraceIdFilter;
import com.opsdesk.common.enums.OutboxStatus;
import com.opsdesk.mq.entity.EventOutbox;
import com.opsdesk.mq.event.NotificationRequestedEvent;
import com.opsdesk.mq.service.EventOutboxService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 通知事件监听器（工单 D6-03 建立，<b>D9-03 改为「写本地消息表」</b>）
 *
 * <h2>本单（D9-03）的唯一改动：落库 → 写 outbox</h2>
 * <pre>
 * 触发点（分派/状态变更/评论/SLA 提醒）
 *     │  publishEvent(new NotificationEvent(...))     ← Day 6 的 4 处，D9-03 一行不改
 *     ▼
 * 本类（同步 @EventListener）                          ← 只改这里
 *     │  写 event_outbox(status = PENDING, event_type = NotificationRequestedEvent)
 *     ▼
 * OutboxPublisher（D9-01）→ opsdesk.notification.queue
 *     ▼
 * NotificationRequestedListener（复用 D9-02 的幂等骨架）
 *     ▼
 * NotificationService.notify(...)                      ← 唯一写库入口
 * </pre>
 *
 * <p>这正是 {@link NotificationEvent} 类注释里写的设计意图：
 * 「Day 9 接 MQ 时，<b>触发点一行都不用改</b>：把 NotificationEventListener 换成
 * {@code @RabbitListener} 即可」。这里没有换成 {@code @RabbitListener}，
 * 而是换成「写 outbox」—— 多了一步，但换来两件必须的东西：
 * <ol>
 *   <li><b>事务性</b>：写 outbox 与业务同事务，业务回滚则事件不投递 ——
 *       不会出现「工单没建成却收到通知」（§13.5 本地消息表模式）；</li>
 *   <li><b>可靠性</b>：MQ 抖动时事件不丢，投递器会重试（§13.5）。</li>
 * </ol>
 * 直接在业务事务里 {@code rabbitTemplate.send} 做不到这两点：MQ 不是事务资源，
 * 业务回滚后消息已经发出去了（假通知），而且发送失败没法补偿。
 *
 * <h2>⚠️ 仍然是同步 {@code @EventListener}（故意的，别改成 @Async）</h2>
 * 同步意味着本方法跑在<b>发布方的事务里</b> —— 这正是上面第 1 条的前提。
 * 换成 {@code @Async} 就脱离了业务事务，「业务回滚 → outbox 一起回滚」立刻失效。
 *
 * <h2>⚠️ 为什么这里自己拼 EventOutbox 而不复用别处的代码</h2>
 * 与 {@code TicketCreateServiceImpl#writeOutbox} 结构相同但字段不同（一个是业务事件、
 * 一个是通知请求）。抽公共方法要动 {@code ticket/} 或新增 {@code mq/support/} 的文件，
 * 两者都不在 D9-03 的允许改动内。两处各 ~15 行，代价可接受。
 */
@Slf4j
@Component
public class NotificationEventListener {

    private final EventOutboxService eventOutboxService;
    private final ObjectMapper objectMapper;

    public NotificationEventListener(EventOutboxService eventOutboxService,
                                     ObjectMapper objectMapper) {
        this.eventOutboxService = eventOutboxService;
        this.objectMapper = objectMapper;
    }

    /**
     * 收到通知事件 → 落一行 PENDING 到本地消息表。
     *
     * <p>⚠️ 不 catch 异常：写 outbox 失败必须让业务事务一起回滚 ——
     * 否则就是「业务成功但通知永远发不出去」，而且没有任何地方会重试。
     */
    @EventListener
    public void onNotification(NotificationEvent event) {
        NotificationRequestedEvent requested = toRequestedEvent(event);

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(requested);
        }
        catch (JsonProcessingException ex) {
            // 序列化失败 = 事件模型有问题，属开发期错误 → 让它回滚整笔业务（同 writeOutbox 的取舍）
            throw new IllegalStateException("通知事件载荷序列化失败", ex);
        }

        EventOutbox outbox = new EventOutbox();
        outbox.setEventType(NotificationRequestedEvent.EVENT_TYPE);
        // messageId 是消费端幂等键（§13.6），必须唯一（uk_outbox_message_id）
        outbox.setMessageId(UUID.randomUUID().toString());
        outbox.setRoutingKey(NotificationRequestedEvent.ROUTING_KEY);
        outbox.setPayload(payloadJson);
        outbox.setStatus(OutboxStatus.PENDING);
        eventOutboxService.save(outbox);

        log.debug("[通知] 已写入本地消息表待投递 userId={} type={} bizType={} bizId={} traceId={}",
                requested.userId(), requested.type(), requested.bizType(), requested.bizId(),
                requested.traceId());
    }

    /**
     * {@link NotificationEvent} → 可序列化的 {@link NotificationRequestedEvent}。
     *
     * <h2>为什么要截断</h2>
     * 过 MQ 之后 payload 会进 {@code event_outbox.payload}（TEXT），
     * 再被消费端写进 {@code notification}（{@code title} VARCHAR(255)、{@code biz_type} VARCHAR(32)）。
     * 在<b>源头</b>截断，payload 就不会被一个超长标题撑大。
     * {@code notify(...)} 里还会再截一次（防御），两处口径一致。
     *
     * <p>⚠️ {@code content} 不按列宽截断：它在 {@code notification} 里是 TEXT（64KB），
     * 现在这条链路上的正文都只有几十字；额外加的 4000 字符上限只是防 payload 失控，
     * 真触发会<b>记 warn</b>（不静默丢数据）。
     */
    private NotificationRequestedEvent toRequestedEvent(NotificationEvent event) {
        return new NotificationRequestedEvent(
                event.userId(),
                event.type() == null ? null : event.type().name(),
                truncate(event.title(), NotificationRequestedEvent.MAX_TITLE_LENGTH, "title"),
                truncate(event.content(), NotificationRequestedEvent.MAX_CONTENT_LENGTH, "content"),
                truncate(event.bizType(), NotificationRequestedEvent.MAX_BIZ_TYPE_LENGTH, "bizType"),
                event.bizId(),
                // HTTP 请求的 traceId：随 payload 过 MQ，消费端取出后覆盖 MDC，
                // 这样「写通知」的日志与最初那次 HTTP 请求串在同一个 traceId 上（验收 5）
                MDC.get(TraceIdFilter.TRACE_ID));
    }

    /** 超长就截断并记 warn —— 截断是兜底，不该静默发生 */
    private String truncate(String value, int maxLength, String field) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        log.warn("[通知] 字段超过上限已截断。field={} 原长={} 上限={}", field, value.length(), maxLength);
        return value.substring(0, maxLength);
    }
}
