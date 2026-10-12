package com.opsdesk.mq.listener;

import com.opsdesk.config.RabbitMQConfig;
import com.opsdesk.mq.event.TicketCreatedEvent;
import com.opsdesk.mq.support.ConsumeIdempotencyGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * {@code ticket.created} 队列消费者（工单 D9-02，规格基线 §13.2 / §13.4 / §13.6）
 *
 * <h2>⚠️ 本单只做骨架 + 日志</h2>
 * 真正的业务接线（AI 分析、通知受理方）是 <b>D9-03</b> 的交付物。
 * 本单的目标是让链路先通起来：消息能进来、幂等生效、traceId 串得上、失败能进 DLQ。
 *
 * <h2>⚠️ 消费端只抛异常，绝不碰 Channel（§13.4 约束 1）</h2>
 * 禁止 {@code basicAck} / {@code basicNack} / {@code basicReject}。
 * 失败就让它抛出去 —— 框架按 {@code maxAttempts=3} 重试（1s/2s 退避），
 * 耗尽后 {@code RepublishMessageRecoverer} republish 到 {@code opsdesk.dlx} → 对应 DLQ。
 * 手写 ack 会绕过这整条链（yml 的 {@code acknowledge-mode=auto} 已配好）。
 *
 * <h2>⚠️ 参数里的 {@code Message} 不能省</h2>
 * 它是 {@link ConsumeIdempotencyGuard} 取 {@code messageId}（幂等键）与 {@code traceId}（日志串联）
 * 的唯一来源。事件对象本身只带业务字段，没有消息头。
 *
 * <h2>⚠️ 反序列化失败进不了本方法</h2>
 * {@code Jackson2JsonMessageConverter} 抛异常发生在<b>进入监听器之前</b>，
 * 由队列的 {@code x-dead-letter-exchange} 参数（broker 原生死信）兜底 —— 见 {@code RabbitMQConfig}。
 */
@Slf4j
@Component
public class TicketCreatedListener {

    private final ConsumeIdempotencyGuard guard;

    public TicketCreatedListener(ConsumeIdempotencyGuard guard) {
        this.guard = guard;
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_TICKET_CREATED)
    public void onTicketCreated(TicketCreatedEvent event, Message message) {
        // consumerName 用队列名：4 个消费者互不相同（§13.6 幂等键前缀），且与拓扑一眼对得上
        guard.consume(RabbitMQConfig.QUEUE_TICKET_CREATED, message, () -> {
            log.info("[MQ 消费] TicketCreatedEvent ticketId={} ticketNo={} title={} creatorId={} "
                            + "departmentId={} priority={} category={} type={}",
                    event.ticketId(), event.ticketNo(), event.title(), event.creatorId(),
                    event.departmentId(), event.priority(), event.category(), event.type());

            requestAiAnalysis(event);
        });
    }

    /**
     * AI 分析的调用点（§13.3：{@code TicketCreatedEvent → AI 分析}）。
     *
     * <p>⚠️ 本单（D9-03）只留调用点，<b>不实现</b> —— AI 分析是 Day 10 的工单（D10-04），
     * 跨工单写 AI 调用会破坏「一张工单一个 commit」的边界。
     *
     * <p>D10-04 接线时注意：
     * <ul>
     *   <li>AI 是<b>只做建议</b>、不自动覆盖工单字段（§10.2），结果写 {@code ai_analysis} 表；</li>
     *   <li>外部依赖（Milvus / LLM）不可用时<b>不能把异常抛出去</b>（本批特有约束 6）：
     *       抛出去会被重试 3 次然后进 DLQ，看起来像代码 bug，实际是环境没起。
     *       应先探测可用性，不可用则降级（50300 / 记 warn 后正常消费）；</li>
     *   <li>输出必须落在 §3 枚举白名单内，否则回退默认值并记告警（§10.3）。</li>
     * </ul>
     */
    private void requestAiAnalysis(TicketCreatedEvent event) {
        // TODO(D10-04): 触发 AI 分析（调 AiAnalysisService，注意上面的降级与白名单要求）
        log.debug("[MQ 消费] 待 D10-04 接线：AI 分析 ticketId={}", event.ticketId());
    }
}
