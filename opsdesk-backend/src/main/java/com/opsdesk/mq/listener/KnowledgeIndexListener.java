package com.opsdesk.mq.listener;

import com.opsdesk.config.RabbitMQConfig;
import com.opsdesk.mq.event.KnowledgeIndexEvent;
import com.opsdesk.mq.support.ConsumeIdempotencyGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * {@code knowledge.index} 队列消费者（工单 D9-02，规格基线 §13.2 / §13.4 / §13.6）
 *
 * <h2>⚠️ 生产者与真正的业务都在 Day 12</h2>
 * 文档向量化（切分 → embedding → 写 Milvus）是 D12 的 RAG 模块。
 * 本单只保证「消息能进来、幂等生效、失败进 DLQ」这条骨架是通的。
 *
 * <h2>⚠️ 向量化必须做「外部依赖降级」（本批特有约束 6）</h2>
 * D12 接线时注意：Milvus / LLM 不可用时<b>不能把异常直接抛出去</b> ——
 * 那样消息会重试 3 次然后进 DLQ，看起来像「代码 bug」，实际是环境没起。
 * 应先探测可用性，不可用就记 warn 并<b>正常消费掉</b>（或按 §10 的策略降级），
 * 由运维在依赖恢复后手工重投。
 */
@Slf4j
@Component
public class KnowledgeIndexListener {

    private final ConsumeIdempotencyGuard guard;

    public KnowledgeIndexListener(ConsumeIdempotencyGuard guard) {
        this.guard = guard;
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_KNOWLEDGE_INDEX)
    public void onKnowledgeIndex(KnowledgeIndexEvent event, Message message) {
        guard.consume(RabbitMQConfig.QUEUE_KNOWLEDGE_INDEX, message, () ->
                log.info("[MQ 消费] KnowledgeIndexEvent documentId={}", event.documentId()));

        // TODO(D12-05): 文档向量化（切分 + embedding + 写 Milvus），注意上面的降级要求。
        //   本单（D9-03）只留调用点，不跨工单实现 RAG。
    }
}
