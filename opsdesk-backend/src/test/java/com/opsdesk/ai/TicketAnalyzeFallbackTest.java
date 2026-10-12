package com.opsdesk.ai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.opsdesk.ai.dto.AiAnalysisResult;
import com.opsdesk.ai.service.TicketAnalyzeService;
import com.opsdesk.ai.service.impl.TicketAnalyzeServiceImpl;
import com.opsdesk.ai.support.AiChatGateway;
import com.opsdesk.ai.vo.AiAnalysisVO;
import com.opsdesk.common.BizException;
import com.opsdesk.common.enums.AiAnalysisStatus;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * D10-02 验收 2 / 3 —— 回退与失败路径（<b>不依赖真实 LLM</b>）
 *
 * <h2>⚠️ 为什么要 mock 掉 {@code AiChatGateway}</h2>
 * 「模型返回非法枚举」「模型返回无法解析的内容」「模型不可用」这三种场景，
 * 靠提示词去诱导模型复现是<b>不可控</b>的（模型可能就是不听话、也可能碰巧听话）。
 * 而这三条恰恰是 §10.3 的核心兜底逻辑，必须<b>确定性地</b>验证 —— 所以用 spy 把
 * {@code call(...)} 换成"返回一份指定内容"，把不确定性挡在外面。
 *
 * <p>种子工单 id=1 原本是 {@code INCIDENT / NETWORK / P2} —— 正是「原工单值」回退的对照基准。
 */
@SpringBootTest
@Transactional
class TicketAnalyzeFallbackTest {

    /** 种子工单：type=INCIDENT, category=NETWORK, priority=P2 */
    private static final long TICKET_ID = 1L;

    @MockitoSpyBean
    private AiChatGateway aiChatGateway;

    @Autowired
    private TicketAnalyzeService ticketAnalyzeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("验收2：模型返回非法枚举 → 回退为原工单值，日志有告警，status 仍 SUCCESS")
    void 非法枚举回退为原工单值() {
        AiAnalysisResult illegal = new AiAnalysisResult();
        illegal.setType("HACK");
        illegal.setCategory("HACK");
        illegal.setPriority("P0");
        illegal.setSummary("诱导出来的摘要");
        illegal.setSuggestions(List.of("建议一"));
        stubReturn(illegal);

        ListAppender<ILoggingEvent> logs = attachLogAppender();
        AiAnalysisVO vo;
        try {
            vo = ticketAnalyzeService.analyze(TICKET_ID);
        }
        finally {
            detachLogAppender(logs);
        }

        assertThat(vo).isNotNull();
        assertThat(vo.getStatus()).as("§10.3：回退不算失败，状态仍是 SUCCESS")
                .isEqualTo(AiAnalysisStatus.SUCCESS);
        assertThat(vo.getType()).as("回退为原工单值 INCIDENT").isEqualTo("INCIDENT");
        assertThat(vo.getCategory()).as("回退为原工单值 NETWORK").isEqualTo(TicketCategory.NETWORK);
        assertThat(vo.getPriority()).as("回退为原工单值 P2").isEqualTo(TicketPriority.P2);
        assertThat(vo.getSummary()).as("summary 没有白名单，模型给什么就用什么").isEqualTo("诱导出来的摘要");
        assertThat(vo.getSuggestions()).containsExactly("建议一");

        String allWarnings = logs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(allWarnings).as("§10.3 要求「回退并记录告警日志」")
                .contains("不在白名单").contains("HACK");
    }

    @Test
    @DisplayName("字段缺失（模型没给 / 给了空串）→ 同样回退为原工单值并告警")
    void 字段缺失也回退() {
        AiAnalysisResult missing = new AiAnalysisResult();
        missing.setType(null);
        missing.setCategory("   ");
        missing.setPriority("p1");            // 小写：应当被正常识别（不算非法）
        missing.setSummary("缺字段测试");

        stubReturn(missing);

        AiAnalysisVO vo = ticketAnalyzeService.analyze(TICKET_ID);

        assertThat(vo.getType()).as("null → 原工单值").isEqualTo("INCIDENT");
        assertThat(vo.getCategory()).as("空白 → 原工单值").isEqualTo(TicketCategory.NETWORK);
        assertThat(vo.getPriority()).as("小写 p1 应被识别成 P1，而不是回退").isEqualTo(TicketPriority.P1);
        assertThat(vo.getStatus()).isEqualTo(AiAnalysisStatus.SUCCESS);
    }

