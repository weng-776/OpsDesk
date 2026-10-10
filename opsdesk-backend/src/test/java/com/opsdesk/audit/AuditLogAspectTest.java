package com.opsdesk.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsdesk.audit.service.AuditRecordService;
import com.opsdesk.audit.service.AuditRequestContext;
import com.opsdesk.common.BizException;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.AuditOperation;
import com.opsdesk.common.enums.AuditResourceType;
import com.opsdesk.common.enums.Role;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.common.enums.TicketType;
import com.opsdesk.ticket.dto.TicketAssignDTO;
import com.opsdesk.ticket.dto.TicketCreateDTO;
import com.opsdesk.ticket.service.TicketCreateService;
import com.opsdesk.ticket.service.TicketFlowService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code @AuditLog} 注解 + AOP 切面验收测试（工单 D6-01，规格基线 §14.2 / §14.3）
 *
 * <p>本类 {@code @Transactional}：审计与业务<b>同事务</b>，所以整类跑完自动回滚，
 * 不会在开发库里留下审计行（也正好是「同事务」这件事的间接证明）。
 *
 * <h2>⚠️ 非 Web 上下文的断言</h2>
 * 测试里没有 {@code HttpServletRequest} → {@code ip} / {@code user_agent} 必须是
 * {@code null}（不是空串、更不是异常）。这正是定时任务 / MQ 消费者会走的路径。
 *
 * <h2>⚠️ 断言一律用 {@code jdbcTemplate} 直读</h2>
 * 不用 {@code auditLogService.getById} —— 后者命中 MyBatis 一级缓存可能读到旧实体
 * （本项目已踩过 4 次）。
 */
@SpringBootTest
@Transactional
class AuditLogAspectTest {

    private static final long ADMIN = 1L;
    private static final long AGENT_ZHANG = 2L;
    private static final long AGENT_LI = 3L;
    private static final long EMP_WANG = 4L;

    private static final long TICKET_OPEN = 1L;
    private static final long TICKET_WAITING_CONFIRM = 3L;

    /** 与 {@code AuditRecordServiceImpl.MAX_TEXT_LENGTH} 对齐（§14.3 的 2000 字符） */
    private static final int MAX_TEXT_LENGTH = 2000;

    private static final String AUDIT_COLUMNS =
            "id, user_id, operation, resource_type, resource_id, "
                    + "before_data, after_data, ip, user_agent, created_at";

    @Autowired
    private TicketFlowService ticketFlowService;

    @Autowired
    private TicketCreateService ticketCreateService;

    @Autowired
    private AuditRecordService auditRecordService;

