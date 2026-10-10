package com.opsdesk.ticket;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.Role;
import com.opsdesk.ticket.service.TicketFlowService;
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
 * 「两人同时 accept 同一工单」真并发验收（工单 D4-01，§25.4 用例 #2）
 *
 * <h2>⚠️ 本类刻意<b>不加</b> {@code @Transactional}</h2>
 * 同一个事务里两次 UPDATE 会互相看到未提交的数据，乐观锁永远「成功」—— 测出来是假绿。
 * 必须让两个线程各自开事务、真正竞争，才能验证 §7.4 的 CAS。
 *
 * <p>代价：不能靠事务回滚，必须自己在 {@link #restore()} 里把种子数据还原
 * （工单 1 的状态 / 处理人 / 首次响应时间 / 版本号，以及本次产生的 history 行）。
 */
@SpringBootTest
class TicketFlowConcurrencyTest {

    private static final long TICKET_ID = 1L;
    private static final long AGENT_ZHANG = 2L;
    private static final long AGENT_LI = 3L;

    @Autowired
    private TicketFlowService ticketFlowService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 跑之前的原始快照，用于还原 */
    private Map<String, Object> before;

    /** 原 department_id，用于还原 */
    private Long originalDeptId;

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

        if (before != null) {
            jdbcTemplate.update(
                    "UPDATE ticket SET status = ?, assignee_id = ?, first_response_at = ?, version = ? WHERE id = ?",
                    before.get("status"), before.get("assignee_id"),
                    before.get("first_response_at"), before.get("version"), TICKET_ID);
            if (originalDeptId != null) {
                jdbcTemplate.update("UPDATE ticket SET department_id = ? WHERE id = ?",
                        originalDeptId, TICKET_ID);
            }
            jdbcTemplate.update(
                    "DELETE FROM ticket_history WHERE ticket_id = ? AND action = 'ACCEPT' AND operator_id IN (?, ?)",
                    TICKET_ID, AGENT_ZHANG, AGENT_LI);
            before = null;
            originalDeptId = null;
        }

        // ⚠️ D6-01 起，流转会写审计（@AuditLog）。本类**不带事务**，所以那行审计是**真提交**的 ——
        //    不清理会在 audit_log 里留下 residue，让后续依赖审计表的断言假红。
        //    与上面删 ticket_history 是同一个道理。
        //    用「审计 id 基线」而不是按 resource_id / user_id 过滤：audit_log 有种子数据，
        //    按字段过滤迟早误删种子（id 基线对种子永远安全）。
        if (auditIdBaseline != null) {
            jdbcTemplate.update("DELETE FROM audit_log WHERE id > ?", auditIdBaseline);
            auditIdBaseline = null;
        }
    }

    @Test
    @DisplayName("§25.4 用例 #2：两人同时 accept 同一工单 → 恰好一人成功、一人 40900")
    void 两人同时accept一人成功一人40900() throws Exception {
        before = jdbcTemplate.queryForMap(
                "SELECT status, assignee_id, first_response_at, version FROM ticket WHERE id = ?", TICKET_ID);
        assertThat(before.get("status")).as("前置：工单 1 必须是 OPEN 且在公共池").isEqualTo("OPEN");

        // 把工单放进「agent_zhang 所在部门（技术部/后端组）」——
        // 该部门同时落在 agent_zhang 与 agent_li（技术部，父部门）的子树里，
        // 于是**无论分派给谁，两人都仍然可见**。这样可见性就不会成为干扰变量，
        // 竞争点纯粹落在「状态机 + CAS」上 —— 这正是用例 #2 要验的东西。
        // （否则后到者可能因为「工单已离开公共池」先撞 40301，而不是 40900。）
        originalDeptId = jdbcTemplate.queryForObject(
                "SELECT department_id FROM ticket WHERE id = ?", Long.class, TICKET_ID);
        Long sharedDeptId = jdbcTemplate.queryForObject(
                "SELECT department_id FROM `user` WHERE id = ?", Long.class, AGENT_ZHANG);
        jdbcTemplate.update("UPDATE ticket SET department_id = ? WHERE id = ?", sharedDeptId, TICKET_ID);

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
                        go.await(10, TimeUnit.SECONDS);   // 等两个线程都就绪，最大化重叠
                        ticketFlowService.accept(TICKET_ID);
                        return null;                       // 成功
                    }
                    catch (BizException e) {
                        return e.getErrorCode();           // 预期 40900
                    }
                    finally {
                        UserContext.clear();
                    }
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).as("两个线程都已就绪").isTrue();
            go.countDown();

            List<ErrorCode> results = new ArrayList<>();
            for (Future<ErrorCode> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }

            long success = results.stream().filter(java.util.Objects::isNull).count();
            long conflict = results.stream().filter(ErrorCode.CONFLICT::equals).count();
            assertThat(success).as("恰好一人成功").isEqualTo(1);
            assertThat(conflict).as("恰好一人 40900（乐观锁 / 状态前置拒绝）").isEqualTo(1);
        }
        finally {
            pool.shutdownNow();
        }

        // 落库结果：ASSIGNED，处理人是成功的那位，且响应时点已写入
        Map<String, Object> after = jdbcTemplate.queryForMap(
                "SELECT status, assignee_id, first_response_at, version FROM ticket WHERE id = ?", TICKET_ID);
        assertThat(after.get("status")).isEqualTo("ASSIGNED");
        assertThat(((Number) after.get("assignee_id")).longValue())
                .as("处理人 = 成功的那位").isIn(AGENT_ZHANG, AGENT_LI);
        assertThat(after.get("first_response_at")).as("§9.3 响应时点已写入").isNotNull();
        assertThat(((Number) after.get("version")).intValue())
                .as("§7.4 版本号 +1").isEqualTo(((Number) before.get("version")).intValue() + 1);
    }
}
