package com.opsdesk.ticket;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.enums.Role;
import com.opsdesk.common.enums.SlaState;
import com.opsdesk.common.enums.TicketHistoryAction;
import com.opsdesk.common.enums.TicketStatus;
import com.opsdesk.ticket.dto.TicketAssignDTO;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.mapper.TicketMapper;
import com.opsdesk.ticket.service.TicketFlowService;
import com.opsdesk.ticket.service.TicketService;
import com.opsdesk.ticket.statemachine.TicketStateMachine;
import com.opsdesk.ticket.vo.TicketDetailVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态机骨架 + assign / accept / start 验收测试（工单 D4-01，SOP §5 红区：状态机）
 *
 * <p>规格依据：规格基线 §7.2 矩阵 #2/#3/#5、§7.3、§7.4、§9.3、§25.4；API 文档 §8.12。
 *
 * <h2>种子数据（与 D3-02 一致）</h2>
 * <pre>
 * 工单 1: OPEN           creator=4 dept=5 assignee=null
 * 工单 2: IN_PROGRESS    creator=5 assignee=2
 * 工单 3: WAITING_CONFIRM creator=4 assignee=3
 * 工单 4: CLOSED         creator=5 assignee=2
 * 工单 5: WAITING_USER   creator=4 assignee=3
 * 用户：admin=1  agent_zhang=2(AGENT,dept2)  agent_li=3(AGENT)  emp_wang=4(EMPLOYEE)  emp_zhao=5(EMPLOYEE)
 * </pre>
 *
 * <p>整类 {@link Transactional}：跑完自动回滚，不污染种子数据。
 * <b>并发场景不在本类</b>（同事务里两次 update 会互相看到未提交数据，测不出乐观锁）——
 * 见 {@code TicketFlowConcurrencyTest}。
 */
@SpringBootTest
@Transactional
class TicketFlowServiceTest {

    private static final long ADMIN = 1L;
    private static final long AGENT_ZHANG = 2L;
    private static final long AGENT_LI = 3L;
    private static final long EMP_WANG = 4L;

    @Autowired
    private TicketFlowService ticketFlowService;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private TicketStateMachine stateMachine;

    @Autowired
    private com.opsdesk.ticket.service.TicketQueryService ticketQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 只用于在「裸 JDBC 改库」之后清 MyBatis 一级缓存，见 D4-03 的顺延用例 */
    @Autowired
    private org.mybatis.spring.SqlSessionTemplate sqlSessionTemplate;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ==================== 验收 1：全链路 ====================

