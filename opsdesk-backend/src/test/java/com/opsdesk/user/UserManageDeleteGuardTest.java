package com.opsdesk.user;

import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.enums.TicketCategory;
import com.opsdesk.common.enums.TicketPriority;
import com.opsdesk.common.enums.TicketSource;
import com.opsdesk.common.enums.TicketStatus;
import com.opsdesk.common.enums.TicketType;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.service.TicketService;
import com.opsdesk.user.entity.User;
import com.opsdesk.user.service.UserManageService;
import com.opsdesk.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 删除用户的「未关闭工单」守卫 —— 回归测试（工单 D1-03 / API 文档 §5.7）
 *
 * <h2>为什么单独为这一条写测试</h2>
 * D1-03 第一版把「未关闭」的判定写反了：用 {@code notIn(非终态集合)}，
 * 等价于「只统计<b>终态</b>工单」，语义正好取反 ——
 * 结果是<b>有未关闭工单永远查不出来，删除守卫形同虚设</b>，
 * 而且 {@code DELETE /api/users/3} 会返回 200 并真的把用户软删掉。
 *
 * <p>这类 {@code in} / {@code notIn} 取反错误<b>编译期完全看不出来</b>，
 * 只有端到端断言才拦得住。所以这里把两侧边界都钉死：
 * <ul>
 *   <li>{@link TicketStatus} 的<b>每一个非终态</b>都算「未关闭」→ 必须拒绝删除</li>
 *   <li>{@link TicketStatus} 的<b>每一个终态</b>都不算「未关闭」→ 必须放行</li>
 *   <li>{@code creator_id} 与 {@code assignee_id} <b>两个分支</b>都要计入</li>
 * </ul>
 * 任何一侧写反，都会有用例红。
 *
 * <p>整类 {@link Transactional} —— 探针用户与探针工单跑完自动回滚，<b>不污染种子数据</b>
 * （沿用 {@code DataLayerTest} 的做法）。
 */
@SpringBootTest
@Transactional
class UserManageDeleteGuardTest {

    /** 种子账号 admin，只用来当「另一个人」充当 creator / assignee */
    private static final Long SEED_ADMIN_ID = 1L;

    /** 探针用户所属部门：财务部（种子里的 id=5） */
    private static final Long PROBE_DEPARTMENT_ID = 5L;

    /** 同一纳秒内多次建工单也要拿到不同的 ticket_no（唯一索引 uk_ticket_no） */
    private static final AtomicInteger TICKET_NO_SEQ = new AtomicInteger();

    /** 非终态 = 「未关闭」，从枚举的 terminal 标志推导，不硬编码 */
    private static final List<TicketStatus> NON_TERMINAL =
            Arrays.stream(TicketStatus.values()).filter(s -> !s.isTerminal()).toList();

    /** 终态 = 「已关闭 / 已撤销」 */
    private static final List<TicketStatus> TERMINAL =
            Arrays.stream(TicketStatus.values()).filter(TicketStatus::isTerminal).toList();

    @Autowired
    private UserManageService userManageService;

    @Autowired
    private UserService userService;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================== 核心回归：非终态全部算「未关闭」 ====================

    @Test
    @DisplayName("回归：每一个非终态工单都算「未关闭」→ 删除必须被拒（§5.7）")
    void 非终态工单全部阻止删除() {
        Long userId = createProbeUser();

        for (TicketStatus status : NON_TERMINAL) {
            createTicket(SEED_ADMIN_ID, userId, status);

            BizException ex = catchBiz(() -> userManageService.delete(userId));
            assertThat(ex.getErrorCode())
                    .as("工单状态 %s 是非终态，应算「未关闭」而拒绝删除", status)
                    .isEqualTo(ErrorCode.CONFLICT);
            assertThat(deletedFlagOf(userId))
                    .as("被拒后 %s 的用户不能被标记删除", status)
                    .isEqualTo(0);
            assertThat(userService.getById(userId)).as("用户还在").isNotNull();
        }
    }

    @Test
    @DisplayName("回归边界：终态工单不算「未关闭」→ 删除必须放行")
    void 终态工单不阻止删除() {
        assertThat(TERMINAL).as("终态应恰为 CLOSED / CANCELLED（§3.5）")
                .containsExactlyInAnyOrder(TicketStatus.CLOSED, TicketStatus.CANCELLED);

        for (TicketStatus status : TERMINAL) {
            // 删除会成功，所以每个终态都要用一个全新的探针用户
            Long userId = createProbeUser();
            createTicket(SEED_ADMIN_ID, userId, status);

            userManageService.delete(userId);

            assertThat(userService.getById(userId))
                    .as("工单状态 %s 是终态，不应阻止删除", status)
                    .isNull();
            assertThat(deletedFlagOf(userId))
                    .as("逻辑删除：物理行还在，deleted = 1")
                    .isEqualTo(1);
        }
    }

