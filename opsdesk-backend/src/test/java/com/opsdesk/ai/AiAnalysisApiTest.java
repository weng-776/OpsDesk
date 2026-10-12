package com.opsdesk.ai;

import com.opsdesk.ai.dto.AiAnalysisResult;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D10-04 接口验收：两个端点的路径、权限、数据范围与 40400 语义
 *
 * <h2>⚠️ 为什么 spy 掉 {@code AiChatGateway}</h2>
 * 本类验的是<b>接口层</b>（路径 / 权限 / 数据范围 / 状态码），不是 AI 质量 ——
 * 真调 LLM 会让每个用例慢 10s+，还可能因为网络抖动随机红。
 * 让 gateway 直接返回一份固定结果，就能把"接口行为"与"模型行为"解耦（AI 本身已由 D10-02 的测试覆盖）。
 *
 * <h2>种子数据对照</h2>
 * 工单 1（VPN）：{@code creator_id=4}（emp_wang，财务部）、{@code status=OPEN}（公共池）
 * → 对 AGENT 是可见的（公共池），所以验 40301 要另造一张不可见的工单。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AiAnalysisApiTest {

    private static final long VPN_TICKET_ID = 1L;
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

    /** 让分析立刻返回固定结果，避免真调 LLM（详见类注释） */
    @MockitoSpyBean
    private AiChatGateway aiChatGateway;

    @BeforeEach
    void stubAnalyzer() {
        AiAnalysisResult ok = new AiAnalysisResult();
        ok.setType("INCIDENT");
        ok.setCategory("NETWORK");
        ok.setPriority("P2");
        ok.setSummary("接口测试用的分析摘要");
        ok.setSuggestions(List.of("建议一"));
        doReturn(new ResponseEntity<ChatResponse, AiAnalysisResult>(null, ok))
                .when(aiChatGateway).call(anyString(), any());
    }

    @AfterEach
    void cleanAuthCache() {
        TOUCHED.forEach(id -> redisTemplate.delete(PermissionLoader.PERMS_CACHE_PREFIX + id));
    }

    // ==================== 验收 3：未生成过分析 → 40400 ====================

    @Test
    @DisplayName("验收3：从未生成过分析的工单 → GET 返回 40400")
    void 未生成分析返回40400() throws Exception {
        jdbcTemplate.update("DELETE FROM ai_analysis WHERE ticket_id = ?", VPN_TICKET_ID);

        mockMvc.perform(get("/api/tickets/{id}/ai-analysis", VPN_TICKET_ID)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    @DisplayName("工单本身不存在 → 也是 40400，但语义是「工单不存在」（先查存在性再判可见性）")
    void 工单不存在返回40400() throws Exception {
        mockMvc.perform(get("/api/tickets/{id}/ai-analysis", 999999L)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    @DisplayName("已有 FAILED 记录 → 返回 200 + FAILED 的 VO（不是 40400：失败 ≠ 没分析过）")
    void 失败记录仍正常返回() throws Exception {
        jdbcTemplate.update("DELETE FROM ai_analysis WHERE ticket_id = ?", VPN_TICKET_ID);
        jdbcTemplate.update("INSERT INTO ai_analysis (ticket_id, model, status) VALUES (?, 'rule-based', 'FAILED')",
                VPN_TICKET_ID);

        mockMvc.perform(get("/api/tickets/{id}/ai-analysis", VPN_TICKET_ID)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("FAILED"));
    }

    // ==================== 路径：GET 必须挂在 /api/tickets 下 ====================

    @Test
    @DisplayName("§8.13：查询路径是 /api/tickets/{id}/ai-analysis，写错成 /api/ai/... 会 404")
    void 查询路径挂在工单域下() throws Exception {
        // 先清历史记录：本用例断言的是「没有记录 → 40400」，不能受残留数据影响
        // （非事务的 TicketCreatedAnalysisFlowTest 会往这张工单落记录，踩过）
        jdbcTemplate.update("DELETE FROM ai_analysis WHERE ticket_id = ?", VPN_TICKET_ID);

        mockMvc.perform(get("/api/ai/tickets/{id}/analysis", VPN_TICKET_ID)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID)))
                .andExpect(status().isNotFound());

        // 正确路径必须存在（ADMIN 可见任意工单）
        mockMvc.perform(get("/api/tickets/{id}/ai-analysis", VPN_TICKET_ID)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID)))
                .andExpect(status().isNotFound())          // 40400：尚未生成
                .andExpect(jsonPath("$.code").value(40400));
    }

    // ==================== 验收 4 / 5：权限与数据范围 ====================

    @Test
    @DisplayName("验收4：AGENT 对他人工单触发分析 → 40301（数据范围外）")
    void AGENT对他人工单触发返回40301() throws Exception {
        long invisibleTicketId = insertTicketInvisibleToAgentZhang();

        mockMvc.perform(post("/api/ai/tickets/{id}/analyze", invisibleTicketId)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(AGENT_ZHANG_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("验收5：EMPLOYEE 对自己工单触发分析 → 40300（没有 ticket:process 权限）")
    void EMPLOYEE触发返回40300() throws Exception {
        // 工单 1 就是 emp_wang 自己创建的（可见），但 EMPLOYEE 没有 ticket:process
        mockMvc.perform(post("/api/ai/tickets/{id}/analyze", VPN_TICKET_ID)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(EMP_WANG_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    @DisplayName("数据范围也覆盖 GET：不可见工单查询 → 40301（不泄露它有没有分析过）")
    void GET不可见工单返回40301() throws Exception {
        long invisibleTicketId = insertTicketInvisibleToAgentZhang();

        mockMvc.perform(get("/api/tickets/{id}/ai-analysis", invisibleTicketId)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(AGENT_ZHANG_ID)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    // ==================== 验收 2：手动触发落新记录、查询取最新 ====================

    @Test
    @DisplayName("验收2：手动触发两次 → 落两条记录，GET 返回最新那条")
    void 手动触发落新记录且查询取最新() throws Exception {
        jdbcTemplate.update("DELETE FROM ai_analysis WHERE ticket_id = ?", VPN_TICKET_ID);

        mockMvc.perform(post("/api/ai/tickets/{id}/analyze", VPN_TICKET_ID)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        mockMvc.perform(post("/api/ai/tickets/{id}/analyze", VPN_TICKET_ID)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID)))
                .andExpect(status().isOk());

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_analysis WHERE ticket_id = ?", Long.class, VPN_TICKET_ID);
        assertThat(count).as("重复触发是「新增一条」而不是覆盖").isEqualTo(2L);

        Long latestId = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM ai_analysis WHERE ticket_id = ?", Long.class, VPN_TICKET_ID);

        mockMvc.perform(get("/api/tickets/{id}/ai-analysis", VPN_TICKET_ID)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(latestId))
                .andExpect(jsonPath("$.data.ticketId").value(VPN_TICKET_ID))
                .andExpect(jsonPath("$.data.suggestions[0]").value("建议一"));
    }

    @Test
    @DisplayName("§16.12：返回体里不含 rawResponse")
    void 返回体不含rawResponse() throws Exception {
        jdbcTemplate.update("DELETE FROM ai_analysis WHERE ticket_id = ?", VPN_TICKET_ID);
        mockMvc.perform(post("/api/ai/tickets/{id}/analyze", VPN_TICKET_ID)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/tickets/{id}/ai-analysis", VPN_TICKET_ID)
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(ADMIN_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawResponse").doesNotExist());
    }

    // ==================== 验收 6：建单不受 AI 影响 ====================

    @Test
    @DisplayName("验收6：创建工单接口不阻塞（AI 是异步的，不在建单事务里）")
    void 建单接口不阻塞() throws Exception {
        long start = System.currentTimeMillis();

        mockMvc.perform(post("/api/tickets")
                        .header(HttpHeaders.AUTHORIZATION, tokenOf(EMP_WANG_ID))
                        .contentType("application/json")
                        .content("{\"title\":\"D10-04 建单耗时验证\",\"description\":\"验证 AI 分析不影响建单响应时间\","
                                + "\"type\":\"INCIDENT\",\"category\":\"NETWORK\",\"priority\":\"P2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        long cost = System.currentTimeMillis() - start;
        assertThat(cost)
                .as("建单只写 outbox，AI 跑在 MQ 消费者线程上；真同步调 AI 会是 10s+（实测 %dms）", cost)
                .isLessThan(2000);
    }

    // ==================== 工具 ====================

    private String tokenOf(long userId) {
        return "Bearer " + jwtHelper.createToken(userId);
    }

    /**
     * 造一张 agent_zhang（id=2）<b>看不见</b>的工单。
     *
     * <p>§8.3 的 AGENT 可见范围是「自己处理的 ∪ 公共池 ∪ 本部门」。种子工单基本都是
     * 公共池或本部门，AGENT 看得见 —— 所以要造一张：别人创建、别的部门、状态非公共池、处理人是别人。
     */
    private long insertTicketInvisibleToAgentZhang() {
        jdbcTemplate.update("""
                INSERT INTO ticket (ticket_no, title, description, type, category, priority, status,
                                    source, creator_id, department_id, assignee_id)
                VALUES (?, ?, ?, 'INCIDENT', 'NETWORK', 'P2', 'IN_PROGRESS', 'WEB', 5, 6, 3)
                """, "OD-D1004-" + System.nanoTime(), "D10-04 越权测试工单", "由 emp_zhao 创建、agent_li 处理，agent_zhang 不可见");
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        assertThat(id).as("造工单失败").isNotNull();
        return id;
    }
}
