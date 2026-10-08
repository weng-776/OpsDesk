package com.opsdesk.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑声明（规格基线 §13.2）
 *
 * <pre>
 * opsdesk.topic (TopicExchange, durable)
 *   ├─ opsdesk.ticket.created.queue        ← ticket.created
 *   ├─ opsdesk.ticket.assigned.queue       ← ticket.assigned
 *   ├─ opsdesk.ticket.status.queue         ← ticket.status.changed
 *   └─ opsdesk.knowledge.index.queue       ← knowledge.index
 *
 * opsdesk.dlx (DirectExchange, durable)   ← 各业务队列的死信都进这里
 *   ├─ opsdesk.ticket.created.dlq
 *   ├─ opsdesk.ticket.assigned.dlq
 *   ├─ opsdesk.ticket.status.dlq
 *   └─ opsdesk.knowledge.index.dlq
 * </pre>
 *
 * <b>关于死信 Routing Key 的约定（重要）</b>
 * <p>业务队列用 {@code x-dead-letter-routing-key = 原 Routing Key}，{@code opsdesk.dlx} 也按
 * <b>原 Routing Key</b> 绑定到对应 DLQ。这样做是为了让「一条 recoverer 配置通吃所有队列」：
 * {@code RepublishMessageRecoverer} 在 routingKey 传 null 时会沿用消息的原始 Routing Key，
 * 因此只需要一个容器工厂，不必给每个队列单独配 recoverer。
 *
 * <p>死信有两条到达路径，都落到同一个 DLQ：
 * <ol>

 *   <li><b>兜底</b>：消息在进入监听器之前就被拒（如反序列化失败被
 *       {@code ConditionalRejectingErrorHandler} 拒绝）→ broker 按队列的
 *       {@code x-dead-letter-*} 参数原生死信</li>
 * </ol>
 */
@Configuration
public class RabbitMQConfig {

    // ==================== 交换机 ====================
    /** 业务主交换机 */
    public static final String EXCHANGE = "opsdesk.topic";
    /** 死信交换机 */
    public static final String DLX = "opsdesk.dlx";

    // ==================== Routing Key ====================
    public static final String RK_TICKET_CREATED        = "ticket.created";
    public static final String RK_TICKET_ASSIGNED       = "ticket.assigned";
    public static final String RK_TICKET_STATUS_CHANGED = "ticket.status.changed";
    public static final String RK_KNOWLEDGE_INDEX       = "knowledge.index";

    // ==================== 业务队列 ====================
    public static final String QUEUE_TICKET_CREATED  = "opsdesk.ticket.created.queue";
    public static final String QUEUE_TICKET_ASSIGNED = "opsdesk.ticket.assigned.queue";
    public static final String QUEUE_TICKET_STATUS   = "opsdesk.ticket.status.queue";
    public static final String QUEUE_KNOWLEDGE_INDEX = "opsdesk.knowledge.index.queue";

    // ==================== 死信队列 ====================
    public static final String DLQ_TICKET_CREATED  = "opsdesk.ticket.created.dlq";
    public static final String DLQ_TICKET_ASSIGNED = "opsdesk.ticket.assigned.dlq";
    public static final String DLQ_TICKET_STATUS   = "opsdesk.ticket.status.dlq";
    public static final String DLQ_KNOWLEDGE_INDEX = "opsdesk.knowledge.index.dlq";

    // ==================================================================
    //  交换机
    // ==================================================================

