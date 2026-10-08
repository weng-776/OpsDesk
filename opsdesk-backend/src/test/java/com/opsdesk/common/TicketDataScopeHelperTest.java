package com.opsdesk.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsdesk.common.datascope.DepartmentScopeHelper;
import com.opsdesk.common.datascope.TicketDataScopeHelper;
import com.opsdesk.common.enums.Role;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.common.enums.TicketSource;
import com.opsdesk.common.enums.TicketStatus;
import com.opsdesk.common.enums.TicketType;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.service.TicketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单数据范围 Helper 验收测试（工单 D2-04，SOP §5 红区）
 *
 * <p>规格依据：规格基线 <b>§8.3（AGENT 可见范围，权威定义）</b>、§8.2、§25.3 用例 #2 / #5。
 *
 * <h2>种子数据（已核对）</h2>
 * <pre>
 * 工单  1: OPEN           creator=4(财务部5)  dept=5  assignee=NULL   ← 跨部门公共池
 * 工单  2: IN_PROGRESS    creator=5(人事部6)  dept=6  assignee=2
 * 工单  3: WAITING_CONFIRM creator=4          dept=5  assignee=3
 * 工单  4: CLOSED         creator=5          dept=6  assignee=2
 * 工单  5: WAITING_USER   creator=4          dept=5  assignee=3
 * 用户  1 admin(ADMIN, 技术部2) / 2 agent_zhang(AGENT, 技术部2) / 3 agent_li(AGENT, 技术部2)
 *      4 emp_wang(EMPLOYEE, 财务部5) / 5 emp_zhao(EMPLOYEE, 人事部6)
 * 部门  技术部2 的子树 = {2, 3(后端组), 4(前端组)}
 * </pre>
 * 按 §8.3 手工推算的期望可见集合：
 * <pre>
 * admin        → {1,2,3,4,5}                (ALL)
 * agent_zhang  → {1,2,4}   ①assignee=2 → {2,4}；②公共池 → {1}；③技术部子树无命中
 * agent_li     → {1,3,5}   ①assignee=3 → {3,5}；②公共池 → {1}
 * emp_wang     → {1,3,5}   (SELF creator=4)
 * emp_zhao     → {2,4}     (SELF creator=5)
 * </pre>
 *
 * <h2>⚠️ ③「含子部门」这一段用种子数据测不到</h2>
 * 5 张种子工单的 {@code department_id} 只有 5 和 6，<b>都不在技术部子树 {2,3,4} 里</b>，
 * 所以 ③ 永远不命中 —— 只跑种子数据的话，把 ③ 整段删掉测试照样全绿。
 * 因此本类会**造探针工单**（部门挂在「后端组」这个<b>子部门</b>上），专门验证递归那一段。
 *
 * <p>整类 {@link Transactional}：探针工单 / 探针用户跑完自动回滚。
 */
@SpringBootTest
@Transactional
class TicketDataScopeHelperTest {

    private static final long ADMIN_ID = 1L;
    private static final long AGENT_ZHANG = 2L;
    private static final long AGENT_LI = 3L;
    private static final long EMP_WANG = 4L;
    private static final long EMP_ZHAO = 5L;

    /** 技术部；其子树 {2,3,4} —— 后端组(3) 是它的子部门，用来验证「含子部门递归」 */
    private static final long TECH_DEPT = 2L;
    private static final long BACKEND_DEPT = 3L;
    private static final long FINANCE_DEPT = 5L;

    private static final AtomicInteger TICKET_NO_SEQ = new AtomicInteger();

    @Autowired
    private TicketDataScopeHelper dataScopeHelper;

    @Autowired
    private DepartmentScopeHelper departmentScopeHelper;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================== 验收 1：5 个账号的可见集合 ====================

