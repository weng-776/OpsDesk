package com.opsdesk.config;

import com.opsdesk.mq.support.OpsDeskMessageRecoverer;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消费者容器的可靠性配置：<b>重试 3 次 → 仍失败则投递死信队列</b>（规格基线 §13.4）
 *
 * <p>整体链路：
 * <pre>
 * 消息投递 → 监听器抛异常
 *     ↓ 第 1 次重试（等 1s）
 *   第 2 次尝试 → 仍失败
 *     ↓ 第 2 次重试（等 2s）
 *   第 3 次尝试 → 仍失败
 *     ↓ 重试次数耗尽
 *   RepublishMessageRecoverer 把消息 republish 到 opsdesk.dlx
 *     ↓ 按原始 Routing Key 路由
 *   opsdesk.xxx.dlq（管理员页面可查看 + 重新投递，§21.12）
 * </pre>
 *
 * <p><b>为什么这段必须写在代码里、而不是 application.yml</b>：
 * yml 里的 {@code spring.rabbitmq.listener.simple.retry.*} 只能配「重试几次、退避多久」，
 * <b>配不了「重试耗尽后去哪」</b> —— 超过重试次数后走哪个 recoverer 是容器工厂的 AdviceChain 决定的。
 * 不显式设置的话，Spring 会用默认的 {@code RejectAndDontRequeueRecoverer}（直接 nack 丢弃），
 * 消息就凭空消失了，不会进死信队列。
 *
 * <p><b>与 ACK 模式的关系（关键）</b>：
 * 本方案依赖 {@code spring.rabbitmq.listener.simple.acknowledge-mode=auto}。
 * 原因：recoverer 成功 republish 后异常不再向上抛，容器认为「消费成功」才会替我们 ack；
 * 若用 {@code manual}，没人调用 basicAck，原消息会永久停在 unacked 状态，重启才被重投。
 */
@Configuration
public class RabbitListenerConfig {

    /** 重试次数：1 次原始 + 2 次重试 = 共 3 次尝试（与规格基线 §13.4 的 max-attempts=3 对齐） */
    private static final int MAX_ATTEMPTS = 3;
    /** 首次重试前等待 1s */
    private static final long BACKOFF_INITIAL_MS = 1000L;
    /** 退避倍数：1s → 2s → 4s ... */
    private static final double BACKOFF_MULTIPLIER = 2.0;
    /** 退避上限 10s（§13.4 要求 max-interval=10s） */
    private static final long BACKOFF_MAX_MS = 10000L;

    /**
     * 覆盖 Boot 默认的 rabbitListenerContainerFactory。
     *
     * <p>先调 {@code configurer.configure(...)} 把 yml 里的
     * acknowledge-mode / prefetch / default-requeue-rejected 等全部继承过来，
     * 再追加我们的重试 + 死信 AdviceChain —— 这样 yml 与代码各管一段，不互相覆盖。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            RabbitTemplate rabbitTemplate) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();

        // ① 继承 application.yml 的监听器配置
        configurer.configure(factory, connectionFactory);

        // ② 追加：3 次重试（带退避）→ 耗尽后 republish 到死信交换机
        factory.setAdviceChain(
                RetryInterceptorBuilder.stateless()
                        .maxAttempts(MAX_ATTEMPTS)
                        .backOffOptions(BACKOFF_INITIAL_MS, BACKOFF_MULTIPLIER, BACKOFF_MAX_MS)
                        // 只传 exchange，routingKey 传 null → 沿用消息的原始 Routing Key，
                        // 因此一个 recoverer 就能服务全部队列（绑定关系见 RabbitMQConfig）
                        .recoverer(recoverer(rabbitTemplate))
                        .build()
        );

        return factory;
    }

    /**
     * 死信 recoverer：重试耗尽后把消息 republish 到 {@code opsdesk.dlx}。
     *
     * <p>用本项目的 {@link OpsDeskMessageRecoverer}（D9-04 起）而不是裸的
     * {@code RepublishMessageRecoverer} —— 前者在 republish 时多补一个
     * {@code x-retry-count} 头，供 D9-05 的死信管理接口读（§13.4 约束 4）。
     *
     * <h2>🔴 它同时保留了「清掉 error. 前缀」这个修复（D9-02 发现的真 bug）</h2>
     * {@link RepublishMessageRecoverer} 默认给路由键加前缀 <b>{@code "error."}</b>
     * （见其字段 {@code DEFAULT_ERROR_ROUTING_KEY_PREFIX}）。而本项目的 DLQ 绑定用的是
     * <b>原始 Routing Key</b>（{@code RabbitMQConfig#ticketCreatedDlqBinding} 绑的是 {@code ticket.created}），
     * 于是重试耗尽后发出去的路由键是 {@code error.ticket.created} ——
     * <b>匹配不上任何绑定</b>，又因为 {@code spring.rabbitmq.template.mandatory=true}，
     * 这条消息被 broker 退回，最终<b>凭空消失</b>：不进 DLQ、也不报错。
     *
     * <p>⚠️ 既有单测 {@code RabbitListenerConfigTest} 已补上「路由键必须等于原 Routing Key」与
     * 「必须带 {@code x-retry-count}」两条断言，改坏任意一条都会红。
     */
    private RepublishMessageRecoverer recoverer(RabbitTemplate rabbitTemplate) {
        // maxAttempts 直接复用本类的常量：重试次数与写进消息头的值必须是同一个来源
        return new OpsDeskMessageRecoverer(rabbitTemplate, RabbitMQConfig.DLX, MAX_ATTEMPTS);
    }
}
