package com.opsdesk.audit;

import com.opsdesk.audit.annotation.AuditLog;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.AuditOperation;
import com.opsdesk.common.enums.AuditResourceType;
import com.opsdesk.common.enums.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审计与业务「同事务」的验收测试（工单 D6-01，验收 3）
 *
 * <h2>⚠️ 本类刻意<b>不加</b> {@code @Transactional}</h2>
 * 验收 3 要证明的是「业务抛异常时，<b>审计不会留下错误的 after 快照</b>」，
 * 而这只能靠<b>真实的事务边界</b>来验：如果测试自己带着事务，
 * 探针改的数据与审计都在同一个「测试事务」里，回滚发生在测试结束时 ——
 * 断言的那一刻数据还在，根本测不出回滚。
 *
 * <p>所以这里让探针方法自己带 {@code @Transactional}（真事务），
 * 测试方法不带 —— 探针抛异常后事务当场回滚，测试再去断言库里的状态。
 * 这样「业务改动回滚了」和「审计没留下」同时成立，才真正证明了两者同事务。
 *
 * <h2>探针（{@link AuditProbe}）为什么必要</h2>
 * 要制造「业务方法<b>改了库之后</b>才失败」的场景，只能用一个带注解的测试专用 Bean。
 * 直接调用某个真实 service 的话，异常大多发生在改动之前，证明不了回滚。
 */
@SpringBootTest
class AuditAspectTransactionTest {

    private static final long ADMIN = 1L;
    private static final long TICKET_OPEN = 1L;

    /** 探针专用的操作码 —— 真实业务不会用它，便于精确清理 */
    private static final String PROBE_OPERATION = AuditOperation.TICKET_PRIORITY_CHANGE.name();

    private static final String PROBE_TITLE = "AUDIT-PROBE-CHANGED";

    @Autowired
    private AuditProbe auditProbe;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        UserContext.set(new UserContext.CurrentUser(
                ADMIN, "d601-jti", Set.of(Role.ADMIN), Set.of(), departmentIdOf(ADMIN)));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        // 探针的审计行会真实提交（同事务语义），跑完清掉，不给开发库留垃圾
        jdbcTemplate.update("DELETE FROM audit_log WHERE operation = ?", PROBE_OPERATION);
    }

    @Test
    @DisplayName("正向对照：探针正常返回 → 审计确实写下来了（防止下面的用例「假绿」）")
    void 探针正常返回会写审计() {
        int before = countProbeAudits();

        auditProbe.readOnly(TICKET_OPEN);

        assertThat(countProbeAudits()).as("探针成功 → 多一条审计").isEqualTo(before + 1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT resource_type, resource_id, before_data, after_data FROM audit_log "
                        + "WHERE operation = ? ORDER BY id DESC LIMIT 1", PROBE_OPERATION);
        assertThat(row.get("resource_type")).isEqualTo("TICKET");
        assertThat(((Number) row.get("resource_id")).longValue()).isEqualTo(TICKET_OPEN);
        // 没改任何东西 → 前后快照相同（这本身也说明 after 快照是「执行后重新查」的）
        assertThat(row.get("before_data")).isNotNull();
        assertThat(row.get("after_data")).isEqualTo(row.get("before_data"));
    }

    @Test
    @DisplayName("验收3：业务方法改了库之后抛异常 → 审计一行不写，业务改动一并回滚（证明同事务）")
    void 业务异常时审计与改动一起回滚() {
        String titleBefore = titleOf(TICKET_OPEN);
        int auditsBefore = countProbeAudits();

        assertThatThrownBy(() -> auditProbe.touchThenFail(TICKET_OPEN))
                .as("异常原样抛出，不被切面包装或吞掉")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("探针故意抛异常");

        assertThat(titleOf(TICKET_OPEN))
                .as("业务改动已回滚 —— 说明审计与业务确实在同一个事务里")
                .isEqualTo(titleBefore);
        assertThat(countProbeAudits())
                .as("§14.3 / 验收3：失败的操作不写审计（after_data 的定义是「变更后」）")
                .isEqualTo(auditsBefore);
    }

    // ==================== 测试专用探针 ====================

    @TestConfiguration
    static class ProbeConfig {

        @Bean
        AuditProbe auditProbe(JdbcTemplate jdbcTemplate) {
            return new AuditProbe(jdbcTemplate);
        }
    }

    /**
     * 测试专用探针：带 {@code @AuditLog} 的方法，一个只读、一个「先改库再抛异常」。
     *
     * <p>必须是 {@code public static} 类 + {@code public} 方法，CGLIB 才能代理它
     * （切面要织入，{@code @Transactional} 也要生效）。
     */
    public static class AuditProbe {

        private final JdbcTemplate jdbcTemplate;

        public AuditProbe(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        /** 只读、正常返回 —— 用于正向对照，证明切面确实织入了 */
        @Transactional(rollbackFor = Exception.class)
        @AuditLog(operation = AuditOperation.TICKET_PRIORITY_CHANGE,
                resourceType = AuditResourceType.TICKET, resourceId = "#ticketId")
        public void readOnly(long ticketId) {
            // 故意什么都不做：before 与 after 快照应当完全相同
        }

        /** 先改库、再抛异常 —— 验收 3 的核心场景 */
        @Transactional(rollbackFor = Exception.class)
        @AuditLog(operation = AuditOperation.TICKET_PRIORITY_CHANGE,
                resourceType = AuditResourceType.TICKET, resourceId = "#ticketId")
        public void touchThenFail(long ticketId) {
            jdbcTemplate.update("UPDATE ticket SET title = ? WHERE id = ?", PROBE_TITLE, ticketId);
            throw new IllegalStateException("探针故意抛异常");
        }
    }

    // ==================== 工具 ====================

    private int countProbeAudits() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE operation = ?", Integer.class, PROBE_OPERATION);
        return count == null ? 0 : count;
    }

    private String titleOf(long ticketId) {
        return jdbcTemplate.queryForObject(
                "SELECT title FROM ticket WHERE id = ?", String.class, ticketId);
    }

    private Long departmentIdOf(long userId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT department_id FROM `user` WHERE id = ?", Long.class, userId);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
