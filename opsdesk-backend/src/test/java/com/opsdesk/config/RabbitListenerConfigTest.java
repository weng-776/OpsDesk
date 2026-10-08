package com.opsdesk.config;

import com.rabbitmq.client.Channel;
import org.aopalliance.aop.Advice;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 不依赖 RabbitMQ 的验证：证明「重试 3 次后，消息被投递到 opsdesk.dlx」。
 *
 * <p>手法：把 {@link RabbitListenerConfig} 装配出来的 AdviceChain 挂到一个
 * {@link ProxyFactory} 代理上，让目标方法必然抛异常，然后断言
 * ① 方法被调用了正好 3 次；② RabbitTemplate 被要求发往 opsdesk.dlx。
 *
 * <p><b>目标方法签名必须与 Spring AMQP 内部代理的方法一致</b>：
 * {@code AbstractMessageListenerContainer.ContainerDelegate#invokeListener(Channel, Object)}。
 * 这一点很关键 —— Spring AMQP 的 {@code StatelessRetryOperationsInterceptorFactoryBean#recover}
 * 是从 <b>{@code args[1]}</b> 取消息的，也就是说
 * <b>args[0] 是 Channel、args[1] 才是 Message</b>。
 * 若签名写成常见的 {@code onMessage(Message, Channel)}（args[1]=Channel），
 * recoverer 会因 {@code instanceof Message} 不成立而被静默跳过，
 * 消息被 ack 掉直接丢失 —— 所以这里刻意对齐真实签名。
 */
class RabbitListenerConfigTest {

    /** 对齐 Spring AMQP 内部 ContainerDelegate#invokeListener(Channel, Object) */
    interface ContainerDelegate {
        void invokeListener(Channel channel, Object data) throws Exception;
    }

    @Test
    void 重试3次后投递死信交换机() throws Exception {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        SimpleRabbitListenerContainerFactoryConfigurer configurer =
                mock(SimpleRabbitListenerContainerFactoryConfigurer.class);
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);

        // 用被测配置类真实装配一次容器工厂
        SimpleRabbitListenerContainerFactory factory =
                new RabbitListenerConfig().rabbitListenerContainerFactory(
                        configurer, connectionFactory, rabbitTemplate);

        Advice[] adviceChain = factory.getAdviceChain();
        assertThat(adviceChain).as("AdviceChain 应恰好含 1 个重试拦截器").hasSize(1);

        // 目标方法：每次调用都计数并抛异常
        AtomicInteger attempts = new AtomicInteger();
        Message message = new Message("payload".getBytes(), new MessageProperties());
        Channel channel = mock(Channel.class);

        ContainerDelegate target = (ch, data) -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("模拟消费失败");
        };

        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(adviceChain[0]);
        ContainerDelegate proxy = (ContainerDelegate) proxyFactory.getProxy();

        try {
            // 参数顺序对齐真实代理：channel 在前、message 在后
            proxy.invokeListener(channel, message);
        } catch (Exception ignored) {
            // recoverer 正常兜底后不应再抛；这里吞掉只为拿到断言结果
        }
        System.out.println("[实测] 目标方法被调用次数 = " + attempts.get());
        assertThat(attempts.get()).as("原始 1 次 + 重试 2 次 = 3 次").isEqualTo(3);
        // 必须给匹配器显式类型：RabbitTemplate 有 send(String,String,Message)
        // 与 send(String,Message,CorrelationData) 两个 3 参重载，裸 any() 会歧义
        verify(rabbitTemplate).send(eq(RabbitMQConfig.DLX), (String) any(), (Message) any());
    }
}
