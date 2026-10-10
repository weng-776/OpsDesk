package com.opsdesk.sla;

import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.Role;
import com.opsdesk.common.enums.SlaState;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.common.enums.TicketType;
import com.opsdesk.sla.service.SlaCalculator;
import com.opsdesk.sla.service.SlaScanService;
import com.opsdesk.ticket.dto.TicketCreateDTO;
import com.opsdesk.ticket.service.TicketCreateService;
import com.opsdesk.ticket.service.TicketFlowService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SLA 与状态流转的耦合回归（工单 D5-04，规格基线 §9.2 / §9.3 / §9.4 / §9.6）
 *
 * <h2>这个类存在的意义</h2>
 * Day 3/4 的 SLA 是「最小版」，Day 5 才补齐完整实现。本类把规格 §9.2~§9.6
 * <b>逐条</b>对着现有实现跑一遍 —— 它是审计结论的<b>证据</b>，不是补充说明。
 * 逐条清单见每条用例的 {@code @DisplayName} 前缀（§9.2 / §9.3 / §9.4 / §9.6）。
 *
 * <h2>为什么可以 {@code @Transactional}</h2>
 * 本类<b>全部通过 service 层调用</b>（创建 / 流转），它们都是 {@code @Transactional}，
 * 会<b>加入</b>测试事务；连 {@code SlaScanService.scan()} 里的 MP 查询与更新也走当前线程的事务。
 * 所以：造的数据在事务里可见，所有写入最后整体回滚 —— 不留痕迹。
 *
 * <p>⚠️ 断言一律用 {@code jdbcTemplate} 直读库，而不是 {@code ticketService.getById}：
 * 后者会命中 MyBatis 一级缓存，读到「改之前」的旧实体（本项目踩过 3 次）。
 * 而 {@code jdbcTemplate} 在同一个事务里能读到本事务未提交的写入。
 *
 * <h2>种子数据（OpsDesk_Seed_V1.sql）</h2>
 * <pre>
 * 工单 1  OPEN           P2 policy2 creator=4(emp_wang)  first_response_at=NULL
 * 工单 2  IN_PROGRESS    P1 policy1 assignee=2(agent_zhang) creator=5
 * 工单 3  WAITING_CONFIRM P3 policy3 creator=4  first_response_at='2026-10-06 10:20'
 * </pre>
 */
@SpringBootTest
@Transactional
class SlaCouplingRegressionTest {

    private static final long ADMIN = 1L;
    private static final long AGENT_ZHANG = 2L;
    private static final long EMP_WANG = 4L;
    private static final long EMP_ZHAO = 5L;

    private static final long TICKET_OPEN = 1L;
    private static final long TICKET_IN_PROGRESS = 2L;
    private static final long TICKET_WAITING_CONFIRM = 3L;

    /** §23.4 的锁 key（scan 用例里要先清掉残留锁） */
    private static final String LOCK_KEY = "lock:sla:scan";

    @Autowired
    private TicketCreateService ticketCreateService;

    @Autowired
    private TicketFlowService ticketFlowService;

    @Autowired
    private SlaScanService slaScanService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 裸 JDBC 改库后清 MyBatis 一级缓存（否则 service 的 getById 读到旧实体） */
    @Autowired
    private SqlSessionTemplate sqlSessionTemplate;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ==================== §9.2 起算点 ====================

    @Test
    @DisplayName("[§9.2] 起算点：两个 deadline 一律 = created_at + 策略分钟数（不是从受理时间起算）")
    void 起算点是createdAt() {
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        long id = createTicket(TicketPriority.P1);

        Map<String, Object> row = slaRow(id);
        LocalDateTime createdAt = toLdt(row.get("created_at"));

        assertThat(toLdt(row.get("response_deadline")))
                .as("§9.2：response_deadline = created_at + response_minutes")
                .isEqualTo(createdAt.plusMinutes(intOf(row.get("response_minutes"))));
        assertThat(toLdt(row.get("resolution_deadline")))
                .as("§9.2：resolution_deadline = created_at + resolution_minutes")
                .isEqualTo(createdAt.plusMinutes(intOf(row.get("resolution_minutes"))));
    }

