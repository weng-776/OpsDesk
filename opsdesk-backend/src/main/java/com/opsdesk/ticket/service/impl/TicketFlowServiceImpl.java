package com.opsdesk.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsdesk.common.BizException;
import com.opsdesk.common.ErrorCode;
import com.opsdesk.common.UserContext;
import com.opsdesk.common.datascope.TicketDataScopeHelper;
import com.opsdesk.common.enums.Role;
import com.opsdesk.common.enums.SlaState;
import com.opsdesk.common.enums.TicketHistoryAction;
import com.opsdesk.sla.service.SlaPauseService;
import com.opsdesk.sla.service.TicketSlaCalculator;
import com.opsdesk.ticket.dto.TicketAssignDTO;
import com.opsdesk.ticket.dto.TicketCancelDTO;
import com.opsdesk.ticket.entity.Ticket;
import com.opsdesk.ticket.mapper.TicketMapper;
import com.opsdesk.ticket.service.TicketFlowService;
import com.opsdesk.ticket.service.TicketQueryService;
import com.opsdesk.ticket.service.TicketService;
import com.opsdesk.ticket.statemachine.TicketStateMachine;
import com.opsdesk.ticket.statemachine.TicketStatePatch;
import com.opsdesk.ticket.support.TicketHistoryRecorder;
import com.opsdesk.ticket.vo.TicketDetailVO;
import com.opsdesk.user.entity.User;
import com.opsdesk.user.entity.UserRole;
import com.opsdesk.user.service.RoleService;
import com.opsdesk.user.service.UserRoleService;
import com.opsdesk.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单状态流转实现（工单 D4-01 ~ D4-04，SOP §5 红区：状态机 + SLA）
 *
 * <p>规格依据：规格基线 §7.2 矩阵 #2/#3/#5/#6/#8/#9/#10/#11/#12/#13、§7.3、§7.4、
 * §6.2、§6.3、§6.4、§9.3、§9.4、§9.6；API 文档 §8.12。
 *
 * <h2>每个流转方法的四步（§8.12 抬头的顺序，不能乱）</h2>
 * <pre>
 * ① 取工单：不存在 → 40400
 * ② 数据范围：不可见 → 40301
 * ③ 状态机：非法流转 → 40900 / 角色不符 → 40300 / 非处理人 → 40301
 * ④ CAS 更新 + 写历史（同事务）
 * </pre>
 * ①②的顺序与 D3-03 详情一致 —— 先判「不存在」再判「不可见」，否则会泄露 id 是否存在。
 *
 * <h2>⚠️ 状态改动的唯一出口是 CAS</h2>
 * 绝不 {@code ticket.setStatus(...)} + {@code updateById}：那样没有 {@code status} 前置条件，
 * 并发下会「在已经变了的状态上再流转一次」。见 {@code TicketMapper#updateStatusCas}。
 *
 * <h2>响应为什么用 {@code detailForFlow} 而不是 {@code detail}</h2>
 * 流转会改变归属（assign 给别人后离开公共池），操作者可能因此不再可见；
 * 若再校验一次数据范围，会出现「库里改成功、接口返 40301」。详见 {@code TicketQueryService#detailForFlow}。
 */
@Slf4j
@Service
public class TicketFlowServiceImpl implements TicketFlowService {

    /** 账号启用（与 {@code AuthServiceImpl} / {@code UserManageServiceImpl} 口径一致） */
    private static final int USER_STATUS_ENABLED = 1;

    private final TicketService ticketService;
    private final TicketQueryService ticketQueryService;
    private final TicketMapper ticketMapper;
    private final TicketStateMachine stateMachine;
    private final TicketHistoryRecorder historyRecorder;
    private final TicketDataScopeHelper dataScopeHelper;
    private final UserService userService;
    private final UserRoleService userRoleService;
    private final RoleService roleService;
    private final SlaPauseService slaPauseService;
    private final TicketSlaCalculator ticketSlaCalculator;

    public TicketFlowServiceImpl(TicketService ticketService,
                                 TicketQueryService ticketQueryService,
                                 TicketMapper ticketMapper,
                                 TicketStateMachine stateMachine,
                                 TicketHistoryRecorder historyRecorder,
                                 TicketDataScopeHelper dataScopeHelper,
                                 UserService userService,
                                 UserRoleService userRoleService,
                                 RoleService roleService,
                                 SlaPauseService slaPauseService,
                                 TicketSlaCalculator ticketSlaCalculator) {
        this.ticketService = ticketService;
        this.ticketQueryService = ticketQueryService;
        this.ticketMapper = ticketMapper;
        this.stateMachine = stateMachine;
        this.historyRecorder = historyRecorder;
        this.dataScopeHelper = dataScopeHelper;
        this.userService = userService;
        this.userRoleService = userRoleService;
        this.roleService = roleService;
        this.slaPauseService = slaPauseService;
        this.ticketSlaCalculator = ticketSlaCalculator;
    }

    // ==================== 矩阵 #2 assign ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO assign(Long ticketId, TicketAssignDTO dto) {
        UserContext.CurrentUser user = UserContext.get();
        Ticket ticket = loadVisibleTicket(ticketId);

        // 被分派人必须是「启用的 AGENT / ADMIN」—— 分派给员工语义不成立（已与用户确认要校验）
        User assignee = requireAssignable(dto.getAssigneeId());

        TicketStateMachine.Transition transition =
                stateMachine.check(ticket, TicketHistoryAction.ASSIGN, user);
        apply(ticket, transition, user, assignee.getId(), "分派给 " + displayNameOf(assignee));
        return ticketQueryService.detailForFlow(ticketId);
    }

    // ==================== 矩阵 #3 accept ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO accept(Long ticketId) {
        UserContext.CurrentUser user = UserContext.get();
        Ticket ticket = loadVisibleTicket(ticketId);

        TicketStateMachine.Transition transition =
                stateMachine.check(ticket, TicketHistoryAction.ACCEPT, user);
        // assignee_id = 当前用户（§7.2 矩阵 #3 的附加约束）
        apply(ticket, transition, user, user.userId(), "受理工单");
        return ticketQueryService.detailForFlow(ticketId);
    }

    // ==================== 矩阵 #5 start ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO start(Long ticketId) {
        UserContext.CurrentUser user = UserContext.get();
        Ticket ticket = loadVisibleTicket(ticketId);

        TicketStateMachine.Transition transition =
                stateMachine.check(ticket, TicketHistoryAction.START, user);
        // 不改 assignee：矩阵 #5 只流转状态（矩阵 #13 REOPENED→IN_PROGRESS 同样沿用原 assignee）
        apply(ticket, transition, user, null, "开始处理");
        return ticketQueryService.detailForFlow(ticketId);
    }

    // ==================== 矩阵 #6 transfer ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO transfer(Long ticketId, TicketAssignDTO dto) {
        UserContext.CurrentUser user = UserContext.get();
        Ticket ticket = loadVisibleTicket(ticketId);

        // 新处理人同样必须是「启用的 AGENT / ADMIN」（与 assign 同一把尺子）
        User assignee = requireAssignable(dto.getAssigneeId());

        TicketStateMachine.Transition transition =
                stateMachine.check(ticket, TicketHistoryAction.TRANSFER, user);
        // 矩阵 #6：只换 assignee_id，状态仍是 ASSIGNED。
        // ⚠️ stampsFirstResponse = false → apply() 传 null → SQL 跳过该列 → 保留首轮响应时间
        apply(ticket, transition, user, assignee.getId(), "转派给 " + displayNameOf(assignee));
        return ticketQueryService.detailForFlow(ticketId);
    }

    // ==================== 矩阵 #8 hold / #9 resume（D4-03，§9.4）====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO hold(Long ticketId) {
        UserContext.CurrentUser user = UserContext.get();
        Ticket ticket = loadVisibleTicket(ticketId);

        TicketStateMachine.Transition transition =
                stateMachine.check(ticket, TicketHistoryAction.HOLD, user);
        // §9.4「进入暂停状态：sla_paused_at = now」
        apply(ticket, transition, user, "挂起（等待用户补充）",
                TicketStatePatch.builder().slaPausedAt(LocalDateTime.now()).build());
        return ticketQueryService.detailForFlow(ticketId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO resume(Long ticketId) {
        UserContext.CurrentUser user = UserContext.get();
        Ticket ticket = loadVisibleTicket(ticketId);

        TicketStateMachine.Transition transition =
                stateMachine.check(ticket, TicketHistoryAction.RESUME, user);

        // §9.4「退出暂停状态」—— 计算收口在 SlaPauseService（纯函数，可单测）
        SlaPauseService.ResumeOutcome outcome = slaPauseService.resume(
                ticket.getSlaPausedAt(),
                ticket.getResolutionDeadline(),
                ticket.getSlaPausedMinutes(),
                LocalDateTime.now());

        apply(ticket, transition, user, "恢复处理", resumePatch(outcome));
        return ticketQueryService.detailForFlow(ticketId);
    }

    // ==================== 矩阵 #10 resolve / #11 close / #12 reject（D4-04）====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO resolve(Long ticketId) {
        UserContext.CurrentUser user = UserContext.get();
        Ticket ticket = loadVisibleTicket(ticketId);

        TicketStateMachine.Transition transition =
                stateMachine.check(ticket, TicketHistoryAction.RESOLVE, user);

        // 矩阵 #10：写 resolved_at，且 SLA **进入暂停**（§9.4：WAITING_CONFIRM 是暂停态）
        LocalDateTime now = LocalDateTime.now();
        apply(ticket, transition, user, "标记解决",
                TicketStatePatch.builder().slaPausedAt(now).resolvedAt(now).build());
        return ticketQueryService.detailForFlow(ticketId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO close(Long ticketId) {
        UserContext.CurrentUser user = UserContext.get();
        Ticket ticket = loadVisibleTicket(ticketId);

        TicketStateMachine.Transition transition =
                stateMachine.check(ticket, TicketHistoryAction.CLOSE, user);

        // 矩阵 #11：写 closed_at，SLA **恢复**（退出 WAITING_CONFIRM 的暂停，顺延逻辑同 resume，§9.4）
        SlaPauseService.ResumeOutcome outcome = slaPauseService.resume(
                ticket.getSlaPausedAt(),
                ticket.getResolutionDeadline(),
                ticket.getSlaPausedMinutes(),
                LocalDateTime.now());

        TicketStatePatch patch = resumePatch(outcome).toBuilder()
                .closedAt(LocalDateTime.now())
                .build();
        apply(ticket, transition, user, "确认关闭", patch);
        return ticketQueryService.detailForFlow(ticketId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO reject(Long ticketId) {
        UserContext.CurrentUser user = UserContext.get();
        Ticket ticket = loadVisibleTicket(ticketId);

        TicketStateMachine.Transition transition =
                stateMachine.check(ticket, TicketHistoryAction.REJECT, user);

        LocalDateTime now = LocalDateTime.now();

        // §9.6 ①：新一轮解决时限 = reopen 时间 + resolution_minutes（**整轮重算，不是累加**）
        //   取 ticket.sla_policy_id 冻结的那一版策略（§9.1/§9.7）
        LocalDateTime newDeadline =
                ticketSlaCalculator.reopenResolutionDeadline(ticket.getSlaPolicyId(), now);
        if (newDeadline == null) {
            // 取不到策略 → 保持原 deadline 不动（宁可不动，也不要写坏）
            log.warn("[工单驳回] 取不到 SLA 策略，resolution_deadline 保持不变。ticketId={}", ticketId);
            newDeadline = ticket.getResolutionDeadline();
        }

        // §9.4：REOPENED 不是暂停态 → 必须退出暂停（清空起点），并累加本次暂停时长
        //   （不清空会让「暂停」一直开着，之后任何 resume 会算出巨大的 delta）
        SlaPauseService.ResumeOutcome pauseOutcome = slaPauseService.resume(
                ticket.getSlaPausedAt(),
                ticket.getResolutionDeadline(),
                ticket.getSlaPausedMinutes(),
                now);

        TicketStatePatch patch = TicketStatePatch.builder()
                .clearSlaPausedAt(true)
                .slaPausedMinutes(pauseOutcome.pausedMinutes())
                .resolutionDeadline(newDeadline)
                // §9.6 ②：重置 SLA 状态与超时标志位（新一轮从零开始）
                .slaResolutionState(SlaState.NORMAL)
                .slaWarningNotified(0)
                .slaBreachNotified(0)
                // §9.6 ③：重新打开次数 +1
                .reopenCount((ticket.getReopenCount() == null ? 0 : ticket.getReopenCount()) + 1)
                .build();
        // ⚠️ §7.3：只走到 REOPENED 为止，**绝不**顺手推到 IN_PROGRESS
        //    （要进 IN_PROGRESS 必须由处理人再调一次 start，矩阵 #13）
        apply(ticket, transition, user, "驳回（未解决）", patch);
        return ticketQueryService.detailForFlow(ticketId);
    }

    // ==================== 矩阵 #4 / #7 cancel、#14 force-close（D4-05）====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO cancel(Long ticketId, TicketCancelDTO dto) {
        UserContext.CurrentUser user = UserContext.get();
        Ticket ticket = loadVisibleTicket(ticketId);

        TicketStateMachine.Transition transition =
                stateMachine.check(ticket, TicketHistoryAction.CANCEL, user);

        // 矩阵 #4/#7：写 cancel_reason（必填，DTO 上已 @NotBlank 拦过）
        apply(ticket, transition, user, "撤销：" + dto.getReason(),
                TicketStatePatch.builder().cancelReason(dto.getReason()).build());
        return ticketQueryService.detailForFlow(ticketId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO forceClose(Long ticketId) {
        UserContext.CurrentUser user = UserContext.get();
        Ticket ticket = loadVisibleTicket(ticketId);

        TicketStateMachine.Transition transition =
                stateMachine.check(ticket, TicketHistoryAction.FORCE_CLOSE, user);

        // 矩阵 #14 / §6.4：只写 closed_at。
        // ⚠️ 刻意不碰 SLA —— §6.4 的原文只有「写 closed_at」，
        //    与 #11 close（员工正常确认，会恢复 SLA）不是一回事。
        apply(ticket, transition, user, "管理员强制关闭",
                TicketStatePatch.builder().closedAt(LocalDateTime.now()).build());
        return ticketQueryService.detailForFlow(ticketId);
    }

    // ==================== 私有 ====================

    /**
     * CAS 更新状态 + 写历史（同事务）—— <b>简单路径</b>：只改 assignee / first_response_at。
     *
     * @param newAssigneeId 新的处理人；{@code null} 表示不改该列
     */
    private void apply(Ticket ticket, TicketStateMachine.Transition transition,
                       UserContext.CurrentUser user, Long newAssigneeId, String remark) {
        LocalDateTime now = LocalDateTime.now();

        // 「写 first_response_at（若空）」：已经响应过就传 null → SQL 跳过该列 → 保留首轮值（§9.6）
        LocalDateTime firstResponseAt =
                transition.stampsFirstResponse() && ticket.getFirstResponseAt() == null ? now : null;

        int rows = ticketMapper.updateStatusCas(ticket.getId(), ticket.getStatus(), transition.to(),
                ticket.getVersion(), newAssigneeId, firstResponseAt);
        afterCas(ticket, transition, user, remark, rows);
    }

    /**
     * CAS 更新状态 + 写历史（同事务）—— <b>扩展路径</b>：要写 SLA / 生命周期附加列。
     *
     * <p>走 {@code updateStatusCasExtended} + {@link TicketStatePatch}；
     * 两条路径共用同一份 CAS 条件（XML 的 {@code casWhere} 片段），并发语义完全一致。
     */
    private void apply(Ticket ticket, TicketStateMachine.Transition transition,
                       UserContext.CurrentUser user, String remark, TicketStatePatch patch) {
        int rows = ticketMapper.updateStatusCasExtended(ticket.getId(), ticket.getStatus(),
                transition.to(), ticket.getVersion(), patch);
        afterCas(ticket, transition, user, remark, rows);
    }

    /** CAS 之后：影响 0 行 = 并发冲突；否则写历史。两条路径共用 */
    private void afterCas(Ticket ticket, TicketStateMachine.Transition transition,
                          UserContext.CurrentUser user, String remark, int rows) {
        if (rows == 0) {
            // status 或 version 与读取时不一致 → 别人抢先改过（§25.4 用例 #2「两人同时 accept」）
            log.warn("[状态流转] CAS 失败：ticketId={} from={} expectedVersion={} action={}",
                    ticket.getId(), ticket.getStatus(), ticket.getVersion(), transition.action());
            throw BizException.conflict("工单状态已被其他操作变更，请刷新后重试");
        }

        historyRecorder.record(ticket.getId(), user.userId(), transition.action(),
                transition.from(), transition.to(), remark);

        log.info("[状态流转] 成功：ticketId={} operator={} {} {} → {}",
                ticket.getId(), user.userId(), transition.action(),
                transition.from(), transition.to());

        // TODO(D6): 审计（AuditOperation.TICKET_CLOSE / TICKET_TRANSFER / TICKET_HOLD …，
        //   注意枚举里目前只有 TICKET_ASSIGN/TRANSFER/CLOSE，RESOLVE/REJECT/HOLD/RESUME 待补）
        //   与通知（通知处理人 / 创建人）—— 都是 Day 6 的交付物，本单只留调用点。
    }

    /** §9.4「退出暂停态」的补丁：清空暂停起点 + 顺延解决时限 + 累加暂停分钟（resume / close 共用） */
    private TicketStatePatch resumePatch(SlaPauseService.ResumeOutcome outcome) {
        return TicketStatePatch.builder()
                .clearSlaPausedAt(true)
                .resolutionDeadline(outcome.resolutionDeadline())
                .slaPausedMinutes(outcome.pausedMinutes())
                .build();
    }

    /**
     * 按 id 取工单并做数据范围校验。
     *
     * <p>⚠️ 顺序与 D3-03 详情 / D3-04 评论完全一致，<b>不能反</b>：
     * 先 40400 再 40301，否则「不存在的 id」也会返回 40301，泄露 id 是否存在（§2.7）。
     */
    private Ticket loadVisibleTicket(Long ticketId) {
        Ticket ticket = ticketService.getById(ticketId);
        if (ticket == null) {
            throw BizException.notFound("工单不存在");
        }
        dataScopeHelper.assertVisible(ticket, UserContext.get());
        return ticket;
    }

    /**
     * 校验被分派人可受理工单（已与用户确认：必须校验）。
     *
     * <p>三道：存在（40400）→ 启用（40001）→ 有 AGENT/ADMIN 角色（40001）。
     * 用 40001 而不是 40300：这是<b>请求参数不合法</b>（传了个不能当处理人的 id），
     * 不是「调用者没有权限」——40300 会让前端误以为是自己的权限问题。
     */
    private User requireAssignable(Long assigneeId) {
        if (assigneeId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "处理人不能为空");
        }
        User user = userService.getById(assigneeId);
        if (user == null) {
            throw BizException.notFound("指定的处理人不存在");
        }
        if (user.getStatus() == null || user.getStatus() != USER_STATUS_ENABLED) {
            throw new BizException(ErrorCode.PARAM_INVALID, "指定的处理人已被禁用");
        }
        if (!hasAgentOrAdminRole(assigneeId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "只能分派给 IT 服务人员或管理员");
        }
        return user;
    }

    /** 用户是否持有 AGENT 或 ADMIN 角色（经 {@code user_role} → {@code role.code}） */
    private boolean hasAgentOrAdminRole(Long userId) {
        List<Long> roleIds = userRoleService
                .list(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId))
                .stream()
                .map(UserRole::getRoleId)
                .toList();
        if (roleIds.isEmpty()) {
            return false;
        }
        return roleService.listByIds(roleIds).stream()
                .anyMatch(role -> role.getCode() == Role.AGENT || role.getCode() == Role.ADMIN);
    }

    /** 显示名口径与 D3-02 列表 / D3-04 评论一致：昵称优先，无昵称回落用户名 */
    private String displayNameOf(User user) {
        return user.getNickname() == null ? user.getUsername() : user.getNickname();
    }
}
