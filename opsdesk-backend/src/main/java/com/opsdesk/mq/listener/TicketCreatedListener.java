package com.opsdesk.mq.listener;

import com.opsdesk.ai.service.TicketAnalyzeService;
import com.opsdesk.config.RabbitMQConfig;
import com.opsdesk.mq.event.TicketCreatedEvent;
import com.opsdesk.mq.support.ConsumeIdempotencyGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * {@code ticket.created} 队列消费者（工单 D9-02 建立；D10-04 接上 AI 分析）
 *
 * <h2>本类做两件事</h2>
 * <ol>
 *   <li><b>消费幂等 + traceId 透传</b>（D9-02）：消息能进来、重复投递只执行一次、失败能进 DLQ；</li>
 *   <li><b>触发 AI 分析</b>（D10-04）：见 {@link #requestAiAnalysis}。</li>
 * </ol>
 * <p>⚠️ 通知受理方<b>不在</b>本类：建单进公共池时没有确定的收件人（D9-03 的结论，不广播所有 AGENT）。
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
    private final TicketAnalyzeService ticketAnalyzeService;

    public TicketCreatedListener(ConsumeIdempotencyGuard guard,
                                 TicketAnalyzeService ticketAnalyzeService) {
        this.guard = guard;
        this.ticketAnalyzeService = ticketAnalyzeService;
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
     * AI 分析的调用点（§13.3：{@code TicketCreatedEvent → AI 分析}，D10-04 接线）。
     *
     * <p>§10.1 要求「工单创建后<b>异步</b>触发，不阻塞主流程」—— 异步性就来自这里：
     * 建单接口只写 {@code event_outbox}（同事务、极快），真正耗时的分析跑在消费者线程上。
     *
     * <h2>⚠️ 消费端只负责"叫一声"，成败交给 analyze 自己兜</h2>
     * D9-03 在这里留注释时担心的是「AI 抛异常 → 被重试 3 次 → 进 DLQ，看起来像代码 bug」。
     * D10-02/03 已经把这件事解决了：{@code TicketAnalyzeService.analyze} <b>永不抛异常</b> ——
     * <ul>
     *   <li>AI 正常 → 落 {@code SUCCESS}；</li>
     *   <li>AI 返回非法枚举 → 回退为原工单值，仍 {@code SUCCESS}；</li>
     *   <li>AI 不可用 / 超时 / 开关关闭 → <b>规则引擎兜底</b>（§10.4），仍 {@code SUCCESS}；</li>
     *   <li>连规则兜底都失败（多半 DB 问题）→ 落 {@code FAILED} 记录。</li>
     * </ul>
     * 所以这里<b>不需要</b>再包一层 try-catch，也不会污染队列的重试/DLQ 机制。
     *
     * <h2>⚠️ 阻塞消费线程是已知代价（本单给的取舍）</h2>
     * 分析最长会占用消费者线程 30s（D10-01 的总超时上限），而当前
     * {@code prefetch=1} 且未配 {@code concurrency}（默认单线程）→ 串行。
     * 意味着分析期间 {@code opsdesk.ticket.created.queue} 里的其它消息在排队。
     * <p><b>取舍</b>：V1 先按现状跑通（建单峰值不高，且分析失败会走规则引擎、不长期占线程），
     * 若观察到积压再优化 —— 可选方案是给分析单开一条队列（改 {@code RabbitMQConfig} 加队列 + DLQ + 绑定），
     * 或给本队列调大 {@code concurrency}。两条都涉及拓扑/配置变更，不在本单范围内。
     */
    private void requestAiAnalysis(TicketCreatedEvent event) {
        ticketAnalyzeService.analyze(event.ticketId());
    }
}