    @Test
    @DisplayName("[§9.2] 队列等待时间计入解决时限：受理不会把 deadline 顺延到 first_response_at 之后")
    void 队列等待计入解决时限() {
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        long id = createTicket(TicketPriority.P2);

        // 模拟「工单在队列里等了 30 分钟」：created_at 与两个 deadline 一起往前挪，
        // 保持 §9.2 的算术关系不变（否则就不是「等了一会儿」而是「数据不一致」）
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> beforeShift = slaRow(id);
        LocalDateTime createdAt = toLdt(beforeShift.get("created_at"));
        jdbcTemplate.update(
                "UPDATE ticket SET created_at = ?, response_deadline = ?, resolution_deadline = ? WHERE id = ?",
                Timestamp.valueOf(createdAt.minusMinutes(30)),
                Timestamp.valueOf(toLdt(beforeShift.get("response_deadline")).minusMinutes(30)),
                Timestamp.valueOf(toLdt(beforeShift.get("resolution_deadline")).minusMinutes(30)),
                id);
        sqlSessionTemplate.clearCache();

        LocalDateTime deadlineBefore = toLdt(slaRow(id).get("resolution_deadline"));

        // 30 分钟后才受理
        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        ticketFlowService.accept(id);

        Map<String, Object> after = slaRow(id);
        LocalDateTime firstResponseAt = toLdt(after.get("first_response_at"));

        assertThat(firstResponseAt).as("已受理，写入首次响应时间").isNotNull();
        assertThat(firstResponseAt).as("受理发生在创建之后（队列里等过）").isAfter(createdAt.minusMinutes(30));
        assertThat(toLdt(after.get("resolution_deadline")))
                .as("§9.2：队列等待时间【计入】解决时限 —— 受理不会把 deadline 推后")
                .isEqualTo(deadlineBefore);
        assertThat(toLdt(after.get("resolution_deadline")))
                .as("若从受理时间起算，deadline 会是 first_response_at + minutes；实际更早")
                .isBefore(firstResponseAt.plusMinutes(intOf(after.get("resolution_minutes"))));
    }

    @Test
    @DisplayName("[§9.2] 新建工单的 sla_policy_id 指向【当时 ACTIVE】的策略版本")
    void 新建工单指向当时ACTIVE版本() {
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        long id = createTicket(TicketPriority.P2);

        Long activePolicyId = jdbcTemplate.queryForObject(
                "SELECT id FROM sla_policy WHERE priority = 'P2' AND status = 'ACTIVE'", Long.class);

        assertThat(longOf(slaRow(id).get("sla_policy_id")))
                .as("§9.1/§9.2：冻结当时 ACTIVE 的那一版（用于追溯）")
                .isEqualTo(activePolicyId);
    }

    // ==================== §9.3 响应判定 ====================

    @Test
    @DisplayName("[§9.3] 响应达标：first_response_at <= response_deadline 才算达标（恰好压线也算）")
    void 响应达标判定() {
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        long id = createTicket(TicketPriority.P2);

        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        ticketFlowService.accept(id);

        Map<String, Object> row = slaRow(id);
        LocalDateTime firstResponseAt = toLdt(row.get("first_response_at"));
        LocalDateTime responseDeadline = toLdt(row.get("response_deadline"));
        Integer responseMinutes = intOf(row.get("response_minutes"));

        assertThat(firstResponseAt).isNotNull();
        assertThat(firstResponseAt)
                .as("§9.3：响应时间早于 deadline → 达标").isBeforeOrEqualTo(responseDeadline);
        assertThat(SlaCalculator.judgeResponse(responseDeadline, firstResponseAt, responseMinutes,
                LocalDateTime.now())).isEqualTo(SlaState.NORMAL);

        // 边界：恰好压线 = 达标（原文是 <=）
        assertThat(SlaCalculator.judgeResponse(responseDeadline, responseDeadline, responseMinutes,
                LocalDateTime.now())).isEqualTo(SlaState.NORMAL);
        // 迟到 1 秒 = 未达标
        assertThat(SlaCalculator.judgeResponse(responseDeadline, responseDeadline.plusSeconds(1),
                responseMinutes, LocalDateTime.now())).isEqualTo(SlaState.BREACHED);
    }