    // ==================== OR 的两侧都要计入 ====================

    @Test
    @DisplayName("回归：工单是「受理人」时也阻止删除（assignee_id 分支）")
    void 作为受理人被计入() {
        Long userId = createProbeUser();
        createTicket(SEED_ADMIN_ID, userId, TicketStatus.IN_PROGRESS);

        assertThat(catchBiz(() -> userManageService.delete(userId)).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        assertThat(deletedFlagOf(userId)).isEqualTo(0);
    }

    @Test
    @DisplayName("回归：工单是「创建人」时也阻止删除（creator_id 分支）")
    void 作为创建人被计入() {
        Long userId = createProbeUser();
        // 自己创建、还没人接单 → 只有 creator_id 命中
        createTicket(userId, null, TicketStatus.OPEN);

        assertThat(catchBiz(() -> userManageService.delete(userId)).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        assertThat(deletedFlagOf(userId)).isEqualTo(0);
    }

    // ==================== 正常路径 + 错误码 ====================

    @Test
    @DisplayName("没有任何工单的用户可以删除，且是逻辑删除（物理行保留）")
    void 无工单可删除且为逻辑删除() {
        Long userId = createProbeUser();

        userManageService.delete(userId);

        assertThat(userService.getById(userId)).as("查询已过滤掉").isNull();
        assertThat(deletedFlagOf(userId)).as("物理行还在，deleted = 1").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE id = ?", Integer.class, userId))
                .as("确认是 UPDATE 而不是 DELETE")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("删除不存在的用户 → 40400，而不是静默成功")
    void 删除不存在的用户返回40400() {
        assertThat(catchBiz(() -> userManageService.delete(999_999L)).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("被拒时的提示带上未关闭工单数量，便于定位")
    void 被拒提示包含未关闭数量() {
        Long userId = createProbeUser();
        createTicket(SEED_ADMIN_ID, userId, TicketStatus.OPEN);
        createTicket(userId, null, TicketStatus.WAITING_USER);

        assertThatThrownBy(() -> userManageService.delete(userId))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("2");
    }

    // ==================== 工具 ====================

    /**
     * 执行动作并断言它抛 {@link BizException}，返回该异常以便继续断言错误码。
     *
     * <p>手写而不用 {@code catchThrowableOfType}：后者在 AssertJ 3.21 前后参数顺序变过
     * （{@code (ThrowingCallable, Class)} → {@code (Class, ThrowingCallable)}），
     * 升级依赖时会踩坑；这里就 6 行，稳。
     */
    private static BizException catchBiz(Runnable action) {
        try {
            action.run();
        }
        catch (BizException ex) {
            return ex;
        }
        throw new AssertionError("预期抛出 BizException，但调用正常返回了");
    }

    /** 建一个探针用户（跑完随事务回滚，不会占住 username） */
    private Long createProbeUser() {
        User probe = new User();
        probe.setUsername("probe_guard_" + System.nanoTime());
        probe.setPassword("not-a-real-hash");
        probe.setNickname("删除守卫探针");
        probe.setDepartmentId(PROBE_DEPARTMENT_ID);
        probe.setStatus(1);
        userService.save(probe);
        return probe.getId();
    }

    /**
     * 建一张探针工单。
     *
     * @param creatorId  创建人
     * @param assigneeId 受理人，{@code null} 表示未分派（公共池）
     * @param status     状态
     */
    private void createTicket(Long creatorId, Long assigneeId, TicketStatus status) {
        Ticket ticket = new Ticket();
        ticket.setTicketNo("ODT" + System.nanoTime() + TICKET_NO_SEQ.incrementAndGet());
        ticket.setTitle("删除守卫探针工单");
        ticket.setDescription("用于验证「未关闭工单阻止删除用户」的回归用例");
        ticket.setType(TicketType.INCIDENT);
        ticket.setCategory(TicketCategory.OTHER);
        ticket.setPriority(TicketPriority.P3);
        ticket.setStatus(status);
        ticket.setSource(TicketSource.WEB);
        ticket.setCreatorId(creatorId);
        ticket.setAssigneeId(assigneeId);
        ticketService.save(ticket);
    }

    /** 直接读原始列 —— 绕过 {@code @TableLogic} 的 deleted = 0 过滤 */
    private Integer deletedFlagOf(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT deleted FROM `user` WHERE id = ?", Integer.class, userId);
    }
}
