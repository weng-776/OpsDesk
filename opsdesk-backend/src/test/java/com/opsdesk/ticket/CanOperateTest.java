package com.opsdesk.ticket;

import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.Role;
import com.opsdesk.ticket.dto.TicketCancelDTO;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.service.TicketFlowService;
import com.opsdesk.ticket.service.TicketQueryService;
import com.opsdesk.ticket.service.TicketService;
import com.opsdesk.ticket.vo.TicketDetailVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §16.8 {@code canOperate} 验收测试（D4-05 之后回填）
 *
 * <p>{@code canOperate} = 当前用户对这张工单可执行的流转动作（前端渲染按钮用）。
 * 由 {@code TicketStateMachine.availableActions} 算出，与流转接口<b>共用同一套判定</b>。
 *
 * <h2>种子数据</h2>
 * <pre>
 * 工单 1: OPEN            creator=4(emp_wang)  assignee=null
 * 工单 2: IN_PROGRESS     creator=5(emp_zhao)  assignee=2(agent_zhang)
 * 工单 3: WAITING_CONFIRM creator=4(emp_wang)  assignee=3(agent_li)
 * 工单 4: CLOSED          终态
 * </pre>
 *
 * <p>整类 {@link Transactional}：只读，跑完回滚。
 */
@SpringBootTest
@Transactional
class CanOperateTest {

    private static final long ADMIN = 1L;
    private static final long AGENT_ZHANG = 2L;
    private static final long AGENT_LI = 3L;
    private static final long EMP_WANG = 4L;

    @Autowired
    private TicketQueryService ticketQueryService;

    @Autowired
    private TicketFlowService ticketFlowService;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private com.opsdesk.ticket.statemachine.TicketStateMachine ticketStateMachine;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 只用于在「裸 JDBC 改库」之后清 MyBatis 一级缓存 */
    @Autowired
    private org.mybatis.spring.SqlSessionTemplate sqlSessionTemplate;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("OPEN 工单：ADMIN 看到 assign/accept/cancel/force-close；创建人只看到 cancel")
    void OPEN工单的可执行动作() {
        assertThat(actionsAs(ADMIN, Role.ADMIN, 1L))
                .as("ADMIN 在 OPEN 上可做四件事")
                .containsExactlyInAnyOrder("assign", "accept", "cancel", "force-close");

        // emp_wang(4) 是创建人但只是 EMPLOYEE：
        //   cancel ✓（creator）、assign/accept ✗（角色）、force-close ✗（非 ADMIN）
        assertThat(actionsAs(EMP_WANG, Role.EMPLOYEE, 1L))
                .as("EMPLOYEE 创建人只能撤销").containsExactly("cancel");

        // agent_zhang(2) 是 AGENT 但不是创建人：
        //   assign/accept ✓、cancel ✗（非 creator）、force-close ✗（非 ADMIN）
        assertThat(actionsAs(AGENT_ZHANG, Role.AGENT, 1L))
                .containsExactlyInAnyOrder("assign", "accept");
    }

    @Test
    @DisplayName("IN_PROGRESS 工单：处理人看到 hold/resolve；ADMIN 多一个 force-close")
    void IN_PROGRESS工单的可执行动作() {
        assertThat(actionsAs(AGENT_ZHANG, Role.AGENT, 2L))
                .as("处理人可挂起 / 标记解决")
                .containsExactlyInAnyOrder("hold", "resolve");

        assertThat(actionsAs(ADMIN, Role.ADMIN, 2L))
                .containsExactlyInAnyOrder("hold", "resolve", "force-close");

        // 非处理人的 AGENT 看不到 hold/resolve（前置条件不满足）
        // —— 先把工单 2 的部门改成 agent_li 也能看见的部门，排除「看不见」这个干扰
        Long zhangDept = jdbcTemplate.queryForObject(
                "SELECT department_id FROM `user` WHERE id = ?", Long.class, AGENT_ZHANG);
        jdbcTemplate.update("UPDATE ticket SET department_id = ? WHERE id = 2", zhangDept);
        // ⚠️ 裸 JDBC 不走 MyBatis，不会触发一级缓存失效 → 必须手动清，
        //    否则下面的 getById 仍返回「改部门前」的实体，agent_li 会先撞 40301
        sqlSessionTemplate.clearCache();
        assertThat(actionsAs(AGENT_LI, Role.AGENT, 2L))
                .as("可见但不是处理人 → 没有任何可执行动作").isEmpty();
    }

