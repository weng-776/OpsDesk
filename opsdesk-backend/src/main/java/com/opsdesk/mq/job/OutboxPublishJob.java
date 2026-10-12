package com.opsdesk.mq.job;

import com.opsdesk.mq.support.OutboxPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 定时投递任务（工单 D9-01，规格基线 §13.5）
 *
 * <p>本类<b>只做触发</b>：一行调用 {@link OutboxPublisher#publishPending()}，加异常兜底。
 * 所有逻辑（认领、投递、回写、重试）都在 publisher 里 ——
 * 这样投递行为可以被测试直接调用验证，不依赖调度器（与 D5-03 的 {@code SlaScanJob} 同一写法）。
 *
 * <h2>⚠️ 为什么是 {@code fixedDelay} 而不是 {@code fixedRate}</h2>
 * {@code fixedDelay} 是「上一轮<b>结束</b>后隔 10 秒」，天然不会重叠。
 * 若用 {@code fixedRate}，某一轮因 broker 抖动变慢时下一轮会立刻叠上来 ——
 * 虽然 {@code FOR UPDATE SKIP LOCKED} 能保证不重复投递，但会让事务互相等待、日志难读。
 *
 * <h2>⚠️ 为什么要有 {@code @ConditionalOnProperty}</h2>
 * 定时任务在<b>测试 JVM</b> 里也会跑起来，而它走自己的事务、<b>不回滚</b> ——
 * 会把测试造出来的 PENDING 记录投出去并改成 SENT，打红「PENDING → SENT」这类精确断言。
 * 所以由 {@code src/test/resources/application.properties} 把
 * {@code opsdesk.outbox.publish.enabled} 设为 {@code false}；
 * 投递逻辑由 {@code OutboxPublisherTest} 直接调 {@code publishPending()} 验证。
 *
 * <p>{@code matchIfMissing = true}：生产环境不配这个键就是<b>开启</b>的
 * （Outbox 不投递 = 事件永远发不出去）。要临时停掉，设环境变量
 * {@code OPSDESK_OUTBOX_PUBLISH_ENABLED=false} 即可。
 *
 * <p>{@code @EnableScheduling} 已在 D5-03 加在 {@code OpsDeskApplication} 上 ——
 * <b>本单不要重复加</b>。只加 {@code @Scheduled} 而不开 {@code @EnableScheduling}
 * 是「编译通过 + 静默不触发」，验收 4 要盯的就是这一点。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "opsdesk.outbox.publish.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublishJob {

    /** 投递间隔：10 秒（§13.5 未规定间隔；比 SLA 的 5 分钟短，事件要尽快发出去） */
    private static final long INTERVAL_MS = 10 * 1000L;

    private final OutboxPublisher outboxPublisher;

    public OutboxPublishJob(OutboxPublisher outboxPublisher) {
        this.outboxPublisher = outboxPublisher;
    }

    /**
     * 每 10 秒投递一批。
     *
     * <p>异常在这里兜住：定时任务抛异常只会被 Spring 记日志、不会中断后续调度，
     * 但显式 catch 能留下一条明确的「本轮失败」记录，便于排查。
     */
    @Scheduled(fixedDelay = INTERVAL_MS)
    public void publish() {
        try {
            outboxPublisher.publishPending();
        }
        catch (Exception ex) {
            log.error("[Outbox 投递] 本轮执行异常（下一轮 10 秒后照常执行）", ex);
        }
    }
}
