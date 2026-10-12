package com.opsdesk.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsdesk.ai.dto.AiAnalysisResult;
import com.opsdesk.ai.support.AiChatGateway;
import com.opsdesk.config.RabbitMQConfig;
import com.opsdesk.mq.AbstractMqIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

/**
 * D10-04 验收 1：<b>建单事件被消费后自动触发分析</b>（§10.1 的异步链路）
 *
 * <h2>链路</h2>
 * <pre>
 * TicketCreatedEvent → opsdesk.topic → opsdesk.ticket.created.queue
 *   → TicketCreatedListener（D9-02 骨架 + D10-04 接线）
 *   → TicketAnalyzeService.analyze → ai_analysis
 * </pre>
 * 这里直接投递事件消息，而不是走 HTTP 建单 —— 两者等效（建单就是在事务里写 outbox 再投递），
 * 但直接投递能把「消费端接线」这件事单独验清楚，不受建单接口的干扰。
 *
 * <h2>⚠️ 不加 {@code @Transactional}</h2>
 * 消费者跑在<b>另一个线程</b>上，它看不到测试方法的事务 —— 加了事务反而会让断言看不到落库结果。
 * 所以本类自己清理数据（{@code @BeforeEach} 删该工单的历史分析记录）。
 *
 * <h2>⚠️ spy 掉 gateway</h2>
 * 本类验的是「事件到了、分析跑了、结果落库了」，不是 AI 质量。
 * 真调 LLM 会让等待时间变成不可控的 10s+（还可能因网络抖动随机红）。
 */
class TicketCreatedAnalysisFlowTest extends AbstractMqIntegrationTest {

    private static final long TICKET_ID = 1L;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private AiChatGateway aiChatGateway;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void stubAnalyzerAndClean() {
        AiAnalysisResult ok = new AiAnalysisResult();
        ok.setType("INCIDENT");
        ok.setCategory("NETWORK");
        ok.setPriority("P2");
        ok.setSummary("消费端自动触发落下的分析");
        ok.setSuggestions(List.of("检查 VPN 客户端"));
        doReturn(new ResponseEntity<ChatResponse, AiAnalysisResult>(null, ok))
                .when(aiChatGateway).call(anyString(), any());

        jdbcTemplate.update("DELETE FROM ai_analysis WHERE ticket_id = ?", TICKET_ID);
    }

    /**
     * ⚠️ 本类非事务（消费者在别的线程，看不到测试事务），所以留下的记录<b>必须自己清掉</b> ——
     * 否则其它测试类会读到这些残留：例如 {@code AiAnalysisApiTest} 里「尚未生成分析 → 40400」
     * 的用例，会因为这里留下的记录变成 200 而失败（踩过）。
     */
    @AfterEach
    void cleanRecords() {
        jdbcTemplate.update("DELETE FROM ai_analysis WHERE ticket_id = ?", TICKET_ID);
    }

    @Test
    @DisplayName("验收1：投一条 TicketCreatedEvent → 消费者自动触发分析 → ai_analysis 落库")
    void 建单事件自动触发分析() throws Exception {
        String messageId = UUID.randomUUID().toString();

        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.RK_TICKET_CREATED,
                payloadNode(), (Message message) -> {
                    // messageId 是消费端幂等键（§13.6）—— 每个用例必须用新的，否则被 guard 挡掉
                    message.getMessageProperties().setMessageId(messageId);
                    return message;
                });

        awaitAnalysisRecord();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT ticket_id, status, model, category, priority FROM ai_analysis "
                        + "WHERE ticket_id = ? ORDER BY id DESC LIMIT 1", TICKET_ID);

        // ⚠️ ticket_id 列是 BIGINT UNSIGNED，经 JDBC 拿到的是 BigInteger ——
        //    直接和 long 比会因类型不同而失败（断言文案是「expected: 1L but was: 1」，很迷惑）
        assertThat(((Number) row.get("ticket_id")).longValue()).isEqualTo(TICKET_ID);
        assertThat(row.get("status")).as("分析成功落 SUCCESS").isEqualTo("SUCCESS");
        assertThat(row.get("category")).as("回退/校验后一定落在白名单内").isEqualTo("NETWORK");
        assertThat(row.get("priority")).isEqualTo("P2");

        System.out.println("[D10-04 验收1] 事件消费 → 自动分析落库：" + row);
    }

    @Test
    @DisplayName("消费触发同一条消息重复投递 → 幂等生效，不会重复分析")
    void 重复投递不重复分析() throws Exception {
        String messageId = UUID.randomUUID().toString();

        for (int i = 0; i < 2; i++) {
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.RK_TICKET_CREATED,
                    payloadNode(), (Message message) -> {
                        message.getMessageProperties().setMessageId(messageId);
                        return message;
                    });
        }

        awaitAnalysisRecord();
        TimeUnit.MILLISECONDS.sleep(1500);   // 给第二条消息留出被消费（并跳过）的时间

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_analysis WHERE ticket_id = ?", Long.class, TICKET_ID);
        assertThat(count).as("§13.6：同 messageId 只应产生一条分析").isEqualTo(1L);
    }

    // ==================== 工具 ====================

    /** 与 {@code TicketCreatedEvent} 同构的载荷（对象形式，不是 JSON 字符串——否则会被二次序列化） */
    private Object payloadNode() throws Exception {
        return objectMapper.readTree("""
                {"ticketId":1,"ticketNo":"OD2026100600001","title":"公司 VPN 无法连接","creatorId":4,
                 "departmentId":5,"priority":"P2","category":"NETWORK","type":"INCIDENT"}
                """);
    }

    private void awaitAnalysisRecord() {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_analysis WHERE ticket_id = ?", Long.class, TICKET_ID);
            if (count != null && count > 0) {
                return;
            }
            sleep();
        }
        throw new AssertionError("等待消费者触发 AI 分析落库超时（30s）");
    }

    private void sleep() {
        try {
            TimeUnit.MILLISECONDS.sleep(300);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", ex);
        }
    }
}
