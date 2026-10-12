package com.opsdesk.ai;

import com.opsdesk.ai.support.ChatMemoryCleanup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话记忆清理的测试（D11-02 遗留项）
 *
 * <h2>为什么加 {@code @Transactional}</h2>
 * 测试会往 {@code SPRING_AI_CHAT_MEMORY} 插「8 天前」的假数据，而清理就是真删。
 * 放在测试事务里，跑完自动回滚 —— 既验证了真实 SQL（不是 mock），又不会污染开发库。
 */
@SpringBootTest
@Transactional
class ChatMemoryCleanupTest {

    private static final String EXPIRED_CONVERSATION = "test-cleanup-expired";
    private static final String FRESH_CONVERSATION = "test-cleanup-fresh";

    @Autowired
    private ChatMemoryCleanup chatMemoryCleanup;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("只删超过保留期的行；保留期内的原样不动（不能把还在聊的对话删了）")
    void 只删过期的() {
        insert(EXPIRED_CONVERSATION, daysAgo(8));     // 默认保留 7 天 → 超期
        insert(EXPIRED_CONVERSATION, daysAgo(30));
        insert(FRESH_CONVERSATION, daysAgo(1));       // 未超期

        int deleted = chatMemoryCleanup.cleanupExpired();

        assertThat(deleted).as("至少删掉本次造的两条超期数据").isGreaterThanOrEqualTo(2);
        assertThat(countOf(EXPIRED_CONVERSATION)).as("超期会话被清空").isZero();
        assertThat(countOf(FRESH_CONVERSATION)).as("保留期内的不能被删").isEqualTo(1);
    }

    @Test
    @DisplayName("幂等：没有过期数据时删 0 行，连跑两次也安全")
    void 幂等且可重复执行() {
        insert(FRESH_CONVERSATION, daysAgo(1));

        assertThat(chatMemoryCleanup.cleanupExpired()).as("没有超期数据时应当删 0 行").isZero();
        assertThat(chatMemoryCleanup.cleanupExpired()).as("再跑一次同样安全").isZero();

        assertThat(countOf(FRESH_CONVERSATION)).isEqualTo(1);
    }

    @Test
    @DisplayName("删除语句走 timestamp 索引（range），不是全表扫 —— 索引被删掉这里就红")
    void 删除语句走索引() {
        List<Map<String, Object>> plan = jdbcTemplate.queryForList(
                "EXPLAIN DELETE FROM SPRING_AI_CHAT_MEMORY WHERE `timestamp` < NOW() LIMIT 1000");

        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).get("type")).as("range = 走范围索引").isEqualTo("range");
        assertThat(plan.get(0).get("key"))
                .as("必须命中 idx(timestamp)；被 drop 掉的话每天的清理都会全表扫")
                .isEqualTo("SPRING_AI_CHAT_MEMORY_TIMESTAMP_IDX");
    }

    @Test
    @DisplayName("保留期可配（默认 7 天）")
    void 保留期默认七天() {
        assertThat(chatMemoryCleanup.getRetentionDays()).isEqualTo(7);
    }

    // ==================== 工具 ====================

    private LocalDateTime daysAgo(int days) {
        return LocalDateTime.now().minusDays(days);
    }

    private void insert(String conversationId, LocalDateTime timestamp) {
        jdbcTemplate.update(
                "INSERT INTO SPRING_AI_CHAT_MEMORY (conversation_id, content, type, `timestamp`) "
                        + "VALUES (?, ?, 'USER', ?)",
                conversationId, "测试内容", Timestamp.valueOf(timestamp));
    }

    private int countOf(String conversationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ?",
                Integer.class, conversationId);
        return count == null ? 0 : count;
    }
}