    @Test
    @DisplayName("D10-03 起：LLM 不可用 → 降级为规则引擎，落 SUCCESS 记录且调用方不抛异常（§10.4 / §10.2）")
    void LLM不可用时降级为规则引擎() {
        doThrow(BizException.serviceUnavailable("模拟 AI 不可用"))
                .when(aiChatGateway).call(anyString(), any());

        assertThatCode(() -> ticketAnalyzeService.analyze(TICKET_ID))
                .as("§10.2：AI 失败绝不能影响工单主流程").doesNotThrowAnyException();

        Map<String, Object> row = latestRow();
        assertThat(row.get("status"))
                .as("§10.4：降级产出也算分析成功（只是方式不同），标 FAILED 会让成功率统计失真")
                .isEqualTo(AiAnalysisStatus.SUCCESS.name());
        assertThat(row.get("model")).as("model=rule-based，事后可区分 AI 产出与规则产出")
                .isEqualTo("rule-based");
        assertThat(row.get("category")).as("§10.4：任何情况下工单都要有分类").isNotNull();
        assertThat(row.get("priority")).as("§10.4：priority 默认 P3").isEqualTo("P3");
    }

    @Test
    @DisplayName("§10.3 第 1 条：首次返回无法解析 → 重试 1 次；重试成功则状态 SUCCESS")
    void 解析失败会重试一次() {
        AiAnalysisResult ok = new AiAnalysisResult();
        ok.setType("INCIDENT");
        ok.setCategory("NETWORK");
        ok.setPriority("P1");
        ok.setSummary("重试后成功");
        ok.setSuggestions(List.of("建议"));

        // 第一次抛（模拟解析失败），第二次返回正常结果
        doThrow(new IllegalStateException("模拟 JSON 解析失败"))
                .doReturn(new ResponseEntity<ChatResponse, AiAnalysisResult>(null, ok))
                .when(aiChatGateway).call(anyString(), any());

        AiAnalysisVO vo = ticketAnalyzeService.analyze(TICKET_ID);

        assertThat(vo.getStatus()).as("重试一次后成功").isEqualTo(AiAnalysisStatus.SUCCESS);
        assertThat(vo.getSummary()).isEqualTo("重试后成功");
    }

    @Test
    @DisplayName("§10.3 第 1 条：重试后仍无法解析 → 降级为规则引擎（D10-03 起不再直接判 FAILED）")
    void 重试仍失败则降级() {
        doThrow(new IllegalStateException("模拟 JSON 解析失败 1"))
                .doThrow(new IllegalStateException("模拟 JSON 解析失败 2"))
                .when(aiChatGateway).call(anyString(), any());

        assertThatCode(() -> ticketAnalyzeService.analyze(TICKET_ID)).doesNotThrowAnyException();

        Map<String, Object> row = latestRow();
        assertThat(row.get("status"))
                .as("重试两次都解析不出来 → 走规则引擎兜底，而不是让工单没有分类")
                .isEqualTo(AiAnalysisStatus.SUCCESS.name());
        assertThat(row.get("model")).isEqualTo("rule-based");
    }

    @Test
    @DisplayName("LLM 不可用时不重试（只有「解析失败」才重试，超时/不可用直接判失败）")
    void AI不可用不重试() {
        doThrow(BizException.serviceUnavailable("模拟 AI 不可用"))
                .when(aiChatGateway).call(anyString(), any());

        ticketAnalyzeService.analyze(TICKET_ID);

        org.mockito.Mockito.verify(aiChatGateway, org.mockito.Mockito.times(1))
                .call(anyString(), any());
    }

    // ==================== 工具 ====================

    /** 让 gateway 的调用直接返回一份指定结果（不带 ChatResponse，rawText/model 走 null 分支） */
    private void stubReturn(AiAnalysisResult result) {
        doReturn(new ResponseEntity<ChatResponse, AiAnalysisResult>(null, result))
                .when(aiChatGateway).call(anyString(), any());
    }

    private Map<String, Object> latestRow() {
        return jdbcTemplate.queryForMap(
                "SELECT status, model, type, category, priority, summary, suggestions "
                        + "FROM ai_analysis WHERE ticket_id = ? ORDER BY id DESC LIMIT 1", TICKET_ID);
    }

    private ListAppender<ILoggingEvent> attachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(TicketAnalyzeServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(TicketAnalyzeServiceImpl.class);
        logger.detachAppender(appender);
    }
}
