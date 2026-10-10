package com.opsdesk.audit;

import com.opsdesk.audit.dto.AuditLogQuery;
import com.opsdesk.audit.service.AuditLogQueryService;
import com.opsdesk.audit.vo.AuditLogVO;
import com.opsdesk.common.PageResult;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.AuditOperation;
import com.opsdesk.common.enums.AuditResourceType;
import com.opsdesk.common.enums.Role;
import com.opsdesk.ticket.dto.TicketAssignDTO;
import com.opsdesk.ticket.service.TicketFlowService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计日志查询验收测试（工单 D6-02，API 文档 §13.1 / §16.16）
 *
 * <h2>⚠️ 为什么测试自己插审计行，而不是用种子数据</h2>
 * {@code audit_log} 有 4 行种子数据，而且**非事务测试**（D6-01 起流转会写审计）
 * 会往表里提交真实行 —— 靠种子行数做断言迟早假红。
 * 所以这里用 {@link JdbcTemplate} **直接插**几行，并**自己钉死 {@code created_at}**：
 * 于是筛选/分页/时间范围全部变成确定性的，跟表里还有多少别的行无关。
 * 用 {@code startTime} 把断言范围限定在自己插的这些行上。
 *
 * <h2>为什么可以 {@code @Transactional}</h2>
 * 只读查询 + 本类自己插的行都在测试事务里，跑完整体回滚，不留痕迹。
 */
@SpringBootTest
@Transactional
class AuditLogQueryServiceTest {

    private static final long ADMIN = 1L;
    private static final long AGENT_ZHANG = 2L;
    private static final long EMP_WANG = 4L;

