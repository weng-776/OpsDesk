package com.opsdesk.ai;

import com.opsdesk.ai.dto.AiAnalysisResult;
import com.opsdesk.ai.service.TicketAnalyzeService;
import com.opsdesk.ai.support.AiChatGateway;
import com.opsdesk.auth.service.PermissionLoader;
import com.opsdesk.common.JwtHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D10-05 验收：采纳 AI 建议（改字段 + 审计 + 四道校验）
 *
 * <h2>种子数据</h2>
 * 工单 1：VPN，{@code category=NETWORK / priority=P2 / status=OPEN / creator=4}（emp_wang）
 * —— 用它验"采纳后字段真的变了"，原值就是最好的对照。
 * 工单 4：打印机，{@code status=CLOSED} —— 终态，用它验 40900。
 *
 * <h2>⚠️ 为什么加 {@code @Transactional}</h2>
 * 采纳会真改 {@code ticket} 表、真写 {@code audit_log}。测试事务回滚，避免污染开发库
 * （也避免把种子工单的 category/priority 改坏，影响其它测试的精确断言）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AiAnalysisAdoptTest {

    private static final long VPN_TICKET_ID = 1L;
    private static final long CLOSED_TICKET_ID = 4L;

    private static final long ADMIN_ID = 1L;
    private static final long AGENT_ZHANG_ID = 2L;
    private static final long EMP_WANG_ID = 4L;
    private static final List<Long> TOUCHED = List.of(ADMIN_ID, AGENT_ZHANG_ID, EMP_WANG_ID);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtHelper jwtHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private TicketAnalyzeService ticketAnalyzeService;

    /** 验收 7 要用它跑一次"分析"，spy 掉避免真调 LLM */
    @MockitoSpyBean
    private AiChatGateway aiChatGateway;

    @BeforeEach
    void stubAnalyzer() {
        AiAnalysisResult ok = new AiAnalysisResult();
        ok.setType("INCIDENT");
        ok.setCategory("ACCOUNT");      // 故意与工单原值（NETWORK）不同，方便验证"采纳了才变"
        ok.setPriority("P1");           // 原值是 P2
        ok.setSummary("接口测试摘要");
        ok.setSuggestions(List.of("建议一"));
        doReturn(new ResponseEntity<ChatResponse, AiAnalysisResult>(null, ok))
                .when(aiChatGateway).call(anyString(), any());
    }

    @AfterEach
    void cleanAuthCache() {
        TOUCHED.forEach(id -> redisTemplate.delete(PermissionLoader.PERMS_CACHE_PREFIX + id));
    }

    // ==================== 验收 1 / 2：采纳优先级（不采纳分类） ====================

    @Test
    @DisplayName("验收1+2：adoptPriority=true / adoptCategory=false → 只改优先级，分类不变，且写了审计")
    void 采纳优先级改字段并写审计() throws Exception {
        insertAnalysis(VPN_TICKET_ID, "ACCOUNT", "P1", "SUCCESS");

        mockMvc.perform(post("/api/tickets/{id}/ai-analysis/adopt", VPN_TICKET_ID)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID))
                        .contentType("application/json")
                        .content("{\"adoptCategory\":false,\"adoptPriority\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.priority").value("P1"));

        Map<String, Object> ticket = ticketRow(VPN_TICKET_ID);
        assertThat(ticket.get("priority")).as("采纳后优先级变成 AI 建议值 P1").isEqualTo("P1");
        assertThat(ticket.get("category")).as("adoptCategory=false → 分类保持原值 NETWORK")
                .isEqualTo("NETWORK");

        List<Map<String, Object>> audits = adoptAudits(VPN_TICKET_ID);
        assertThat(audits).as("§10.2：采纳动作必须写审计").hasSize(1);
        assertThat(audits.get(0).get("operation")).isEqualTo("TICKET_PRIORITY_CHANGE");
        assertThat(audits.get(0).get("before_data")).as("§3.11：before/after 必须有差异，否则审计没有信息量")
                .isNotEqualTo(audits.get(0).get("after_data"));
    }

    @Test
    @DisplayName("两个开关都采纳 → 两个字段都变成 AI 建议值")
    void 两个开关都采纳() throws Exception {
        insertAnalysis(VPN_TICKET_ID, "ACCOUNT", "P1", "SUCCESS");

        mockMvc.perform(post("/api/tickets/{id}/ai-analysis/adopt", VPN_TICKET_ID)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID))
                        .contentType("application/json")
                        .content("{\"adoptCategory\":true,\"adoptPriority\":true}"))
                .andExpect(status().isOk());

        Map<String, Object> ticket = ticketRow(VPN_TICKET_ID);
        assertThat(ticket.get("category")).isEqualTo("ACCOUNT");
        assertThat(ticket.get("priority")).isEqualTo("P1");
    }

    // ==================== §9.9：采纳优先级连带重算解决时限 ====================

    @Test
    @DisplayName("§9.9：采纳优先级 → 解决时限按新旧策略分钟数之差平移，policyId 指向新策略，response_deadline 不动")
    void 采纳优先级连带重算解决时限() throws Exception {
        insertAnalysis(VPN_TICKET_ID, "NETWORK", "P1", "SUCCESS");

        Map<String, Object> before = slaRow(VPN_TICKET_ID);
        assertThat(before.get("priority_value")).as("前提：工单原优先级是 P2").isEqualTo("P2");

        mockMvc.perform(post("/api/tickets/{id}/ai-analysis/adopt", VPN_TICKET_ID)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID))
                        .contentType("application/json")
                        .content("{\"adoptPriority\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.priority").value("P1"));

        Map<String, Object> after = slaRow(VPN_TICKET_ID);

        // ① policyId 必须指向新优先级的策略版本 —— deadline 换了却仍指向旧版本会自相矛盾
        //    ⚠️ sla_policy_id 是 BIGINT UNSIGNED，JDBC 拿到的是 BigInteger：
        //       直接和 long 比会报出很误导人的 "expected: 1L but was: 1" → 统一走 Number.longValue()
        assertThat(((Number) after.get("sla_policy_id")).longValue()).as("policy 应指向 P1 的 ACTIVE 策略")
                .isEqualTo(policyIdOf("P1"));

        // ② 解决时限按「新旧策略分钟数之差」平移（P2 480 → P1 240，即提前 240 分钟）
        //    ⚠️ queryForMap 拿到的 datetime 是 LocalDateTime（不是 java.sql.Timestamp）
        long deltaMinutes = Duration.between(
                (LocalDateTime) before.get("resolution_deadline"),
                (LocalDateTime) after.get("resolution_deadline")).toMinutes();
        assertThat(deltaMinutes).as("差值平移：新分钟数 - 旧分钟数")
                .isEqualTo(resolutionMinutesOf("P1") - resolutionMinutesOf("P2"));

        // ③ response_deadline 不动 —— §9.4 已有「响应发生在早期」的先例，重算会改写历史判定
        assertThat(after.get("response_deadline")).as("响应截止不应被优先级采纳改动")
                .isEqualTo(before.get("response_deadline"));
    }

    @Test
    @DisplayName("§9.9：不采纳优先级（只采纳分类）→ SLA 一个字都不动")
    void 不采纳优先级则SLA不动() throws Exception {
        insertAnalysis(VPN_TICKET_ID, "ACCOUNT", "P1", "SUCCESS");

        Map<String, Object> before = slaRow(VPN_TICKET_ID);

        mockMvc.perform(post("/api/tickets/{id}/ai-analysis/adopt", VPN_TICKET_ID)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID))
                        .contentType("application/json")
                        .content("{\"adoptCategory\":true}"))
                .andExpect(status().isOk());

        Map<String, Object> after = slaRow(VPN_TICKET_ID);
        assertThat(after.get("sla_policy_id")).isEqualTo(before.get("sla_policy_id"));
        assertThat(after.get("resolution_deadline")).isEqualTo(before.get("resolution_deadline"));
    }

    // ==================== 验收 3：都不采纳 ====================

    @Test
    @DisplayName("验收3：两个开关都 false → 工单一个字段都不改，但仍返回最新快照且不报错")
    void 两个开关都false不改字段() throws Exception {
        insertAnalysis(VPN_TICKET_ID, "ACCOUNT", "P1", "SUCCESS");

        mockMvc.perform(post("/api/tickets/{id}/ai-analysis/adopt", VPN_TICKET_ID)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID))
                        .contentType("application/json")
                        .content("{\"adoptCategory\":false,\"adoptPriority\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(VPN_TICKET_ID));

        Map<String, Object> ticket = ticketRow(VPN_TICKET_ID);
        assertThat(ticket.get("category")).isEqualTo("NETWORK");
        assertThat(ticket.get("priority")).isEqualTo("P2");
    }

    @Test
    @DisplayName("不传请求体（等于两个开关都 false）→ 同样不改字段（前端忘传参不应变成误改工单）")
    void 不传请求体不改字段() throws Exception {
        insertAnalysis(VPN_TICKET_ID, "ACCOUNT", "P1", "SUCCESS");

        mockMvc.perform(post("/api/tickets/{id}/ai-analysis/adopt", VPN_TICKET_ID)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID)))
                .andExpect(status().isOk());

        assertThat(ticketRow(VPN_TICKET_ID).get("priority")).isEqualTo("P2");
    }

    // ==================== 验收 4：FAILED 记录不能采纳 ====================

    @Test
    @DisplayName("验收4：最近一次分析是 FAILED → 采纳被拒（40001），工单不变")
    void FAILED记录不能采纳() throws Exception {
        insertAnalysis(VPN_TICKET_ID, null, null, "FAILED");

        mockMvc.perform(post("/api/tickets/{id}/ai-analysis/adopt", VPN_TICKET_ID)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID))
                        .contentType("application/json")
                        .content("{\"adoptPriority\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        assertThat(ticketRow(VPN_TICKET_ID).get("priority")).isEqualTo("P2");
    }

    @Test
    @DisplayName("从未分析过 → 采纳返回 40400")
    void 没有分析记录返回40400() throws Exception {
        jdbcTemplate.update("DELETE FROM ai_analysis WHERE ticket_id = ?", VPN_TICKET_ID);

        mockMvc.perform(post("/api/tickets/{id}/ai-analysis/adopt", VPN_TICKET_ID)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID))
                        .contentType("application/json")
                        .content("{\"adoptPriority\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    // ==================== 验收 6：终态工单 ====================

    @Test
    @DisplayName("验收6：终态工单（CLOSED）采纳 → 40900")
    void 终态工单返回40900() throws Exception {
        insertAnalysis(CLOSED_TICKET_ID, "ACCOUNT", "P1", "SUCCESS");

        mockMvc.perform(post("/api/tickets/{id}/ai-analysis/adopt", CLOSED_TICKET_ID)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID))
                        .contentType("application/json")
                        .content("{\"adoptPriority\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));

        assertThat(ticketRow(CLOSED_TICKET_ID).get("priority")).as("终态工单一个字都不能改").isEqualTo("P3");
    }

    // ==================== 验收 5：权限与数据范围 ====================

    @Test
    @DisplayName("验收5：AGENT 对他人工单采纳 → 40301")
    void AGENT对他人工单返回40301() throws Exception {
        long invisibleTicketId = insertTicketInvisibleToAgentZhang();
        insertAnalysis(invisibleTicketId, "ACCOUNT", "P1", "SUCCESS");

        mockMvc.perform(post("/api/tickets/{id}/ai-analysis/adopt", invisibleTicketId)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(AGENT_ZHANG_ID))
                        .contentType("application/json")
                        .content("{\"adoptPriority\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("验收5：EMPLOYEE 采纳 → 40300（没有 ticket:process）")
    void EMPLOYEE返回40300() throws Exception {
        insertAnalysis(VPN_TICKET_ID, "ACCOUNT", "P1", "SUCCESS");

        mockMvc.perform(post("/api/tickets/{id}/ai-analysis/adopt", VPN_TICKET_ID)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(EMP_WANG_ID))
                        .contentType("application/json")
                        .content("{\"adoptPriority\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    // ==================== 验收 7：AI 不会自己改字段 ====================

    @Test
    @DisplayName("验收7：跑一次完整的 AI 分析 → 工单字段一个都没变（§10.2 的核心：不自动覆盖）")
    void 分析不会自动改工单字段() {
        Map<String, Object> before = ticketRow(VPN_TICKET_ID);

        ticketAnalyzeService.analyze(VPN_TICKET_ID);

        Map<String, Object> after = ticketRow(VPN_TICKET_ID);
        assertThat(after.get("category")).as("分析只写 ai_analysis，绝不碰 ticket").isEqualTo(before.get("category"));
        assertThat(after.get("priority")).isEqualTo(before.get("priority"));
        assertThat(after.get("status")).isEqualTo(before.get("status"));

        // 反过来确认分析确实跑了（否则上面的断言毫无意义）
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_analysis WHERE ticket_id = ?", Long.class, VPN_TICKET_ID);
        assertThat(count).as("分析结果确实落库了").isPositive();
    }

    // ==================== 工具 ====================

    private String tokenOf(long userId) {
        return "Bearer " + jwtHelper.createToken(userId);
    }

    private Map<String, Object> ticketRow(long ticketId) {
        return jdbcTemplate.queryForMap("SELECT category, priority, status FROM ticket WHERE id = ?", ticketId);
    }

    /** SLA 相关列 + 当前优先级（§9.9 重算断言用） */
    private Map<String, Object> slaRow(long ticketId) {
        return jdbcTemplate.queryForMap(
                "SELECT priority AS priority_value, sla_policy_id, response_deadline, resolution_deadline "
                        + "FROM ticket WHERE id = ?", ticketId);
    }

    /** 取某优先级当前 ACTIVE 策略的 id（不写死数字，避免种子调整后测试悄悄失效） */
    private long policyIdOf(String priority) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM sla_policy WHERE priority = ? AND status = 'ACTIVE'", Long.class, priority);
        assertThat(id).as("种子里 %s 应有 ACTIVE 策略", priority).isNotNull();
        return id;
    }

    private int resolutionMinutesOf(String priority) {
        Integer minutes = jdbcTemplate.queryForObject(
                "SELECT resolution_minutes FROM sla_policy WHERE priority = ? AND status = 'ACTIVE'",
                Integer.class, priority);
        assertThat(minutes).isNotNull();
        return minutes;
    }

    private List<Map<String, Object>> adoptAudits(long ticketId) {
        return jdbcTemplate.queryForList(
                "SELECT operation, before_data, after_data FROM audit_log "
                        + "WHERE resource_type = 'TICKET' AND resource_id = ? AND operation = 'TICKET_PRIORITY_CHANGE' "
                        + "ORDER BY id DESC", ticketId);
    }

    /** 造一条分析记录（category / priority 传 null 即模拟 FAILED） */
    private void insertAnalysis(long ticketId, String category, String priority, String status) {
        jdbcTemplate.update("DELETE FROM ai_analysis WHERE ticket_id = ?", ticketId);
        jdbcTemplate.update("""
                INSERT INTO ai_analysis (ticket_id, model, type, category, priority, summary, suggestions, status)
                VALUES (?, 'test-model', 'INCIDENT', ?, ?, '测试摘要', '["建议一"]', ?)
                """, ticketId, category, priority, status);
    }

    /** 造一张 agent_zhang 看不见的工单（别人创建、别的部门、处理人是别人） */
    private long insertTicketInvisibleToAgentZhang() {
        jdbcTemplate.update("""
                INSERT INTO ticket (ticket_no, title, description, type, category, priority, status,
                                    source, creator_id, department_id, assignee_id)
                VALUES (?, ?, ?, 'INCIDENT', 'NETWORK', 'P2', 'IN_PROGRESS', 'WEB', 5, 6, 3)
                """, "OD-D1005-" + System.nanoTime(), "D10-05 越权测试工单", "agent_zhang 不可见");
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        assertThat(id).isNotNull();
        return id;
    }
}
