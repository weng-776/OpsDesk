package com.opsdesk.mq.listener;

import com.opsdesk.config.RabbitMQConfig;
import com.opsdesk.knowledge.service.KnowledgeIndexService;
import com.opsdesk.mq.event.KnowledgeIndexEvent;
import com.opsdesk.mq.support.ConsumeIdempotencyGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * {@code knowledge.index} 队列消费者（规格基线 §13.2 / §13.3 / §13.4 / §13.6；
 * 工单 D9-02 建骨架，<b>D12-05 填实现</b>）
 *
 * <h2>链路</h2>
 * <pre>
 *   上传接口写 event_outbox(PENDING)
 *     → OutboxPublishJob 投递（D9-01）
 *     → 本监听器
 *     → KnowledgeIndexService.index(documentId)
 *         解析 → 切分 → Embedding → 写 Milvus → 置 READY / FAILED
 * </pre>
 *
 * <h2>⚠️ 这里为什么没有 try-catch（看着像"没做降级"，其实是刻意）</h2>
 * {@code index()} <b>永不抛异常</b>（D12-03 的约定，与 {@code TicketAnalyzeService#analyze} 同一模式）——
 * Milvus / Embedding 不可用、解析失败、文档不存在等情况全在它内部消化成「置 FAILED + 记日志」。
 * 所以本监听器不需要再兜一层；消息也不会因为"环境没起"被重试 3 次然后进 DLQ
 * （那看起来像代码 bug）。运维在依赖恢复后手工重投即可 —— 重投是新 {@code messageId}，
 * 不会被下面这层幂等挡住。
 *
 * <h2>⚠️ 「文档已被删除」是正常情况，不是错误</h2>
 * 上传后立刻删除时，outbox 里的 {@code KnowledgeIndexEvent} 可能已经发出去了，
 * 消费者跑起来发现文档没了 —— <b>消息比用户慢了一步</b>，不是故障。
 * {@code index()} 会记 warn 后直接返回（不抛异常），避免落进 DLQ 变成噪音。
 * <p>为什么不在删除时顺手清掉 outbox 里的 PENDING 事件：outbox 是<b>只追加</b>的投递台账，
 * 而且事件此时可能已经被投递出去了 —— "清理"治不住，<b>消费者端容错才是唯一可靠的兜底</b>。
 *
 * <h2>两层幂等</h2>
 * <ul>
 *   <li>{@link ConsumeIdempotencyGuard} —— 按 {@code messageId} 防<b>同一条消息</b>重复消费（§13.6）；</li>
 *   <li>{@code index()} 自身 —— 防<b>同一个文档</b>被两条不同消息索引（清旧重建，见 D12-03）。</li>
 * </ul>
 * ⚠️ 所以 {@code index()} 必须在 {@code guard.consume} 的 lambda <b>内部</b>调用 ——
 * 放到外面等于没被幂等保护（D9-02 的骨架里 TODO 原本写在外面，本单挪进来了）。
 */
@Slf4j
@Component
public class KnowledgeIndexListener {

    private final ConsumeIdempotencyGuard guard;
    private final KnowledgeIndexService knowledgeIndexService;

    public KnowledgeIndexListener(ConsumeIdempotencyGuard guard, KnowledgeIndexService knowledgeIndexService) {
        this.guard = guard;
        this.knowledgeIndexService = knowledgeIndexService;
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_KNOWLEDGE_INDEX)
    public void onKnowledgeIndex(KnowledgeIndexEvent event, Message message) {
        guard.consume(RabbitMQConfig.QUEUE_KNOWLEDGE_INDEX, message, () -> {
            log.info("[MQ 消费] KnowledgeIndexEvent documentId={}", event.documentId());
            knowledgeIndexService.index(event.documentId());
        });
    }
}
