package com.opsdesk.ai.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 会话记忆的过期清理（工单 D11-02 的遗留项，2026-10-12 补齐）
 *
 * <h2>要解决的问题：{@code SPRING_AI_CHAT_MEMORY} 只增不减</h2>
 * {@code MessageWindowChatMemory} 的「窗口 20 条」只限制<b>读取</b>（get 时取最近 20 条），
 * 而 {@code saveAll} 每轮都往表里<b>追加</b> —— 也就是说：功能上永远正确，存储上永远增长。
 * 一次对话问 4 轮就有 8 行，且没有任何机制回收。
 *
 * <p>AI 对话历史不是业务数据（不需要留痕、不需要审计），**过期即丢**是最合适的口径：
 * 超过保留期（默认 7 天）的对话，用户也不会再回去接着聊。
 *
 * <h2>⚠️ 为什么用原生 SQL 而不是框架 API</h2>
 * {@code ChatMemoryRepository} 只提供 {@code deleteByConversationId(整个会话)}，
 * <b>没有</b>「按时间删」。按会话删需要先 {@code findConversationIds()} 全量取出再逐个判断，
 * 而「删除 N 天前的行」本来就是一条 SQL 的事 —— 直接 {@code DELETE} 更简单也更快。
 *
 * <h2>⚠️ 分批删除</h2>
 * 一次性 {@code DELETE WHERE timestamp < ?} 在数据量大时会长时间持有行锁、撑爆 undo log。
 * 所以按 {@value #BATCH_SIZE} 行一批循环删，并给单轮加一个批数上限（防止一轮调度跑成几小时）。
 *
 * <h2>⚠️ 为什么不需要分布式锁</h2>
 * 与 §9.5 的 SLA 扫描不同：那个任务要「判定状态 + 发通知」，必须串行；
 * 而 {@code DELETE} 是<b>幂等</b>的 —— 多实例同时删同一批，删掉的还是那几行，重复执行无副作用。
 */
@Slf4j
@Component
public class ChatMemoryCleanup {

    /** 每批删除行数：兼顾「删得动」与「锁得住」 */
    static final int BATCH_SIZE = 1000;

    /**
     * 单轮最多删多少批（= {@value #BATCH_SIZE} × 100 = 10 万行）。
     *
     * <p>留下的量由下一轮（明天）继续删 —— 宁可持续几轮，也不让一次调度长时间占着库。
     */
    static final int MAX_BATCHES = 100;

    /** 表名与列名来自框架的建表脚本（{@code sql/schema-mysql.sql}），不是业务表 */
    private static final String DELETE_SQL =
            "DELETE FROM SPRING_AI_CHAT_MEMORY WHERE `timestamp` < ? LIMIT ?";

    private final JdbcTemplate jdbcTemplate;

    /** 保留天数（{@code opsdesk.ai.chat.memory-retention-days}，默认 7） */
    private final int retentionDays;

    public ChatMemoryCleanup(JdbcTemplate jdbcTemplate,
                             @Value("${opsdesk.ai.chat.memory-retention-days:7}") int retentionDays) {
        this.jdbcTemplate = jdbcTemplate;
        this.retentionDays = retentionDays;
    }

    /**
     * 删除超过保留期的会话记忆。
     *
     * @return 实际删除的行数（0 表示没有过期数据，属正常情况）
     */
    public int cleanupExpired() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(retentionDays);
        Timestamp thresholdParam = Timestamp.valueOf(threshold);

        int total = 0;
        for (int batch = 1; batch <= MAX_BATCHES; batch++) {
            int deleted = jdbcTemplate.update(DELETE_SQL, thresholdParam, BATCH_SIZE);
            total += deleted;
            // 不满一批说明已经删干净了
            if (deleted < BATCH_SIZE) {
                break;
            }
            log.debug("[AI 记忆清理] 第 {} 批删除 {} 行，继续", batch, deleted);
        }

        if (total > 0) {
            log.info("[AI 记忆清理] 已删除 {} 行超过 {} 天（阈值 {}）的会话记忆",
                    total, retentionDays, threshold);
        }
        else {
            log.debug("[AI 记忆清理] 没有超过 {} 天的会话记忆", retentionDays);
        }
        return total;
    }

    /** 保留天数（供测试与运维排查用） */
    public int getRetentionDays() {
        return retentionDays;
    }
}
