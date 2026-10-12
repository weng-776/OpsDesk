package com.opsdesk.mq.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.opsdesk.config.RabbitMQConfig;

import java.io.Serializable;

/**
 * 知识文档待向量化（规格基线 §13.3）→ 消费方：文档向量化
 *
 * <h2>⚠️ 为什么只带 {@code documentId}</h2>
 * 向量化的输入是「文档正文 + 元数据」，那些都在库里 —— 消费方按 id 查即可。
 * 把正文塞进消息会让 payload 变成几百 KB（{@code event_outbox.payload} 是 {@code TEXT}，
 * 且 §23.1 附件上限 10MB），既不划算也没必要。
 *
 * <p>生产者是 D12 的知识库模块（本单不写生产端）。
 *
 * @param documentId 知识文档 ID
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KnowledgeIndexEvent(Long documentId) implements Serializable {

    /** 事件类型名，写 {@code event_outbox.event_type} */
    public static final String EVENT_TYPE = "KnowledgeIndexEvent";

    /** MQ 路由键（复用拓扑常量，不写裸字符串） */
    public static final String ROUTING_KEY = RabbitMQConfig.RK_KNOWLEDGE_INDEX;
}