    @Test
    @DisplayName("验收1：5 个种子账号的可见集合与 §8.3 手工推算逐条一致")
    void 五个账号可见集合与规格一致() {
        assertThat(visibleTicketIds(userOf(ADMIN_ID, Role.ADMIN)))
                .as("ADMIN = ALL（§8.2）").containsExactly(1L, 2L, 3L, 4L, 5L);

        assertThat(visibleTicketIds(userOf(AGENT_ZHANG, Role.AGENT)))
                .as("agent_zhang：①assignee=2→{2,4}；②公共池→{1}；③技术部子树{2,3,4}无命中")
                .containsExactly(1L, 2L, 4L);

        assertThat(visibleTicketIds(userOf(AGENT_LI, Role.AGENT)))
                .as("agent_li：①assignee=3→{3,5}；②公共池→{1}")
                .containsExactly(1L, 3L, 5L);

        assertThat(visibleTicketIds(userOf(EMP_WANG, Role.EMPLOYEE)))
                .as("emp_wang = SELF(creator=4)").containsExactly(1L, 3L, 5L);

        assertThat(visibleTicketIds(userOf(EMP_ZHAO, Role.EMPLOYEE)))
                .as("emp_zhao = SELF(creator=5)").containsExactly(2L, 4L);
    }

    @Test
    @DisplayName("验收1重点：AGENT 能看到【跨部门】的公共池工单（§8.1 那个错误写法必漏这张）")
    void agent能看到跨部门公共池工单() {
        // 工单 1 是财务部(5) 王五提的、status=OPEN、无人接单；
        // agent_zhang 在技术部(2) —— 按「自己部门」过滤永远看不到它
        assertThat(visibleTicketIds(userOf(AGENT_ZHANG, Role.AGENT))).contains(1L);
        assertThat(visibleTicketIds(userOf(AGENT_LI, Role.AGENT))).contains(1L);

        // 反证：这张工单的部门确实不在 AGENT 的部门子树里 → 只能靠 ② 看见，
        // 不是「碰巧部门匹配上了」
        assertThat(jdbcTemplate.queryForObject(
                "SELECT department_id FROM ticket WHERE id = 1", Long.class)).isEqualTo(FINANCE_DEPT);
        assertThat(departmentScopeHelper.subtreeIds(TECH_DEPT)).doesNotContain(FINANCE_DEPT);
    }

    @Test
    @DisplayName("验收1重点：AGENT 能看到【本部门子部门】创建的工单（③ 的含子部门递归）")
    void agent能看到子部门创建的工单() {
        // 探针：admin(1) 创建、department_id = 后端组(3)【技术部的子部门】、
        // status=IN_PROGRESS（不是公共池）、assignee=NULL（没分给任何人）
        // → ①②③ 里只有 ③ 能命中它
        Long probeId = createProbeTicket(ADMIN_ID, BACKEND_DEPT, TicketStatus.IN_PROGRESS, null);

        assertThat(visibleTicketIds(userOf(AGENT_ZHANG, Role.AGENT)))
                .as("agent_zhang 在技术部(2)，后端组(3) 是其子部门 → 必须可见")
                .contains(probeId);
        assertThat(visibleTicketIds(userOf(AGENT_LI, Role.AGENT))).contains(probeId);

        // 关键反证：如果 ③ 写成「department_id = 自己的部门」而不是「子树」，这张就看不见了
        assertThat(jdbcTemplate.queryForObject(
                "SELECT department_id FROM ticket WHERE id = ?", Long.class, probeId))
                .as("探针的部门是子部门 3，不等于 AGENT 自己的部门 2").isEqualTo(BACKEND_DEPT);

        // 非本部门的人看不到它
        assertThat(visibleTicketIds(userOf(EMP_WANG, Role.EMPLOYEE))).doesNotContain(probeId);
        assertThat(visibleTicketIds(userOf(EMP_ZHAO, Role.EMPLOYEE))).doesNotContain(probeId);
    }

