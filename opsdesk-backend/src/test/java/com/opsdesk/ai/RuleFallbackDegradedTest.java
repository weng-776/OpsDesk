package com.opsdesk.ai;

import com.opsdesk.ai.service.TicketAnalyzeService;
import com.opsdesk.ai.vo.AiAnalysisVO;
import com.opsdesk.common.enums.AiAnalysisStatus;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * D10-03 验收 3：关掉降级开关后，工单分析<b>走规则引擎</b>
 *
 * <h2>⚠️ 为什么单独一个测试类</h2>
 * {@code opsdesk.ai.enabled=false} 会改变上下文（不装配 {@code ChatClient}），
 * 必须用自己的 properties，不能污染其它用例。
 *
 * <p>种子里工单 id=1 是「公司 VPN 无法连接 / 今天上午开始 VPN 一直连接失败」——
 * 正好验证规则引擎能命中 {@code NETWORK}。
 */
@SpringBootTest(properties = "opsdesk.ai.enabled=false")
@Transactional
class RuleFallbackDegradedTest {

    private static final long VPN_TICKET_ID = 1L;

    @Autowired
    private TicketAnalyzeService ticketAnalyzeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("验收3：开关关闭 → 走规则引擎，落库、model 标记 rule-based、status 仍 SUCCESS")
    void 开关关闭时走规则引擎并落库() {
        AiAnalysisVO vo = ticketAnalyzeService.analyze(VPN_TICKET_ID);

        assertThat(vo).as("降级也要有结果").isNotNull();
        assertThat(vo.getStatus()).as("§10.4：降级产出算成功，只是方式不同")
                .isEqualTo(AiAnalysisStatus.SUCCESS);
        assertThat(vo.getModel()).as("便于事后区分 AI 产出与规则产出")
                .isEqualTo("rule-based");
        assertThat(vo.getCategory()).as("VPN 工单 → 规则命中 NETWORK").isEqualTo(TicketCategory.NETWORK);
        assertThat(vo.getPriority()).as("§10.4：priority 默认 P3").isEqualTo(TicketPriority.P3);
        assertThat(vo.getSummary()).as("摘要取描述前 100 字").isNotBlank();
        assertThat(vo.getSuggestions()).as("给通用建议").isNotEmpty();
        assertThat(vo.getCreatedAt()).as("落库后应能拿到生成时间").isNotNull();

        Long stored = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_analysis WHERE ticket_id = ? AND model = 'rule-based'",
                Long.class, VPN_TICKET_ID);
        assertThat(stored).as("§24.3：规则引擎兜底时 ai_analysis 仍要落库").isEqualTo(1L);

        System.out.println("[D10-03 验收3] 规则引擎产出：category=" + vo.getCategory()
                + " priority=" + vo.getPriority() + " model=" + vo.getModel()
                + " summary=" + vo.getSummary());
    }

    @Test
    @DisplayName("验收3：开关关闭时调用方同样不抛异常（§10.2 主流程不受影响）")
    void 开关关闭时调用方不抛异常() {
        assertThatCode(() -> ticketAnalyzeService.analyze(VPN_TICKET_ID)).doesNotThrowAnyException();
    }
}
