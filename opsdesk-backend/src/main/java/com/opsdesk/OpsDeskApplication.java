package com.opsdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * OpsDesk 启动类
 *
 * <p>{@code @EnableScheduling} 是工单 D5-03（§9.5 SLA 定时扫描）的必要条件 ——
 * 没有它 {@code @Scheduled} 只是个注解、不会触发。整个项目只有
 * {@code sla/job/SlaScanJob} 一个定时任务，所以开在这里（全局开关）。
 *
 * <p>⚠️ 定时任务会在测试 JVM 里也跑起来，而它<b>走自己的事务、不回滚</b>，
 * 会把种子工单的 SLA 状态改掉 → 打红既有断言。
 * 所以 {@code SlaScanJob} 上有 {@code @ConditionalOnProperty}，
 * 并由 {@code src/test/resources/application.properties} 在测试里关掉。
 */
@SpringBootApplication
@EnableScheduling
public class OpsDeskApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsDeskApplication.class, args);
    }

}
