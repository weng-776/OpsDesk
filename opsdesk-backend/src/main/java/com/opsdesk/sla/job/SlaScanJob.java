package com.opsdesk.sla.job;

import com.opsdesk.sla.service.SlaScanService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SLA 定时扫描任务（工单 D5-03，规格基线 §9.5）
 *
 * <p>本类<b>只做触发</b>：一行调用 {@link SlaScanService#scan()}，加异常兜底。
 * 所有逻辑（锁、取数、判定、回写、幂等）都在 service 里 ——
 * 这样扫描行为可以被测试直接调用验证，不依赖调度器。
 *
 * <h2>⚠️ 为什么是 {@code fixedDelay} 而不是 {@code fixedRate}</h2>
 * §9.5 要求「每 5 分钟扫描一次」。{@code fixedRate} 按<b>固定频率</b>触发 ——
 * 若某一轮扫描超过 5 分钟，下一轮会立刻叠上来（Spring 默认单线程会排队，
 * 配了线程池则会并发跑同一份数据）。{@code fixedDelay} 是「上一轮<b>结束</b>后隔 5 分钟」，
 * 天然不会重叠，更符合批处理的语义。
 *
 * <h2>⚠️ 为什么要有 {@code @ConditionalOnProperty}</h2>
 * 定时任务在<b>测试 JVM</b> 里也会跑起来，而它走自己的事务、<b>不回滚</b> ——
 * 会把种子工单的 {@code sla_resolution_state} 从 NORMAL/WARNING 改成 BREACHED
 * （种子 deadline 是 2026-10-06，跑测试时早已过期），打红既有断言。
 * 所以由 {@code src/test/resources/application.properties} 把
 * {@code opsdesk.sla.scan.enabled} 设为 {@code false}。
 *
 * <p>{@code matchIfMissing = true}：生产环境不配这个键就是<b>开启</b>的
 * （定时扫描是本项目 SLA 能力的一部分，不该默认关着）。要临时停掉，
 * 设环境变量 {@code OPSDESK_SLA_SCAN_ENABLED=false} 即可。
 *
 * <p>{@code @EnableScheduling} 在 {@code OpsDeskApplication} 上。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "opsdesk.sla.scan.enabled", havingValue = "true", matchIfMissing = true)
public class SlaScanJob {

    /** 扫描间隔：5 分钟（§9.5） */
    private static final long INTERVAL_MS = 5 * 60 * 1000L;

    private final SlaScanService slaScanService;

    public SlaScanJob(SlaScanService slaScanService) {
        this.slaScanService = slaScanService;
    }

    /**
     * 每 5 分钟扫一轮。
     *
     * <p>异常在这里兜住：定时任务抛异常只会被 Spring 记日志，不会中断后续调度，
     * 但显式 catch 能让日志里有一条明确的「本轮失败」记录，便于排查。
     */
    @Scheduled(fixedDelay = INTERVAL_MS)
    public void scan() {
        try {
            slaScanService.scan();
        }
        catch (Exception ex) {
            log.error("[SLA 扫描] 本轮执行异常（下一轮 5 分钟后照常执行）", ex);
        }
    }
}