    @Autowired
    private AuditRequestContext auditRequestContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        UserContext.clear();
        // 本类自己绑过 Mock 请求（Web 分支用例），跑完必须还原
        RequestContextHolder.resetRequestAttributes();
    }

    // ==================== 验收 1：分派后留下审计，且前后快照不同 ====================

    @Test
    @DisplayName("验收1：分派工单后 audit_log 有一条 TICKET_ASSIGN，before/after 都非空且内容不同")
    void 分派工单留下审计() throws Exception {
        setCurrentUser(ADMIN, Role.ADMIN);
        ticketFlowService.assign(TICKET_OPEN, assignDto(AGENT_ZHANG));

        Map<String, Object> row = latestAuditFor(TICKET_OPEN);

        assertThat(row.get("operation")).isEqualTo("TICKET_ASSIGN");
        assertThat(row.get("resource_type")).isEqualTo("TICKET");
        assertThat(longOf(row.get("resource_id"))).isEqualTo(TICKET_OPEN);
        assertThat(longOf(row.get("user_id"))).as("操作人 = UserContext 里的当前用户").isEqualTo(ADMIN);

        String before = (String) row.get("before_data");
        String after = (String) row.get("after_data");
        assertThat(before).as("§14.3：执行前查一次快照").isNotNull();
        assertThat(after).as("§14.3：执行后重新查一次").isNotNull();
        assertThat(after).as("两次快照必须不同 —— 否则等于没记到变更").isNotEqualTo(before);

        // 快照内容确实反映了这次分派（而不是恰好「非空但没变」）
        JsonNode beforeNode = objectMapper.readTree(before);
        JsonNode afterNode = objectMapper.readTree(after);
        assertThat(beforeNode.get("status").asText()).isEqualTo("OPEN");
        assertThat(afterNode.get("status").asText()).isEqualTo("ASSIGNED");
        assertThat(beforeNode.get("assigneeId").isNull()).as("分派前无处理人").isTrue();
        assertThat(afterNode.get("assigneeId").asLong()).isEqualTo(AGENT_ZHANG);

        // ⚠️ 这里不断言 row 的 ip / user_agent：@SpringBootTest 的 ServletTestExecutionListener
        //    会在测试方法前绑一个 MockHttpServletRequest，所以测试里 ip 一定不是 null。
        //    「非 Web 上下文降级为 null」与「Web 上下文取 XFF / 截断 UA」各有一条专门的用例。
        assertThat(row.get("created_at")).as("created_at 由 DB 默认值填").isNotNull();
    }

    @Test
    @DisplayName("Web 上下文：ip 取 X-Forwarded-For 首段，UA 按 VARCHAR(255) 截断")
    void Web上下文取ip与ua() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // XFF 是逗号分隔链路，取最靠近客户端的第一段
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        request.addHeader("User-Agent", "U".repeat(300));
        request.setRemoteAddr("10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(auditRequestContext.currentIp()).isEqualTo("203.0.113.7");
        assertThat(auditRequestContext.currentUserAgent())
                .as("audit_log.user_agent 是 VARCHAR(255)，不截断会让 insert 失败")
                .hasSize(255);

        // 没有 XFF 时退回 getRemoteAddr()
        RequestContextHolder.resetRequestAttributes();
        MockHttpServletRequest fallback = new MockHttpServletRequest();
        fallback.setRemoteAddr("198.51.100.9");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(fallback));
        assertThat(auditRequestContext.currentIp()).isEqualTo("198.51.100.9");
    }

    @Test
    @DisplayName("业务方法抛异常（工单不存在 → 40400）→ 一行审计都不写")
    void 业务异常不写审计() {
        setCurrentUser(ADMIN, Role.ADMIN);

        assertThatThrownBy(() -> ticketFlowService.assign(999_999L, assignDto(AGENT_ZHANG)))
                .isInstanceOf(BizException.class);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE resource_id = 999999", Integer.class);
        assertThat(count).as("§14.3：失败的操作不写审计（after_data 的定义是「变更后」）").isZero();
    }

    // ==================== 验收 2：超长字段被截断且不报错 ====================

    @Test
    @DisplayName("验收2：超过 2000 字符的字段被截断，且整个 JSON 仍然合法（没被拦腰截断）")
    void 超长字段被截断且JSON仍合法() throws Exception {
        String longDescription = "A".repeat(MAX_TEXT_LENGTH + 500);

        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        long ticketId = createTicket(longDescription);

        setCurrentUser(ADMIN, Role.ADMIN);
        ticketFlowService.assign(ticketId, assignDto(AGENT_ZHANG));

        String after = (String) latestAuditFor(ticketId).get("after_data");
        assertThat(after).isNotNull();

        // ⭐ 能反序列化 = JSON 合法 —— 证明截的是「字段」而不是把整段 JSON 切断
        JsonNode node = objectMapper.readTree(after);
        String description = node.get("description").asText();
        assertThat(description).as("§14.3：长文本截断至 %s 字符", MAX_TEXT_LENGTH)
                .hasSize(MAX_TEXT_LENGTH);
        assertThat(description).as("截断后应是原值的前缀").isEqualTo("A".repeat(MAX_TEXT_LENGTH));
        assertThat(after).as("整体长度也远小于 TEXT 的 64KB 上限").hasSizeLessThan(60_000);
    }

    // ==================== 操作码映射（§3.11 没有 ACCEPT/START/... 这些码）====================

    @Test
    @DisplayName("操作码映射：assign/transfer/accept/close 各自落到 §3.11 的正确码")
    void 操作码映射正确() {
        setCurrentUser(ADMIN, Role.ADMIN);
        ticketFlowService.assign(TICKET_OPEN, assignDto(AGENT_ZHANG));      // TICKET_ASSIGN
        ticketFlowService.transfer(TICKET_OPEN, assignDto(AGENT_LI));       // TICKET_TRANSFER

        // §3.11 里没有 TICKET_ACCEPT → 统一记 TICKET_STATUS_CHANGE
        setCurrentUser(AGENT_LI, Role.AGENT);
        ticketFlowService.start(TICKET_OPEN);                              // TICKET_STATUS_CHANGE

        setCurrentUser(EMP_WANG, Role.EMPLOYEE);                            // 工单 3 的创建人
        ticketFlowService.close(TICKET_WAITING_CONFIRM);                    // TICKET_CLOSE

        assertThat(operationsFor(TICKET_OPEN))
                .as("按 id 倒序：最近的在前（工单 1 没有种子审计行，可以精确断言）")
                .containsExactly("TICKET_STATUS_CHANGE", "TICKET_TRANSFER", "TICKET_ASSIGN");
        // ⚠️ 工单 3 在种子里**本来就有一条** TICKET_STATUS_CHANGE 审计行（OpsDesk_Seed_V1.sql），
        //    所以只能断言「最新一条」，不能断言全量 —— 同 known-traps「断言子表条数要带够条件」
        assertThat(latestOperationFor(TICKET_WAITING_CONFIRM)).isEqualTo("TICKET_CLOSE");
    }

    // ==================== 快照服务与请求上下文的边界 ====================

    @Test
    @DisplayName("快照：TICKET 有 provider；未注册的类型与不存在的资源都安全降级为 null")
    void 快照降级路径() {
        String ticketJson = auditRecordService.snapshot(AuditResourceType.TICKET, TICKET_OPEN);
        assertThat(ticketJson).as("TICKET 已注册 provider").isNotNull();
        assertThat(ticketJson).contains("ticketNo").contains("OD2026100600001");

        assertThat(auditRecordService.snapshot(AuditResourceType.USER, 1L))
                .as("USER 还没注册 provider → 降级为 null，不阻断业务").isNull();
        assertThat(auditRecordService.snapshot(AuditResourceType.TICKET, 999_999L))
                .as("资源不存在 → null").isNull();
        assertThat(auditRecordService.snapshot(AuditResourceType.TICKET, null))
                .as("resourceId 为空 → null").isNull();
    }

    @Test
    @DisplayName("无登录上下文时记为系统操作（user_id=0），不抛异常")
    void 无登录上下文记为系统操作() {
        UserContext.clear();

        auditRecordService.record(AuditOperation.SLA_POLICY_UPDATE,
                AuditResourceType.SLA_POLICY, 1L, null, null);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT " + AUDIT_COLUMNS + " FROM audit_log WHERE operation = 'SLA_POLICY_UPDATE' "
                        + "ORDER BY id DESC LIMIT 1");
        assertThat(longOf(row.get("user_id")))
                .as("audit_log.user_id 是 NOT NULL；无登录上下文记 0（系统）").isZero();
        assertThat(row.get("before_data")).isNull();
        assertThat(row.get("after_data")).isNull();
    }

    @Test
    @DisplayName("非 Web 上下文：ip / user_agent 取值为 null（不抛异常）")
    void 非Web上下文安全降级() {
        // ⚠️ @SpringBootTest 默认（MOCK）环境下，ServletTestExecutionListener 会在每个测试方法前
        //    绑一个 MockHttpServletRequest —— 不清掉的话这里拿到的是 "127.0.0.1"，
        //    根本测不到「没有请求」这条分支。
        RequestContextHolder.resetRequestAttributes();

        assertThat(auditRequestContext.currentIp()).isNull();
        assertThat(auditRequestContext.currentUserAgent()).isNull();
    }

    // ==================== 工具 ====================

    private Map<String, Object> latestAuditFor(long resourceId) {
        return jdbcTemplate.queryForMap(
                "SELECT " + AUDIT_COLUMNS + " FROM audit_log "
                        + "WHERE resource_type = 'TICKET' AND resource_id = ? "
                        + "ORDER BY id DESC LIMIT 1", resourceId);
    }

    private List<String> operationsFor(long resourceId) {
        return jdbcTemplate.queryForList(
                "SELECT operation FROM audit_log "
                        + "WHERE resource_type = 'TICKET' AND resource_id = ? ORDER BY id DESC",
                String.class, resourceId);
    }

    /** 该资源最新一条审计的操作码（种子数据里已有历史审计行时，只能这样断言） */
    private String latestOperationFor(long resourceId) {
        return jdbcTemplate.queryForObject(
                "SELECT operation FROM audit_log "
                        + "WHERE resource_type = 'TICKET' AND resource_id = ? ORDER BY id DESC LIMIT 1",
                String.class, resourceId);
    }

    private long createTicket(String description) {
        TicketCreateDTO dto = new TicketCreateDTO();
        dto.setTitle("D6-01 审计用例");
        dto.setDescription(description);
        dto.setType(TicketType.INCIDENT);
        dto.setCategory(TicketCategory.NETWORK);
        dto.setPriority(TicketPriority.P2);
        return ticketCreateService.create(dto, null).getId();
    }

    private TicketAssignDTO assignDto(long assigneeId) {
        TicketAssignDTO dto = new TicketAssignDTO();
        dto.setAssigneeId(assigneeId);
        return dto;
    }

    private void setCurrentUser(long userId, Role role) {
        UserContext.set(new UserContext.CurrentUser(
                userId, "d601-jti", Set.of(role), Set.of(), departmentIdOf(userId)));
    }

    private Long departmentIdOf(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT department_id FROM `user` WHERE id = ?", Long.class, userId);
    }

    /** {@code BIGINT UNSIGNED} 经 JDBC 是 {@code BigInteger}，不是 {@code Long} */
    private static long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("无法识别的数值类型：" + (value == null ? "null" : value.getClass()));
    }
}