    @Test
    @DisplayName("[§9.3] first_response_at 为空 + 已过 response_deadline → 响应侧判 BREACHED（走真实扫描）")
    void 未响应且已超时判BREACHED() {
        LocalDateTime past = LocalDateTime.now().minusMinutes(5).truncatedTo(ChronoUnit.SECONDS);
        jdbcTemplate.update(
                "UPDATE ticket SET response_deadline = ?, sla_response_state = 'NORMAL', "
                        + "sla_breach_notified = 0 WHERE id = ?", Timestamp.valueOf(past), TICKET_OPEN);
        sqlSessionTemplate.clearCache();

        assertThat(slaRow(TICKET_OPEN).get("first_response_at")).as("前置：工单 1 从未被受理").isNull();

        runScan();

        Map<String, Object> after = slaRow(TICKET_OPEN);
        assertThat(after.get("sla_response_state"))
                .as("§9.3：未响应且已超时 → 响应侧 BREACHED").isEqualTo("BREACHED");
        assertThat(intOf(after.get("sla_breach_notified"))).as("已置超时通知标志").isEqualTo(1);
    }

    // ==================== §9.4 暂停（两个状态都要暂停）====================

    @Test
    @DisplayName("[§9.4] WAITING_USER 进入即暂停：hold 写入 sla_paused_at")
    void WAITING_USER进入即暂停() {
        assertThat(slaRow(TICKET_IN_PROGRESS).get("sla_paused_at")).as("前置：工单 2 未暂停").isNull();

        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        ticketFlowService.hold(TICKET_IN_PROGRESS);

        Map<String, Object> after = slaRow(TICKET_IN_PROGRESS);
        assertThat(after.get("status")).isEqualTo("WAITING_USER");
        assertThat(after.get("sla_paused_at")).as("§9.4：进入暂停状态 → sla_paused_at = now").isNotNull();
    }

    @Test
    @DisplayName("[§9.4] ⭐ WAITING_CONFIRM 也暂停（最容易漏的一条）：resolve 写入 sla_paused_at，且扫描不判它超时")
    void WAITING_CONFIRM也暂停() {
        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        ticketFlowService.resolve(TICKET_IN_PROGRESS);

        Map<String, Object> afterResolve = slaRow(TICKET_IN_PROGRESS);
        assertThat(afterResolve.get("status")).isEqualTo("WAITING_CONFIRM");
        assertThat(afterResolve.get("sla_paused_at"))
                .as("§9.4 状态表：WAITING_CONFIRM 也是暂停态 → resolve 必须写 sla_paused_at")
                .isNotNull();

        // 暂停的另一半含义：扫描不判它超时、不发通知
        LocalDateTime past = LocalDateTime.now().minusMinutes(5).truncatedTo(ChronoUnit.SECONDS);
        jdbcTemplate.update(
                "UPDATE ticket SET resolution_deadline = ?, sla_resolution_state = 'NORMAL', "
                        + "sla_warning_notified = 0, sla_breach_notified = 0 WHERE id = ?",
                Timestamp.valueOf(past), TICKET_IN_PROGRESS);
        sqlSessionTemplate.clearCache();

        runScan();

        Map<String, Object> afterScan = slaRow(TICKET_IN_PROGRESS);
        assertThat(afterScan.get("sla_resolution_state"))
                .as("§9.4：暂停期间不应由 IT 背锅 → 扫描不判它 BREACHED").isEqualTo("NORMAL");
        assertThat(intOf(afterScan.get("sla_breach_notified")))
                .as("也不发超时通知").isZero();
        assertThat(afterScan.get("sla_paused_at")).as("扫描不会清掉暂停起点").isNotNull();
    }

    // ==================== §9.4 response_deadline 不参与顺延 ====================

