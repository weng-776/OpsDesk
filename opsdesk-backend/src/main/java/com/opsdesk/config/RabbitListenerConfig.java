package com.opsdesk.config;

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
     * <h2>🔴 为什么必须显式清掉路由键前缀（D9-02 修的真实 bug）</h2>
     * {@link RepublishMessageRecoverer} 默认会给路由键加前缀 <b>{@code "error."}</b>
     * （见其字段 {@code DEFAULT_ERROR_ROUTING_KEY_PREFIX}）。而本项目的 DLQ 绑定用的是
     * <b>原始 Routing Key</b>（{@code RabbitMQConfig#ticketCreatedDlqBinding} 绑的是 {@code ticket.created}），
     * 于是重试耗尽后发出去的路由键是 {@code error.ticket.created} ——
     * <b>匹配不上任何绑定</b>，又因为 {@code spring.rabbitmq.template.mandatory=true}，
     * 这条消息被 broker 退回，最终<b>凭空消失</b>：不进 DLQ、也不报错，
     * 只在日志里留一行 {@code Republishing failed message to exchange 'opsdesk.dlx' with routing key error.ticket.created}。
     *
     * <p>清掉前缀后，recoverer 路径与「broker 原生死信」路径（队列的
     * {@code x-dead-letter-routing-key = 原 Routing Key}）用的是同一个键，
     * 两条路径才真正汇到同一个 DLQ —— 这正是 {@code RabbitMQConfig} 类注释里写的设计意图。
     *
     * <p>⚠️ 既有单测 {@code RabbitListenerConfigTest} 只断言了「调用了
     * {@code send(opsdesk.dlx, ?, ?)}」，没断言路由键，所以这个 bug 从 Day 1 一直没暴露。
     * 现在那里补了 {@code eq(原始 Routing Key)} 的断言，改回去就会红。
     *
     * <p>⚠️ 边界：若消息的 {@code receivedRoutingKey} 为空（只会出现在
     * 手工构造消息的测试里，正常从交换机进来的消息一定有），清理后仍是空 →
     * 无法路由。生产路径不受影响。
     */
    private RepublishMessageRecoverer recoverer(RabbitTemplate rabbitTemplate) {
        return new RepublishMessageRecoverer(rabbitTemplate, RabbitMQConfig.DLX)
                // 空前缀 = 沿用消息原始 Routing Key（见方法注释）
                .errorRoutingKeyPrefix("");
    }
}
