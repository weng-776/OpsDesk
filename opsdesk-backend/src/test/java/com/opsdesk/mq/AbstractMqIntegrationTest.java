package com.opsdesk.mq;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * MQ 集成测试基类：<b>显式管理监听器容器的启停</b>（工单 D9-02）
 *
 * <h2>⚠️ 为什么需要这个基类（踩过的坑）</h2>
 * Spring 的测试框架会<b>缓存</b>多个 {@code ApplicationContext}，被缓存的上下文<b>不会关闭</b>。
 * D9-02 起有了消费者，于是「测试 JVM 里同时存在多份 {@code @RabbitListener} 容器」，
 * 而它们监听的是<b>同一批队列</b> —— 同一份队列上出现多个竞争消费者。
 *
 * <p>症状极具迷惑性：<b>单独跑某个类全绿，全量跑就随机红</b>。
 * 例如 {@code RabbitListenerSkeletonTest} 用 {@code @MockitoSpyBean} 让监听器抛异常、
 * 再断言「重试 3 次后进 DLQ」；但消息可能被<b>另一个缓存的上下文</b>里的真实监听器
 * 抢先消费掉（它只打日志、不抛异常），于是 spy 一次都没被调用，
 * 报错是 {@code Wanted but not invoked ... there were zero interactions with this mock}。
 *
 * <h2>做法</h2>
 * <ol>
 *   <li>{@code src/test/resources/application.properties} 里
 *       {@code spring.rabbitmq.listener.simple.auto-startup=false} —— 任何上下文都不自动起容器；</li>
 *   <li>本基类在 {@code @BeforeEach} 启动<b>本上下文</b>的容器、{@code @AfterEach} 停掉。</li>
 * </ol>
 * 于是任意时刻只有一个上下文的容器在跑，竞争消费不复存在。
 *
 * <p>⚠️ 需要「消息被消费掉」的测试类必须继承本类 —— 否则队列上根本没有消费者。
 *
 * <h2>生命周期顺序</h2>
 * JUnit 先跑父类的 {@code @BeforeEach}（先起容器，子类再去等队列排空/挂 Appender），
 * 先跑子类的 {@code @AfterEach}（先摘 Appender，再停容器）。两边都是对的顺序。
 */
@SpringBootTest
public abstract class AbstractMqIntegrationTest {

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @BeforeEach
    void startListenerContainers() {
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            if (!container.isRunning()) {
                // 直接 start()，不受 autoStartup=false 影响
                container.start();
            }
        }
    }

    @AfterEach
    void stopListenerContainers() {
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            if (container.isRunning()) {
                container.stop();
            }
        }
    }
}