    @Bean
    public TopicExchange opsdeskTopicExchange() {
        // durable = true, autoDelete = false
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange opsdeskDlxExchange() {
        return new DirectExchange(DLX, true, false);
    }

    // ==================================================================
    //  业务队列（带原生死信参数，作为第二道兜底）
    // ==================================================================

    /** 统一构造业务队列：durable + 死信路由到 opsdesk.dlx */
    private static Queue businessQueue(String queueName, String routingKey) {
        return QueueBuilder.durable(queueName)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(routingKey)
                .build();
    }

    @Bean
    public Queue ticketCreatedQueue() {
        return businessQueue(QUEUE_TICKET_CREATED, RK_TICKET_CREATED);
    }

    @Bean
    public Queue ticketAssignedQueue() {
        return businessQueue(QUEUE_TICKET_ASSIGNED, RK_TICKET_ASSIGNED);
    }

    @Bean
    public Queue ticketStatusQueue() {
        return businessQueue(QUEUE_TICKET_STATUS, RK_TICKET_STATUS_CHANGED);
    }

    @Bean
    public Queue knowledgeIndexQueue() {
        return businessQueue(QUEUE_KNOWLEDGE_INDEX, RK_KNOWLEDGE_INDEX);
    }

    // ==================================================================
    //  死信队列
    // ==================================================================

    @Bean
    public Queue ticketCreatedDlq() {
        return QueueBuilder.durable(DLQ_TICKET_CREATED).build();
    }

    @Bean
    public Queue ticketAssignedDlq() {
        return QueueBuilder.durable(DLQ_TICKET_ASSIGNED).build();
    }

    @Bean
    public Queue ticketStatusDlq() {
        return QueueBuilder.durable(DLQ_TICKET_STATUS).build();
    }

    @Bean
    public Queue knowledgeIndexDlq() {
        return QueueBuilder.durable(DLQ_KNOWLEDGE_INDEX).build();
    }

    // ==================================================================
    //  绑定：业务队列 → opsdesk.topic
    // ==================================================================

    @Bean
    public Binding ticketCreatedBinding(@Qualifier("ticketCreatedQueue") Queue queue,
                                       TopicExchange opsdeskTopicExchange) {
        return BindingBuilder.bind(queue).to(opsdeskTopicExchange).with(RK_TICKET_CREATED);
    }

    @Bean
    public Binding ticketAssignedBinding(@Qualifier("ticketAssignedQueue") Queue queue,
                                        TopicExchange opsdeskTopicExchange) {
        return BindingBuilder.bind(queue).to(opsdeskTopicExchange).with(RK_TICKET_ASSIGNED);
    }

    @Bean
    public Binding ticketStatusBinding(@Qualifier("ticketStatusQueue") Queue queue,
                                      TopicExchange opsdeskTopicExchange) {
        return BindingBuilder.bind(queue).to(opsdeskTopicExchange).with(RK_TICKET_STATUS_CHANGED);
    }

    @Bean
    public Binding knowledgeIndexBinding(@Qualifier("knowledgeIndexQueue") Queue queue,
                                        TopicExchange opsdeskTopicExchange) {
        return BindingBuilder.bind(queue).to(opsdeskTopicExchange).with(RK_KNOWLEDGE_INDEX);
    }

    // ==================================================================
    //  绑定：DLQ → opsdesk.dlx（Routing Key = 原业务 Routing Key）
    // ==================================================================

    @Bean
    public Binding ticketCreatedDlqBinding(@Qualifier("ticketCreatedDlq") Queue dlq,
                                          DirectExchange opsdeskDlxExchange) {
        return BindingBuilder.bind(dlq).to(opsdeskDlxExchange).with(RK_TICKET_CREATED);
    }

    @Bean
    public Binding ticketAssignedDlqBinding(@Qualifier("ticketAssignedDlq") Queue dlq,
                                           DirectExchange opsdeskDlxExchange) {
        return BindingBuilder.bind(dlq).to(opsdeskDlxExchange).with(RK_TICKET_ASSIGNED);
    }

    @Bean
    public Binding ticketStatusDlqBinding(@Qualifier("ticketStatusDlq") Queue dlq,
                                         DirectExchange opsdeskDlxExchange) {
        return BindingBuilder.bind(dlq).to(opsdeskDlxExchange).with(RK_TICKET_STATUS_CHANGED);
    }

    @Bean
    public Binding knowledgeIndexDlqBinding(@Qualifier("knowledgeIndexDlq") Queue dlq,
                                           DirectExchange opsdeskDlxExchange) {
        return BindingBuilder.bind(dlq).to(opsdeskDlxExchange).with(RK_KNOWLEDGE_INDEX);
    }

    // ==================================================================
    //  消息序列化：JSON + 自动生成 messageId（§13.6 消费幂等靠它）
    // ==================================================================

    @Bean
    public MessageConverter jsonMessageConverter() {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter();
        // 生产者未显式设置 messageId 时自动补一个 UUID，
        // 消费者用 Redis SETNX mq:consumed:{consumer}:{messageId} 做幂等去重
        converter.setCreateMessageIds(true);
        return converter;
    }
}