    @Test
    @DisplayName("[§9.4] 响应时限不参与顺延：resume 顺延解决时限，response_deadline 逐字不变")
    void 响应时限不参与顺延_resume() {
        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        ticketFlowService.hold(TICKET_IN_PROGRESS);

        Map<String, Object> before = slaRow(TICKET_IN_PROGRESS);
        LocalDateTime responseDeadlineBefore = toLdt(before.get("response_deadline"));
        LocalDateTime resolutionDeadlineBefore = toLdt(before.get("resolution_deadline"));

        // 把暂停起点回拨 30 分钟（= 已暂停 30 分钟）；截断到整秒，避开 DATETIME(0) 的进位
        LocalDateTime pausedAt = LocalDateTime.now().minusMinutes(30).truncatedTo(ChronoUnit.SECONDS);
        jdbcTemplate.update("UPDATE ticket SET sla_paused_at = ? WHERE id = ?",
                Timestamp.valueOf(pausedAt), TICKET_IN_PROGRESS);
        sqlSessionTemplate.clearCache();

        ticketFlowService.resume(TICKET_IN_PROGRESS);

        Map<String, Object> after = slaRow(TICKET_IN_PROGRESS);
        assertThat(toLdt(after.get("response_deadline")))
                .as("§9.4：response_deadline 不参与顺延").isEqualTo(responseDeadlineBefore);
        assertThat(Duration.between(resolutionDeadlineBefore, toLdt(after.get("resolution_deadline"))).toMinutes())
                .as("§9.4：resolution_deadline 顺延了暂停时长（约 30 分钟）")
                .isBetween(29L, 31L);
        assertThat(after.get("sla_paused_at")).isNull();
    }

    @Test
    @DisplayName("[§9.4] 响应时限不参与顺延：reject 重算解决时限，response_deadline 逐字不变")
    void 响应时限不参与顺延_reject() {
        LocalDateTime responseDeadlineBefore = toLdt(slaRow(TICKET_WAITING_CONFIRM).get("response_deadline"));

        setCurrentUser(EMP_WANG, Role.EMPLOYEE);   // 工单 3 的创建人
        ticketFlowService.reject(TICKET_WAITING_CONFIRM);

        assertThat(toLdt(slaRow(TICKET_WAITING_CONFIRM).get("response_deadline")))
                .as("§9.4 / §9.6：reject 只重算解决时限，响应时限一个字都不动")
                .isEqualTo(responseDeadlineBefore);
    }

    @Test
    @DisplayName("[§9.4] 响应时限不参与顺延：close 恢复暂停，response_deadline 逐字不变")
    void 响应时限不参与顺延_close() {
        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        ticketFlowService.resolve(TICKET_IN_PROGRESS);   // 造出 WAITING_CONFIRM + 暂停

        LocalDateTime responseDeadlineBefore = toLdt(slaRow(TICKET_IN_PROGRESS).get("response_deadline"));

        setCurrentUser(EMP_ZHAO, Role.EMPLOYEE);         // 工单 2 的创建人
        ticketFlowService.close(TICKET_IN_PROGRESS);

        Map<String, Object> after = slaRow(TICKET_IN_PROGRESS);
        assertThat(after.get("status")).isEqualTo("CLOSED");
        assertThat(toLdt(after.get("response_deadline")))
                .as("§9.4：close 走的是解决时限的顺延，响应时限不动")
                .isEqualTo(responseDeadlineBefore);
    }

    // ==================== §9.6 reopen 后的 SLA ====================

    @Test
    @DisplayName("[§9.6] reopen：resolution_deadline 重算为「reopen 时间 + resolution_minutes」，first_response_at 不重置")
    void reopen重算解决时限且首次响应不重置() {
        Map<String, Object> before = slaRow(TICKET_WAITING_CONFIRM);
        LocalDateTime firstResponseAtBefore = toLdt(before.get("first_response_at"));
        Integer resolutionMinutes = intOf(before.get("resolution_minutes"));
        assertThat(firstResponseAtBefore).as("前置：工单 3 已响应过").isNotNull();

        LocalDateTime reopenAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        ticketFlowService.reject(TICKET_WAITING_CONFIRM);

        Map<String, Object> after = slaRow(TICKET_WAITING_CONFIRM);
        assertThat(after.get("status")).as("§7.3：停在 REOPENED，不一步走完").isEqualTo("REOPENED");
        assertThat(Duration.between(reopenAt, toLdt(after.get("resolution_deadline"))).toMinutes())
                .as("§9.6：新一轮解决时限 = reopen 时间 + resolution_minutes（整轮重算，不是累加）")
                .isBetween((long) resolutionMinutes - 1, (long) resolutionMinutes + 1);
        assertThat(toLdt(after.get("first_response_at")))
                .as("§9.6：first_response_at 保留首轮值，不重置").isEqualTo(firstResponseAtBefore);
        assertThat(after.get("sla_paused_at"))
                .as("§9.4：REOPENED 不是暂停态 → 必须退出暂停").isNull();
    }

