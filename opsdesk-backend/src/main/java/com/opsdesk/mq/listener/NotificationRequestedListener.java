package com.opsdesk.mq.listener;

import com.opsdesk.common.TraceIdFilter;
import com.opsdesk.common.enums.NotificationType;
import com.opsdesk.config.RabbitMQConfig;
import com.opsdesk.mq.event.NotificationRequestedEvent;
import com.opsdesk.mq.support.ConsumeIdempotencyGuard;
import com.opsdesk.notification.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * {@code notification.requested} 队列消费者（工单 D9-03，规格基线 §13.2 / §13.4 / §13.6）
 *
 * <p>通知链路的最下游：拿到 {@link NotificationRequestedEvent} 后调
 * {@link NotificationService#notify} —— 它是全项目<b>唯一</b>写 {@code notification} 表的入口
 * （§3.10）。本类<b>不自己 insert</b>。
 *
 * <pre>
 * 触发点（4 处，一行不改）→ NotificationEventListener（写 outbox，同业务事务）
 *     → OutboxPublisher → 本类 → notify(...) → notification 表
 * </pre>
 *
 * <h2>⚠️ 毒消息策略：非法枚举值 → 记 error 后跳过（不重试、不进 DLQ）</h2>
 * {@code type} 在消息体里是字符串。若声明成枚举，非法值会让 Jackson 在<b>进入本方法之前</b>
 * 反序列化失败 → 重试 3 次 → 进 DLQ —— 一条永远不可能成功的坏消息被重试 3 次、还占着 DLQ。
 * 所以这里自己 {@code valueOf}：值非法就<b>记 error 日志并正常返回</b>
 * （正常返回 = 容器 auto ACK = 这条消息到此为止）。
 *
 * <p>⚠️ 注意与「禁止吞掉异常」的区别：这里吞掉的只有「<b>重试多少次都不会成功</b>」的
 * 毒消息（非法枚举、缺收件人）。其余异常一律抛出，交给框架重试 + 死信 ——
 * 那才是「可能只是暂时失败」的情况。
 *
 * <h2>⚠️ traceId：用 payload 里那个（HTTP 请求的），不是消息头的</h2>
 * 消息头的 traceId 是 D9-01 投递器在定时任务线程里生成的（那个线程没有请求上下文），
 * 只能标识「哪一轮投递」。真正要把「HTTP 请求 → 写通知」串起来，得用 payload 里带过来的
 * HTTP traceId（见 {@link NotificationRequestedEvent#traceId()}）。
 * 这里在业务开始前把它覆盖进 MDC，于是「写通知」的日志与最初那次 HTTP 请求同 traceId。
 */
@Slf4j
@Component
public class NotificationRequestedListener {

    private final ConsumeIdempotencyGuard guard;
    private final NotificationService notificationService;

    public NotificationRequestedListener(ConsumeIdempotencyGuard guard,
                                         NotificationService notificationService) {
        this.guard = guard;
        this.notificationService = notificationService;
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_NOTIFICATION)
    public void onNotificationRequested(NotificationRequestedEvent event, Message message) {
        guard.consume(RabbitMQConfig.QUEUE_NOTIFICATION, message, () -> {
            NotificationType type = parseType(event);
            if (type == null) {
                // 毒消息：重试多少次都不会成功，直接跳过（正常返回 = auto ACK）
                return;
            }
            if (event.userId() == null) {
                log.error("[MQ 消费] 通知请求缺收件人，跳过（重试也不会成功）。type={} title={}",
                        type, event.title());
                return;
            }

            // 用 payload 里的 HTTP traceId 覆盖（消息头那个是投递轮次的 id）——验收 5
            if (StringUtils.hasText(event.traceId())) {
                MDC.put(TraceIdFilter.TRACE_ID, event.traceId());
            }

            notificationService.notify(event.userId(), type, event.title(),
                    event.content(), event.bizType(), event.bizId());

            log.info("[MQ 消费] 通知已落库 userId={} type={} bizType={} bizId={}",
                    event.userId(), type, event.bizType(), event.bizId());
        });
    }

    /**
     * 把消息体里的类型名还原成枚举（§3.10 白名单）。
     *
     * <p>非法值记 error 并返回 {@code null}（由调用方跳过）。这里不抛异常是<b>刻意的</b>：
     * 抛出去会被重试 3 次再进 DLQ，对一条永远不可能成功的消息毫无意义。
     */
    private NotificationType parseType(NotificationRequestedEvent event) {
        if (!StringUtils.hasText(event.type())) {
            log.error("[MQ 消费] 通知请求缺 type，跳过。userId={} title={}", event.userId(), event.title());
            return null;
        }
        try {
            return NotificationType.valueOf(event.type());
        }
        catch (IllegalArgumentException ex) {
            // §3.10 是白名单：不在枚举里的值一律视为脏数据
            log.error("[MQ 消费] 通知类型不在 §3.10 白名单内，跳过。type={} userId={}",
                    event.type(), event.userId());
            return null;
        }
    }
}
