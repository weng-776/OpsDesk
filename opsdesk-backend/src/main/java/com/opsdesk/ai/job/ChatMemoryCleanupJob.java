package com.opsdesk.ai.job;

import com.opsdesk.ai.support.ChatMemoryCleanup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 会话记忆清理的调度（2026-10-12 补，D11-02 遗留项）
 *
 * <h2>本类只做调度</h2>
 * 真正的清理逻辑在 {@link ChatMemoryCleanup}（可以脱离调度单独测试）。
 * 与 {@code SlaScanJob} / {@code OutboxPublishJob} 同一模板：
 * <ul>
 *   <li>{@code @ConditionalOnProperty} 留一个开关，出问题时能不改代码就关掉；</li>
 *   <li>整个任务体 try-catch 包住 —— 一轮失败不能让调度线程挂掉（否则以后再也不会执行）。</li>
 * </ul>
 *
 * <p>{@code @EnableScheduling} 已在 {@code OpsDeskApplication} 上（D5-03），本类不重复加。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "opsdesk.ai.chat.memory-cleanup.enabled", havingValue = "true",
        matchIfMissing = true)
public class ChatMemoryCleanupJob {

    /**
     * 每天 03:30 跑一次。
     *
     * <p>选这个时间是因为：① 业务最闲，删数据的影响最小；② 与 SLA 扫描（每 5 分钟）、
     * Outbox 投递（每 10 秒）错开，不挤在同一条时间线上。
     *
     * <p>清理是「过期即丢」，不需要精确到分钟 —— 早几小时晚几小时都无所谓。
     */
    private static final String CRON = "0 30 3 * * ?";

    private final ChatMemoryCleanup chatMemoryCleanup;

    public ChatMemoryCleanupJob(ChatMemoryCleanup chatMemoryCleanup) {
        this.chatMemoryCleanup = chatMemoryCleanup;
    }

    @Scheduled(cron = CRON)
    public void cleanup() {
        try {
            chatMemoryCleanup.cleanupExpired();
        }
        catch (Exception ex) {
            log.error("[AI 记忆清理] 本轮执行异常（明天照常执行）", ex);
        }
    }
}
