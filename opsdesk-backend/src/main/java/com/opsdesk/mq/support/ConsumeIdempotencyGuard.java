package com.opsdesk.mq.support;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.UUID;

/**
 * 消费幂等守卫 + 每条消息的执行外壳（工单 D9-02，规格基线 §13.6 / §23.3 / §23.6）
 *
 * <pre>
 * ① 从消息头取 traceId → MDC.put（没有就自己生成一个）
 * ② SETNX mq:consumed:{consumerName}:{messageId}  TTL 24h
 *     占位失败 → 已消费过 → 直接 return（容器 auto ACK）
 *     占位成功 → 执行业务
 * ③ 业务抛异常 → <b>先删除占位再抛出</b>（否则重试会被自己的占位挡掉）
 * ④ finally → MDC.clear()
 * </pre>
 *
 * <h2>⚠️ 顺序之争：先占位还是先消费？——本类选「先占位、异常时释放」</h2>
 * <table border="1">
 *   <tr><th>方案</th><th>问题</th></tr>
 *   <tr>
 *     <td><b>先消费、成功后占位</b></td>
 *     <td>并发重复投递时两个消费者都读到「没占位」→ <b>各执行一次</b>，幂等失效。
 *         §13.6 的 {@code SETNX} 语义本就是「抢到的才执行」。
 *         另外还有「业务成功但占位写入失败」的窗口 → 下次重投再执行一遍。</td>
 *   </tr>
 *   <tr>
 *     <td><b>先占位、失败不释放</b></td>
 *     <td>业务抛异常后占位还在 → 框架重试时被幂等挡掉 → <b>业务永远不会成功</b>，
 *         而消息最终会被 ack（重试耗尽后进 DLQ 时业务其实一次都没跑成）。</td>
 *   </tr>
 *   <tr>
 *     <td><b>先占位、失败释放（本类）</b> ✅</td>
 *     <td>第 1 次：SETNX 成功 → 业务异常 → <b>删占位</b> → 抛异常；框架重试第 2 次：
 *         SETNX 又能成功 → <b>真正重跑</b>。与创建工单的
 *         {@code IdempotencyGuard}（reserve → 失败 release）同一范式。</td>
 *   </tr>
 * </table>
 *
 * <h2>⚠️ 释放必须在异常抛出之前</h2>
 * {@code catch} 里先 {@link #release} 再 {@code throw}。若写成 {@code finally} 里释放，
 * 业务<b>成功</b>的那次也会把占位删掉 —— 幂等直接失效（下次重投会再执行一遍）。
 * 「失败才释放」是这段代码唯一不能写错的地方。
 *
 * <h2>⚠️ 为什么把 traceId 也放在这个类里</h2>
 * 幂等与 traceId 都是「每条消息的执行外壳」，且<b>顺序敏感</b>（traceId 要在业务日志之前进 MDC、
 * MDC 要在 finally 清）。拆成两个类让 4 个监听器各自拼装，等于把「顺序」复制 4 份 ——
 * 错一处就是静默的日志串线或幂等失效。收口在这里，4 个监听器各只剩一行调用。
 *
 * <h2>⚠️ MDC 的清理责任</h2>
 * HTTP 链路归 {@code TraceIdFilter} 独占（它在 finally 里 {@code MDC.clear()}）；
 * <b>MQ 链路归消费者自己</b> —— 消费者跑在容器线程上，与请求线程无关，
 * 不清理会把 traceId 泄漏给<b>同一个容器线程上的下一条消息</b>（{@code prefetch=1} 时线程会复用）。
 *
 * <h2>⚠️ 没有 messageId 时怎么办：fail-open</h2>
 * 记 warn 后<b>照常消费</b>。D9-01 的投递器一定会带 messageId，没有 messageId 说明生产者不规范；
 * 此时「丢一条业务事件」比「可能重复消费」更糟 —— 重复消费可以在业务侧用唯一约束兜，
 * 而丢事件没有任何兜底。
 */
@Slf4j
@Component
public class ConsumeIdempotencyGuard {

    /** 幂等键前缀（§13.6 / §23.3：{@code mq:consumed:{consumerName}:{messageId}}） */
    public static final String KEY_PREFIX = "mq:consumed:";

