package com.opsdesk.mq.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 生产者侧可靠性回调：Publisher Confirm + Return（工单 D9-04，规格基线 §13.4）
 *
 * <p>yml 里已经配好了 {@code publisher-confirm-type: correlated} / {@code publisher-returns: true}
 * / {@code template.mandatory: true} —— 本类只负责<b>接上回调把失败暴露出来</b>。
 *
 * <pre>
 * ConfirmCallback  ack = false  → 消息压根没到 broker（连接/内部错误）
 * ReturnsCallback  broker 收到了，但**路由不到任何队列**（routing key 写错 / 绑定缺失）
 *                  ⚠️ 这个更危险：不接回调的话消息会被 broker 直接丢弃，且**没有任何报错**
 * </pre>
 *
 * <h2>⚠️ 这两个回调是「发现配置错误」的手段，不是重试机制</h2>
 * 生产者的可靠性最终由 <b>Outbox</b> 保证（§13.5：投递失败 {@code retry_count++}，下一轮重投）。
 * 回调只做<b>可观测性</b>：让「routing key 写错」「broker 拒收」这类问题<b>立刻可见</b>，
 * 而不是等到演示时发现某个队列一直没消息。
 *
 * <h2>⚠️ 为什么用 {@code RabbitTemplateCustomizer} 而不是自己定义 RabbitTemplate Bean</h2>
 * 自己定义会<b>覆盖 Boot 的自动配置</b> —— 那意味着 yml 里的
 * {@code jsonMessageConverter}（含自动补 messageId）、{@code mandatory}、
 * {@code publisher-confirm-type} 等全都要自己重新接一遍，漏一项就是静默降级。
 * {@code RabbitTemplateCustomizer} 由 Boot 在创建 RabbitTemplate 时调用，
 * 只是「在既有配置上再加两笔」，不动其它任何设置。
 *
 * <h2>⚠️ 与 Outbox 的分工：回调负责「可见」，投递成功由「等 confirm」定义</h2>
 * 回调本身不重试 —— 「投递失败 → {@code retry_count++} → 下一轮重投」由 {@code OutboxPublisher} 负责。
 * 但「投递成功」不能只看 {@code convertAndSend} 是否返回（那只表示消息进了本地 channel）：
 * 投递器会传 {@link CorrelationData}（id = {@code event_outbox.message_id}）并等
 * {@link CorrelationData#getFuture()}，拿到 broker <b>ack 且未被退回</b>才置 SENT。
 * 否则 broker 拒收或路由不到时，回调虽已记 error，outbox 仍会置 SENT → 事件静默丢失。
 *
 * <p>好处之一：nack 日志现在能带出具体 messageId（D9-04 当时「拿不到 messageId」的局限已消除）。
 */
@Slf4j
@Configuration
public class RabbitConfirmCallbackConfig {

    /** {@link CorrelationData} 缺失时的占位（消息头里的 messageId 才是权威值） */
    private static final String UNKNOWN = "<无 CorrelationData>";

    @Bean
    public RabbitTemplateCustomizer rabbitConfirmAndReturnCallbacks() {
        return rabbitTemplate -> {
            rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
                if (ack) {
                    log.debug("[MQ Confirm] broker 已确认 messageId={}", correlationId(correlationData));
                }
                else {
                    // 到不了 broker：多半是连接断了 / 交换机不存在
                    log.error("[MQ Confirm] broker **未确认**（消息没到 broker）。messageId={} cause={}",
                            correlationId(correlationData), cause);
                }
            });

            rabbitTemplate.setReturnsCallback(returned -> log.error(
                    "[MQ Return] 消息到了 broker 但**路由不到队列**（routing key 写错或绑定缺失？），"
                            + "broker 已丢弃。exchange={} routingKey={} replyCode={} replyText={} messageId={}",
                    returned.getExchange(), returned.getRoutingKey(),
                    returned.getReplyCode(), returned.getReplyText(),
                    returned.getMessage().getMessageProperties().getMessageId()));
        };
    }

    private String correlationId(CorrelationData correlationData) {
        return correlationData == null || !StringUtils.hasText(correlationData.getId())
                ? UNKNOWN : correlationData.getId();
    }
}