    /** 本类自插行的统一时间基准（2026-10-10 09:00 / 10:00 / 11:00） */
    private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 10, 9, 0, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 10, 10, 10, 0, 0);
    private static final LocalDateTime T3 = LocalDateTime.of(2026, 10, 10, 11, 0, 0);

    /** 只看本类自插行的下界 —— 种子审计行都是 2026-10-06，会被它挡在外面 */
    private static final LocalDateTime OWN_ROWS_FROM = LocalDateTime.of(2026, 10, 10, 0, 0, 0);

    /**
     * 本类自插行用的 resourceId 段。
     *
     * <p>⚠️ 必须与真实业务用到的 id（工单 1..5）**错开**：否则「真实流转写下的那条审计」
     * 会和自插行撞在同一个筛选条件里，断言条数就会多出来一条（这是踩过的坑）。
     */
    private static final long SYNTHETIC_RESOURCE_ID = 900_001L;

    @Autowired
    private AuditLogQueryService auditLogQueryService;

    @Autowired
    private TicketFlowService ticketFlowService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @BeforeEach
    void insertOwnAuditRows() {
        insertAudit(AuditOperation.TICKET_ASSIGN, AuditResourceType.TICKET, SYNTHETIC_RESOURCE_ID, ADMIN,
                "{\"status\":\"OPEN\"}", "{\"status\":\"ASSIGNED\"}", T1);
        insertAudit(AuditOperation.TICKET_STATUS_CHANGE, AuditResourceType.TICKET,
                SYNTHETIC_RESOURCE_ID + 1, AGENT_ZHANG,
                "{\"status\":\"ASSIGNED\"}", "{\"status\":\"IN_PROGRESS\"}", T2);
        insertAudit(AuditOperation.USER_ROLE_CHANGE, AuditResourceType.USER,
                SYNTHETIC_RESOURCE_ID + 1, ADMIN,
                "{\"roles\":[\"EMPLOYEE\"]}", "{\"roles\":[\"AGENT\"]}", T3);
    }

    // ==================== 验收 2：筛选条件 ====================

    @Test
    @DisplayName("验收2：不带筛选 → 返回本类插入的全部 3 行，且按时间倒序（最新在前）")
    void 无筛选按时间倒序() {
        PageResult<AuditLogVO> result = page(q -> q.setStartTime(OWN_ROWS_FROM));

        assertThat(result.getTotal()).isEqualTo(3);
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(10);
        assertThat(result.getList()).extracting(AuditLogVO::getCreatedAt)
                .as("最新在前").containsExactly(T3, T2, T1);
    }

    @Test
    @DisplayName("验收2：userId / operation / resourceType / resourceId 四个筛选各自生效")
    void 各筛选条件生效() {
        // userId
        PageResult<AuditLogVO> byUser = page(q -> {
            q.setStartTime(OWN_ROWS_FROM);
            q.setUserId(ADMIN);
        });
        assertThat(byUser.getTotal()).isEqualTo(2);
        assertThat(byUser.getList()).extracting(AuditLogVO::getUserId).containsOnly(ADMIN);

        // operation
        PageResult<AuditLogVO> byOp = page(q -> {
            q.setStartTime(OWN_ROWS_FROM);
            q.setOperation(AuditOperation.USER_ROLE_CHANGE);
        });
        assertThat(byOp.getTotal()).isEqualTo(1);
        assertThat(byOp.getList().get(0).getResourceType()).isEqualTo(AuditResourceType.USER);

        // resourceType
        PageResult<AuditLogVO> byType = page(q -> {
            q.setStartTime(OWN_ROWS_FROM);
            q.setResourceType(AuditResourceType.TICKET);
        });
        assertThat(byType.getTotal()).isEqualTo(2);

        // resourceType + resourceId 组合（命中 idx_audit_resource 的最左前缀）
        PageResult<AuditLogVO> byResource = page(q -> {
            q.setStartTime(OWN_ROWS_FROM);
            q.setResourceType(AuditResourceType.TICKET);
            q.setResourceId(SYNTHETIC_RESOURCE_ID + 1);
        });
        assertThat(byResource.getTotal()).isEqualTo(1);
        assertThat(byResource.getList().get(0).getOperation())
                .isEqualTo(AuditOperation.TICKET_STATUS_CHANGE);

        // 组合条件查不到 → 空列表，而不是 40400
        PageResult<AuditLogVO> empty = page(q -> {
            q.setStartTime(OWN_ROWS_FROM);
            q.setResourceType(AuditResourceType.TICKET);
            q.setResourceId(999_999L);
        });
        assertThat(empty.getTotal()).isZero();
        assertThat(empty.getList()).isEmpty();
    }

    // ==================== 验收 2：分页 ====================

    @Test
    @DisplayName("验收2：分页正确 —— total 稳定、两页不重叠、id 做 tiebreaker")
    void 分页正确() {
        AuditLogQuery query = new AuditLogQuery();
        query.setStartTime(OWN_ROWS_FROM);
        query.setSize(2L);

        query.setPage(1L);
        PageResult<AuditLogVO> first = auditLogQueryService.page(query);
        query.setPage(2L);
        PageResult<AuditLogVO> second = auditLogQueryService.page(query);

        assertThat(first.getTotal()).isEqualTo(3);
        assertThat(second.getTotal()).as("total 与页码无关").isEqualTo(3);
        assertThat(first.getList()).hasSize(2);
        assertThat(second.getList()).hasSize(1);

        Set<Long> ids = java.util.stream.Stream
                .concat(first.getList().stream(), second.getList().stream())
                .map(AuditLogVO::getId)
                .collect(Collectors.toSet());
        assertThat(ids).as("两页合起来不重不漏").hasSize(3);

        assertThat(first.getList()).extracting(AuditLogVO::getCreatedAt).containsExactly(T3, T2);
        assertThat(second.getList()).extracting(AuditLogVO::getCreatedAt).containsExactly(T1);
    }

    @Test
    @DisplayName("分页：size 越界被收敛到上限 50（§20.1）")
    void size越界被收敛() {
        PageResult<AuditLogVO> result = page(q -> {
            q.setStartTime(OWN_ROWS_FROM);
            q.setSize(9999L);
        });
        assertThat(result.getSize()).as("§20.1：size 最大 50").isEqualTo(50);
    }

    // ==================== 验收 3：时间范围无时区偏差 ====================

    @Test
    @DisplayName("验收3：时间范围闭区间、无时区偏差 —— 恰好落在边界的那一行能被查到")
    void 时间范围闭区间且无时区偏差() {
        // 上下界都精确到「那一秒」，闭区间应当把 T2 那一行包进来
        PageResult<AuditLogVO> result = page(q -> {
            q.setStartTime(T2);
            q.setEndTime(T2);
        });

        assertThat(result.getTotal()).as("startTime/endTime 都是闭区间").isEqualTo(1);
        AuditLogVO vo = result.getList().get(0);
        assertThat(vo.getCreatedAt())
                .as("读出来必须与库里逐秒一致 —— 任何时区换算都会在这里露出来")
                .isEqualTo(T2);

        // 上界少 1 秒 → 查不到（证明上界真的生效，不是被忽略）
        PageResult<AuditLogVO> before = page(q -> {
            q.setStartTime(T2);
            q.setEndTime(T2.minusSeconds(1));
        });
        assertThat(before.getTotal()).isZero();

        // 半开区间 [T1, T2) 等价于 [T1, T2-1s] → 只命中 T1
        PageResult<AuditLogVO> range = page(q -> {
            q.setStartTime(T1);
            q.setEndTime(T2.minusSeconds(1));
        });
        assertThat(range.getList()).extracting(AuditLogVO::getCreatedAt).containsExactly(T1);
    }

    // ==================== 出参装配（§16.16）====================

    @Test
    @DisplayName("出参：userName 批量装配；before/after 是 JSON 对象（不是转义字符串）")
    void 出参装配正确() {
        PageResult<AuditLogVO> result = page(q -> {
            q.setStartTime(OWN_ROWS_FROM);
            q.setOperation(AuditOperation.TICKET_ASSIGN);
        });

        AuditLogVO vo = result.getList().get(0);
        assertThat(vo.getUserName()).as("userId=1 → admin 的显示名").isNotBlank();
        assertThat(vo.getResourceType()).isEqualTo(AuditResourceType.TICKET);
        assertThat(vo.getResourceId()).isEqualTo(SYNTHETIC_RESOURCE_ID);

        // ⭐ §16.16 把这两个字段标成 object：应当是真正的 JSON 对象，能直接取子字段
        assertThat(vo.getBeforeData().get("status").asText()).isEqualTo("OPEN");
        assertThat(vo.getAfterData().get("status").asText()).isEqualTo("ASSIGNED");

        // §16.16 恰好这 10 个字段
        Set<String> fields = java.util.Arrays.stream(AuditLogVO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .collect(Collectors.toSet());
        assertThat(fields).containsExactlyInAnyOrder("id", "userId", "userName", "operation",
                "resourceType", "resourceId", "beforeData", "afterData", "ip", "userAgent", "createdAt");
    }

    @Test
    @DisplayName("出参：user_id=0（系统操作）→ userName 显示为「系统」")
    void 系统操作的显示名() {
        insertAudit(AuditOperation.SLA_POLICY_UPDATE, AuditResourceType.SLA_POLICY,
                SYNTHETIC_RESOURCE_ID, 0L, null, null, T3.plusMinutes(30));

        PageResult<AuditLogVO> result = page(q -> {
            q.setStartTime(OWN_ROWS_FROM);
            q.setOperation(AuditOperation.SLA_POLICY_UPDATE);
        });

        assertThat(result.getList().get(0).getUserName()).isEqualTo("系统");
        assertThat(result.getList().get(0).getBeforeData()).as("没有快照 → null").isNull();
    }

    @Test
    @DisplayName("出参：脏数据（非法 JSON）降级为 null，不让整页查询失败")
    void 非法JSON降级() {
        insertAudit(AuditOperation.TICKET_CLOSE, AuditResourceType.TICKET,
                SYNTHETIC_RESOURCE_ID + 2, ADMIN,
                "这不是 JSON", "{\"ok\":true}", T3.plusMinutes(45));

        PageResult<AuditLogVO> result = page(q -> {
            q.setStartTime(OWN_ROWS_FROM);
            q.setOperation(AuditOperation.TICKET_CLOSE);
        });

        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).getBeforeData()).as("坏的那条降级为 null").isNull();
        assertThat(result.getList().get(0).getAfterData().get("ok").asBoolean()).isTrue();
    }

    // ==================== 与 D6-01 的端到端衔接 ====================

    @Test
    @DisplayName("端到端：走真实 @AuditLog 的分派 → 能被查询接口按 resourceType+resourceId 查到")
    void 真实审计能被查到() {
        UserContext.set(new UserContext.CurrentUser(
                ADMIN, "d602-jti", Set.of(Role.ADMIN), Set.of(), departmentIdOf(ADMIN)));

        TicketAssignDTO dto = new TicketAssignDTO();
        dto.setAssigneeId(AGENT_ZHANG);
        ticketFlowService.assign(1L, dto);

        AuditLogQuery query = new AuditLogQuery();
        query.setResourceType(AuditResourceType.TICKET);
        query.setResourceId(1L);
        query.setOperation(AuditOperation.TICKET_ASSIGN);
        query.setUserId(ADMIN);
        PageResult<AuditLogVO> result = auditLogQueryService.page(query);

        assertThat(result.getTotal()).as("恰好一条：本次分派写下的那条").isEqualTo(1);
        AuditLogVO vo = result.getList().get(0);
        assertThat(vo.getOperation()).isEqualTo(AuditOperation.TICKET_ASSIGN);
        assertThat(vo.getBeforeData().get("status").asText()).isEqualTo("OPEN");
        assertThat(vo.getAfterData().get("status").asText()).isEqualTo("ASSIGNED");
        assertThat(vo.getAfterData().get("assigneeId").asLong()).isEqualTo(AGENT_ZHANG);
        assertThat(vo.getUserId()).isEqualTo(ADMIN);
    }

    // ==================== 工具 ====================

    private PageResult<AuditLogVO> page(java.util.function.Consumer<AuditLogQuery> customizer) {
        AuditLogQuery query = new AuditLogQuery();
        customizer.accept(query);
        return auditLogQueryService.page(query);
    }

    /** 直接插一行审计并**钉死 created_at** —— 让筛选/分页断言完全确定 */
    private void insertAudit(AuditOperation operation, AuditResourceType resourceType, Long resourceId,
                             long userId, String beforeData, String afterData, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO audit_log (user_id, operation, resource_type, resource_id, "
                            + "before_data, after_data, ip, user_agent, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, operation.name());
            ps.setString(3, resourceType.name());
            if (resourceId == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            }
            else {
                ps.setLong(4, resourceId);
            }
            ps.setString(5, beforeData);
            ps.setString(6, afterData);
            ps.setString(7, "127.0.0.1");
            ps.setString(8, "JUnit");
            ps.setTimestamp(9, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);

        assertThat(keyHolder.getKey()).as("插入应当返回自增 id").isNotNull();
    }

    private Long departmentIdOf(long userId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT department_id FROM `user` WHERE id = ?", Long.class, userId);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
