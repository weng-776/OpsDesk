package com.opsdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * OpsDesk 启动类
 *
 * <h2>{@code @EnableScheduling}（工单 D5-03）</h2>
 * §9.5 的 SLA 定时扫描需要它 —— 没有它 {@code @Scheduled} 只是个注解、不会触发。
 * 整个项目只有 {@code sla/job/SlaScanJob} 一个定时任务，所以开在这里（全局开关）。
 *
 * <p>⚠️ 定时任务会在测试 JVM 里也跑起来，而它<b>走自己的事务、不回滚</b>，
 * 会把种子工单的 SLA 状态改掉 → 打红既有断言。
 * 所以 {@code SlaScanJob} 上有 {@code @ConditionalOnProperty}，
 * 并由 {@code src/test/resources/application.properties} 在测试里关掉。
 *
 * <h2>⚠️ {@code @EnableTransactionManagement(order = 0)}（工单 D6-01，<b>不是可有可无</b>）</h2>
 * Spring 的 {@code TransactionInterceptor} 默认 order 是 {@code Ordered.LOWEST_PRECEDENCE}
 * （最低优先级 = 最内层），而自写的 {@code @Aspect} 默认也是 {@code LOWEST_PRECEDENCE} ——
 * <b>两者的相对顺序是未定义的</b>。
 *
 * <p>审计切面（{@code AuditLogAspect}）<b>必须跑在事务里面</b>：§14.3 要求
 * 「{@code before_data} / {@code after_data} 快照 + 写入审计」与业务<b>同事务同步写</b>。
 * 若切面落到事务外面，审计 insert 就成了独立提交 —— 业务回滚时审计会残留，
 * 而且失败的操作会留下一个「看起来成功了」的快照。
 *
 * <p>所以这里把事务显式提到最外层（{@code order = 0}），并让切面用
 * {@code @Order(Ordered.LOWEST_PRECEDENCE)} 待在它里面。
 *
 * <p>注：显式声明 {@code @EnableTransactionManagement} 会让 Spring Boot 的
 * {@code TransactionAutoConfiguration} 自动配置退让（它是 {@code @ConditionalOnMissingBean}），
 * 其余属性保持默认，行为与之前一致 —— 只多了「事务在最外层」这一条保证。
 */
@SpringBootApplication
@EnableScheduling
@EnableTransactionManagement(order = 0)
public class OpsDeskApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsDeskApplication.class, args);
    }

}