    @Test
    @DisplayName("ADMIN 是真正的「无条件」：连创建人是幽灵用户、部门为 NULL 的工单也可见")
    void admin不加任何条件() {
        // 这张工单对任何「creator / assignee / department」条件都不友好：
        // creator 是不存在的用户、department 为 NULL、assignee 为 NULL
        Long orphanId = createProbeTicket(999_999L, null, TicketStatus.CLOSED, null);

        assertThat(visibleTicketIds(userOf(ADMIN_ID, Role.ADMIN)))
                .as("ADMIN 若加了任何条件，这张就会被漏掉").contains(orphanId);
        assertThat(visibleTicketIds(userOf(AGENT_ZHANG, Role.AGENT)))
                .as("AGENT 三条都不命中 → 不可见（department 为 NULL，IN 不会匹配 NULL）")
                .doesNotContain(orphanId);
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("AGENT 没有部门时不炸（IN () 是 SQL 语法错），且仍可见 ①∪②")
    void agent无部门时仍可见分配与公共池() {
        Long deptlessAgent = createProbeUser(null);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT department_id FROM `user` WHERE id = ?", Long.class, deptlessAgent))
                .as("前置：这个探针用户确实没有部门").isNull();

        // 若 ③ 没对空子树短路，MyBatis-Plus 会生成 department_id IN () → SQL 语法错 → 这里就红了
        assertThat(visibleTicketIds(userOf(deptlessAgent, Role.AGENT)))
                .as("③ 跳过 → 只剩 ② 的公共池").containsExactly(1L);
    }

    @Test
    @DisplayName("没有任何角色的用户什么都看不到（fail-closed，绝不能因为拿不到角色就放行）")
    void 无角色时不可见任何数据() {
        UserContext.CurrentUser noRole =
                new UserContext.CurrentUser(999_999L, "test-jti", Set.of(), Set.of());

        assertThat(visibleTicketIds(noRole)).isEmpty();
        assertThat(dataScopeHelper.isVisible(ticketService.getById(1L), noRole)).isFalse();
    }

    @Test
    @DisplayName("多角色取最宽：EMPLOYEE+AGENT 按 AGENT 算，EMPLOYEE+ADMIN 按 ALL 算")
    void 多角色取最宽() {
        // agent_zhang(2) 单独当 EMPLOYEE 时看不到任何工单（没有他创建的），
        // 加上 AGENT 后应变成 AGENT 的范围 → 能区分「取最宽」和「取最窄」
        assertThat(visibleTicketIds(userOf(AGENT_ZHANG, Role.EMPLOYEE)))
                .as("只当 EMPLOYEE：creator=2 没有工单").isEmpty();
        assertThat(visibleTicketIds(userOf(AGENT_ZHANG, Role.EMPLOYEE, Role.AGENT)))
                .as("EMPLOYEE+AGENT → 按更宽的 AGENT").containsExactly(1L, 2L, 4L);
        assertThat(visibleTicketIds(userOf(AGENT_ZHANG, Role.EMPLOYEE, Role.ADMIN)))
                .as("EMPLOYEE+ADMIN → ALL").hasSize(5);
    }

    // ==================== 交叉验证：两个出口不能有分歧 ====================

    @Test
    @DisplayName("交叉验证：isVisible(t,u) 与 applyScope 的结果对所有账号 × 所有工单完全一致")
    void isVisible与applyScope完全一致() {
        // 先把覆盖面撑大：子部门创建的 + 幽灵创建的
        createProbeTicket(ADMIN_ID, BACKEND_DEPT, TicketStatus.IN_PROGRESS, null);
        createProbeTicket(999_999L, null, TicketStatus.CLOSED, null);

        List<Ticket> allTickets = ticketService.list();
        assertThat(allTickets).as("前置：至少有种子 5 张 + 探针 2 张").hasSizeGreaterThanOrEqualTo(7);

        List<UserContext.CurrentUser> users = List.of(
                userOf(ADMIN_ID, Role.ADMIN),
                userOf(AGENT_ZHANG, Role.AGENT),
                userOf(AGENT_LI, Role.AGENT),
                userOf(EMP_WANG, Role.EMPLOYEE),
                userOf(EMP_ZHAO, Role.EMPLOYEE),
                new UserContext.CurrentUser(999_999L, "test-jti", Set.of(), Set.of()));

        for (UserContext.CurrentUser user : users) {
            Set<Long> visible = new HashSet<>(visibleTicketIds(user));
            for (Ticket ticket : allTickets) {
                assertThat(dataScopeHelper.isVisible(ticket, user))
                        .as("userId=%s ticketId=%s：isVisible 与 applyScope 必须一致",
                                user.userId(), ticket.getId())
                        .isEqualTo(visible.contains(ticket.getId()));
            }
        }
    }

    // ==================== §25.3 越权用例 ====================

    @Test
    @DisplayName("§25.3 #2：EMPLOYEE 查他人工单 → isVisible=false（调用方据此抛 40301）")
    void employee查他人工单不可见() {
        // 工单 2 是 emp_zhao(5) 提的
        assertThat(dataScopeHelper.isVisible(ticketService.getById(2L), userOf(EMP_WANG, Role.EMPLOYEE)))
                .as("emp_wang(4) 不是创建人").isFalse();
        // 自己的能看到（反证不是恒假）
        assertThat(dataScopeHelper.isVisible(ticketService.getById(1L), userOf(EMP_WANG, Role.EMPLOYEE)))
                .isTrue();
    }

    @Test
    @DisplayName("§25.3 #5：AGENT 查「非本人、非公共池、非本部门」的工单 → isVisible=false")
    void agent查范围外工单不可见() {
        // 工单 3：assignee=agent_li(3)、status=WAITING_CONFIRM（非公共池）、department_id=财务部(5)
        Ticket ticket = ticketService.getById(3L);
        assertThat(ticket.getAssigneeId()).as("受理人是 agent_li，不是 agent_zhang").isEqualTo(AGENT_LI);
        assertThat(ticket.getStatus()).as("不是公共池状态").isNotEqualTo(TicketStatus.OPEN);

        assertThat(dataScopeHelper.isVisible(ticket, userOf(AGENT_ZHANG, Role.AGENT)))
                .as("三条都不命中 → 不可见").isFalse();
        // 受理人本人可见（反证不是恒假）
        assertThat(dataScopeHelper.isVisible(ticket, userOf(AGENT_LI, Role.AGENT))).isTrue();
    }

    // ==================== 工具 ====================

    private UserContext.CurrentUser userOf(Long userId, Role... roles) {
        return new UserContext.CurrentUser(userId, "test-jti-" + userId, Set.of(roles), Set.of());
    }

    /** 用 applyScope 真查一次库，返回可见工单 id（升序） */
    private List<Long> visibleTicketIds(UserContext.CurrentUser user) {
        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(Ticket::getId);
        dataScopeHelper.applyScope(wrapper, user);
        return ticketService.list(wrapper).stream().map(Ticket::getId).sorted().toList();
    }

    private Long createProbeTicket(Long creatorId, Long departmentId, TicketStatus status, Long assigneeId) {
        Ticket ticket = new Ticket();
        ticket.setTicketNo("ODT" + System.nanoTime() + TICKET_NO_SEQ.incrementAndGet());
        ticket.setTitle("数据范围探针工单");
        ticket.setDescription("D2-04 验收用");
        ticket.setType(TicketType.INCIDENT);
        ticket.setCategory(TicketCategory.OTHER);
        ticket.setPriority(TicketPriority.P3);
        ticket.setStatus(status);
        ticket.setSource(TicketSource.WEB);
        ticket.setCreatorId(creatorId);
        ticket.setDepartmentId(departmentId);
        ticket.setAssigneeId(assigneeId);
        ticketService.save(ticket);
        return ticket.getId();
    }

    /** 造一个探针用户（{@code departmentId} 可为 null），跑完随事务回滚 */
    private Long createProbeUser(Long departmentId) {
        String username = "probe_scope_" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO `user` (username, password, nickname, department_id, status, deleted) "
                + "VALUES (?, ?, ?, ?, 1, 0)", username, "not-a-real-hash", "数据范围探针", departmentId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM `user` WHERE username = ?", Long.class, username);
    }
}