    @Test
    @DisplayName("验收1：OPEN → ASSIGNED → IN_PROGRESS 全链路走通，first_response_at 被写入")
    void 全链路走通且写入首次响应时间() {
        // ---- OPEN → ASSIGNED（agent_zhang 把工单 1 分派给 agent_li）----
        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        TicketDetailVO afterAssign = ticketFlowService.assign(1L, assignDto(AGENT_LI));

        assertThat(afterAssign.getStatus()).as("状态推进到 ASSIGNED").isEqualTo(TicketStatus.ASSIGNED);
        assertThat(afterAssign.getAssigneeId()).as("处理人已变更").isEqualTo(AGENT_LI);
        assertThat(afterAssign.getFirstResponseAt())
                .as("§9.3「响应 = 首次 OPEN→ASSIGNED」→ 分派时就该写 first_response_at")
                .isNotNull();
        LocalDateTime firstResponse = afterAssign.getFirstResponseAt();

        // ---- ASSIGNED → IN_PROGRESS（agent_li 接手开始处理）----
        setCurrentUser(AGENT_LI, Role.AGENT);
        TicketDetailVO afterStart = ticketFlowService.start(1L);

        assertThat(afterStart.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(afterStart.getFirstResponseAt())
                .as("§9.6：已响应过 → 保留首轮值，不重置").isEqualTo(firstResponse);

        // ---- 数据库落地校验（不只信返回值）----
        Ticket db = ticketService.getById(1L);
        assertThat(db.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(db.getAssigneeId()).isEqualTo(AGENT_LI);
        assertThat(db.getFirstResponseAt()).isEqualTo(firstResponse);
        assertThat(db.getVersion()).as("§7.4 每次流转 version +1（两次流转 → 2）").isEqualTo(2);
    }

    @Test
    @DisplayName("验收1补充：每次流转都写 ticket_history（from/to/action/operator）")
    void 每次流转都写历史() {
        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        ticketFlowService.assign(1L, assignDto(AGENT_LI));
        setCurrentUser(AGENT_LI, Role.AGENT);
        ticketFlowService.start(1L);

        // ASSIGN 那条
        List<java.util.Map<String, Object>> assignRows = jdbcTemplate.queryForList(
                "SELECT operator_id, from_status, to_status FROM ticket_history "
                        + "WHERE ticket_id = 1 AND action = 'ASSIGN'");
        assertThat(assignRows).hasSize(1);
        assertThat(((Number) assignRows.get(0).get("operator_id")).longValue()).isEqualTo(AGENT_ZHANG);
        assertThat(assignRows.get(0).get("from_status")).isEqualTo("OPEN");
        assertThat(assignRows.get(0).get("to_status")).isEqualTo("ASSIGNED");

        // START 那条
        List<java.util.Map<String, Object>> startRows = jdbcTemplate.queryForList(
                "SELECT operator_id, from_status, to_status FROM ticket_history "
                        + "WHERE ticket_id = 1 AND action = 'START'");
        assertThat(startRows).hasSize(1);
        assertThat(((Number) startRows.get(0).get("operator_id")).longValue()).isEqualTo(AGENT_LI);
        assertThat(startRows.get(0).get("from_status")).isEqualTo("ASSIGNED");
        assertThat(startRows.get(0).get("to_status")).isEqualTo("IN_PROGRESS");
    }

    // ==================== 验收 2：终态拒绝（§25.4 用例 #1）====================

    @Test
    @DisplayName("验收2（§25.4 #1）：CLOSED 工单执行 assign → 40900")
    void 终态工单assign被拒() {
        // 工单 4 是 CLOSED（终态）
        setCurrentUser(ADMIN, Role.ADMIN);
        assertThat(catchBiz(() -> ticketFlowService.assign(4L, assignDto(AGENT_LI))))
                .as("终态在状态机表里没有任何入边 → 40900（矩阵 #15）")
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("验收2补充：CANCELLED 同理；且状态机表里确实没有终态的入边")
    void 终态在状态机表里无入边() {
        // 把工单 1 改成 CANCELLED，验证同样被拒
        jdbcTemplate.update("UPDATE ticket SET status = 'CANCELLED' WHERE id = 1");
        setCurrentUser(ADMIN, Role.ADMIN);
        assertThat(catchBiz(() -> ticketFlowService.assign(1L, assignDto(AGENT_LI))))
                .isEqualTo(ErrorCode.CONFLICT);
        assertThat(catchBiz(() -> ticketFlowService.accept(1L)))
                .isEqualTo(ErrorCode.CONFLICT);
        assertThat(catchBiz(() -> ticketFlowService.start(1L)))
                .isEqualTo(ErrorCode.CONFLICT);

        // 表驱动：终态没有任何登记（这就是「终态拒绝」的实现方式）
        assertThat(stateMachine.isRegistered(TicketStatus.CLOSED, TicketHistoryAction.ASSIGN)).isFalse();
        assertThat(stateMachine.isRegistered(TicketStatus.CANCELLED, TicketHistoryAction.ACCEPT)).isFalse();
        assertThat(stateMachine.isRegistered(TicketStatus.CANCELLED, TicketHistoryAction.START)).isFalse();
    }

    @Test
    @DisplayName("状态前置：非 OPEN 状态执行 assign/accept → 40900（如 IN_PROGRESS 的工单 2）")
    void 非open状态不能assign或accept() {
        setCurrentUser(ADMIN, Role.ADMIN);
        assertThat(catchBiz(() -> ticketFlowService.assign(2L, assignDto(AGENT_LI))))
                .as("工单 2 是 IN_PROGRESS").isEqualTo(ErrorCode.CONFLICT);
        assertThat(catchBiz(() -> ticketFlowService.accept(2L)))
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("状态前置：ASSIGNED 之外不能 start（如 OPEN 的工单 1）→ 40900")
    void open状态不能start() {
        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        assertThat(catchBiz(() -> ticketFlowService.start(1L)))
                .as("工单 1 是 OPEN，start 只登记了 ASSIGNED（#5）与 REOPENED（#13，待 D4-04）")
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ==================== 验收 3：乐观锁 ====================

    @Test
    @DisplayName("验收3核心（§25.4 #2）：同一版本号并发 CAS —— 第一次成功、第二次影响 0 行")
    void CAS乐观锁生效() {
        Ticket before = ticketService.getById(1L);
        Integer staleVersion = before.getVersion();

        // 第一次：status + version 都对 → 成功
        int first = ticketMapper.updateStatusCas(1L, TicketStatus.OPEN, TicketStatus.ASSIGNED,
                staleVersion, AGENT_LI, null);
        assertThat(first).as("第一次 CAS 成功").isEqualTo(1);

        // 第二次：拿**同一个旧 version** 再来一次 —— 这正是「两人同时 accept」时后到者的处境
        int second = ticketMapper.updateStatusCas(1L, TicketStatus.OPEN, TicketStatus.ASSIGNED,
                staleVersion, AGENT_ZHANG, null);
        assertThat(second).as("版本已被改 → 影响 0 行").isZero();
    }

    @Test
    @DisplayName("验收3补充：status 条件也独立生效 —— version 对上但 status 已变，同样 0 行")
    void CAS的状态条件独立生效() {
        Ticket before = ticketService.getById(1L);
        // 把状态推进到 ASSIGNED（version 也变了）
        ticketMapper.updateStatusCas(1L, TicketStatus.OPEN, TicketStatus.ASSIGNED,
                before.getVersion(), AGENT_LI, null);

        // 拿「新的 version」+「旧的 from=OPEN」→ version 对了，但 status 已经是 ASSIGNED → 0 行
        int rows = ticketMapper.updateStatusCas(1L, TicketStatus.OPEN, TicketStatus.IN_PROGRESS,
                before.getVersion() + 1, null, null);
        assertThat(rows)
                .as("只带 version 条件是不够的（updateById 就只带 version）——status 条件必须也在")
                .isZero();
    }

    @Test
    @DisplayName("验收3补充：连续两次 accept → 第二次 40900（一人成功一人失败）")
    void 连续两次accept第二次40900() {
        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        TicketDetailVO ok = ticketFlowService.accept(1L);
        assertThat(ok.getStatus()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(ok.getAssigneeId()).as("accept → assignee = 当前用户").isEqualTo(AGENT_ZHANG);

        // 第二个人再 accept：状态已不是 OPEN → 40900。
        // 用 ADMIN 当第二个人是为了**排除可见性干扰**（工单 1 在财务部，
        // agent_li 在它被分派走之后按 §8.3 就看不见了，会先撞 40301 而不是 40900）。
        // 「两个 AGENT 真并发」的场景由 TicketFlowConcurrencyTest 专门覆盖。
        setCurrentUser(ADMIN, Role.ADMIN);
        assertThat(catchBiz(() -> ticketFlowService.accept(1L)))
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ==================== assign 的参数校验 ====================

    @Test
    @DisplayName("assign：被分派人不存在 → 40400；是 EMPLOYEE → 40001（已与用户确认要校验）")
    void assign校验被分派人() {
        setCurrentUser(ADMIN, Role.ADMIN);

        assertThat(catchBiz(() -> ticketFlowService.assign(1L, assignDto(999_999L))))
                .as("不存在的用户 → 40400").isEqualTo(ErrorCode.NOT_FOUND);

        assertThat(catchBiz(() -> ticketFlowService.assign(1L, assignDto(EMP_WANG))))
                .as("emp_wang 是 EMPLOYEE，不能当处理人 → 40001").isEqualTo(ErrorCode.PARAM_INVALID);

        assertThat(catchBiz(() -> ticketFlowService.assign(1L, assignDto(null))))
                .as("assigneeId 为空 → 40001").isEqualTo(ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("assign：允许分派给自己（已与用户确认）—— 等价于 accept")
    void assign允许分派给自己() {
        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        TicketDetailVO vo = ticketFlowService.assign(1L, assignDto(AGENT_ZHANG));
        assertThat(vo.getStatus()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(vo.getAssigneeId()).isEqualTo(AGENT_ZHANG);
    }

    // ==================== start 的角色 / 前置条件 ====================

    @Test
    @DisplayName("start：非当前处理人（但可见）→ 40301；ADMIN 例外可执行")
    void start仅限处理人或admin() {
        // 造一个「agent_zhang 可见、但处理人是 agent_li」的 ASSIGNED 工单：
        // 把工单 1 的部门改成 agent_zhang 的部门(2) → 命中 §8.3 的 ③「本部门创建的」→ 可见
        jdbcTemplate.update(
                "UPDATE ticket SET status = 'ASSIGNED', assignee_id = ?, department_id = 2 WHERE id = 1",
                AGENT_LI);

        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        assertThat(catchBiz(() -> ticketFlowService.start(1L)))
                .as("看得见但不是处理人 → 40301（前置条件，不是 40300 角色问题）")
                .isEqualTo(ErrorCode.DATA_SCOPE_DENIED);

        // 处理人本人可以
        setCurrentUser(AGENT_LI, Role.AGENT);
        assertThat(ticketFlowService.start(1L).getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("accept：EMPLOYEE 调用 → 40300（角色不符，矩阵 #3 只允许 AGENT/ADMIN）")
    void accept员工角色被拒() {
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        assertThat(catchBiz(() -> ticketFlowService.accept(1L)))
                .as("emp_wang 是工单 1 的创建人（可见），但角色不满足 → 40300")
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("数据范围：看不到的工单 → 40301；不存在的工单 → 40400（顺序不能反）")
    void 数据范围与存在性() {
        // emp_wang(4) 看不到工单 2（emp_zhao 创建、且非公共池）
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        assertThat(catchBiz(() -> ticketFlowService.accept(2L)))
                .isEqualTo(ErrorCode.DATA_SCOPE_DENIED);

        // 不存在的 id 一律 40400（不能因「先判可见性」而变成 40301，否则泄露存在性）
        setCurrentUser(ADMIN, Role.ADMIN);
        assertThat(catchBiz(() -> ticketFlowService.accept(999_999L)))
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("⚠️ 回归：assign 给他人后操作者可能失去可见性，但响应仍要能返回快照（detailForFlow）")
    void assign后响应不因失去可见性而报错() {
        // agent_zhang(2, 技术部) 把「财务部(5)创建的公共池工单 1」分派给 agent_li(3)。
        // 流转后工单变成 ASSIGNED 且处理人是 3 —— agent_zhang 按 §8.3 已经看不到它了。
        // 若响应复用 detail()（带数据范围校验），这里会变成「库里改成功、接口返 40301」。
        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        TicketDetailVO vo = ticketFlowService.assign(1L, assignDto(AGENT_LI));
        assertThat(vo.getId()).isEqualTo(1L);
        assertThat(vo.getStatus()).isEqualTo(TicketStatus.ASSIGNED);

        // 对照：用带校验的 detail() 确实会拒绝（证明这个场景真的会触发）
        assertThat(catchBiz(() -> ticketQueryService.detail(1L)))
                .as("普通查询接口仍然严格按数据范围拒绝").isEqualTo(ErrorCode.DATA_SCOPE_DENIED);
    }

    // ==================== D4-02 转派（矩阵 #6） ====================

    @Test
    @DisplayName("D4-02 验收1：转派后 assignee 变更、status 仍 ASSIGNED、first_response_at 未被重置")
    void 转派只换处理人() {
        // 先用 ADMIN 把工单 1 分派给 agent_zhang（顺带写入 first_response_at）
        setCurrentUser(ADMIN, Role.ADMIN);
        TicketDetailVO assigned = ticketFlowService.assign(1L, assignDto(AGENT_ZHANG));
        LocalDateTime firstResponse = assigned.getFirstResponseAt();
        assertThat(firstResponse).as("分派时已写入响应时点").isNotNull();

        // agent_zhang 转派给 agent_li
        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        TicketDetailVO after = ticketFlowService.transfer(1L, assignDto(AGENT_LI));

        assertThat(after.getStatus()).as("矩阵 #6：状态不变，仍为 ASSIGNED").isEqualTo(TicketStatus.ASSIGNED);
        assertThat(after.getAssigneeId()).as("处理人已更换").isEqualTo(AGENT_LI);
        assertThat(after.getFirstResponseAt())
                .as("⚠️ 已响应过 → 转派不得重置 first_response_at")
                .isEqualTo(firstResponse);

        // 落库校验
        Ticket db = ticketService.getById(1L);
        assertThat(db.getStatus()).isEqualTo(TicketStatus.ASSIGNED);
        assertThat(db.getAssigneeId()).isEqualTo(AGENT_LI);
        assertThat(db.getFirstResponseAt()).isEqualTo(firstResponse);
    }

    @Test
    @DisplayName("D4-02 验收2：EMPLOYEE 调 transfer → 40300（矩阵 #6 只允许 AGENT/ADMIN）")
    void 转派员工角色被拒() {
        setCurrentUser(ADMIN, Role.ADMIN);
        ticketFlowService.assign(1L, assignDto(AGENT_ZHANG));

        // emp_wang 是工单 1 的创建人（可见），但角色不满足矩阵 #6
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        assertThat(catchBiz(() -> ticketFlowService.transfer(1L, assignDto(AGENT_LI))))
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("D4-02 验收3：OPEN / IN_PROGRESS 状态调 transfer → 40900")
    void 非assigned状态不能转派() {
        setCurrentUser(ADMIN, Role.ADMIN);
        // 工单 1 是 OPEN —— 矩阵 #6 只登记了 ASSIGNED 一个来源
        assertThat(catchBiz(() -> ticketFlowService.transfer(1L, assignDto(AGENT_LI))))
                .isEqualTo(ErrorCode.CONFLICT);
        // 工单 2 是 IN_PROGRESS
        assertThat(catchBiz(() -> ticketFlowService.transfer(2L, assignDto(AGENT_LI))))
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("D4-02 补充：新处理人必须是启用的 AGENT/ADMIN；并写 action=TRANSFER 的历史")
    void 转派校验与历史() {
        setCurrentUser(ADMIN, Role.ADMIN);
        ticketFlowService.assign(1L, assignDto(AGENT_ZHANG));

        assertThat(catchBiz(() -> ticketFlowService.transfer(1L, assignDto(EMP_WANG))))
                .as("转派给 EMPLOYEE → 40001").isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(catchBiz(() -> ticketFlowService.transfer(1L, assignDto(999_999L))))
                .as("转派给不存在的用户 → 40400").isEqualTo(ErrorCode.NOT_FOUND);

        // 正常转派 → 写 TRANSFER 历史（状态不变，from == to）
        ticketFlowService.transfer(1L, assignDto(AGENT_LI));
        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT operator_id, from_status, to_status FROM ticket_history "
                        + "WHERE ticket_id = 1 AND action = 'TRANSFER'");
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get("operator_id")).longValue()).isEqualTo(ADMIN);
        assertThat(rows.get(0).get("from_status")).as("矩阵 #6 是 ASSIGNED → ASSIGNED").isEqualTo("ASSIGNED");
        assertThat(rows.get(0).get("to_status")).isEqualTo("ASSIGNED");
    }

    @Test
    @DisplayName("D4-02 补充：转派同样走 CAS（version 递增，陈旧版本 → 0 行）")
    void 转派走CAS() {
        setCurrentUser(ADMIN, Role.ADMIN);
        ticketFlowService.assign(1L, assignDto(AGENT_ZHANG));   // version 0 → 1
        ticketFlowService.transfer(1L, assignDto(AGENT_LI));    // version 1 → 2

        Ticket db = ticketService.getById(1L);
        assertThat(db.getVersion()).as("两次流转 → version = 2").isEqualTo(2);

        // 拿陈旧版本再转派一次 → 影响 0 行（乐观锁）
        int rows = ticketMapper.updateStatusCas(1L, TicketStatus.ASSIGNED, TicketStatus.ASSIGNED,
                db.getVersion() - 1, AGENT_ZHANG, null);
        assertThat(rows).isZero();
    }

    // ==================== D4-03 挂起 / 恢复（矩阵 #8 / #9，§9.4）====================

    @Test
    @DisplayName("D4-03 验收1：IN_PROGRESS → WAITING_USER → IN_PROGRESS；sla_paused_at 进入有值、退出为 null")
    void 挂起恢复流转与暂停起点() {
        // 工单 2 是 IN_PROGRESS、assignee = agent_zhang(2)
        setCurrentUser(AGENT_ZHANG, Role.AGENT);

        TicketDetailVO held = ticketFlowService.hold(2L);
        assertThat(held.getStatus()).isEqualTo(TicketStatus.WAITING_USER);
        // ⚠️ sla_paused_at 是 SLA 内部字段，VO 刻意不暴露（§16.8 不含它）→ 断言走实体
        assertThat(ticketService.getById(2L).getSlaPausedAt())
                .as("§9.4 进入暂停：sla_paused_at = now").isNotNull();

        TicketDetailVO resumed = ticketFlowService.resume(2L);
        assertThat(resumed.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);

        // 落库校验
        Ticket db = ticketService.getById(2L);
        assertThat(db.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(db.getSlaPausedAt()).as("§9.4 退出暂停：sla_paused_at = null").isNull();
        assertThat(db.getSlaPausedMinutes()).as("累计暂停分钟字段非空").isNotNull();
    }

    @Test
    @DisplayName("D4-03 验收2：恢复后 resolution_deadline 顺延了暂停时长（构造暂停 2 分钟）")
    void 恢复后解决时限顺延暂停时长() {
        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        ticketFlowService.hold(2L);

        Ticket before = ticketService.getById(2L);
        LocalDateTime deadlineBefore = before.getResolutionDeadline();
        assertThat(deadlineBefore).as("工单 2 种子数据应有 SLA 解决时限").isNotNull();

        // 不可能真等 2 分钟 —— 把暂停起点回拨 2 分钟，等价于「已经暂停了 2 分钟」
        // ⚠️ 必须 truncatedTo(SECONDS)：sla_paused_at 是 DATETIME(0)，
        //    MySQL 会把小数秒**四舍五入**。若 now 的小数部分 ≥0.5s，回拨值会被进位成晚 1 秒，
        //    于是 delta ≈ 119.5s → Duration.toMinutes() 截断成 1，分钟断言就会偶发失败
        //    （deadline 顺延 119s 仍在容差内，所以只有分钟那条会红 —— 真踩过）。
        LocalDateTime pausedAt = LocalDateTime.now().minusMinutes(2)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        jdbcTemplate.update("UPDATE ticket SET sla_paused_at = ? WHERE id = 2",
                java.sql.Timestamp.valueOf(pausedAt));
        // ⚠️ 必须清 MyBatis 一级缓存：jdbcTemplate 的裸 SQL 不走 MyBatis，
        //    不会触发 SqlSession 的缓存失效 → resume() 里的 getById 会拿到**回拨前**的旧实体，
        //    于是 delta 只算了 ~1 秒（这条踩过：known-traps「MyBatis 一级缓存」）
        sqlSessionTemplate.clearCache();

        TicketDetailVO resumed = ticketFlowService.resume(2L);

        LocalDateTime deadlineAfter = resumed.getResolutionDeadline();
        long shiftedSeconds = java.time.Duration.between(deadlineBefore, deadlineAfter).getSeconds();
        assertThat(shiftedSeconds)
                .as("§9.4：resolution_deadline 应后移约 120 秒（实际 %d 秒）", shiftedSeconds)
                .isBetween(115L, 130L);

        Ticket after = ticketService.getById(2L);
        assertThat(after.getSlaPausedMinutes())
                .as("§9.4：累计暂停分钟 += 2").isEqualTo(before.getSlaPausedMinutes() + 2);
    }

    @Test
    @DisplayName("D4-03 验收3：response_deadline 没有被改动；非 assignee 调用 → 40301")
    void 挂起恢复不动响应时限且限处理人() {
        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        LocalDateTime responseDeadlineBefore = ticketService.getById(2L).getResponseDeadline();
        assertThat(responseDeadlineBefore).isNotNull();

        ticketFlowService.hold(2L);
        ticketFlowService.resume(2L);

        assertThat(ticketService.getById(2L).getResponseDeadline())
                .as("⚠️ §9.4：response_deadline 不参与顺延，一个字都不能变")
                .isEqualTo(responseDeadlineBefore);

        // 非 assignee：把工单 2 的部门改成 agent_zhang 的部门，让 agent_li 也能看见它
        // （否则他会先撞数据范围 40301，测不出「可见但不是处理人」这条前置条件）
        Long zhangDept = jdbcTemplate.queryForObject(
                "SELECT department_id FROM `user` WHERE id = ?", Long.class, AGENT_ZHANG);
        jdbcTemplate.update("UPDATE ticket SET department_id = ? WHERE id = 2", zhangDept);

        setCurrentUser(AGENT_LI, Role.AGENT);
        assertThat(catchBiz(() -> ticketFlowService.hold(2L)))
                .as("可见但不是当前处理人 → 40301（前置条件）").isEqualTo(ErrorCode.DATA_SCOPE_DENIED);
        assertThat(catchBiz(() -> ticketFlowService.resume(2L)))
                .isEqualTo(ErrorCode.DATA_SCOPE_DENIED);
    }

    @Test
    @DisplayName("D4-03 幂等：已在 WAITING_USER 再 hold → 40900；IN_PROGRESS 直接 resume → 40900")
    void 挂起恢复的状态前置() {
        setCurrentUser(AGENT_ZHANG, Role.AGENT);

        // IN_PROGRESS 直接 resume → 状态机表里没有 (IN_PROGRESS, RESUME)
        assertThat(catchBiz(() -> ticketFlowService.resume(2L)))
                .as("IN_PROGRESS 不能 resume").isEqualTo(ErrorCode.CONFLICT);

        ticketFlowService.hold(2L);
        // 已在 WAITING_USER 再 hold → 表里没有 (WAITING_USER, HOLD)
        assertThat(catchBiz(() -> ticketFlowService.hold(2L)))
                .as("已在 WAITING_USER 再 hold → 40900").isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("D4-03 补充：写 action=HOLD / RESUME 的历史")
    void 挂起恢复写历史() {
        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        ticketFlowService.hold(2L);
        ticketFlowService.resume(2L);

        List<java.util.Map<String, Object>> holdRows = jdbcTemplate.queryForList(
                "SELECT operator_id, from_status, to_status FROM ticket_history "
                        + "WHERE ticket_id = 2 AND action = 'HOLD'");
        assertThat(holdRows).hasSize(1);
        assertThat(((Number) holdRows.get(0).get("operator_id")).longValue()).isEqualTo(AGENT_ZHANG);
        assertThat(holdRows.get(0).get("from_status")).isEqualTo("IN_PROGRESS");
        assertThat(holdRows.get(0).get("to_status")).isEqualTo("WAITING_USER");

        List<java.util.Map<String, Object>> resumeRows = jdbcTemplate.queryForList(
                "SELECT operator_id, from_status, to_status FROM ticket_history "
                        + "WHERE ticket_id = 2 AND action = 'RESUME'");
        assertThat(resumeRows).hasSize(1);
        assertThat(resumeRows.get(0).get("from_status")).isEqualTo("WAITING_USER");
        assertThat(resumeRows.get(0).get("to_status")).isEqualTo("IN_PROGRESS");
    }

    // ==================== D4-04 解决 / 关闭 / 驳回（矩阵 #10 / #11 / #12 / #13）====================

    @Test
    @DisplayName("D4-04：resolve → WAITING_CONFIRM，写 resolved_at 且 SLA 进入暂停")
    void resolve进入待确认并暂停SLA() {
        // 工单 2：IN_PROGRESS、assignee = agent_zhang(2)
        setCurrentUser(AGENT_ZHANG, Role.AGENT);

        TicketDetailVO vo = ticketFlowService.resolve(2L);

        assertThat(vo.getStatus()).isEqualTo(TicketStatus.WAITING_CONFIRM);
        assertThat(vo.getResolvedAt()).as("§7.2 #10：写 resolved_at").isNotNull();

        Ticket db = ticketService.getById(2L);
        assertThat(db.getSlaPausedAt())
                .as("§9.4：WAITING_CONFIRM 是暂停态 → sla_paused_at = now").isNotNull();
    }

    @Test
    @DisplayName("D4-04 验收1（§25.4 #4）：reject → REOPENED，reopen_count+1、deadline 重算、超时标志归零")
    void 驳回重新打开并按96重算() {
        // 工单 3：WAITING_CONFIRM、creator = emp_wang(4)、assignee = agent_li(3)
        // 先把 SLA 状态与超时标志「弄脏」，才能证明 reject 确实把它们归零了
        jdbcTemplate.update("UPDATE ticket SET sla_resolution_state = 'BREACHED', "
                + "sla_warning_notified = 1, sla_breach_notified = 1, reopen_count = 0, "
                + "sla_paused_at = ? WHERE id = 3",
                java.sql.Timestamp.valueOf(LocalDateTime.now()
                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));
        sqlSessionTemplate.clearCache();

        Integer resolutionMinutes = jdbcTemplate.queryForObject(
                "SELECT p.resolution_minutes FROM sla_policy p JOIN ticket t ON t.sla_policy_id = p.id "
                        + "WHERE t.id = 3", Integer.class);
        assertThat(resolutionMinutes).as("工单 3 应能查到冻结版本的策略").isNotNull();

        LocalDateTime before = LocalDateTime.now();
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        TicketDetailVO vo = ticketFlowService.reject(3L);
        LocalDateTime after = LocalDateTime.now();

        // ① 状态与计数
        assertThat(vo.getStatus()).isEqualTo(TicketStatus.REOPENED);
        Ticket db = ticketService.getById(3L);
        assertThat(db.getReopenCount()).isEqualTo(1);

        // ② resolution_deadline = reopen 时间 + resolution_minutes（整轮重算）
        assertThat(db.getResolutionDeadline()).isNotNull();
        assertThat(java.time.Duration.between(before, db.getResolutionDeadline()).toMinutes())
                .as("§9.6：新一轮解决时限 = reopen 时间 + %d 分钟", resolutionMinutes)
                .isBetween((long) resolutionMinutes - 1, (long) resolutionMinutes + 1);

        // ③ SLA 状态与两个超时标志归零
        assertThat(db.getSlaResolutionState()).isEqualTo(SlaState.NORMAL);
        assertThat(db.getSlaWarningNotified()).isZero();
        assertThat(db.getSlaBreachNotified()).isZero();

        // ④ 退出暂停态（§9.4：REOPENED 不是暂停态）
        assertThat(db.getSlaPausedAt()).isNull();

        // ⑤ first_response_at 保留首轮值，不重置
        assertThat(db.getFirstResponseAt()).as("§9.6：保留首轮响应时间").isNotNull();

        assertThat(after).isAfter(before);
    }

    @Test
    @DisplayName("D4-04 验收2（§7.3）：驳回后必须再 start 一次才进 IN_PROGRESS，中间态 REOPENED 真实存在过")
    void 驳回后需再start一次() {
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        TicketDetailVO rejected = ticketFlowService.reject(3L);
        assertThat(rejected.getStatus())
                .as("⚠️ §7.3：接口必须停在 REOPENED，不能一步走到 IN_PROGRESS").isEqualTo(TicketStatus.REOPENED);

        // 历史里确实留下了 WAITING_CONFIRM → REOPENED 这一条（中间态可观察）
        List<java.util.Map<String, Object>> rejectRows = jdbcTemplate.queryForList(
                "SELECT from_status, to_status FROM ticket_history "
                        + "WHERE ticket_id = 3 AND action = 'REJECT'");
        assertThat(rejectRows).hasSize(1);
        assertThat(rejectRows.get(0).get("from_status")).isEqualTo("WAITING_CONFIRM");
        assertThat(rejectRows.get(0).get("to_status")).isEqualTo("REOPENED");

        // 此刻 REOPENED 不能直接 close（矩阵里没有 (REOPENED, CLOSE)）
        setCurrentUser(ADMIN, Role.ADMIN);
        assertThat(catchBiz(() -> ticketFlowService.close(3L)))
                .as("REOPENED 不能直接关闭").isEqualTo(ErrorCode.CONFLICT);

        // 处理人 start（矩阵 #13）→ 才进 IN_PROGRESS
        setCurrentUser(AGENT_LI, Role.AGENT);
        TicketDetailVO started = ticketFlowService.start(3L);
        assertThat(started.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS);

        List<java.util.Map<String, Object>> startRows = jdbcTemplate.queryForList(
                "SELECT from_status, to_status FROM ticket_history "
                        + "WHERE ticket_id = 3 AND action = 'START' AND from_status = 'REOPENED'");
        // ⚠️ 只数 from_status = REOPENED 那条：种子数据里工单 3 本来就有
        //    ASSIGNED → IN_PROGRESS 的历史（它当前是 WAITING_CONFIRM，必然走过 IN_PROGRESS）
        assertThat(startRows).hasSize(1);
        assertThat(startRows.get(0).get("to_status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("D4-04：close → CLOSED，写 closed_at，SLA 恢复并顺延（同 resume）")
    void 关闭写关闭时间并恢复SLA() {
        // 模拟 WAITING_CONFIRM 期间的暂停（正常由 resolve 写入），并回拨 2 分钟
        jdbcTemplate.update("UPDATE ticket SET sla_paused_at = ? WHERE id = 3",
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusMinutes(2)
                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));
        sqlSessionTemplate.clearCache();

        Ticket before = ticketService.getById(3L);
        LocalDateTime deadlineBefore = before.getResolutionDeadline();
        assertThat(deadlineBefore).isNotNull();

        // 工单 3 的创建人 = emp_wang(4)
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        TicketDetailVO vo = ticketFlowService.close(3L);

        assertThat(vo.getStatus()).isEqualTo(TicketStatus.CLOSED);
        assertThat(vo.getClosedAt()).as("§7.2 #11：写 closed_at").isNotNull();

        Ticket db = ticketService.getById(3L);
        assertThat(db.getSlaPausedAt()).as("§9.4：退出暂停态").isNull();
        long shifted = java.time.Duration.between(deadlineBefore, db.getResolutionDeadline()).getSeconds();
        assertThat(shifted).as("SLA 恢复：顺延约 120 秒（实际 %d）", shifted).isBetween(115L, 130L);
    }

    @Test
    @DisplayName("D4-04 验收3：IN_PROGRESS 直接 close → 40900；非 creator 调 reject → 40300")
    void 状态前置与创建人约束() {
        setCurrentUser(ADMIN, Role.ADMIN);
        // 工单 2 是 IN_PROGRESS —— 矩阵里没有 (IN_PROGRESS, CLOSE)
        assertThat(catchBiz(() -> ticketFlowService.close(2L)))
                .as("§25.4 #3：IN_PROGRESS 直接 close → 40900").isEqualTo(ErrorCode.CONFLICT);

        // 工单 3 的 creator 是 emp_wang(4)；agent_li(3) 是处理人（可见）但不是创建人
        setCurrentUser(AGENT_LI, Role.AGENT);
        assertThat(catchBiz(() -> ticketFlowService.reject(3L)))
                .as("非 creator → 40300（不是 40301）").isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(catchBiz(() -> ticketFlowService.close(3L)))
                .as("非 creator → 40300").isEqualTo(ErrorCode.FORBIDDEN);

        // 创建人自己可以
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        assertThat(ticketFlowService.close(3L).getStatus()).isEqualTo(TicketStatus.CLOSED);
    }

    @Test
    @DisplayName("D4-04：resolve 限当前处理人（非处理人 → 40301）")
    void resolve限处理人() {
        // 工单 3：WAITING_CONFIRM、assignee = agent_li(3)。先看 resolve 的状态前置
        setCurrentUser(AGENT_LI, Role.AGENT);
        assertThat(catchBiz(() -> ticketFlowService.resolve(3L)))
                .as("WAITING_CONFIRM 不能再 resolve（表里无该入边）").isEqualTo(ErrorCode.CONFLICT);

        // 工单 2：IN_PROGRESS、assignee = agent_zhang(2)
        // 把部门改成 agent_zhang 的部门，让 agent_li 也能看见它 → 隔离出「前置条件」而非「数据范围」
        Long zhangDept = jdbcTemplate.queryForObject(
                "SELECT department_id FROM `user` WHERE id = ?", Long.class, AGENT_ZHANG);
        jdbcTemplate.update("UPDATE ticket SET department_id = ? WHERE id = 2", zhangDept);
        sqlSessionTemplate.clearCache();

        setCurrentUser(AGENT_LI, Role.AGENT);
        assertThat(catchBiz(() -> ticketFlowService.resolve(2L)))
                .as("可见但不是处理人 → 40301").isEqualTo(ErrorCode.DATA_SCOPE_DENIED);
    }

    // ==================== D4-05 撤销 / 强制关闭（矩阵 #4 / #7 / #14）====================

    @Test
    @DisplayName("D4-05 验收1（矩阵 #4）：OPEN 工单 cancel → CANCELLED 且写 cancel_reason")
    void 撤销OPEN工单写原因() {
        // 工单 1 是 OPEN，创建人 = emp_wang(4)
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        TicketDetailVO cancelled = ticketFlowService.cancel(1L, cancelDto("问题已自行解决"));

        assertThat(cancelled.getStatus()).isEqualTo(TicketStatus.CANCELLED);
        assertThat(ticketService.getById(1L).getCancelReason()).isEqualTo("问题已自行解决");
    }

    @Test
    @DisplayName("D4-05 验收1（矩阵 #7）：ASSIGNED 工单 cancel → CANCELLED")
    void 撤销ASSIGNED工单() {
        // 种子数据里唯一的 OPEN 工单是工单 1，所以先把它分派掉再撤销
        setCurrentUser(ADMIN, Role.ADMIN);
        TicketDetailVO assigned = ticketFlowService.assign(1L, assignDto(AGENT_ZHANG));
        assertThat(assigned.getStatus()).isEqualTo(TicketStatus.ASSIGNED);

        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        TicketDetailVO cancelled = ticketFlowService.cancel(1L, cancelDto("重复提单"));
        assertThat(cancelled.getStatus()).isEqualTo(TicketStatus.CANCELLED);
        assertThat(ticketService.getById(1L).getCancelReason()).isEqualTo("重复提单");
    }

    @Test
    @DisplayName("D4-05：cancel 写 action=CANCEL 的历史")
    void 撤销写历史() {
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        ticketFlowService.cancel(1L, cancelDto("不修了"));

        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT from_status, to_status, remark FROM ticket_history "
                        + "WHERE ticket_id = 1 AND action = 'CANCEL'");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("from_status")).isEqualTo("OPEN");
        assertThat(rows.get(0).get("to_status")).isEqualTo("CANCELLED");
        assertThat((String) rows.get(0).get("remark")).as("历史备注带原因").contains("不修了");
    }

    @Test
    @DisplayName("D4-05：cancel 只能从 OPEN / ASSIGNED（IN_PROGRESS → 40900）；非 creator → 40300")
    void 撤销的状态前置与创建人约束() {
        // 工单 2 是 IN_PROGRESS —— 矩阵 #4/#7 只登记了 OPEN / ASSIGNED
        setCurrentUser(ADMIN, Role.ADMIN);
        assertThat(catchBiz(() -> ticketFlowService.cancel(2L, cancelDto("想撤"))))
                .as("IN_PROGRESS 不能撤销").isEqualTo(ErrorCode.CONFLICT);

        // 工单 1 的创建人是 emp_wang(4)；agent_zhang 能看见它（公共池）但不是创建人
        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        assertThat(catchBiz(() -> ticketFlowService.cancel(1L, cancelDto("想撤"))))
                .as("非 creator → 40300").isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("D4-05 验收2：force-close 仅 ADMIN（EMPLOYEE / AGENT → 40300）")
    void 强制关闭仅管理员() {
        // 工单 1 是 OPEN；emp_wang 是创建人且能看见它，但角色不是 ADMIN
        setCurrentUser(EMP_WANG, Role.EMPLOYEE);
        assertThat(catchBiz(() -> ticketFlowService.forceClose(1L)))
                .as("EMPLOYEE → 40300").isEqualTo(ErrorCode.FORBIDDEN);

        setCurrentUser(AGENT_ZHANG, Role.AGENT);
        assertThat(catchBiz(() -> ticketFlowService.forceClose(1L)))
                .as("AGENT → 40300").isEqualTo(ErrorCode.FORBIDDEN);

        // ADMIN 可以
        setCurrentUser(ADMIN, Role.ADMIN);
        TicketDetailVO vo = ticketFlowService.forceClose(1L);
        assertThat(vo.getStatus()).isEqualTo(TicketStatus.CLOSED);
        assertThat(vo.getClosedAt()).as("§6.4：写 closed_at").isNotNull();
    }

    @Test
    @DisplayName("D4-05：force-close 对任意非终态可用；对终态 → 40900（矩阵 #15）")
    void 强制关闭的来源状态() {
        setCurrentUser(ADMIN, Role.ADMIN);

        // 任意非终态都行：IN_PROGRESS 的工单 2
        assertThat(ticketFlowService.forceClose(2L).getStatus()).isEqualTo(TicketStatus.CLOSED);

        // 终态：工单 4 是 CLOSED
        assertThat(catchBiz(() -> ticketFlowService.forceClose(4L)))
                .as("CLOSED 是终态 → 40900").isEqualTo(ErrorCode.CONFLICT);
        // 工单 1 先撤销再强关 → 同样被拒
        ticketFlowService.cancel(1L, cancelDto("先撤销"));
        assertThat(catchBiz(() -> ticketFlowService.forceClose(1L)))
                .as("CANCELLED 是终态 → 40900").isEqualTo(ErrorCode.CONFLICT);
    }

    // ==================== 工具 ====================

    private TicketAssignDTO assignDto(Long assigneeId) {
        TicketAssignDTO dto = new TicketAssignDTO();
        dto.setAssigneeId(assigneeId);
        return dto;
    }

    private com.opsdesk.ticket.dto.TicketCancelDTO cancelDto(String reason) {
        var dto = new com.opsdesk.ticket.dto.TicketCancelDTO();
        dto.setReason(reason);
        return dto;
    }

    private void setCurrentUser(long userId, Role role) {
        UserContext.set(new UserContext.CurrentUser(
                userId, "test-jti", Set.of(role), Set.of(), departmentIdOf(userId)));
    }

    private Long departmentIdOf(long userId) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT department_id FROM `user` WHERE id = ?", Long.class, userId);
        return ids.isEmpty() ? null : ids.get(0);
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
