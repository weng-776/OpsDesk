package com.opsdesk.ticket;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.Role;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.service.TicketFlowService;
import com.opsdesk.ticket.service.TicketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §25.4 状态机用例 —— <b>四条一次性跑通</b>（工单 D4-05 的验收 3 明确要求）
 *
 * <pre>
 * #1  CLOSED 工单执行 assign            → 40900
 * #2  两人同时 accept 同一工单          → 一人成功，一人 40900
 * #3  IN_PROGRESS 直接 close            → 40900
 * #4  WAITING_CONFIRM 执行 reject       → REOPENED，reopen_count = 1，resolution_deadline 重算
 * </pre>
 *
 * <h2>⚠️ 本类刻意<b>不加</b> {@code @Transactional}</h2>
 * 用例 #2 必须让两个线程各自开事务、真正竞争，否则乐观锁测不出来（会假绿）。
 * 代价是不能靠回滚，必须在 {@link #restore()} 里把动过的种子行还原回去。
 * 动到的是：工单 1（#2 accept）与工单 3（#4 reject）。
 *
 * <p>用例 #1 / #3 是「非法流转被拒」，不写库，无需还原。
 */
@SpringBootTest
class StateMachineUseCasesTest {

    private static final long TICKET_OPEN = 1L;          // OPEN，公共池
    private static final long TICKET_IN_PROGRESS = 2L;   // IN_PROGRESS
    private static final long TICKET_WAITING_CONFIRM = 3L; // WAITING_CONFIRM
    private static final long TICKET_CLOSED = 4L;        // CLOSED（终态）

    private static final long ADMIN = 1L;
    private static final long AGENT_ZHANG = 2L;
    private static final long AGENT_LI = 3L;

    @Autowired
    private TicketFlowService ticketFlowService;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 工单 1 的原始快照（用例 #2 用） */
    private Map<String, Object> openSnapshot;

    /** 工单 3 的原始快照（用例 #4 用） */
    private Map<String, Object> waitingConfirmSnapshot;

    /**
     * 跑用例前的 {@code audit_log} 最大 id。
     *
     * <p>本类不带事务，流转写下的审计行会真提交 —— {@code @AfterEach} 用这个基线把
     * 「本次跑出来的行」整段删掉，同时**绝不碰种子审计数据**。
     */
    private Long auditIdBaseline;

    @BeforeEach
    void captureAuditBaseline() {
        auditIdBaseline = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM audit_log", Long.class);
    }

    @AfterEach
    void restore() {
        UserContext.clear();

        if (openSnapshot != null) {
            jdbcTemplate.update("UPDATE ticket SET status = ?, assignee_id = ?, first_response_at = ?, "
                            + "department_id = ?, version = ? WHERE id = ?",
                    openSnapshot.get("status"), openSnapshot.get("assignee_id"),
                    openSnapshot.get("first_response_at"), openSnapshot.get("department_id"),
                    openSnapshot.get("version"), TICKET_OPEN);
            jdbcTemplate.update("DELETE FROM ticket_history WHERE ticket_id = ? AND action = 'ACCEPT'",
                    TICKET_OPEN);
            openSnapshot = null;
        }

        if (waitingConfirmSnapshot != null) {
            jdbcTemplate.update("UPDATE ticket SET status = ?, reopen_count = ?, resolution_deadline = ?, "
                            + "sla_resolution_state = ?, sla_warning_notified = ?, sla_breach_notified = ?, "
                            + "sla_paused_at = ?, sla_paused_minutes = ?, version = ? WHERE id = ?",
                    waitingConfirmSnapshot.get("status"), waitingConfirmSnapshot.get("reopen_count"),
                    waitingConfirmSnapshot.get("resolution_deadline"),
                    waitingConfirmSnapshot.get("sla_resolution_state"),
                    waitingConfirmSnapshot.get("sla_warning_notified"),
                    waitingConfirmSnapshot.get("sla_breach_notified"),
                    waitingConfirmSnapshot.get("sla_paused_at"),
                    waitingConfirmSnapshot.get("sla_paused_minutes"),
                    waitingConfirmSnapshot.get("version"), TICKET_WAITING_CONFIRM);
            jdbcTemplate.update("DELETE FROM ticket_history WHERE ticket_id = ? AND action = 'REJECT'",
                    TICKET_WAITING_CONFIRM);
            waitingConfirmSnapshot = null;
        }

        // ⚠️ D6-01 起，流转会写审计（@AuditLog）。本类**不带事务**（用例 #2 要真并发），
        //    所以那些审计行是**真提交**的 —— 不清理会在 audit_log 里留 residue，
        //    让后续依赖审计表的断言假红。与上面删 ticket_history 是同一个道理。
        //    ⚠️ 按「审计 id 基线」删，而不是按 resource_id / user_id 删：
        //       audit_log 有种子数据，且种子里 `resource_id = 3` 恰好是 `user_id = 3` 的行，
        //       按用户过滤会误删种子。id 基线对种子永远安全。
        if (auditIdBaseline != null) {
            jdbcTemplate.update("DELETE FROM audit_log WHERE id > ?", auditIdBaseline);
            auditIdBaseline = null;
        }
    }

    // ==================== 用例 #1 ====================

    @Test
    @DisplayName("§25.4 #1：CLOSED 工单执行 assign → 40900")
    void 用例1_终态工单不可分派() {
        setCurrentUser(ADMIN, Role.ADMIN);
        assertThat(catchBiz(() -> ticketFlowService.assign(TICKET_CLOSED, assignDto(AGENT_LI))))
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ==================== 用例 #2 ====================

    @Test
    @DisplayName("§25.4 #2：两人同时 accept 同一工单 → 恰好一人成功、一人 40900")
    void 用例2_并发受理() throws Exception {
        openSnapshot = jdbcTemplate.queryForMap(
                "SELECT status, assignee_id, first_response_at, department_id, version FROM ticket WHERE id = ?",
                TICKET_OPEN);
        assertThat(openSnapshot.get("status")).as("前置：工单 1 必须是 OPEN").isEqualTo("OPEN");

        // 让工单落在两位 AGENT 都能看见的部门（技术部/后端组）——
        // 这样可见性不会成为干扰变量，竞争点纯粹落在状态机 + CAS 上
        Long sharedDept = jdbcTemplate.queryForObject(
                "SELECT department_id FROM `user` WHERE id = ?", Long.class, AGENT_ZHANG);
        jdbcTemplate.update("UPDATE ticket SET department_id = ? WHERE id = ?", sharedDept, TICKET_OPEN);

        long[] actors = {AGENT_ZHANG, AGENT_LI};
        CountDownLatch ready = new CountDownLatch(actors.length);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(actors.length);
        List<Future<ErrorCode>> futures = new ArrayList<>();
        try {
            for (long actor : actors) {
                Long deptId = jdbcTemplate.queryForObject(
                        "SELECT department_id FROM `user` WHERE id = ?", Long.class, actor);
                futures.add(pool.submit(() -> {
                    // UserContext 是 ThreadLocal —— 必须在线程内部设置
                    UserContext.set(new UserContext.CurrentUser(
                            actor, "jti-" + actor, Set.of(Role.AGENT), Set.of(), deptId));
                    try {
                        ready.countDown();
                        go.await(10, TimeUnit.SECONDS);
                        ticketFlowService.accept(TICKET_OPEN);
                        return null;
                    }
                    catch (BizException e) {
                        return e.getErrorCode();
                    }
                    finally {
                        UserContext.clear();
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<ErrorCode> results = new ArrayList<>();
            for (Future<ErrorCode> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(results.stream().filter(java.util.Objects::isNull).count())
                    .as("恰好一人成功").isEqualTo(1);
            assertThat(results.stream().filter(ErrorCode.CONFLICT::equals).count())
                    .as("恰好一人 40900").isEqualTo(1);
        }
        finally {
            pool.shutdownNow();
        }
    }

    // ==================== 用例 #3 ====================

    @Test
    @DisplayName("§25.4 #3：IN_PROGRESS 直接 close → 40900")
    void 用例3_处理中不能直接关闭() {
        setCurrentUser(ADMIN, Role.ADMIN);
        assertThat(catchBiz(() -> ticketFlowService.close(TICKET_IN_PROGRESS)))
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ==================== 用例 #4 ====================

    @Test
    @DisplayName("§25.4 #4：WAITING_CONFIRM 执行 reject → REOPENED，reopen_count = 1，deadline 重算")
    void 用例4_驳回重开() {
        waitingConfirmSnapshot = jdbcTemplate.queryForMap(
                "SELECT status, reopen_count, resolution_deadline, sla_resolution_state, "
                        + "sla_warning_notified, sla_breach_notified, sla_paused_at, sla_paused_minutes, version "
                        + "FROM ticket WHERE id = ?", TICKET_WAITING_CONFIRM);
        assertThat(waitingConfirmSnapshot.get("status"))
                .as("前置：工单 3 必须是 WAITING_CONFIRM").isEqualTo("WAITING_CONFIRM");

        Integer resolutionMinutes = jdbcTemplate.queryForObject(
                "SELECT p.resolution_minutes FROM sla_policy p JOIN ticket t ON t.sla_policy_id = p.id "
                        + "WHERE t.id = ?", Integer.class, TICKET_WAITING_CONFIRM);

        // 工单 3 的创建人是 emp_wang(4)
        java.time.LocalDateTime before = java.time.LocalDateTime.now();
        setCurrentUser(4L, Role.EMPLOYEE);
        var vo = ticketFlowService.reject(TICKET_WAITING_CONFIRM);

        assertThat(vo.getStatus().name()).isEqualTo("REOPENED");
        Ticket db = ticketService.getById(TICKET_WAITING_CONFIRM);
        assertThat(db.getReopenCount()).isEqualTo(1);
        assertThat(java.time.Duration.between(before, db.getResolutionDeadline()).toMinutes())
                .as("§9.6：新一轮解决时限 = reopen 时间 + %d 分钟", resolutionMinutes)
                .isBetween((long) resolutionMinutes - 1, (long) resolutionMinutes + 1);
    }

    // ==================== 工具 ====================

    private com.opsdesk.ticket.dto.TicketAssignDTO assignDto(Long assigneeId) {
        var dto = new com.opsdesk.ticket.dto.TicketAssignDTO();
        dto.setAssigneeId(assigneeId);
        return dto;
    }

    private void setCurrentUser(long userId, Role role) {
        Long deptId = jdbcTemplate.queryForList(
                "SELECT department_id FROM `user` WHERE id = ?", Long.class, userId)
                .stream().findFirst().orElse(null);
        UserContext.set(new UserContext.CurrentUser(userId, "test-jti", Set.of(role), Set.of(), deptId));
    }

    private static ErrorCode catchBiz(Runnable action) {
        try {
            action.run();
        }
        catch (BizException ex) {
            return ex.getErrorCode();
        }
        throw new AssertionError("预期抛出 BizException，但调用正常返回了");
    }
}