    @Test
    @DisplayName("[§9.6] reopen：两个 notified 标志归零、解决状态重置 NORMAL、reopen_count +1")
    void reopen归零通知标志() {
        // 先把标志位与状态弄脏（模拟扫描已经通知过）
        jdbcTemplate.update(
                "UPDATE ticket SET sla_warning_notified = 1, sla_breach_notified = 1, "
                        + "sla_resolution_state = 'BREACHED' WHERE id = ?", TICKET_WAITING_CONFIRM);
        sqlSessionTemplate.clearCache();

        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        ticketFlowService.reject(TICKET_WAITING_CONFIRM);

        Map<String, Object> after = slaRow(TICKET_WAITING_CONFIRM);
        assertThat(intOf(after.get("sla_warning_notified")))
                .as("§9.6：sla_warning_notified 归零（新一轮要能重新提醒）").isZero();
        assertThat(intOf(after.get("sla_breach_notified")))
                .as("§9.6：sla_breach_notified 归零").isZero();
        assertThat(after.get("sla_resolution_state"))
                .as("§9.6：sla_resolution_state = NORMAL").isEqualTo("NORMAL");
        assertThat(intOf(after.get("reopen_count"))).as("§9.6：reopen_count += 1").isEqualTo(1);
    }

    // ==================== 工具 ====================

    /** 跑一轮真实扫描（先清掉可能残留的锁，否则会被跳过） */
    private void runScan() {
        redisTemplate.delete(LOCK_KEY);
        SlaScanService.ScanResult result = slaScanService.scan();
        assertThat(result.lockAcquired()).as("扫描应该抢到锁").isTrue();
    }

    /**
     * 读一张工单的 SLA 相关列 + 它冻结的那一版策略的分钟数。
     *
     * <p>用 {@code jdbcTemplate} 而不是 {@code ticketService.getById} —— 后者会命中
     * MyBatis 一级缓存，读到「改之前」的旧实体（本项目踩过 3 次）。
     */
    private Map<String, Object> slaRow(long ticketId) {
        return jdbcTemplate.queryForMap(
                "SELECT t.status, t.created_at, t.sla_policy_id, "
                        + "t.response_deadline, t.resolution_deadline, t.first_response_at, "
                        + "t.sla_response_state, t.sla_resolution_state, "
                        + "t.sla_paused_at, t.sla_paused_minutes, "
                        + "t.sla_warning_notified, t.sla_breach_notified, t.reopen_count, "
                        + "p.response_minutes, p.resolution_minutes "
                        + "FROM ticket t JOIN sla_policy p ON p.id = t.sla_policy_id "
                        + "WHERE t.id = ?", ticketId);
    }

    private long createTicket(TicketPriority priority) {
        TicketCreateDTO dto = new TicketCreateDTO();
        dto.setTitle("D5-04 回归用例");
        dto.setDescription("SLA 与状态流转耦合回归 —— 本单由测试创建，事务回滚。");
        dto.setType(TicketType.INCIDENT);
        dto.setCategory(TicketCategory.NETWORK);
        dto.setPriority(priority);
        return ticketCreateService.create(dto, null).getId();
    }

    private void setCurrentUser(long userId, Role role) {
        UserContext.set(new UserContext.CurrentUser(
                userId, "d504-jti", Set.of(role), Set.of(), departmentIdOf(userId)));
    }

    private Long departmentIdOf(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT department_id FROM `user` WHERE id = ?", Long.class, userId);
    }

    /** MySQL 的 DATETIME 经 JDBC 读出来是 {@code java.sql.Timestamp}；两种都兼容 */
    private static LocalDateTime toLdt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        throw new IllegalStateException("无法识别的时间类型：" + value.getClass());
    }

    /** TINYINT 经 JDBC 可能是 Integer，也可能是 Boolean（tinyInt1isBit）—— 两种都兼容 */
    private static int intOf(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        throw new IllegalStateException("无法识别的整数类型：" + value.getClass());
    }

    /**
     * id 列经 JDBC 可能是 {@code Long}，也可能是 {@code BigInteger}
     * （DDL 里 {@code id} 是 {@code BIGINT UNSIGNED}，Connector/J 会映射成 {@code BigInteger}）——
     * 直接用 {@code assertThat(map.get(...)).isEqualTo(2L)} 会「expected: 2L but was: 2」。
     */
    private static long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("无法识别的 id 类型：" + (value == null ? "null" : value.getClass()));
    }
}