    /** 幂等记录 TTL（§13.6：24 小时） */
    public static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    public ConsumeIdempotencyGuard(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 在「幂等 + traceId」外壳里执行业务。
     *
     * @param consumerName 消费者名（幂等键前缀的一部分）。⚠️ 4 个消费者<b>必须互不相同</b> ——
     *                     共用一个前缀的话，A 队列消费过的 messageId 会把 B 队列的同 id 消息挡掉
     * @param message      原始消息（取 messageId 与 traceId 头）
     * @param business     业务体；抛出的异常会原样向上抛给容器（由容器的重试/死信链处理）
     */
    public void consume(String consumerName, Message message, Runnable business) {
        String traceId = bindTraceId(message);
        try {
            String messageId = messageIdOf(message);
            if (messageId == null) {
                log.warn("[MQ 消费] 消息没有 messageId，无法幂等去重，按首次消费处理。consumer={} traceId={}",
                        consumerName, traceId);
                business.run();
                return;
            }

            String key = redisKey(consumerName, messageId);
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", TTL);
            if (!Boolean.TRUE.equals(acquired)) {
                // §13.6：占位失败 = 已消费过 → 直接 return，容器 auto ACK
                log.info("[MQ 消费] 已消费，跳过。consumer={} messageId={} traceId={}",
                        consumerName, messageId, traceId);
                return;
            }

            try {
                business.run();
            }
            catch (RuntimeException | Error ex) {
                // ⚠️ 必须在异常抛出之前释放：不释放的话框架重试会被自己的占位挡掉，业务永远跑不成。
                //    ⚠️ 也不能放 finally —— 成功的那次会把占位删掉，幂等就失效了。
                release(key, consumerName, messageId);
                throw ex;
            }

            log.debug("[MQ 消费] 处理完成。consumer={} messageId={}", consumerName, messageId);
        }
        finally {
            // MQ 链路的 MDC 归消费者自己（HTTP 链路归 TraceIdFilter）；不清理会污染下一条消息
            MDC.clear();
        }
    }

    // ==================== 私有 ====================

    /**
     * 把消息头里的 traceId 放进 MDC，返回最终使用的值。
     *
     * <p>消息头名与 D9-01 的投递器共用同一个常量（{@link OutboxPublisher#HEADER_TRACE_ID}），
     * 生产端与消费端不会各写一套命名。
     *
     * <p>头里没有（非本项目的生产者，或老的投递器）就<b>自己生成一个</b> ——
     * 宁可串不上上游，也不要这条消息的日志完全没有 traceId。
     */
    private String bindTraceId(Message message) {
        Object header = message.getMessageProperties() == null
                ? null : message.getMessageProperties().getHeader(OutboxPublisher.HEADER_TRACE_ID);

        String traceId = header == null ? null : header.toString();
        if (!StringUtils.hasText(traceId)) {
            traceId = UUID.randomUUID().toString().replace("-", "");
            log.debug("[MQ 消费] 消息头没有 traceId，本条自生成 traceId={}", traceId);
        }
        MDC.put(OutboxPublisher.HEADER_TRACE_ID, traceId);
        return traceId;
    }

    /** 取 messageId（消费端幂等键）；没有返回 {@code null} */
    private String messageIdOf(Message message) {
        if (message.getMessageProperties() == null) {
            return null;
        }
        String messageId = message.getMessageProperties().getMessageId();
        return StringUtils.hasText(messageId) ? messageId : null;
    }

    /** 删占位键；失败只记 warn（key 会自然过期，最坏是这条消息在 TTL 内重投被挡） */
    private void release(String key, String consumerName, String messageId) {
        try {
            redisTemplate.delete(key);
            log.debug("[MQ 消费] 业务失败，已释放幂等占位，等待框架重试。consumer={} messageId={}",
                    consumerName, messageId);
        }
        catch (Exception ex) {
            log.warn("[MQ 消费] 释放幂等占位失败（该 key 会自然过期，本次重试可能被幂等挡掉）。"
                    + "consumer={} messageId={}", consumerName, messageId, ex);
        }
    }

    private String redisKey(String consumerName, String messageId) {
        return KEY_PREFIX + consumerName + ":" + messageId;
    }
}