    @Test
    @DisplayName("WAITING_CONFIRM 工单：创建人看到 close/reject；处理人（非创建人）看不到")
    void WAITING_CONFIRM工单的可执行动作() {
        assertThat(actionsAs(EMP_WANG, Role.EMPLOYEE, 3L))
                .as("创建人可关闭 / 驳回")
                .containsExactlyInAnyOrder("close", "reject");

        assertThat(actionsAs(AGENT_LI, Role.AGENT, 3L))
                .as("处理人不是创建人 → 空列表").isEmpty();

        assertThat(actionsAs(ADMIN, Role.ADMIN, 3L))
                .containsExactlyInAnyOrder("close", "reject", "force-close");
    }

    @Test
    @DisplayName("终态工单：任何人都是空列表（矩阵 #15）")
    void 终态工单无可执行动作() {
        assertThat(actionsAs(ADMIN, Role.ADMIN, 4L)).as("CLOSED 是终态").isEmpty();

        // 把工单 1 撤销掉再看
        TicketCancelDTO dto = new TicketCancelDTO();
        dto.setReason("测试");
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        ticketFlowService.cancel(1L, dto);

        assertThat(actionsAs(ADMIN, Role.ADMIN, 1L)).as("CANCELLED 是终态").isEmpty();
    }

    @Test
    @DisplayName("⚠️ 一致性：canOperate 里出现的动作，真调接口不会被状态机拒（按钮能点就一定能成）")
    void 与流转接口判定一致() {
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        Ticket ticket = ticketService.getById(1L);
        List<String> actions = ticketQueryService.detail(1L).getCanOperate();

        assertThat(actions).as("EMPLOYEE 创建人在 OPEN 上只能撤销").containsExactly("cancel");
        assertThat(ticket.getStatus().name()).isEqualTo("OPEN");

        // 真调一次 —— 不应抛 40900/40300/40301
        TicketCancelDTO dto = new TicketCancelDTO();
        dto.setReason("按 canOperate 提示操作");
        TicketDetailVO vo = ticketFlowService.cancel(1L, dto);
        assertThat(vo.getStatus().name()).as("按钮能点 → 调用必成功").isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("边界：工单为 null / 用户为 null → 空列表，不抛异常（fail-closed）")
    void 边界不抛异常() {
        UserContext.clear();
        Ticket ticket = ticketService.getById(1L);

        assertThat(ticketStateMachine.availableActions(null, currentUser(ADMIN, Role.ADMIN)))
                .as("工单为 null").isEmpty();
        assertThat(ticketStateMachine.availableActions(ticket, null))
                .as("用户为 null（未登录）").isEmpty();
    }

    // ==================== 工具 ====================

    private List<String> actionsAs(long userId, Role role, long ticketId) {
        setCurrentUser(userId, role);
        return ticketQueryService.detail(ticketId).getCanOperate();
    }

    private UserContext.CurrentUser currentUser(long userId, Role role) {
        Long deptId = jdbcTemplate.queryForList(
                "SELECT department_id FROM `user` WHERE id = ?", Long.class, userId)
                .stream().findFirst().orElse(null);
        return new UserContext.CurrentUser(userId, "test-jti", Set.of(role), Set.of(), deptId);
    }

    private void setCurrentUser(long userId, Role role) {
        UserContext.set(currentUser(userId, role));
    }
}
